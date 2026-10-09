import SwiftUI

/// 汎用表示: ESP32 から届いた信号定義 (schema) をそのまま一覧表示する。
/// can_signals.h を変えればアプリを変更しなくても自動で追従する。
struct SignalListView: View {
    let t: Telemetry

    private var groups: [(String, [SignalInfo])] {
        // 受信順 (schema の並び) を保ったままグループ化
        var order: [String] = []
        var map: [String: [SignalInfo]] = [:]
        for s in t.schema.signals {
            if map[s.group] == nil { order.append(s.group) }
            map[s.group, default: []].append(s)
        }
        return order.map { ($0, map[$0]!) }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                header
                ForEach(groups, id: \.0) { group, signals in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(group.isEmpty ? "-" : group).font(.system(.headline, design: .rounded, weight: .bold))
                        ForEach(signals.filter { !$0.isBool }, id: \.name) { numberRow($0) }
                        let bools = signals.filter(\.isBool)
                        if !bools.isEmpty {
                            FlowLayout(spacing: 6, lineSpacing: 6) {
                                ForEach(bools, id: \.name) { boolChip($0) }
                            }
                            .padding(.top, 4)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder private var header: some View {
        if t.frames.isEmpty {
            Text("信号定義を受信待ち…")
        } else {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(t.frames, id: \.canId) { f in
                    let fresh = (0...t.schema.timeoutMs).contains(f.ageMs)
                    Text(String(format: "ID 0x%03X  ", f.canId)
                         + (f.ageMs < 0 ? "未受信" : fresh ? "受信中" : "途絶 (\(f.ageMs) ms)")
                         + String(format: "  %.0f Hz", f.hz))
                        .font(.mono(14))
                        .foregroundStyle(fresh ? Color.brand : Color.red)
                }
                if !t.unknown.isEmpty {
                    Text("定義外の ID (診断用)").font(.system(.headline, design: .rounded, weight: .bold))
                    ForEach(t.unknown, id: \.canId) { u in
                        Text(String(format: "ID 0x%03X", u.canId) + (u.extended ? " 拡張" : "")
                             + "  \(u.len)B \(u.fd ? "FD" : "CC")\(u.brs ? "+BRS" : "")  \(u.count)件  "
                             + (u.ageMs <= t.schema.timeoutMs ? "受信中" : "\(u.ageMs / 1000)s前"))
                            .font(.mono(12))
                    }
                }
                Text("ESP32 稼働 \(DebugSpec.uptimeText(t.espUptimeMs))  再起動 \(t.espReboots)回  起動理由 \(resetReasonText(t.espResetReason))")
                    .font(.mono(12))
                    .foregroundStyle(t.espReboots > 0 ? Color.red : Color.primary)
                Text("UART ok=\(t.uartOk) crc_err=\(t.uartCrcErrors)").font(.mono(12))
            }
        }
    }

    private func numberRow(_ s: SignalInfo) -> some View {
        let v = t.value(s.name)
        let fresh = t.isFresh(s.name)
        let ratio: Double? = v.flatMap { s.max > s.min ? ($0 - s.min) / (s.max - s.min) : nil }
        let text = (v.map { $0.truncatingRemainder(dividingBy: 1) == 0 ? String(Int64($0)) : String(format: "%.3f", $0) } ?? "--")
            + (s.unit.isEmpty ? "" : " \(s.unit)")
        return HStack(spacing: 0) {
            Text(s.name).font(.mono(14)).frame(width: 96, alignment: .leading)
            RatioBar(ratio: ratio, color: fresh ? .brand : .gray, height: 14)
            Text(text).font(.mono(14)).lineLimit(1).minimumScaleFactor(0.6)
                .padding(.leading, 8).frame(width: 88, alignment: .leading)
        }
        .padding(.vertical, 2)
    }

    private func boolChip(_ s: SignalInfo) -> some View {
        let on = t.pressed(s.name)
        let fresh = t.isFresh(s.name)
        let bg: Color = !fresh ? Color.gray.opacity(0.15) : on ? .brand : Color.gray.opacity(0.25)
        let fg: Color = !fresh ? .secondary : on ? .white : .primary
        return Text(s.name)
            .fontWeight(on ? .bold : .regular)
            .foregroundStyle(fg)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(bg, in: Capsule())
    }
}

/// esp_reset_reason() の値。電源起動以外 (特に 9=ブラウンアウト) は電源や処理落ちの手掛かり
private func resetReasonText(_ r: Int) -> String {
    switch r {
    case -1: "--"
    case 1: "電源投入"
    case 3: "ソフトリセット"
    case 4: "クラッシュ"
    case 5, 6, 7: "ウォッチドッグ"
    case 9: "電源低下(ブラウンアウト)"
    default: "コード\(r)"
    }
}
