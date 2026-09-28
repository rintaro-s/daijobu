package com.nbks.controoooooool.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nbks.controoooooool.model.Telemetry

/**
 * スマホに表示する形式。
 *
 * 表示を変えたい / 増やしたいときは、このインターフェースを実装したクラスを作り
 * [DisplayLayouts.all] に追加するだけでよい (画面上部のタブに並ぶ)。
 * 値は [Telemetry.value] / [Telemetry.pressed] に ESP32 側 can_signals.h の信号名を渡して取る。
 */
interface DisplayLayout {
    val title: String

    @Composable
    fun Content(telemetry: Telemetry, modifier: Modifier)
}

object DisplayLayouts {
    val all: List<DisplayLayout> = listOf(
        GamepadLayout,
        DebugLayout,
        SignalListLayout,
    )
}
