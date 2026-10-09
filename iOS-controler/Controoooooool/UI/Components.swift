import SwiftUI

/// Android 版の色と揃える
enum Palette {
    static let ok = Color.green
    static let warn = Color.orange
    static let bad = Color.red
    static let none = Color.gray
    static let accent = Color.blue
    static let barNeg = Color.red
    static let track = Color(.systemFill)

    static func link(_ l: DebugSpec.Link) -> Color {
        switch l {
        case .ok: ok
        case .lost: bad
        case .unsupported: warn
        case .notReceived: none
        }
    }

    static func linkText(_ l: DebugSpec.Link) -> String {
        switch l {
        case .ok: "受信中"
        case .lost: "通信途絶"
        case .unsupported: "未対応"
        case .notReceived: "未受信"
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// 折り返して並べる (Compose の FlowRow 相当)
struct FlowLayout: Layout {
    var spacing: CGFloat = 4
    var lineSpacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews)
        let w = rows.map(\.width).max() ?? 0
        let h = rows.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: proposal.width ?? w, height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews) {
            var x = bounds.minX
            for i in row.items {
                let s = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y + (row.height - s.height) / 2), proposal: ProposedViewSize(s))
                x += s.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row { var items: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, _ subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var cur = Row()
        for i in subviews.indices {
            let s = subviews[i].sizeThatFits(.unspecified)
            let next = cur.items.isEmpty ? s.width : cur.width + spacing + s.width
            if next > width && !cur.items.isEmpty {
                rows.append(cur)
                cur = Row()
            }
            cur.width = cur.items.isEmpty ? s.width : cur.width + spacing + s.width
            cur.height = max(cur.height, s.height)
            cur.items.append(i)
        }
        if !cur.items.isEmpty { rows.append(cur) }
        return rows
    }
}

/// Material の Card 相当
struct CardBox<Content: View>: View {
    var padding: CGFloat = 14
    var spacing: CGFloat = 6
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentCard()
    }
}

/// 色付きの小さいラベル
struct Tag: View {
    let text: String
    let color: Color
    /// nil なら Dynamic Type (caption)。横画面の調整タブなど、画面に収める必要がある所だけ固定サイズ
    var size: CGFloat? = nil

    init(_ text: String, _ color: Color, size: CGFloat? = nil) {
        self.text = text; self.color = color; self.size = size
    }

    // 純正アプリ風: 薄い色の背景に、同じ色の文字
    var body: some View {
        Text(text)
            .font(size.map { .system(size: $0, weight: .medium) } ?? .caption.weight(.medium))
            .foregroundStyle(color)
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(color.opacity(0.15), in: Capsule())
    }
}

struct Tags: View {
    let tags: [(String, Color)]
    var body: some View {
        if !tags.isEmpty {
            FlowLayout(spacing: 4, lineSpacing: 2) {
                ForEach(Array(tags.enumerated()), id: \.offset) { _, t in Tag(t.0, t.1) }
            }
        }
    }
}

struct Dot: View {
    let color: Color
    var size: CGFloat = 9
    var body: some View { Circle().fill(color).frame(width: size, height: size) }
}

/// 0～1 の横バー。nil は未計測 (枠だけ)
struct RatioBar: View {
    let ratio: Double?
    var color: Color = Palette.accent
    var height: CGFloat = 12

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Palette.track
                if let ratio {
                    color.frame(width: g.size.width * min(max(ratio, 0), 1))
                }
            }
        }
        .frame(height: height)
        .clipShape(Capsule())
    }
}

/// -1～+1 の中央ゼロのバー (正は青で右へ、負は赤で左へ)
struct CenterBar: View {
    let ratio: Double?
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { g in
            let half = g.size.width / 2
            ZStack(alignment: .leading) {
                Palette.track
                if let ratio {
                    let v = min(max(ratio, -1), 1)
                    let w = half * abs(v)
                    (v >= 0 ? Palette.accent : Palette.barNeg)
                        .frame(width: w)
                        .offset(x: v >= 0 ? half : half - w)
                }
                Color.secondary.frame(width: 1).offset(x: half - 0.5)
            }
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: height / 2))
    }
}

extension Font {
    /// 等幅の数値表示。Dynamic Type に追従する (size は近いテキストスタイルを選ぶための目安)
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(Font.TextStyle.near(size), design: .monospaced).weight(weight)
    }

    /// 等幅・固定サイズ。画面に収める必要がある横画面の調整タブだけで使う
    static func monoFixed(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }

    /// 通常の文字。Dynamic Type に追従する
    static func ui(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(Font.TextStyle.near(size)).weight(weight)
    }
}

extension Font.TextStyle {
    /// pt 指定に一番近いテキストスタイル (標準の文字サイズ設定での大きさ)
    static func near(_ size: CGFloat) -> Font.TextStyle {
        switch size {
        case ..<11.5: .caption2
        case ..<12.5: .caption
        case ..<13.5: .footnote
        case ..<15.5: .subheadline
        case ..<17.5: .body
        case ..<21: .title3
        case ..<24: .title2
        case ..<30: .title
        default: .largeTitle
        }
    }
}

extension View {
    /// 中身のカード。純正アプリ (設定・ヘルスケア) と同じく、グループ背景の上に置くソリッドな角丸
    func contentCard(_ radius: CGFloat = 20, tint: Color? = nil) -> some View {
        background(tint ?? Color(.secondarySystemGroupedBackground),
                   in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }

}

extension Color {
    /// アプリ全体のアクセント
    static let brand = Color.accentColor
}

/// カードの見出し。Apple 純正アプリ風に SF Symbols のアイコンを添える
struct CardTitle: View {
    let title: String
    var size: CGFloat = 17
    var fixed = false

    init(_ title: String, size: CGFloat = 17, fixed: Bool = false) {
        self.title = title; self.size = size; self.fixed = fixed
    }

    var body: some View {
        HStack(spacing: 6) {
            if let s = Self.symbol(title) {
                Image(systemName: s)
                    .foregroundStyle(Color.accentColor)
                    .symbolRenderingMode(.hierarchical)
            }
            Text(title)
        }
        .font(fixed ? .system(size: size, weight: .semibold) : .ui(size, weight: .semibold))
    }

    static func symbol(_ title: String) -> String? {
        let table: [(String, String)] = [
            ("通信", "antenna.radiowaves.left.and.right"),
            ("昇降エンコーダー", "dial.medium"),
            ("昇降", "arrow.up.and.down.circle.fill"),
            ("足回り", "car.rear.fill"),
            ("ジャイロ", "gyroscope"),
            ("発射", "scope"),
            ("制御", "slider.horizontal.3"),
            ("サーボ", "gauge.with.dots.needle.33percent"),
            ("制御・安全", "shield.lefthalf.filled"),
        ]
        return table.first { title.hasPrefix($0.0) }?.1
    }
}
