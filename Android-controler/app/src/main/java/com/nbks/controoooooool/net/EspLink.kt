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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * ESP32 の AP にアプリ単位で接続 (WifiNetworkSpecifier) し、TCP で JSON Lines を受け取る。
 *
 * - 初回だけシステムの「デバイスに接続」ダイアログが出る。一度許可すると以降は自動で繋がる
 * - この接続はアプリ専用で、スマホのモバイル通信などには影響しない
 * - 切れたら自動で再接続する
 */
class EspLink(context: Context, private val scope: CoroutineScope) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var readerJob: Job? = null
    @Volatile private var socket: Socket? = null

    fun start() {
        if (callback != null) return
        _state.value = LinkState.JoiningWifi

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
                Log.i(TAG, "wifi available: $network")
                startReader(network)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "wifi lost")
                stopReader()
                _state.value = LinkState.JoiningWifi
            }

            override fun onUnavailable() {
                // ダイアログでキャンセルされた / AP が見つからない
                callback = null
                _state.value = LinkState.Error("${ConnectionConfig.SSID} に接続できませんでした")
            }
        }
        callback = cb
        cm.requestNetwork(request, cb)
    }

    fun stop() {
        stopReader()
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        _state.value = LinkState.Idle
    }

    private fun startReader(network: Network) {
        stopReader()
        readerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _state.value = LinkState.ConnectingTcp
                try {
                    val s = network.socketFactory.createSocket() as Socket
                    socket = s
                    s.tcpNoDelay = true
                    s.soTimeout = ConnectionConfig.READ_TIMEOUT_MS
                    s.connect(
                        InetSocketAddress(ConnectionConfig.HOST, ConnectionConfig.PORT),
                        ConnectionConfig.CONNECT_TIMEOUT_MS,
                    )
                    _state.value = LinkState.Connected
                    readLines(s)
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "tcp: ${e.message}")
                    _state.value = LinkState.Error("ESP32 と通信できません (${e.javaClass.simpleName})")
                } finally {
                    runCatching { socket?.close() }
                    socket = null
                }
                delay(ConnectionConfig.RETRY_DELAY_MS)
            }
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

    private fun stopReader() {
        readerJob?.cancel()
        readerJob = null
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "EspLink"
    }
}

