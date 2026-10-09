import SwiftUI

@main
struct ControoooooooolApp: App {
    @State private var link = EspLink()

    var body: some Scene {
        WindowGroup {
            MonitorScreen(link: link)
                .onAppear {
                    // 走行中に画面が消えないようにする
                    UIApplication.shared.isIdleTimerDisabled = true
                    link.start()
                }
        }
    }
}

/// 標準の TabView (タブバー) + NavigationStack で構成する (HIG のトップレベルナビゲーション)。
/// 接続状態はナビゲーションバーのサブタイトル、接続 / 切断はツールバーのボタン。
struct MonitorScreen: View {
    let link: EspLink
    @SceneStorage("tab") private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            ForEach(Array(DisplayLayouts.all.enumerated()), id: \.offset) { i, layout in
                NavigationStack {
                    layout.content(link.telemetry)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color(.systemGroupedBackground))
                        .navigationTitle(layout.title)
                        .navigationBarTitleDisplayMode(.inline)
                        .modifier(LinkStatusToolbar(link: link))
                }
                .tabItem { Label(layout.title, systemImage: layout.icon) }
                .tag(i)
            }
        }
    }
}

private struct LinkStatus {
    let color: Color
    let text: String
    let symbol: String

    init(_ link: EspLink) {
        switch link.state {
        case .idle: (color, text, symbol) = (.gray, "未接続", "wifi.slash")
        case .joiningWifi: (color, text, symbol) = (.orange, "\(ConnectionConfig.ssid) に接続中…", "wifi.exclamationmark")
        case .connectingTcp: (color, text, symbol) = (.orange, "ESP32 に接続中…", "wifi.exclamationmark")
        case .connected:
            (color, text, symbol) = link.telemetry.anyFresh
                ? (.green, "受信中", "wifi")
                : (.red, "ESP32 接続済み / CAN 受信なし", "exclamationmark.triangle.fill")
        case .error(let m): (color, text, symbol) = (.red, m, "exclamationmark.triangle.fill")
        }
    }
}

private struct LinkStatusToolbar: ViewModifier {
    let link: EspLink

    func body(content: Content) -> some View {
        let st = LinkStatus(link)
        let disconnected: Bool = { if case .error = link.state { return true }; return link.state == .idle }()
        content
            .modifier(Subtitle(text: st.text))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Image(systemName: st.symbol)
                        .foregroundStyle(st.color)
                        .symbolEffect(.pulse, isActive: link.state == .connectingTcp || link.state == .joiningWifi)
                        .accessibilityLabel(st.text)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if disconnected {
                        Button("接続") { link.start() }
                    } else {
                        Button("切断", role: .destructive) { link.stop() }
                    }
                }
            }
    }
}

/// iOS 26 以降はナビゲーションバーのサブタイトルに接続状態を出す
private struct Subtitle: ViewModifier {
    let text: String
    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.navigationSubtitle(text)
        } else {
            content
        }
    }
}
