package com.nbks.controoooooool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbks.controoooooool.record.RecordCodec
import com.nbks.controoooooool.record.RecordMode
import com.nbks.controoooooool.record.ShotRecord
import com.nbks.controoooooool.ui.display.DebugSpec

/** 「記録」タブ: 「入った」ボタンで残した記録の一覧 (新しい順) と CSV 共有 */
@Composable
fun RecordScreen(
    records: List<ShotRecord>,
    mode: RecordMode,
    onShare: () -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("記録 ${records.size} 件", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    RecordMode.entries.joinToString("   ") { m -> "${m.short} ${records.count { it.mode == m }}" } +
                        "    現在のタグ: ${mode.label}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
            OutlinedButton(onClick = onShare, enabled = records.isNotEmpty()) { Text("CSV を共有") }
        }
        if (records.isEmpty()) {
            Text(
                "ヘッダーの「入った」ボタンを押すと、その瞬間のサーボ角度と速度が記録されます。",
                color = MaterialTheme.colorScheme.outline,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 新しい順。番号は記録した順 (1 から)
            val shown = records.mapIndexed { i, r -> (i + 1) to r }.asReversed()
            itemsIndexed(shown, key = { _, p -> p.second.timeMs }) { _, (no, r) ->
                RecordRow(no, r, onDelete = { onDelete(r.timeMs) })
            }
        }
    }
}

@Composable
private fun RecordRow(no: Int, r: ShotRecord, onDelete: () -> Unit) {
    val tagColor = if (r.mode == RecordMode.BUCKET) Color(0xFF1E88E5) else Color(0xFF8E24AA)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#$no", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        r.mode.short,
                        Modifier.clip(RoundedCornerShape(4.dp)).background(tagColor).padding(horizontal = 6.dp, vertical = 1.dp),
                        color = Color.White, fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(RecordCodec.timeText(r.timeMs).substring(11), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (r.preset != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("プリセット${DebugSpec.presetName(r.preset)}", fontSize = 11.sp)
                    }
                    if (r.stale) {
                        Spacer(Modifier.width(8.dp))
                        Text("前回値", color = Color(0xFFD32F2F), fontSize = 11.sp)
                    }
                }
                Text(
                    "角度 ${r.angleDeg?.let { "$it°" } ?: "--"}    速度(PWM) ${r.pwmPermille ?: "--"}",
                    fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                )
            }
            TextButton(onClick = onDelete) { Text("削除", fontSize = 12.sp) }
        }
    }
}

/** 設定: 記録に付けるタグの選択 / 記録の全消去 */
@Composable
fun SettingsDialog(
    mode: RecordMode,
    recordCount: Int,
    onSelectMode: (RecordMode) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    split: Boolean,
    onSplit: (Boolean) -> Unit,
    hasWallpaper: Boolean,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    rearStatus: String,
    rearActive: Boolean,
    rearAvailable: Boolean,
    onRearToggle: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("記録のタグ", fontWeight = FontWeight.Bold)
                Text(
                    "「入った」ボタンで残す記録に、このタグを付けます。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                )
                RecordMode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelectMode(m) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == mode, onClick = { onSelectMode(m) })
                        Text(m.label)
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
                Text("Z Fold の表示", fontWeight = FontWeight.Bold)
                SwitchRow("広い画面 (開いた状態) を上下 2 段に分割する", split, onSplit)
                Spacer(Modifier.padding(top = 8.dp))
                Text("背面ディスプレイ", fontWeight = FontWeight.Bold)
                Text(rearStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPickWallpaper) {
                        Text(if (hasWallpaper) "壁紙を選び直す" else "壁紙を選ぶ")
                    }
                    if (hasWallpaper) {
                        TextButton(onClick = onClearWallpaper) { Text("壁紙を削除") }
                    }
                }
                OutlinedButton(onClick = onRearToggle, enabled = rearAvailable || rearActive) {
                    Text(if (rearActive) "背面表示を止める" else "背面に壁紙を表示")
                }
                Spacer(Modifier.padding(top = 8.dp))
                TextButton(onClick = { confirmClear = true }, enabled = recordCount > 0) {
                    Text("記録を全消去 ($recordCount 件)", color = Color(0xFFD32F2F))
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("閉じる") } },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("記録を全消去しますか？") },
            text = { Text("$recordCount 件の記録を消します。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = { onClearAll(); confirmClear = false }) {
                    Text("消去する", color = Color(0xFFD32F2F))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
