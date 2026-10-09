import Foundation

/// ESP32 が接続直後に送ってくる信号定義 (ESP32-controle/include/can_signals.h 由来)
struct SignalInfo: Hashable {
    let name: String
    let canId: Int64
    let type: String
    let isBool: Bool
    let min: Double
    let max: Double
    let unit: String
    let group: String
}

struct Schema {
    var signals: [SignalInfo] = []
    /// この時間 (ms) 以上フレームが来ていなければ途絶扱い
    var timeoutMs: Int64 = 300

    func find(_ name: String) -> SignalInfo? { signals.first { $0.name == name } }
}

struct FrameStatus {
    let canId: Int64
    /// 最後に「有効な」フレームを受信してからの経過 ms。-1 は未受信
    let ageMs: Int64
    let hz: Double
    /// 最後の有効フレームのシーケンス番号 (無い ID は nil)
    var seq: Int? = nil
    /// シーケンスの飛びから数えた欠落数の累計 (手掛かりであり保証ではない)
    var seqLost: Int64 = 0
    /// SEQ・稼働時間とも前回と同じで捨てたフレーム数
    var duplicates: Int64 = 0
    /// 条件を満たさず捨てたフレーム数
    var rejected: Int64 = 0
    /// 直近フレームが不正だった理由。"fmt" / "len:N" / "ver:N"。正常なら nil
    var reject: String? = nil
}

/// 定義 (can_signals.h) に無いが受信した ID。何がバスに流れているかの診断用
struct UnknownFrame {
    let canId: Int64
    let extended: Bool
    let fd: Bool
    let brs: Bool
    let len: Int
    let count: Int64
    let ageMs: Int64
}

/// 表示に使う現在の状態
struct Telemetry {
    var schema = Schema()
    var values: [String: Double] = [:]
    var frames: [FrameStatus] = []
    var unknown: [UnknownFrame] = []
    /// ESP32 の稼働時間 ms。巻き戻ったら ESP32 が再起動したと判断する
    var espUptimeMs: Int64 = 0
    /// 接続中に検出した ESP32 の再起動回数 (アプリ起動からの累計)
    var espReboots = 0
    /// ESP32 の起動理由 (esp_reset_reason)。-1 = 不明
    var espResetReason = -1
    var uartOk: Int64 = 0
    var uartCrcErrors: Int64 = 0

    func value(_ name: String) -> Double? { values[name] }
    func pressed(_ name: String) -> Bool { (values[name] ?? 0) != 0 }

    /// その信号を運ぶ CAN フレームが途絶していないか
    func isFresh(_ name: String) -> Bool {
        guard let id = schema.find(name)?.canId, let f = frame(id) else { return false }
        return (0...schema.timeoutMs).contains(f.ageMs)
    }

    func frame(_ canId: Int64) -> FrameStatus? { frames.first { $0.canId == canId } }

    /// その信号を運ぶ CAN フレームの受信状態
    func frameOf(_ name: String) -> FrameStatus? { schema.find(name).flatMap { frame($0.canId) } }

    var anyFresh: Bool { frames.contains { (0...schema.timeoutMs).contains($0.ageMs) } }
}

enum TelemetryParser {
    typealias Obj = [String: Any]

    static func parseSchema(_ o: Obj) -> Schema {
        let arr = o["signals"] as? [Obj] ?? []
        let list = arr.compactMap { s -> SignalInfo? in
            guard let name = s["name"] as? String, let id = int64(s["id"]) else { return nil }
            return SignalInfo(
                name: name,
                canId: id,
                type: s["type"] as? String ?? "",
                isBool: s["bool"] as? Bool ?? false,
                min: double(s["min"]) ?? 0,
                max: double(s["max"]) ?? 1,
                unit: s["unit"] as? String ?? "",
                group: s["group"] as? String ?? ""
            )
        }
        return Schema(signals: list, timeoutMs: int64(o["timeout"]) ?? 300)
    }

    static func parseData(_ o: Obj, schema: Schema) -> Telemetry? {
        guard let v = o["v"] as? Obj, let fa = o["frames"] as? [Obj] else { return nil }
        var values = [String: Double](minimumCapacity: v.count)
        for (key, raw) in v {
            if let d = double(raw), !d.isNaN { values[key] = d }
        }

        let frames = fa.compactMap { f -> FrameStatus? in
            guard let id = int64(f["id"]), let age = int64(f["age"]) else { return nil }
            return FrameStatus(
                canId: id,
                ageMs: age,
                hz: double(f["hz"]) ?? 0,
                seq: int64(f["seq"]).map { Int($0) },
                seqLost: int64(f["lost"]) ?? 0,
                duplicates: int64(f["dup"]) ?? 0,
                rejected: int64(f["bad"]) ?? 0,
                reject: f["rej"] as? String
            )
        }
        let unknown = (o["unknown"] as? [Obj] ?? []).compactMap { u -> UnknownFrame? in
            guard let id = int64(u["id"]) else { return nil }
            return UnknownFrame(
                canId: id,
                extended: u["ext"] as? Bool ?? false,
                fd: u["fd"] as? Bool ?? false,
                brs: u["brs"] as? Bool ?? false,
                len: Int(int64(u["len"]) ?? 0),
                count: int64(u["count"]) ?? 0,
                ageMs: int64(u["age"]) ?? 0
            )
        }
        let link = o["link"] as? Obj
        return Telemetry(
            schema: schema,
            values: values,
            frames: frames,
            unknown: unknown,
            espUptimeMs: int64(o["t"]) ?? 0,
            espResetReason: link.flatMap { int64($0["rst"]) }.map { Int($0) } ?? -1,
            uartOk: link.flatMap { int64($0["ok"]) } ?? 0,
            uartCrcErrors: link.flatMap { int64($0["crc"]) } ?? 0
        )
    }

    // JSONSerialization は数値を NSNumber で返す (null は NSNull)
    private static func double(_ v: Any?) -> Double? { (v as? NSNumber)?.doubleValue }
    private static func int64(_ v: Any?) -> Int64? { (v as? NSNumber)?.int64Value }
}
