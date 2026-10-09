import SwiftUI

/// スマホに表示する形式。
///
/// 表示を変えたい / 増やしたいときは、View を作って [DisplayLayouts.all] に追加するだけでよい (画面上部のタブに並ぶ)。
/// 値は Telemetry.value / Telemetry.pressed に ESP32 側 can_signals.h の信号名を渡して取る。
struct DisplayLayout: Identifiable {
    let title: String
    let icon: String
    let content: (Telemetry) -> AnyView
    var id: String { title }
}

enum DisplayLayouts {
    static let all: [DisplayLayout] = [
        DisplayLayout(title: "ダッシュボード", icon: "square.grid.2x2.fill") { AnyView(DashboardView(t: $0)) },
        DisplayLayout(title: "調整", icon: "slider.horizontal.3") { AnyView(TuningView(t: $0)) },
        DisplayLayout(title: "コントローラ", icon: "gamecontroller.fill") { AnyView(GamepadView(t: $0)) },
        DisplayLayout(title: "機構", icon: "gearshape.2.fill") { AnyView(DebugView(t: $0)) },
        DisplayLayout(title: "一覧", icon: "list.bullet.rectangle.fill") { AnyView(SignalListView(t: $0)) },
    ]
}
