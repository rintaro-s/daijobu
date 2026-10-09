import Foundation

/// ゾウキンナゲルマン CAN FD ID台帳 V2 (2026-09-29) の表示用解釈。
///
/// ESP32 はフレームの形式・長さ・バージョンを確認し、各項目を生値のまま
/// 「接頭辞.項目」(例: LIFT1.rate) で送ってくる。無効値・有効フラグ・状態番号・異常ビットの
/// 意味付けはここで行う。接頭辞は ESP32-controle/include/can_signals.h と一致させること。
/// (Android 版 DebugSpec.kt と同じ内容。片方を変えたらもう片方も直す)
enum DebugSpec {
    enum Mech: Int, CaseIterable {
        case lift, shot, wheel, gyro
        // 制御・安全用 ID (表示のみ)
        case liftCmd, liftSt, shotCmd, loadSens

        var isControl: Bool { rawValue >= Mech.liftCmd.rawValue }
    }

    struct Unit: Identifiable {
        let canId: Int64
        let prefix: String
        let label: String
        let mech: Mech
        var id: String { prefix }
    }

    static let units: [Unit] = [
        Unit(canId: 0x301, prefix: "LIFT1", label: "昇降1", mech: .lift),
        Unit(canId: 0x302, prefix: "LIFT2", label: "昇降2", mech: .lift),
        Unit(canId: 0x303, prefix: "LIFT3", label: "昇降3", mech: .lift),
        Unit(canId: 0x310, prefix: "SHOT", label: "発射・装填指令", mech: .shot),
        Unit(canId: 0x321, prefix: "WFL", label: "左前", mech: .wheel),
        Unit(canId: 0x322, prefix: "WFR", label: "右前", mech: .wheel),
        Unit(canId: 0x323, prefix: "WRL", label: "左後", mech: .wheel),
        Unit(canId: 0x324, prefix: "WRR", label: "右後", mech: .wheel),
        Unit(canId: 0x200, prefix: "GYRO", label: "ジャイロ (BNO055)", mech: .gyro),
        Unit(canId: 0x130, prefix: "LCMD", label: "昇降システム指令", mech: .liftCmd),
        Unit(canId: 0x141, prefix: "LST1", label: "昇降ID1 状態", mech: .liftSt),
        Unit(canId: 0x142, prefix: "LST2", label: "昇降ID2 状態", mech: .liftSt),
        Unit(canId: 0x143, prefix: "LST3", label: "昇降ID3 状態", mech: .liftSt),
        Unit(canId: 0x150, prefix: "SCMD", label: "発射・装填・サーボ指令", mech: .shotCmd),
        Unit(canId: 0x210, prefix: "LSEN", label: "装填センサー", mech: .loadSens),
    ]

    static func unit(_ prefix: String) -> Unit { units.first { $0.prefix == prefix }! }

    // 0x310 byte12 / 0x150 byte1 の指令フラグ
    private static let cmd310Bits = ["発射RUN", "装填1 RUN", "装填2 RUN", "クランプ保持", "停止ラッチ", "MINUS調整"]
    private static let cmd150Bits = ["入力有効", "発射RUN", "調整モード", "装填1 RUN", "装填2 RUN", "クランプ保持", "機構停止"]
    private static let lift130Bits = ["ZERO指令", "UP禁止", "DOWN禁止", "全動作禁止"]

    static func bitNames(_ names: [String], _ bits: Int, width: Int) -> [String] {
        var out = names.enumerated().filter { bits & (1 << $0.offset) != 0 }.map(\.element)
        let reserved = bits & ((1 << width) - 1) & ~((1 << names.count) - 1)
        if reserved != 0 { out.append(String(format: "予約bit(0x%X)", reserved)) }
        return out
    }

    static func cmd310Names(_ bits: Int) -> [String] { bitNames(cmd310Bits, bits, width: 8) }
    static func cmd150Names(_ bits: Int) -> [String] { bitNames(cmd150Bits, bits, width: 8) }
    static func lift130Names(_ bits: Int) -> [String] { bitNames(lift130Bits, bits, width: 8) }

    static func adjustName(_ v: Int?) -> String {
        switch v { case nil: "--"; case 0: "なし"; case 1: "PWM"; case 2: "角度"; case let v?: "未対応(\(v))" }
    }
    static func presetName(_ v: Int?) -> String {
        switch v { case nil: "--"; case 2: "左"; case 3: "右"; case 255: "初期/手動"; case let v?: "\(v)" }
    }
    static func sensorName(_ v: Int?) -> String {
        switch v { case nil: "--"; case 0: "非検出"; case 1: "検出"; case 255: "無効"; case let v?: "未対応(\(v))" }
    }
    static func ageText(_ v: Int?) -> String {
        switch v { case nil: "--"; case 65535: "未受信"; case let v?: "\(v) ms" }
    }

    // ---- 無効値 ----
    static let U16_INVALID = 0xFFFF
    static let I16_INVALID = -32768             // PWM 取得不可
    static let I32_INVALID: Int64 = -2147483648 // INT32_MIN
    static let U32_INVALID: Int64 = 0xFFFFFFFF

    // ---- byte3 有効性フラグ ----
    static let F_MEASURED = 1 << 0     // 実測値 (昇降: エンコーダー位置 / 発射・足回り: 実測RPM)
    static let F_HOMED = 1 << 1        // 昇降の原点確定済み
    static let F_SENSOR = 1 << 2       // byte20-21 のリミット/センサー情報が有効
    static let F_TARGET = 1 << 3       // 目標値 (昇降: 目標位置 / 発射: 目標RPM)
    static let F_SHOTS = 1 << 4        // 発射回数
    static let F_CMD_FRESH = 1 << 5    // 必要な操作指令を新鮮な状態で受信できている
    // 昇降 V30 で追加 (昇降のみ。bit2 は昇降では「下限スイッチ機能有効」)
    static let F_PEER_FRESH = 1 << 6   // 他 2 軸の位置を 100ms 以内に受信している
    static let F_V30 = 1 << 7          // V30 位置共有対応マーカー
    static let F_LOWER_SW = 1 << 2     // 昇降: 下限スイッチ機能有効

    // V30: 0=PWMゼロ (他軸位置不足・原点待ちでも 0 になるので停止理由は断定しない) / 3=未使用
    private static let liftStates = ["PWMゼロ", "UP出力", "DOWN出力", "未使用(3)", "停止(故障/指令無効/禁止)"]
    private static let wheelStates = ["出力停止", "正方向", "逆方向", "反転待ち", "異常停止"]
    private static let stopReasons = ["通常/中立", "操作CAN受信無効", "反転待ち", "制御ループ遅延"]

    private static let errorBits = [
        "指令タイムアウト", "エンコーダーストール", "CAN受信FIFOあふれ", "制御周期超過",
        "ドライバ異常", "非常停止", "センサー異常", "その他の異常",
    ]
    private static let liftLimitBits = ["下限SW", "上限", "UP禁止", "DOWN禁止", "全動作禁止"]

    static func stateName(_ mech: Mech, _ state: Int?) -> String {
        guard let state else { return "--" }
        let list: [String]
        switch mech {
        case .lift: list = liftStates
        case .shot: return "状態 \(state)"  // 0x310 の状態番号の意味は台帳に記載なし
        case .wheel: list = wheelStates
        default: list = []
        }
        return list.indices.contains(state) ? list[state] : "未対応(\(state))"
    }

    static func stopReason(_ v: Int?) -> String {
        guard let v else { return "--" }
        return stopReasons.indices.contains(v) ? stopReasons[v] : "未対応(\(v))"
    }

    // 昇降 V30 の故障/診断ビット (byte22-23)。足回り等の共通定義とは意味が違う
    private static let liftErrorBits = [
        "0x123/0x130未受信", "エンコーダー停滞", "受信FIFO損失", "制御周期超過",
        "逆方向カウント", "カウント飛び/範囲異常", "3台位置差継続", "制御側受信異常",
    ]

    /// 異常ビットの名前一覧。予約ビット (8-15) が立っていればそれも示す
    static func errorNamesFor(_ mech: Mech, _ bits: Int) -> [String] {
        guard mech == .lift else { return errorNames(bits) }
        var names = liftErrorBits.enumerated().filter { bits & (1 << $0.offset) != 0 }.map(\.element)
        if bits & 0xFF00 != 0 { names.append(String(format: "予約bit(0x%04X)", bits & 0xFF00)) }
        return names
    }

    // ---- ジャイロ 0x200 ----
    /// 測定値が有効なのは I2C 成功 (bit0) と融合測定有効 (bit1) の両方が立っているとき
    static func gyroValid(_ flags: Int) -> Bool { flags & 0x03 == 0x03 }

    /// 校正 byte2: SYS=bit7-6 GYR=bit5-4 ACC=bit3-2 MAG=bit1-0。I2C 失敗時の 0xFF は「不明」
    static func calText(_ flags: Int, _ cal: Int?) -> String {
        guard let cal else { return "--" }
        if flags & 0x01 == 0 { return "校正情報不明" }
        return "SYS \((cal >> 6) & 3)  GYR \((cal >> 4) & 3)  ACC \((cal >> 2) & 3)  MAG \(cal & 3)"
    }

    static func degText(_ v: Double?, _ valid: Bool, _ unit: String = "°") -> String {
        guard valid, let v else { return "--" }
        return String(format: "%.1f", v) + unit
    }

    static func errorNames(_ bits: Int) -> [String] {
        var names = errorBits.enumerated().filter { bits & (1 << $0.offset) != 0 }.map(\.element)
        if bits & 0xFF00 != 0 { names.append(String(format: "予約bit(0x%04X)", bits & 0xFF00)) }
        return names
    }

    static func liftLimitNames(_ bits: Int) -> [String] {
        var names = liftLimitBits.enumerated().filter { bits & (1 << $0.offset) != 0 }.map(\.element)
        if bits & 0xFFE0 != 0 { names.append(String(format: "予約bit(0x%04X)", bits & 0xFFE0)) }
        return names
    }

    static func pwmText(_ raw: Int?) -> String {
        guard let raw else { return "--" }
        if raw == I16_INVALID { return "取得不可" }
        // PWM は生の値 (permille、-1000～+1000) のまま表示する
        return signed(raw)
    }

    /// 実測RPM×10。有効フラグと無効値の両方が有効なときだけ値を出す (0rpm と未計測を区別)
    static func rpmText(_ flags: Int, _ rpm10: Int64?) -> String {
        guard let rpm10, flags & F_MEASURED != 0, rpm10 != I32_INVALID else { return "未計測" }
        return String(format: "%.1f rpm", Double(rpm10) / 10)
    }

    static func uptimeText(_ ms: Int64?) -> String {
        guard let ms else { return "--" }
        let s = ms / 1000
        return String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    static func rejectText(_ rej: String) -> String {
        if rej == "fmt" { return "形式不正 (拡張ID / 非FD / BRS ON)" }
        if rej.hasPrefix("len:") { return "長さ不正 (\(rej.dropFirst(4)) byte)" }
        if rej.hasPrefix("ver:") { return "未対応バージョン (\(rej.dropFirst(4)))" }
        return rej
    }

    // ---- 受信状態 (仕様 8 節) ----
    enum Link: Equatable {
        case notReceived
        case ok
        case lost(ageMs: Int64)
        case unsupported(reason: String)
    }

    static func link(_ t: Telemetry, _ u: Unit) -> Link {
        guard let f = t.frame(u.canId) else { return .notReceived }
        if let rej = f.reject { return .unsupported(reason: rejectText(rej)) }
        if f.ageMs < 0 { return .notReceived }
        if f.ageMs > t.schema.timeoutMs { return .lost(ageMs: f.ageMs) }
        return .ok
    }

    /// 値を読み出すための小道具
    struct Reader {
        let t: Telemetry
        let prefix: String
        init(_ t: Telemetry, _ prefix: String) { self.t = t; self.prefix = prefix }

        func int(_ key: String) -> Int? { t.value("\(prefix).\(key)").map { Int($0) } }
        func long(_ key: String) -> Int64? { t.value("\(prefix).\(key)").map { Int64($0) } }
        func dbl(_ key: String) -> Double? { t.value("\(prefix).\(key)") }
        var flags: Int { int("flags") ?? 0 }
        func has(_ flag: Int) -> Bool { flags & flag != 0 }
    }
}

// ---- 書式 (Kotlin の "%+d" / "%,d" / "%+,d" 相当) ----
func signed(_ v: Int) -> String { v >= 0 ? "+\(v)" : "\(v)" }
func signed(_ v: Int64) -> String { v >= 0 ? "+\(v)" : "\(v)" }
func grouped(_ v: Int64) -> String { v.formatted(.number.grouping(.automatic).locale(Locale(identifier: "en_US"))) }
func signedGrouped(_ v: Int64) -> String { v >= 0 ? "+" + grouped(v) : grouped(v) }
