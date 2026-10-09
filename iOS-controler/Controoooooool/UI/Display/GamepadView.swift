import SwiftUI

/// Switch Pro コントローラ風の表示。
/// どの信号をどの部品に割り当てるかは [Signals] で変えられる (名前は ESP32 の can_signals.h と一致させる)。
struct GamepadView: View {
    let t: Telemetry
    @Environment(\.verticalSizeClass) private var vSize

    enum Signals {
        static let LX = "LX", LY = "LY", RX = "RX", RY = "RY"
        // 実機で確認した結果、A/B と X/Y は信号名と絵の位置が逆だったので入れ替えて割り当てる
        static let A = "B", B = "A", X = "Y", Y = "X"
        static let UP = "UP", DOWN = "DOWN", LEFT = "LEFT", RIGHT = "RIGHT"
        static let L1 = "L1", L2 = "L2", R1 = "R1", R2 = "R2"
        static let PLUS = "PLUS", MINUS = "MINUS", HOME = "HOME", CAPTURE = "CAPTURE"

        /// スティックの値の範囲 (±この値で端まで振り切る)
        static let stickMax = 127.0
        /// true: LY/RY が正のとき上に倒した表示にする。実機確認で逆だったので false
        static let stickYUpPositive = false
    }

    // 描画は 1000 x 640 の仮想座標で行い、画面に合わせて拡縮する
    static let W: CGFloat = 1000
    static let H: CGFloat = 640

    private func fmt(_ v: Double?) -> String { v.map { String(Int64($0)) } ?? "--" }
    private func pad(_ s: String) -> String { String(repeating: " ", count: max(0, 4 - s.count)) + s }
    private var lText: String { "L\nX \(pad(fmt(t.value(Signals.LX))))\nY \(pad(fmt(t.value(Signals.LY))))" }
    private var rText: String { "R\nX \(pad(fmt(t.value(Signals.RX))))\nY \(pad(fmt(t.value(Signals.RY))))" }

    var body: some View {
        if vSize == .compact {
            // 横画面: 高さを絵に全部使い、スティックの値は左右の余白に置く
            VStack {
                status
                HStack(spacing: 24) {
                    stick(lText)
                    canvas
                    stick(rText)
                }
                .frame(maxHeight: .infinity)
            }
            .padding(.horizontal, 16).padding(.vertical, 4)
        } else {
            VStack(spacing: 8) {
                Spacer(minLength: 0)
                canvas
                HStack(spacing: 48) { stick(lText); stick(rText) }
                status
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
        }
    }

    private func stick(_ s: String) -> some View { Text(s).font(.mono(16)) }

    @ViewBuilder private var status: some View {
        if !t.isFresh(Signals.LX) {
            Text((t.frameOf(Signals.LX)?.ageMs ?? -1) < 0 ? "データ待ち…" : "CAN 途絶 (最後の値を表示中)")
                .foregroundStyle(.red)
        }
    }

    private var canvas: some View {
        // 途絶時は絵全体を 1 枚のレイヤーとして薄くする (部品ごとに薄くすると重なりが汚くなる)
        Canvas { ctx, size in
            let s = min(size.width / Self.W, size.height / Self.H)
            ctx.translateBy(x: (size.width - Self.W * s) / 2, y: (size.height - Self.H * s) / 2)
            ctx.scaleBy(x: s, y: s)
            PadDrawer(t: t, ctx: ctx).draw()
        }
        .aspectRatio(Self.W / Self.H, contentMode: .fit)
        .compositingGroup()
        .opacity(t.isFresh(Signals.LX) ? 1 : 0.45)
    }
}

private struct PadDrawer {
    let t: Telemetry
    var ctx: GraphicsContext
    typealias S = GamepadView.Signals

    let body = Color(hex: 0x2B2D31)
    let bodyEdge = Color(hex: 0x45484F)
    let part = Color(hex: 0x3A3D43)
    let partDark = Color(hex: 0x1C1D20)
    let label = Color(hex: 0xB8BCC4)
    let on = Color(hex: 0xFF4D5E)

    func draw() {
        // ---- トリガ (ZL/ZR = L2/R2) とショルダー (L/R = L1/R1) ----
        shoulder(S.L2, "ZL", CGRect(x: 170, y: 18, width: 210, height: 58))
        shoulder(S.R2, "ZR", CGRect(x: 620, y: 18, width: 210, height: 58))
        shoulder(S.L1, "L", CGRect(x: 150, y: 64, width: 250, height: 60))
        shoulder(S.R1, "R", CGRect(x: 600, y: 64, width: 250, height: 60))

        // ---- 本体 (上部 + 左右グリップ) ----
        grip(CGRect(x: 140, y: 230, width: 230, height: 380), pivot: CGPoint(x: 250, y: 420), deg: 18)
        grip(CGRect(x: 630, y: 230, width: 230, height: 380), pivot: CGPoint(x: 750, y: 420), deg: -18)
        let top = Path(roundedRect: CGRect(x: 110, y: 120, width: 780, height: 330), cornerRadius: 150)
        ctx.fill(top, with: .color(body))
        ctx.stroke(top, with: .color(bodyEdge), lineWidth: 3)

        // ---- - / + / HOME / キャプチャ ----
        round(S.MINUS, "−", CGPoint(x: 400, y: 185), 24)
        round(S.PLUS, "+", CGPoint(x: 600, y: 185), 24)
        round(S.HOME, "⌂", CGPoint(x: 560, y: 262), 24)
        ctx.fill(Path(roundedRect: CGRect(x: 424, y: 244, width: 36, height: 36), cornerRadius: 6),
                 with: .color(t.pressed(S.CAPTURE) ? on : partDark))

        // ---- スティック ----
        stick(S.LX, S.LY, CGPoint(x: 270, y: 245))
        stick(S.RX, S.RY, CGPoint(x: 620, y: 390))

        // ---- 十字キー ----
        dpad(CGPoint(x: 380, y: 390))

        // ---- ABXY ----
        let c = CGPoint(x: 740, y: 255), d: CGFloat = 62
        round(S.X, "X", CGPoint(x: c.x, y: c.y - d), 30)
        round(S.A, "A", CGPoint(x: c.x + d, y: c.y), 30)
        round(S.B, "B", CGPoint(x: c.x, y: c.y + d), 30)
        round(S.Y, "Y", CGPoint(x: c.x - d, y: c.y), 30)
    }

    private func grip(_ r: CGRect, pivot: CGPoint, deg: Double) {
        let tf = CGAffineTransform(translationX: pivot.x, y: pivot.y)
            .rotated(by: deg * .pi / 180).translatedBy(x: -pivot.x, y: -pivot.y)
        ctx.fill(Path(roundedRect: r, cornerRadius: 115).applying(tf), with: .color(body))
    }

    private func shoulder(_ name: String, _ text: String, _ r: CGRect) {
        let p = t.pressed(name)
        let path = Path(roundedRect: r, cornerRadius: 28)
        ctx.fill(path, with: .color(p ? on : part))
        ctx.stroke(path, with: .color(bodyEdge), lineWidth: 2)
        drawLabel(text, CGPoint(x: r.midX, y: r.midY), p ? .white : label, 26)
    }

    private func round(_ name: String, _ text: String, _ c: CGPoint, _ r: CGFloat) {
        let p = t.pressed(name)
        let path = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
        ctx.fill(path, with: .color(p ? on : partDark))
        ctx.stroke(path, with: .color(bodyEdge), lineWidth: 2)
        drawLabel(text, c, p ? .white : label, r * 0.95)
    }

    private func circle(_ c: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
    }

    private func stick(_ xName: String, _ yName: String, _ c: CGPoint) {
        let x = min(max((t.value(xName) ?? 0) / S.stickMax, -1), 1)
        let yRaw = min(max((t.value(yName) ?? 0) / S.stickMax, -1), 1)
        let y = S.stickYUpPositive ? -yRaw : yRaw
        let travel: CGFloat = 42

        ctx.fill(circle(c, 78), with: .color(partDark))
        ctx.stroke(circle(c, 78), with: .color(bodyEdge), lineWidth: 2)
        // 可動範囲の十字線
        var cross = Path()
        cross.move(to: CGPoint(x: c.x - travel, y: c.y)); cross.addLine(to: CGPoint(x: c.x + travel, y: c.y))
        cross.move(to: CGPoint(x: c.x, y: c.y - travel)); cross.addLine(to: CGPoint(x: c.x, y: c.y + travel))
        ctx.stroke(cross, with: .color(part), lineWidth: 2)

        let knob = CGPoint(x: c.x + x * travel, y: c.y + y * travel)
        let active = x != 0 || y != 0
        ctx.fill(circle(knob, 50), with: .color(Color(hex: 0x111214)))
        ctx.stroke(circle(knob, 50), with: .color(active ? on : part), lineWidth: 6)
        ctx.fill(circle(knob, 30), with: .color(part))
    }

    private func dpad(_ c: CGPoint) {
        let arm: CGFloat = 38, len: CGFloat = 52
        ctx.fill(circle(c, 92), with: .color(partDark.opacity(0.6)))
        let arms: [(String, String, CGFloat, CGFloat)] = [
            (S.UP, "▲", 0, -1), (S.DOWN, "▼", 0, 1), (S.LEFT, "◀", -1, 0), (S.RIGHT, "▶", 1, 0),
        ]
        for (name, text, dx, dy) in arms {
            let p = t.pressed(name)
            let ac = CGPoint(x: c.x + dx * (len / 2 + arm / 2), y: c.y + dy * (len / 2 + arm / 2))
            let sz = dx == 0 ? CGSize(width: arm, height: len) : CGSize(width: len, height: arm)
            ctx.fill(Path(roundedRect: CGRect(x: ac.x - sz.width / 2, y: ac.y - sz.height / 2, width: sz.width, height: sz.height), cornerRadius: 6),
                     with: .color(p ? on : part))
            drawLabel(text, ac, p ? .white : label, 18)
        }
        ctx.fill(Path(CGRect(x: c.x - arm / 2, y: c.y - arm / 2, width: arm, height: arm)), with: .color(part))
    }

    private func drawLabel(_ text: String, _ c: CGPoint, _ color: Color, _ px: CGFloat) {
        // 仮想座標系で拡縮されているので、px をそのままフォントサイズに使う
        ctx.draw(Text(text).font(.system(size: px, weight: .bold)).foregroundStyle(color), at: c)
    }
}
