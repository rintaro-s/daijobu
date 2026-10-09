import Foundation
import Network
import NetworkExtension
import Observation
import os

enum LinkState: Equatable {
    case idle
    /// Wi-Fi 未接続 / AP 参加中
    case joiningWifi
    case connectingTcp
    case connected
    case error(String)
}

/// ESP32 の AP に繋いで TCP で JSON Lines を受け取る。接続順・切断のされ方に依存せず自動で復帰する。
///
/// 経路:
///   1. NEHotspotConfiguration で AP に参加 (初回だけシステムの確認ダイアログ。
///      Hotspot Configuration の entitlement が無い署名では失敗するので、そのときは設定アプリから手動で参加する)
///   2. スマホが既に CTRL-MONITOR に繋がっていれば、そのまま TCP で接続
///
/// - 接続ループは [start] から [stop] まで回り続け、切れたら繋ぎ直す
/// - データが readTimeout 来なければ切断扱いにして繋ぎ直す
/// - 通信・JSON の解析は専用キューで行い、画面へは結果だけ渡す (20Hz × 約 3.5KB/行)
@Observable
final class EspLink {
    private(set) var state: LinkState = .idle
    private(set) var telemetry = Telemetry()

    // ---- ここから下は queue 上でだけ触る ----
    @ObservationIgnored private let queue = DispatchQueue(label: "EspLink")
    @ObservationIgnored private var running = false
    /// start/stop のたびに増やし、古いループからのコールバックを無視する
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var connection: NWConnection?
    @ObservationIgnored private var buffer = Data()
    @ObservationIgnored private var lastRx = Date.distantPast
    @ObservationIgnored private var schema = Schema()
    @ObservationIgnored private var espReboots = 0
    @ObservationIgnored private var lastEspUptime: Int64 = -1
    @ObservationIgnored private var wifiUp = false
    @ObservationIgnored private var lastHotspotRequest = Date.distantPast
    @ObservationIgnored private let pathMonitor = NWPathMonitor(requiredInterfaceType: .wifi)

    private static let log = Logger(subsystem: "com.nbks.controoooooool", category: "EspLink")

    init() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            self?.wifiUp = path.status == .satisfied
        }
        pathMonitor.start(queue: queue)
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            self.generation += 1
            self.lastHotspotRequest = .distantPast
            self.attempt(self.generation)
        }
    }

    func stop() {
        queue.async {
            self.running = false
            self.generation += 1
            self.closeConnection()
            self.publish(state: .idle)
        }
    }

    // ---- 経路 1: システムに AP への参加を頼む ----
    private func requestHotspotIfNeeded() {
        guard Date().timeIntervalSince(lastHotspotRequest) > ConnectionConfig.hotspotRetry else { return }
        lastHotspotRequest = Date()
        #if !targetEnvironment(simulator)
        let conf = NEHotspotConfiguration(ssid: ConnectionConfig.ssid, passphrase: ConnectionConfig.passphrase, isWEP: false)
        conf.joinOnce = false
        NEHotspotConfigurationManager.shared.apply(conf) { error in
            // 既に参加済み (alreadyAssociated) も成功扱い。entitlement が無い等の失敗は手動参加に任せる
            if let error { Self.log.info("hotspot: \(error.localizedDescription)") }
        }
        #endif
    }

    // ---- 接続ループ ----
    // 状態表示は「Wi-Fi 未接続 (joiningWifi)」と「Wi-Fi はあるが ESP32 と通信中 (connectingTcp)」の 2 つだけ。
    // 1 回の失敗ごとにエラー表示に切り替えるとチカチカするので、リトライ中は connectingTcp のままにする。
    private func attempt(_ gen: Int) {
        guard running, gen == generation else { return }
        requestHotspotIfNeeded()
        publish(state: wifiUp ? .connectingTcp : .joiningWifi)

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = Int(ConnectionConfig.connectTimeout)
        let params = NWParameters(tls: nil, tcp: tcp)
        // AP はインターネットに出られないので、モバイル通信側に流れないようにする
        params.prohibitedInterfaceTypes = [.cellular]
        let conn = NWConnection(
            host: NWEndpoint.Host(ConnectionConfig.host),
            port: NWEndpoint.Port(rawValue: ConnectionConfig.port)!,
            using: params
        )
        connection = conn
        buffer.removeAll()

        var finished = false
        let finish = { [weak self] (wasConnected: Bool) in
            guard let self, !finished else { return }
            finished = true
            if self.connection === conn { self.closeConnection() }
            // 通信中だったものが切れた直後はすぐ繋ぎ直す。繋がらなかったときだけ待つ
            let delay = wasConnected ? 0.2 : ConnectionConfig.retryDelay
            self.queue.asyncAfter(deadline: .now() + delay) { self.attempt(gen) }
        }

        var connected = false
        conn.stateUpdateHandler = { [weak self] st in
            guard let self, gen == self.generation else { return }
            switch st {
            case .ready:
                connected = true
                Self.log.info("tcp connected")
                self.lastRx = Date()
                self.publish(state: .connected)
                self.receive(conn, gen)
                self.watchdog(conn, gen)
            case .failed, .cancelled:
                finish(connected)
            case .waiting(let e):
                // 経路が無い等。待たずに失敗扱いにして次の試行へ
                Self.log.debug("tcp waiting: \(e.localizedDescription)")
                finish(false)
            default:
                break
            }
        }
        conn.start(queue: queue)
        // 接続そのもののタイムアウト (.preparing のまま止まる場合の保険)
        queue.asyncAfter(deadline: .now() + ConnectionConfig.connectTimeout) {
            if !connected && !finished { conn.cancel() }
        }
    }

    private func receive(_ conn: NWConnection, _ gen: Int) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, gen == self.generation, self.connection === conn else { return }
            if let data, !data.isEmpty {
                self.lastRx = Date()
                self.buffer.append(data)
                self.drainLines()
            }
            if isComplete || error != nil {
                Self.log.info("read end: \(error?.localizedDescription ?? "closed by ESP32")")
                conn.cancel()
                return
            }
            self.receive(conn, gen)
        }
    }

    /// データが readTimeout 来なければ切る (Wi-Fi が黙って切れた場合など)
    private func watchdog(_ conn: NWConnection, _ gen: Int) {
        queue.asyncAfter(deadline: .now() + 0.25) { [weak self] in
            guard let self, gen == self.generation, self.connection === conn else { return }
            if Date().timeIntervalSince(self.lastRx) > ConnectionConfig.readTimeout {
                Self.log.info("read timeout")
                conn.cancel()
            } else {
                self.watchdog(conn, gen)
            }
        }
    }

    private func drainLines() {
        var latest: Telemetry?
        while let nl = buffer.firstIndex(of: 0x0A) {
            let line = buffer[buffer.startIndex..<nl]
            buffer.removeSubrange(buffer.startIndex...nl)
            if let t = handle(line) { latest = t }
        }
        // 1 回の受信に複数行入っていても、画面に渡すのは最新の 1 つだけ
        if let latest {
            DispatchQueue.main.async { self.telemetry = latest }
        }
    }

    /// schema 行なら schema を更新、data 行なら新しい Telemetry を返す
    private func handle(_ line: Data) -> Telemetry? {
        guard !line.isEmpty,
              let o = try? JSONSerialization.jsonObject(with: line) as? [String: Any] else { return nil }
        switch o["type"] as? String {
        case "schema":
            schema = TelemetryParser.parseSchema(o)
            let s = schema
            DispatchQueue.main.async { self.telemetry.schema = s }
            return nil
        case "data":
            guard var t = TelemetryParser.parseData(o, schema: schema) else { return nil }
            // ESP32 の稼働時間が巻き戻ったら ESP32 が再起動した (電源低下・クラッシュ等の見分け用)
            if lastEspUptime >= 0 && t.espUptimeMs + 1000 < lastEspUptime { espReboots += 1 }
            lastEspUptime = t.espUptimeMs
            t.espReboots = espReboots
            return t
        default:
            return nil
        }
    }

    private func closeConnection() {
        connection?.cancel()
        connection = nil
    }

    private func publish(state s: LinkState) {
        DispatchQueue.main.async {
            if self.state != s { self.state = s }
        }
    }
}
