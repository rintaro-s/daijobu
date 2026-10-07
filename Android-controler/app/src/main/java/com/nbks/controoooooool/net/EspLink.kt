package com.nbks.controoooooool.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import android.util.Log
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.model.TelemetryParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

sealed interface LinkState {
    data object Idle : LinkState
    /** システムの Wi-Fi 接続ダイアログ待ち / AP 接続中 */
    data object JoiningWifi : LinkState
    data object ConnectingTcp : LinkState
    data object Connected : LinkState
    data class Error(val message: String) : LinkState
}

/**
 * ESP32 の AP に繋いで TCP で JSON Lines を受け取る。接続順・切断のされ方に依存せず自動で復帰する。
 *
 * 経路は 2 つ用意し、繋がる方を使う:
 *   1. WifiNetworkSpecifier でアプリ専用に AP へ接続 (初回だけシステムの確認ダイアログ)
 *   2. スマホが設定画面から既に CTRL-MONITOR に繋がっている場合、その Wi-Fi 経由で直接接続
 *
 * - 接続ループは [start] から [stop] まで回り続け、切れたら 1 秒後に繋ぎ直す
 * - ダイアログがキャンセル / AP が見つからない場合も、数秒後に自動で要求し直す
 * - データが READ_TIMEOUT_MS 来なければ切断扱いにして繋ぎ直す
 */
class EspLink(context: Context, private val scope: CoroutineScope) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var loopJob: Job? = null
    @Volatile private var specifierNetwork: Network? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var socketNetwork: Network? = null
    private var espReboots = 0
    private var lastEspUptime = -1L

    fun start() {
        if (loopJob?.isActive == true) return
        requestSpecifierNetwork()
        loopJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        closeSocket()
        unregisterCallback()
        specifierNetwork = null
        _state.value = LinkState.Idle
    }

    // ---- 経路 1: アプリ専用の Wi-Fi 接続 ----
    private fun requestSpecifierNetwork() {
        unregisterCallback()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ConnectionConfig.SSID)
            .setWpa2Passphrase(ConnectionConfig.PASSPHRASE)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "specifier network available: $network")
                specifierNetwork = network
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "specifier network lost")
                if (specifierNetwork == network) specifierNetwork = null
                // その Wi-Fi 経由で読んでいた場合だけ閉じる (別経路で繋がっているなら切らない)
                if (socketNetwork == network) closeSocket()
            }

            override fun onUnavailable() {
                // ダイアログのキャンセル / AP が見つからない。
                // 要求のたびに確認ダイアログが出るので、間隔を空け、既に ESP32 と通信できているなら出し直さない
                Log.i(TAG, "specifier unavailable, retry later")
                callback = null
                scope.launch {
                    delay(SPECIFIER_RETRY_MS)
                    if (loopJob?.isActive == true && callback == null && socket == null) requestSpecifierNetwork()
                }
            }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork failed: ${e.message}")
            callback = null
        }
    }

    private fun unregisterCallback() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    // ---- 経路 2: 既に繋がっている Wi-Fi ----
    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<Network> = cm.allNetworks.filter {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    // ---- 接続ループ ----
    // 状態表示は「Wi-Fi 未接続 (JoiningWifi)」と「Wi-Fi はあるが ESP32 と通信中 (ConnectingTcp)」の 2 つだけ。
    // 1 回の失敗ごとにエラー表示に切り替えるとチカチカするので、リトライ中は ConnectingTcp のままにする。
    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            val candidates = (listOfNotNull(specifierNetwork) + wifiNetworks()).distinct()
            _state.value = if (candidates.isEmpty()) LinkState.JoiningWifi else LinkState.ConnectingTcp

            var wasConnected = false
            for (net in candidates) {
                val s = tryConnect(net) ?: continue
                wasConnected = true
                socketNetwork = net
                _state.value = LinkState.Connected
                try {
                    readLines(s)
                } catch (e: Exception) {
                    Log.w(TAG, "read: ${e.message}")
                } finally {
                    closeSocket()
                }
                break
            }
            // 通信中だったものが切れた直後はすぐ繋ぎ直す。繋がらなかったときだけ待つ
            if (!wasConnected) delay(ConnectionConfig.RETRY_DELAY_MS) else delay(200)
        }
    }

    private suspend fun tryConnect(net: Network): Socket? = withContext(Dispatchers.IO) {
        try {
            val s = net.socketFactory.createSocket() as Socket
            s.tcpNoDelay = true
            s.soTimeout = ConnectionConfig.READ_TIMEOUT_MS
            s.connect(
                InetSocketAddress(ConnectionConfig.HOST, ConnectionConfig.PORT),
                ConnectionConfig.CONNECT_TIMEOUT_MS,
            )
            socket = s
            Log.i(TAG, "tcp connected via $net")
            s
        } catch (e: Exception) {
            Log.d(TAG, "tcp via $net failed: ${e.message}")
            null
        }
    }

    private fun readLines(s: Socket) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        var schema = _telemetry.value.schema
        while (true) {
            val line = reader.readLine() ?: throw java.io.EOFException("closed by ESP32")
            if (line.isBlank()) continue
            try {
                val o = JSONObject(line)
                when (o.optString("type")) {
                    "schema" -> {
                        schema = TelemetryParser.parseSchema(o)
                        _telemetry.value = _telemetry.value.copy(schema = schema)
                    }
                    "data" -> {
                        val t = TelemetryParser.parseData(o, schema, SystemClock.elapsedRealtime())
                        // ESP32 の稼働時間が巻き戻ったら ESP32 が再起動した (電源低下・クラッシュ等の見分け用)
                        if (lastEspUptime >= 0 && t.espUptimeMs + 1000 < lastEspUptime) espReboots++
                        lastEspUptime = t.espUptimeMs
                        _telemetry.value = t.copy(espReboots = espReboots)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad line: ${e.message}")
            }
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        socketNetwork = null
    }

    companion object {
        private const val TAG = "EspLink"
        private const val SPECIFIER_RETRY_MS = 30_000L
    }
}
