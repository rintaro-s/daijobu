import Foundation

/// ESP32 の AP / TCP サーバの設定。
/// ESP32-controle/include/config.h の AP_SSID / AP_PASS / TCP_PORT と一致させること。
enum ConnectionConfig {
    static let ssid = "CTRL-MONITOR"
    static let passphrase = "loligodrequiem"
    static let host = "192.168.4.1"
    static let port: UInt16 = 3333

    static let connectTimeout: TimeInterval = 3
    /// この時間データが来なければ切断扱いにして再接続する
    static let readTimeout: TimeInterval = 2
    static let retryDelay: TimeInterval = 1
    /// Wi-Fi 参加要求 (NEHotspotConfiguration) をやり直す間隔
    static let hotspotRetry: TimeInterval = 30
}
