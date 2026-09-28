package com.nbks.controoooooool.net

/**
 * ESP32 の AP / TCP サーバの設定。
 * ESP32-controle/include/config.h の AP_SSID / AP_PASS / TCP_PORT と一致させること。
 */
object ConnectionConfig {
    const val SSID = "CTRL-MONITOR"
    const val PASSPHRASE = "loligodrequiem"
    const val HOST = "192.168.4.1"
    const val PORT = 3333

    const val CONNECT_TIMEOUT_MS = 3000
    /** この時間データが来なければ切断扱いにして再接続する */
    const val READ_TIMEOUT_MS = 2000
    const val RETRY_DELAY_MS = 1000L
}
