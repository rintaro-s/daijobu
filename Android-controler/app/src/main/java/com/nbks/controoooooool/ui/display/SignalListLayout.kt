package com.nbks.controoooooool.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nbks.controoooooool.model.SignalInfo
import com.nbks.controoooooool.model.Telemetry

/**
 * 汎用表示: ESP32 から届いた信号定義 (schema) をそのまま一覧表示する。
 * can_signals.h を変えればアプリを変更しなくても自動で追従する。
 */
object SignalListLayout : DisplayLayout {
    override val title = "一覧"

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content(telemetry: Telemetry, modifier: Modifier) {
        val groups = telemetry.schema.signals.groupBy { it.group }
        LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FrameStatusHeader(telemetry)
            }
            items(groups.entries.toList(), key = { it.key }) { (group, signals) ->
                Text(group.ifEmpty { "-" }, style = MaterialTheme.typography.titleSmall)
                val bools = signals.filter { it.isBool }
                val nums = signals.filterNot { it.isBool }
                nums.forEach { NumberRow(it, telemetry) }
                if (bools.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        bools.forEach { BoolChip(it, telemetry) }
                    }
                }
            }
        }
    }

    @Composable
    private fun FrameStatusHeader(t: Telemetry) {
        if (t.frames.isEmpty()) {
            Text("信号定義を受信待ち…", style = MaterialTheme.typography.bodyMedium)
            return
        }
        t.frames.forEach { f ->
            val fresh = f.ageMs in 0..t.schema.timeoutMs
            Text(
                "ID 0x%03X  %s  %.0f Hz".format(
                    f.canId,
                    if (f.ageMs < 0) "未受信" else if (fresh) "受信中" else "途絶 (${f.ageMs} ms)",
                    f.hz,
                ),
                fontFamily = FontFamily.Monospace,
                color = if (fresh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (t.unknown.isNotEmpty()) {
            Text("定義外の ID (診断用)", style = MaterialTheme.typography.titleSmall)
            t.unknown.forEach { u ->
                Text(
                    "ID 0x%03X%s  %dB %s%s  %d件  %s".format(
                        u.canId,
                        if (u.extended) " 拡張" else "",
                        u.len,
                        if (u.fd) "FD" else "CC",
                        if (u.brs) "+BRS" else "",
                        u.count,
                        if (u.ageMs <= t.schema.timeoutMs) "受信中" else "${u.ageMs / 1000}s前",
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "UART ok=${t.uartOk} crc_err=${t.uartCrcErrors}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    @Composable
    private fun NumberRow(s: SignalInfo, t: Telemetry) {
        val v = t.value(s.name)
        val fresh = t.isFresh(s.name)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, Modifier.width(96.dp), fontFamily = FontFamily.Monospace)
            Box(
                Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (v != null && s.max > s.min) {
                    val ratio = ((v - s.min) / (s.max - s.min)).coerceIn(0.0, 1.0).toFloat()
                    Box(
                        Modifier.fillMaxWidth(ratio).fillMaxHeight()
                            .background(
                                if (fresh) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                }
            }
            Text(
                (v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.3f".format(it) } ?: "--") +
                    if (s.unit.isNotEmpty()) " ${s.unit}" else "",
                Modifier.width(88.dp).padding(start = 8.dp),
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    @Composable
    private fun BoolChip(s: SignalInfo, t: Telemetry) {
        val on = t.pressed(s.name)
        val fresh = t.isFresh(s.name)
        val bg = when {
            !fresh -> MaterialTheme.colorScheme.surfaceVariant
            on -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
        val fg = when {
            !fresh -> MaterialTheme.colorScheme.outline
            on -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }
        Text(
            s.name,
            Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
            color = fg,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
