import SwiftUI

/// 調整用の表示: 調整モードの有無 / サーボ角度 / 発射 PWM / 昇降のエンコーダー。
///
///   - 調整モード: 0x310 byte12 bit5 (MINUS 調整) を優先、無ければ 0x150 byte1 bit2。調整対象は 0x310 byte13
///   - サーボ角度 (投擲角度の設定) と発射 PWM の設定: 0x310 を優先、無ければ 0x150
///   - エンコーダー: 昇降 3 軸の POS / REF (0x301-0x303)。HOME・有効フラグ・エンコーダー関連の異常を併記
///
/// 途絶・未受信の ID の値は薄く表示し、値は前回値のまま (「前回値」と明記)。
struct TuningView: View {
    let t: Telemetry

    /// サーボ角度ゲージの目盛 (度)。実際の可動範囲に合わせて変える
    static let angleMax = 180.0
    private let lifts = ["LIFT1", "LIFT2", "LIFT3"]

    var body: some View {
        GeometryReader { g in
            if g.size.width > g.size.height && g.size.height < 480 {
                landscape(k: min(max(g.size.height / 300, 0.6), 1.2))
            } else {
                portrait
            }
        }
    }

    // ---- 縦画面 ----
    private var portrait: some View {
        GeometryReader { g in
            ScrollView {
                VStack(spacing: 10) {
                    adjustBanner
                    if g.size.width > 600 {
                        HStack(alignment: .top, spacing: 10) {
                            VStack(spacing: 10) { servoCard; pwmCard }
                            encoderCard
                        }
                    } else {
                        servoCard; pwmCard; encoderCard
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
            }
        }
    }

    // ---- 横画面: スクロールなしで 1 画面に収める (3 列) ----
    // 左: 調整モード + 発射 PWM / 中: サーボ角度 / 右: 昇降エンコーダー 3 軸
    // 高さが小さい機種でも収まるよう、文字サイズを高さに比例して縮める (基準 300pt)
    private func landscape(k: CGFloat) -> some View {
        GeometryReader { g in
            let unitW = (g.size.width - 16) / 3.7
            HStack(spacing: 8) {
                VStack(spacing: 8) {
                    compactBanner(k)
                    compactPwm(k).frame(maxHeight: .infinity)
                }
                .frame(width: unitW)
                compactServo(k).frame(width: unitW)
                compactEncoders(k).frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 6)
    }

    private func compactBanner(_ k: CGFloat) -> some View {
        let a = adjustState()
        let (bg, text) = adjustStyle(a.on)
        var sub = ""
        if let target = a.target { sub += "対象: \(target)  " }
        if a.mismatch { sub += "0x310/0x150 不一致  " }
        sub += a.source
        return VStack(alignment: .leading, spacing: 0) {
            Text(text).font(.system(size: 20 * k, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6)
            Text(sub).font(.system(size: 11 * k)).opacity(0.9).lineLimit(2)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 10).padding(.vertical, 6 * k)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentCard(tint: bg)
    }

    private func compactServo(_ k: CGFloat) -> some View {
        let src = pick("angle", "angle")
        let preset = DebugSpec.Reader(t, "SHOT").int("preset")
        return CompactCard(title: "サーボ角度", src: src, k: k) {
            Gauge(angle: src.value).frame(maxHeight: .infinity)
            HStack {
                Text("0°"); Spacer(); Text("\(Int(Self.angleMax))°")
            }
            .font(.system(size: 10 * k)).padding(.horizontal, 4)
            Big(src.value.map { String(format: "%.0f°", $0) } ?? "--", 30 * k)
            Text("プリセット \(DebugSpec.presetName(preset))").font(.system(size: 12 * k))
            CompactMismatch(src: src, unit: "°", k: k)
        }
    }

    private func compactPwm(_ k: CGFloat) -> some View {
        let src = pick("pwm", "pwm")
        return CompactCard(title: "発射 PWM", src: src, k: k) {
            Big(src.value.map { String(format: "%.0f", $0) } ?? "--", 30 * k)
            RatioBar(ratio: src.value.map { $0 / 1000 }, height: 14 * k)
            CompactMismatch(src: src, unit: "‰", k: k)
            Spacer(minLength: 0)
        }
    }

    private func compactEncoders(_ k: CGFloat) -> some View {
        VStack(alignment: .leading) {
            HStack {
                CardTitle("昇降エンコーダー", size: 14 * k, fixed: true)
                Spacer()
                Text("3軸 POS差 " + (posSpread().map(grouped) ?? "--"))
                    .font(.monoFixed(11 * k)).foregroundStyle(.secondary).lineLimit(1)
            }
            ForEach(Array(lifts.enumerated()), id: \.offset) { i, p in
                Spacer(minLength: 0)
                compactEncoderRow(p, no: i + 1, k: k)
            }
            Spacer(minLength: 0)
        }
        .padding(8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .contentCard()
    }

    @ViewBuilder
    private func compactEncoderRow(_ prefix: String, no: Int, k: CGFloat) -> some View {
        let link = DebugSpec.link(t, DebugSpec.unit(prefix))
        let r = DebugSpec.Reader(t, prefix)
        if r.dbl("ver") == nil {
            Text("昇降\(no)  --  (\(Palette.linkText(link)))").font(.monoFixed(13 * k))
        } else {
            let pos = encoder(r, "pos", DebugSpec.F_MEASURED)
            let ref = encoder(r, "ref", DebugSpec.F_TARGET)
            let rate = r.int("rate").flatMap { $0 != DebugSpec.U16_INVALID && $0 <= 1000 ? $0 : nil }
            let homed = r.has(DebugSpec.F_HOMED)
            // 横画面は 1 行に収めるため、エンコーダー関連の異常は短い名前にする (詳細は縦画面 / 機構タブ)
            let e = r.int("err") ?? 0
            let encErr = [(0x02, "停滞"), (0x10, "逆転"), (0x20, "飛び")].filter { e & $0.0 != 0 }.map(\.1)
            // 1 軸 = 2 行 (1 行目: 軸名・タグ・率 / 2 行目: POS・REF・差)。3 軸が確実に 1 画面へ収まるようにする
            VStack(alignment: .leading, spacing: 2 * k) {
                HStack(spacing: 4) {
                    Dot(color: Palette.link(link), size: 8)
                    Text("昇降\(no)").font(.system(size: 13 * k, weight: .bold))
                    if link != .ok { Text("前回値").font(.system(size: 10 * k)).foregroundStyle(Palette.bad) }
                    // 正常時は HOME済 のみ。未原点・無効・エンコーダー異常のときだけ追加で出す
                    HStack(spacing: 3) {
                        Tag(homed ? "HOME済" : "未原点", homed ? Palette.ok : Palette.warn, size: 11 * k)
                        if !r.has(DebugSpec.F_MEASURED) { Tag("無効", Palette.warn, size: 11 * k) }
                        ForEach(encErr, id: \.self) { Tag($0, Palette.bad, size: 11 * k) }
                    }
                    Spacer(minLength: 0)
                    Text(rate.map { String(format: "%.1f%%", Double($0) / 10) } ?? "率 --").font(.monoFixed(12 * k))
                }
                HStack {
                    NumberText(label: "POS", value: pos.map(grouped) ?? "未計測", k: k)
                    Spacer(minLength: 4)
                    NumberText(label: "REF", value: ref.map(grouped) ?? "未計測", k: k)
                    Spacer(minLength: 4)
                    NumberText(label: "差", value: (pos != nil && ref != nil) ? signedGrouped(ref! - pos!) : "--", k: k)
                }
            }
            .opacity(link == .ok ? 1 : 0.45)
        }
    }

    // ---- 値の取得 (0x310 優先、無ければ 0x150) ----
    struct Source {
        let value: Double?
        let from: String
        let link: DebugSpec.Link
        let other: Double?
    }

    private func pick(_ key310: String, _ key150: String) -> Source {
        let a = DebugSpec.Reader(t, "SHOT").dbl(key310)
        let b = DebugSpec.Reader(t, "SCMD").dbl(key150)
        if let a { return Source(value: a, from: "0x310", link: DebugSpec.link(t, DebugSpec.unit("SHOT")), other: b) }
        if let b { return Source(value: b, from: "0x150", link: DebugSpec.link(t, DebugSpec.unit("SCMD")), other: nil) }
        return Source(value: nil, from: "", link: .notReceived, other: nil)
    }

    // ---- 調整モード ----
    private struct Adjust { let on: Bool?; let mismatch: Bool; let target: String?; let source: String }

    private func adjustState() -> Adjust {
        let shot = DebugSpec.Reader(t, "SHOT")
        let scmd = DebugSpec.Reader(t, "SCMD")
        let ok310 = shot.dbl("ver") != nil && DebugSpec.link(t, DebugSpec.unit("SHOT")) == .ok
        let ok150 = scmd.dbl("ver") != nil && DebugSpec.link(t, DebugSpec.unit("SCMD")) == .ok
        let on310 = (shot.int("cmd") ?? 0) & 0x20 != 0
        let on150 = (scmd.int("flags") ?? 0) & 0x04 != 0
        // 新鮮なデータだけで判断する (途絶中の前回値で「ON」と言い続けない)
        let on: Bool? = ok310 ? on310 : ok150 ? on150 : nil
        return Adjust(
            on: on,
            mismatch: ok310 && ok150 && on310 != on150,
            target: on == true && ok310 ? DebugSpec.adjustName(shot.int("adjust")) : nil,
            source: ok310 ? "出典 0x310" : ok150 ? "出典 0x150" : "0x310/0x150 受信なし"
        )
    }

    private func adjustStyle(_ on: Bool?) -> (Color, String) {
        switch on {
        case true?: (Palette.warn, "調整モード ON")
        case false?: (Palette.ok, "調整モード OFF (通常)")
        case nil: (Palette.none, "調整モード: データなし")
        }
    }

    private var adjustBanner: some View {
        let a = adjustState()
        let (bg, text) = adjustStyle(a.on)
        return VStack(alignment: .leading, spacing: 4) {
            Text(text).font(.ui(26, weight: .bold))
            if let target = a.target { Text("調整対象: \(target)").font(.ui(18)) }
            if a.mismatch { Text("0x310 と 0x150 で調整モードが食い違っています").font(.ui(13)) }
            Text(a.source).font(.ui(12)).opacity(0.85)
        }
        .foregroundStyle(.white)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentCard(tint: bg)
    }

    // ---- サーボ角度 ----
    private var servoCard: some View {
        let src = pick("angle", "angle")
        let preset = DebugSpec.Reader(t, "SHOT").int("preset")
        return Panel(title: "サーボ角度 (投擲角度の設定)", src: src) {
            HStack(alignment: .center) {
                VStack(spacing: 2) {
                    Gauge(angle: src.value).aspectRatio(2, contentMode: .fit)
                    HStack { Text("0°"); Spacer(); Text("\(Int(Self.angleMax))°") }
                        .font(.ui(11)).padding(.horizontal, 6)
                }
                VStack(alignment: .trailing) {
                    Big(src.value.map { String(format: "%.0f°", $0) } ?? "--")
                    Text("プリセット \(DebugSpec.presetName(preset))").font(.ui(14))
                }
                .padding(.leading, 12)
            }
            Mismatch(src: src, unit: "°")
        }
    }

    // ---- 発射 PWM ----
    private var pwmCard: some View {
        let src = pick("pwm", "pwm")
        return Panel(title: "発射 PWM (設定・生の値 0〜1000)", src: src) {
            Big(src.value.map { String(format: "%.0f", $0) } ?? "--")
            RatioBar(ratio: src.value.map { $0 / 1000 }, height: 22)
            HStack { Text("0"); Spacer(); Text("1000") }.font(.ui(11))
            Mismatch(src: src, unit: "‰")
        }
    }

    // ---- 昇降のロータリーエンコーダー ----
    private var encoderCard: some View {
        CardBox(padding: 12, spacing: 10) {
            CardTitle("昇降エンコーダー")
            ForEach(Array(lifts.enumerated()), id: \.offset) { i, p in encoderRow(p, no: i + 1) }
            let v = posSpread()
            Text(v == nil ? "3軸の POS 差: --  (全軸が受信中かつ原点確定のときだけ計算)"
                          : "3軸の POS 差 (max-min): \(grouped(v!))  [監視側の推定]")
                .font(.ui(12)).foregroundStyle(.secondary)
        }
    }

    private func encoderRow(_ prefix: String, no: Int) -> some View {
        let link = DebugSpec.link(t, DebugSpec.unit(prefix))
        let r = DebugSpec.Reader(t, prefix)
        let hasData = r.dbl("ver") != nil
        let pos = encoder(r, "pos", DebugSpec.F_MEASURED)
        let ref = encoder(r, "ref", DebugSpec.F_TARGET)
        let rate = r.int("rate").flatMap { $0 != DebugSpec.U16_INVALID && $0 <= 1000 ? $0 : nil }
        let homed = r.has(DebugSpec.F_HOMED)
        let measured = r.has(DebugSpec.F_MEASURED)
        // エンコーダーに関する故障のビット (停滞 / 逆方向カウント / カウント飛び)
        let encErr = DebugSpec.errorNamesFor(.lift, (r.int("err") ?? 0) & 0x0032)

        return VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Dot(color: Palette.link(link), size: 10)
                Text("昇降\(no)").font(.ui(15, weight: .bold))
                if link != .ok && hasData { Text("前回値").font(.ui(12)).foregroundStyle(Palette.bad) }
                Spacer()
                Text(rate.map { String(format: "%.1f%%", Double($0) / 10) } ?? "率 --").font(.mono(14))
            }
            if !hasData {
                Text("--  (\(Palette.linkText(link)))").font(.mono(15))
            } else {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading) {
                        Text("POS").font(.ui(11)).foregroundStyle(.secondary)
                        Big(pos.map(grouped) ?? "未計測", 26)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    VStack(alignment: .leading) {
                        Text("REF").font(.ui(11)).foregroundStyle(.secondary)
                        Big(ref.map(grouped) ?? "未計測", 26)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    VStack(alignment: .trailing) {
                        Text("差 (REF-POS)").font(.ui(11)).foregroundStyle(.secondary)
                        Text((pos != nil && ref != nil) ? signedGrouped(ref! - pos!) : "--").font(.mono(18))
                    }
                }
                RatioBar(ratio: rate.map { Double($0) / 1000 }, height: 10)
                Tags(tags: [
                    (measured ? "エンコーダー有効" : "エンコーダー無効", measured ? Palette.ok : Palette.warn),
                    (homed ? "HOME済" : "未原点 (POSは高さではない)", homed ? Palette.ok : Palette.warn),
                ] + encErr.map { ($0, Palette.bad) })
            }
        }
        .opacity(link == .ok ? 1 : 0.45)
    }

    /// 3 軸の POS の差 (max-min)。全軸が受信中かつ原点確定のときだけ計算。監視側の推定
    private func posSpread() -> Int64? {
        let vals = lifts.map { p -> Int64? in
            let r = DebugSpec.Reader(t, p)
            guard DebugSpec.link(t, DebugSpec.unit(p)) == .ok, r.has(DebugSpec.F_HOMED) else { return nil }
            return encoder(r, "pos", DebugSpec.F_MEASURED)
        }
        let v = vals.compactMap { $0 }
        guard v.count == vals.count, let mx = v.max(), let mn = v.min() else { return nil }
        return mx - mn
    }

    /// エンコーダー値。有効フラグ OFF や INT32_MIN は nil (未計測)
    private func encoder(_ r: DebugSpec.Reader, _ key: String, _ flag: Int) -> Int64? {
        guard let v = r.long(key), r.has(flag), v != DebugSpec.I32_INVALID else { return nil }
        return v
    }
}

// ---- 部品 ----
private struct Panel<Content: View>: View {
    let title: String
    let src: TuningView.Source
    @ViewBuilder var content: Content

    var body: some View {
        CardBox(padding: 12) {
            HStack {
                CardTitle(title)
                Spacer()
                SourceBadge(src: src, size: 12)
            }
            VStack(alignment: .leading, spacing: 6) { content }
                .opacity(src.link == .ok ? 1 : 0.45)
            if src.value == nil { Text("データなし").foregroundStyle(Palette.none) }
        }
    }
}

private struct CompactCard<Content: View>: View {
    let title: String
    let src: TuningView.Source
    let k: CGFloat
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 3 * k) {
            HStack {
                CardTitle(title, size: 14 * k, fixed: true)
                Spacer(minLength: 0)
                SourceBadge(src: src, size: 10 * k)
            }
            VStack(alignment: .leading, spacing: 3 * k) { content }
                .frame(maxHeight: .infinity, alignment: .top)
                .opacity(src.link == .ok ? 1 : 0.45)
            if src.value == nil { Text("データなし").font(.system(size: 12 * k)).foregroundStyle(Palette.none) }
        }
        .padding(8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .contentCard()
    }
}

private struct SourceBadge: View {
    let src: TuningView.Source
    let size: CGFloat
    var body: some View {
        if src.value != nil {
            HStack(spacing: 4) {
                Dot(color: Palette.link(src.link), size: 8)
                Text(src.from + (src.link != .ok ? " 前回値" : "")).font(.system(size: size))
            }
        }
    }
}

private struct Mismatch: View {
    let src: TuningView.Source
    let unit: String
    var body: some View {
        if let v = src.value, let o = src.other, abs(v - o) > 0.5 {
            Text("0x150 の値は \(String(format: "%.0f", o))\(unit) (不一致)")
                .font(.ui(13, weight: .bold)).foregroundStyle(Palette.warn)
        }
    }
}

private struct CompactMismatch: View {
    let src: TuningView.Source
    let unit: String
    let k: CGFloat
    var body: some View {
        if let v = src.value, let o = src.other, abs(v - o) > 0.5 {
            Text("0x150: \(String(format: "%.0f", o))\(unit) (不一致)")
                .font(.system(size: 11 * k, weight: .bold)).foregroundStyle(Palette.warn)
        }
    }
}

/// 「POS 12,345」のように小さい見出しと太字の数値を 1 行に並べる
private struct NumberText: View {
    let label: String
    let value: String
    let k: CGFloat
    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 3) {
            Text(label).font(.system(size: 10 * k)).foregroundStyle(.secondary)
            Text(value).font(.monoFixed(17 * k, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6)
        }
    }
}

private struct Big: View {
    let text: String
    let size: CGFloat
    init(_ text: String, _ size: CGFloat = 36) { self.text = text; self.size = size }
    var body: some View {
        Text(text).font(.mono(size, weight: .bold)).lineLimit(1).minimumScaleFactor(0.5)
    }
}

/// 半円のゲージ。0° が左端、angleMax が右端
private struct Gauge: View {
    let angle: Double?

    var body: some View {
        Canvas { ctx, size in
            let stroke: CGFloat = 18
            let d = min(size.width, size.height * 2) - stroke
            let center = CGPoint(x: size.width / 2, y: stroke / 2 + d / 2)
            func arc(_ ratio: Double) -> Path {
                Path { p in
                    p.addArc(center: center, radius: d / 2, startAngle: .degrees(180),
                             endAngle: .degrees(180 + 180 * ratio), clockwise: false)
                }
            }
            let style = StrokeStyle(lineWidth: stroke, lineCap: .round)
            ctx.stroke(arc(1), with: .color(Palette.track), style: style)
            if let angle {
                let ratio = min(max(angle / TuningView.angleMax, 0), 1)
                ctx.stroke(arc(ratio), with: .color(Palette.accent), style: style)
                let rad = (180 + 180 * ratio) * .pi / 180
                let r = d / 2 - stroke * 1.4
                var needle = Path()
                needle.move(to: center)
                needle.addLine(to: CGPoint(x: center.x + cos(rad) * r, y: center.y + sin(rad) * r))
                ctx.stroke(needle, with: .style(.primary), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            }
        }
    }
}
