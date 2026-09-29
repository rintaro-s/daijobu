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
                if (specifierNetwork == network) {
                    specifierNetwork = null
                    closeSocket()  // 読み込み中なら即座に抜けて繋ぎ直す
                }
            }

            override fun onUnavailable() {
                // ダイアログのキャンセル / AP が見つからない。少し待って要求し直す
                Log.i(TAG, "specifier unavailable, retry later")
                callback = null
                scope.launch {
                    delay(SPECIFIER_RETRY_MS)
                    if (loopJob?.isActive == true && callback == null) requestSpecifierNetwork()
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
    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            val candidates = (listOfNotNull(specifierNetwork) + wifiNetworks()).distinct()
            var connected = false
            for (net in candidates) {
                val s = tryConnect(net) ?: continue
                connected = true
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
            _state.value = when {
                connected -> LinkState.ConnectingTcp  // 切れた直後。すぐ繋ぎ直す
                candidates.isEmpty() -> LinkState.JoiningWifi
                else -> LinkState.Error("ESP32 (${ConnectionConfig.HOST}) に届きません")
            }
            delay(ConnectionConfig.RETRY_DELAY_MS)
        }
    }

    private suspend fun tryConnect(net: Network): Socket? = withContext(Dispatchers.IO) {
        _state.value = LinkState.ConnectingTcp
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
                    "data" -> _telemetry.value =
                        TelemetryParser.parseData(o, schema, SystemClock.elapsedRealtime())
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad line: ${e.message}")
            }
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "EspLink"
        private const val SPECIFIER_RETRY_MS = 5000L
    }
}
