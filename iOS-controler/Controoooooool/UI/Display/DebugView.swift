import SwiftUI

/// デバッグ用 CAN FD 通信仕様 V2 の表示 (昇降 ×3 / 発射 / 足回り ×4)。
///
/// 仕様 8 節に従い:
///   - 初受信前は「未受信」、最後の有効受信から 300ms 超は「通信途絶」
///   - 途絶中・未対応中の値は薄く表示し「前回値」と明記する
///   - 無効値 / 有効フラグ OFF は「未計測」。0rpm と未計測を区別する
///   - 不明な状態番号・バージョンは「未対応」
struct DebugView: View {
    let t: Telemetry

    private let sections: [(String, DebugSpec.Mech)] = [
        ("昇降", .lift), ("発射・装填 (指令状態)", .shot), ("足回り", .wheel), ("ジャイロ", .gyro),
    ]
    private let columns = [GridItem(.adaptive(minimum: 320), spacing: 8, alignment: .top)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                ForEach(sections, id: \.0) { title, mech in
                    Section {
                        ForEach(DebugSpec.units.filter { $0.mech == mech }) { UnitCard(u: $0, t: t) }
                    } header: {
                        sectionHeader(title, mech)
                    }
                }
                Section {
                    ForEach(DebugSpec.units.filter { $0.mech.isControl }) { UnitCard(u: $0, t: t) }
                } header: {
                    CardTitle("制御・安全用 ID", size: 20).padding(.top, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } footer: {
                    Text("10Hz の状態監視用。瞬間的な変化は見逃すことがあります。")
                        .font(.caption).foregroundStyle(.secondary).padding(.vertical, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 12)
        }
    }

    private func sectionHeader(_ title: String, _ mech: DebugSpec.Mech) -> some View {
        HStack(alignment: .bottom) {
            CardTitle(title, size: 20)
            if mech == .lift {
                Spacer()
                Text(liftSpread()).font(.mono(12))
            }
        }
        .padding(.top, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 3 軸の昇降率の最大－最小 (診断用。安全判定には使わない)
    private func liftSpread() -> String {
        let rates = DebugSpec.units.filter { $0.mech == .lift }.map { u -> Int? in
            let r = DebugSpec.Reader(t, u.prefix).int("rate")
            if DebugSpec.link(t, u) == .ok, let r, r <= 1000 { return r }
            return nil
        }
        let v = rates.compactMap { $0 }
        guard v.count == rates.count, let mx = v.max(), let mn = v.min() else { return "率の差: --" }
        return String(format: "率の差 (max-min): %.1f%%", Double(mx - mn) / 10)
    }
}

private struct UnitCard: View {
    let u: DebugSpec.Unit
    let t: Telemetry

    var body: some View {
        let link = DebugSpec.link(t, u)
        let frame = t.frame(u.canId)
        let hasValues = t.value("\(u.prefix).ver") != nil || t.value("\(u.prefix).b0") != nil
        CardBox(padding: 12, spacing: 4) {
            HStack(alignment: .top) {
                Text(u.label + String(format: "  0x%03X", u.canId)).font(.system(.headline, design: .rounded, weight: .bold))
                Spacer()
                LinkBadge(link: link)
            }
            if !hasValues {
                Text("--").font(.mono(15))
            } else {
                let stale = link != .ok
                if stale { Text("前回値").font(.caption2).foregroundStyle(Palette.bad) }
                VStack(alignment: .leading, spacing: 4) {
                    let r = DebugSpec.Reader(t, u.prefix)
                    switch u.mech {
                    case .lift: liftBody(r)
                    case .shot: shotBody(r)
                    case .wheel: wheelBody(r)
                    case .gyro: gyroBody(r)
                    case .liftCmd: liftCmdBody(r)
                    case .liftSt: Mono((0..<8).map { i in r.int("b\(i)").map { String(format: "%02X", $0) } ?? "--" }.joined(separator: " "))
                    case .shotCmd: shotCmdBody(r)
                    case .loadSens: loadSensBody(r)
                    }
                    if !u.mech.isControl && u.mech != .gyro { commonFooter(r, u.mech) }
                }
                .opacity(stale ? 0.5 : 1)
            }
            if let f = frame, f.seq != nil || f.rejected > 0 {
                Text(frameText(f)).font(.mono(11)).foregroundStyle(.secondary)
            }
        }
    }

    private func frameText(_ f: FrameStatus) -> String {
        var s = "経過 \(f.ageMs < 0 ? "--" : "\(f.ageMs)ms")  SEQ \(f.seq.map(String.init) ?? "--")  欠落 \(f.seqLost)"
        if f.duplicates > 0 { s += "  重複 \(f.duplicates)" }
        if f.rejected > 0 { s += "  不正 \(f.rejected)" }
        s += String(format: "  %.0fHz", f.hz)
        return s
    }

    // ---- 昇降 ----
    @ViewBuilder private func liftBody(_ r: DebugSpec.Reader) -> some View {
        let rate = r.int("rate")
        let rateText = rate == nil || rate == DebugSpec.U16_INVALID ? "--"
            : rate! > 1000 ? "範囲外(\(rate!))" : String(format: "%.1f%%", Double(rate!) / 10)
        let homed = r.has(DebugSpec.F_HOMED)
        MainLine(rateText, DebugSpec.stateName(.lift, r.int("state")),
                 "PWM \(DebugSpec.pwmText(r.int("pwm")))", homed ? "HOME済" : "未原点")
        let pos = r.long("pos"), ref = r.long("ref")
        let posText = pos == nil || !r.has(DebugSpec.F_MEASURED) || pos == DebugSpec.I32_INVALID ? "未計測"
            : homed ? "\(pos!)" : "\(pos!) (未原点)"
        let refText = ref == nil || !r.has(DebugSpec.F_TARGET) || ref == DebugSpec.I32_INVALID ? "未計測" : "\(ref!)"
        Mono("POS \(posText)   REF \(refText)")
        // V30: リミット bit は有効性フラグを持たない (bit2 は「下限SW機能有効」の意味)
        BitChips(label: "リミット", names: DebugSpec.liftLimitNames(r.int("limit") ?? 0), empty: "なし", color: Palette.warn)
        Mono("下限SW機能 \(r.has(DebugSpec.F_LOWER_SW) ? "ON" : "OFF")   " +
             (!r.has(DebugSpec.F_V30) ? "V30未対応(旧版)" : "他軸位置 \(r.has(DebugSpec.F_PEER_FRESH) ? "受信OK" : "途絶")"))
    }

    // ---- ジャイロ 0x200 ----
    @ViewBuilder private func gyroBody(_ r: DebugSpec.Reader) -> some View {
        let flags = r.int("flags") ?? 0
        let valid = DebugSpec.gyroValid(flags)
        MainLine(valid ? "測定 VALID" : "IMU測定無効", "I2C \(flags & 1 != 0 ? "OK" : "NG")",
                 r.int("addr").map { String(format: "0x%02X", $0) } ?? "--")
        // 測定無効のとき角度・角速度欄は 0 で送られてくるが、実際の 0 ではないので「--」にする
        Mono("Yaw \(DebugSpec.degText(r.dbl("yaw"), valid))  Roll \(DebugSpec.degText(r.dbl("roll"), valid))  Pitch \(DebugSpec.degText(r.dbl("pitch"), valid))")
        Mono("Gyro X \(DebugSpec.degText(r.dbl("gx"), valid, "dps"))  Y \(DebugSpec.degText(r.dbl("gy"), valid, "dps"))  Z \(DebugSpec.degText(r.dbl("gz"), valid, "dps"))")
        Mono("校正 \(DebugSpec.calText(flags, r.int("cal")))")
        Mono("SYS_STATUS \(r.int("sys_status").map(String.init) ?? "--")   SYS_ERR \(r.int("sys_err").map(String.init) ?? "--")")
        Mono("稼働 \(DebugSpec.uptimeText(r.long("uptime")))")
    }

    // ---- 発射・装填・サーボ指令状態 0x310 (操作mainが把握している指令。機構の応答ではない) ----
    @ViewBuilder private func shotBody(_ r: DebugSpec.Reader) -> some View {
        MainLine(DebugSpec.stateName(.shot, r.int("state")), "PWM \(r.int("pwm").map(String.init) ?? "--")",
                 "角度 \(r.int("angle").map { "\($0)°" } ?? "--")")
        BitChips(label: "指令", names: DebugSpec.cmd310Names(r.int("cmd") ?? 0), empty: "なし", color: Palette.ok)
        Mono("調整 \(DebugSpec.adjustName(r.int("adjust")))   プリセット \(DebugSpec.presetName(r.int("preset")))   装填段階 \(r.int("stage").map(String.init) ?? "--")")
        Mono("センサー \(DebugSpec.sensorName(r.int("sensor"))) (\(DebugSpec.ageText(r.int("sensor_age"))))")
        Mono("コントローラー受信 \(DebugSpec.ageText(r.int("ctrl_age")))")
    }

    // ---- 制御用 ID ----
    @ViewBuilder private func liftCmdBody(_ r: DebugSpec.Reader) -> some View {
        BitChips(label: "指令", names: DebugSpec.lift130Names(r.int("flags") ?? 0), empty: "なし", color: Palette.warn)
        Mono("ver \(r.int("ver").map(String.init) ?? "--")   SEQ \(r.int("seq").map(String.init) ?? "--")")
    }

    @ViewBuilder private func shotCmdBody(_ r: DebugSpec.Reader) -> some View {
        MainLine("PWM \(r.int("pwm").map(String.init) ?? "--")", "角度 \(r.int("angle").map { "\($0)°" } ?? "--")",
                 "プリセット \(DebugSpec.presetName(r.int("preset")))")
        BitChips(label: "指令", names: DebugSpec.cmd150Names(r.int("flags") ?? 0), empty: "なし", color: Palette.ok)
    }

    private func loadSensBody(_ r: DebugSpec.Reader) -> some View {
        MainLine("センサー \((r.int("sensor") ?? 0) != 0 ? "検出" : "非検出")",
                 (r.int("ready") ?? 0) != 0 ? "準備完了" : "準備中")
    }

    // ---- 足回り ----
    @ViewBuilder private func wheelBody(_ r: DebugSpec.Reader) -> some View {
        MainLine(DebugSpec.stateName(.wheel, r.int("state")), "CMD \(r.int("cmd").map(signed) ?? "--")",
                 "PWM \(DebugSpec.pwmText(r.int("pwm")))")
        Mono("RPM \(DebugSpec.rpmText(r.flags, r.long("rpm10")))   RX \(r.long("rx").map(String.init) ?? "--")")
        Mono("STOP理由 \(DebugSpec.stopReason(r.int("stop")))")
    }

    // ---- 共通: 指令受信状態 / 異常ビット / 稼働時間 ----
    @ViewBuilder private func commonFooter(_ r: DebugSpec.Reader, _ mech: DebugSpec.Mech) -> some View {
        Mono((r.has(DebugSpec.F_CMD_FRESH) ? "指令受信OK" : "指令受信なし") + "   稼働 \(DebugSpec.uptimeText(r.long("uptime")))")
        BitChips(label: "異常", names: DebugSpec.errorNamesFor(mech, r.int("err") ?? 0), empty: "検出なし", color: Palette.bad)
    }
}

private struct LinkBadge: View {
    let link: DebugSpec.Link
    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            Tag(Palette.linkText(link), link == .notReceived ? Color(hex: 0x757575) : Palette.link(link), size: 12)
            if case .unsupported(let reason) = link {
                Text(reason).font(.caption2).foregroundStyle(Palette.warn)
            }
        }
    }
}

private struct MainLine: View {
    let text: String
    init(_ parts: String...) { text = parts.joined(separator: "   ") }
    var body: some View { Text(text).font(.mono(16)) }
}

private struct Mono: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.mono(13)) }
}

private struct BitChips: View {
    let label: String
    let names: [String]
    let empty: String
    let color: Color
    var body: some View {
        FlowLayout(spacing: 4, lineSpacing: 4) {
            Text("\(label):").font(.ui(13))
            if names.isEmpty {
                Text(empty).font(.ui(13))
            } else {
                ForEach(names, id: \.self) { Tag($0, color, size: 12) }
            }
        }
    }
}
