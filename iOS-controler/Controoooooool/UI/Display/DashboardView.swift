import SwiftUI

/// 現場用のダッシュボード。全機構の状態を 1 画面にコンパクトに並べる (コントローラー情報は出さない)。
///
///   - 通信: 全 ID の受信状態を色付きの点で一覧
///   - 昇降: 昇降率バー + PWM バー (中央 0) + 状態 / 原点 / 異常
///   - 足回り: 4 輪を車体配置どおり 2×2 で、PWM バー + CMD + 状態
///   - 発射・装填: PWM バー、角度、指令、センサー
///   - 制御: 昇降の禁止指令、装填センサー
///
/// 途絶・未対応中は薄く表示し、値は前回値のまま (詳細は「機構」タブ)。
struct DashboardView: View {
    let t: Telemetry

    var body: some View {
        GeometryReader { g in
            ScrollView {
                VStack(spacing: 8) {
                    linkSummary
                    if g.size.width > 600 {
                        // 横画面・タブレット: 2 列
                        HStack(alignment: .top, spacing: 8) {
                            VStack(spacing: 8) { liftPanel; shotPanel }
                            VStack(spacing: 8) { wheelPanel; gyroPanel; controlPanel }
                        }
                    } else {
                        liftPanel; wheelPanel; gyroPanel; shotPanel; controlPanel
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
            }
        }
    }

    // ---- 通信状態 ----
    private var linkSummary: some View {
        Panel("通信") {
            FlowLayout(spacing: 10, lineSpacing: 4) {
                ForEach(DebugSpec.units) { u in
                    HStack(spacing: 3) {
                        Dot(color: Palette.link(DebugSpec.link(t, u)))
                        Text(String(format: "%03X", u.canId)).font(.mono(12))
                    }
                }
            }
        }
    }

    // ---- 昇降 ----
    private var liftPanel: some View {
        Panel("昇降") {
            ForEach(Array(["LIFT1", "LIFT2", "LIFT3"].enumerated()), id: \.offset) { i, p in
                let r = DebugSpec.Reader(t, p)
                let rate = r.int("rate").flatMap { $0 != DebugSpec.U16_INVALID && $0 <= 1000 ? $0 : nil }
                let pwm = r.int("pwm").flatMap { $0 != DebugSpec.I16_INVALID ? $0 : nil }
                Stale(DebugSpec.link(t, DebugSpec.unit(p))) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 0) {
                            RowLabel("\(i + 1)", 16)
                            RatioBar(ratio: rate.map { Double($0) / 1000 }, height: 12)
                            Value(rate.map { String(format: "%.1f%%", Double($0) / 10) } ?? "--", 58)
                        }
                        HStack(spacing: 0) {
                            Spacer().frame(width: 16)
                            CenterBar(ratio: pwm.map { Double($0) / 1000 })
                            Value(DebugSpec.pwmText(r.int("pwm")), 58)
                        }
                        Tags(tags: liftTags(r))
                    }
                }
            }
        }
    }

    private func liftTags(_ r: DebugSpec.Reader) -> [(String, Color)] {
        let homed = r.has(DebugSpec.F_HOMED)
        var tags: [(String, Color)] = [
            (DebugSpec.stateName(.lift, r.int("state")), Palette.none),
            (homed ? "HOME済" : "未原点", homed ? Palette.ok : Palette.warn),
        ]
        tags += DebugSpec.liftLimitNames(r.int("limit") ?? 0).map { ($0, Palette.warn) }
        if r.has(DebugSpec.F_V30) && !r.has(DebugSpec.F_PEER_FRESH) { tags.append(("他軸位置途絶", Palette.warn)) }
        tags += DebugSpec.errorNamesFor(.lift, r.int("err") ?? 0).map { ($0, Palette.bad) }
        return tags
    }

    // ---- 足回り (車体配置どおり 2×2) ----
    private var wheelPanel: some View {
        Panel("足回り") {
            ForEach([["WFL", "WFR"], ["WRL", "WRR"]], id: \.self) { row in
                HStack(alignment: .top, spacing: 8) {
                    ForEach(row, id: \.self) { p in wheel(p).frame(maxWidth: .infinity, alignment: .leading) }
                }
            }
        }
    }

    private func wheel(_ p: String) -> some View {
        let u = DebugSpec.unit(p)
        let r = DebugSpec.Reader(t, p)
        let pwm = r.int("pwm").flatMap { $0 != DebugSpec.I16_INVALID ? $0 : nil }
        let stop = r.int("stop") ?? 0
        let err = r.int("err") ?? 0
        let link = DebugSpec.link(t, u)
        return Stale(link) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Dot(color: Palette.link(link))
                    Text(u.label).font(.ui(13, weight: .bold))
                    Spacer()
                    Text("CMD \(r.int("cmd").map(signed) ?? "--")").font(.mono(12))
                }
                CenterBar(ratio: pwm.map { Double($0) / 1000 })
                Text(DebugSpec.pwmText(r.int("pwm")) + "  " + DebugSpec.stateName(.wheel, r.int("state")))
                    .font(.mono(12)).lineLimit(1)
                Tags(tags: (stop != 0 ? [(DebugSpec.stopReason(stop), Palette.warn)] : [])
                    + DebugSpec.errorNames(err).map { ($0, Palette.bad) })
            }
        }
    }

    // ---- ジャイロ (0x200) ----
    private var gyroPanel: some View {
        let r = DebugSpec.Reader(t, "GYRO")
        let flags = r.int("flags") ?? 0
        let valid = DebugSpec.gyroValid(flags)
        return Panel("ジャイロ") {
            Stale(DebugSpec.link(t, DebugSpec.unit("GYRO"))) {
                VStack(alignment: .leading, spacing: 3) {
                    Tags(tags: [
                        (valid ? "測定VALID" : "IMU測定無効", valid ? Palette.ok : Palette.warn),
                        (DebugSpec.calText(flags, r.int("cal")), Palette.none),
                    ])
                    Text("Y \(DebugSpec.degText(r.dbl("yaw"), valid))  R \(DebugSpec.degText(r.dbl("roll"), valid))  " +
                         "P \(DebugSpec.degText(r.dbl("pitch"), valid))")
                        .font(.mono(14))
                    Text("Gz \(DebugSpec.degText(r.dbl("gz"), valid, "dps"))   " +
                         "STATUS \(r.int("sys_status").map(String.init) ?? "--") ERR \(r.int("sys_err").map(String.init) ?? "--")")
                        .font(.mono(12))
                }
            }
        }
    }

    // ---- 発射・装填 (0x310: 操作mainの指令状態) ----
    private var shotPanel: some View {
        let r = DebugSpec.Reader(t, "SHOT")
        let pwm = r.int("pwm")
        return Panel("発射・装填 (指令)") {
            Stale(DebugSpec.link(t, DebugSpec.unit("SHOT"))) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 0) {
                        RowLabel("PWM", 40)
                        RatioBar(ratio: pwm.map { Double($0) / 1000 })
                        Value(pwm.map(String.init) ?? "--", 58)
                    }
                    Text("角度 \(r.int("angle").map { "\($0)°" } ?? "--")   " +
                         "プリセット \(DebugSpec.presetName(r.int("preset")))   " +
                         "段階 \(r.int("stage").map(String.init) ?? "--")")
                        .font(.mono(13))
                    Tags(tags: DebugSpec.cmd310Names(r.int("cmd") ?? 0).map { ($0, $0 == "停止ラッチ" ? Palette.bad : Palette.ok) }
                        + [("センサー " + DebugSpec.sensorName(r.int("sensor")), Palette.none)]
                        + DebugSpec.errorNames(r.int("err") ?? 0).map { ($0, Palette.bad) })
                }
            }
        }
    }

    // ---- 制御用 ID ----
    private var controlPanel: some View {
        let lc = DebugSpec.Reader(t, "LCMD")
        let ls = DebugSpec.Reader(t, "LSEN")
        let names = DebugSpec.lift130Names(lc.int("flags") ?? 0)
        let sensor = (ls.int("sensor") ?? 0) != 0
        let ready = (ls.int("ready") ?? 0) != 0
        return Panel("制御") {
            Stale(DebugSpec.link(t, DebugSpec.unit("LCMD"))) {
                HStack(spacing: 0) {
                    RowLabel("昇降指令", 92)
                    if names.isEmpty { Text("制限なし").font(.ui(13)) }
                    else { Tags(tags: names.map { ($0, Palette.warn) }) }
                }
            }
            Stale(DebugSpec.link(t, DebugSpec.unit("LSEN"))) {
                HStack(spacing: 0) {
                    RowLabel("装填センサー", 92)
                    Tags(tags: [
                        (sensor ? "検出" : "非検出", sensor ? Palette.ok : Palette.none),
                        (ready ? "準備完了" : "準備中", ready ? Palette.ok : Palette.warn),
                    ])
                }
            }
        }
    }
}

// ---- 部品 ----
private struct Panel<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    init(_ title: String, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }

    var body: some View {
        CardBox {
            CardTitle(title)
            content
        }
    }
}

/// 受信中以外は薄く表示 (値は前回値)
private struct Stale<Content: View>: View {
    let link: DebugSpec.Link
    @ViewBuilder var content: Content
    init(_ link: DebugSpec.Link, @ViewBuilder content: () -> Content) { self.link = link; self.content = content() }

    var body: some View { content.opacity(link == .ok ? 1 : 0.4) }
}

private struct RowLabel: View {
    let text: String
    let width: CGFloat
    init(_ text: String, _ width: CGFloat) { self.text = text; self.width = width }
    var body: some View {
        Text(text).font(.ui(13, weight: .bold)).frame(width: width, alignment: .leading)
    }
}

private struct Value: View {
    let text: String
    let width: CGFloat
    init(_ text: String, _ width: CGFloat) { self.text = text; self.width = width }
    var body: some View {
        Text(text).font(.mono(13)).lineLimit(1).minimumScaleFactor(0.7)
            .padding(.leading, 6).frame(width: width, alignment: .leading)
    }
}
