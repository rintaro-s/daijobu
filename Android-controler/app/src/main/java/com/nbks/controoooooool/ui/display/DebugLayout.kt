package com.nbks.controoooooool.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.ui.display.DebugSpec.Link
import com.nbks.controoooooool.ui.display.DebugSpec.Mech

/**
 * デバッグ用 CAN FD 通信仕様 V1 の表示 (昇降 ×3 / 発射 / 足回り ×4)。
 *
 * 仕様 8 節に従い:
 *   - 初受信前は「未受信」、最後の有効受信から 300ms 超は「通信途絶」
 *   - 途絶中・未対応中の値は薄く表示し「前回値」と明記する
 *   - 無効値 / 有効フラグ OFF は「未計測」。0rpm と未計測を区別する
 *   - 不明な状態番号・バージョンは「未対応」
 */
object DebugLayout : DisplayLayout {
    override val title = "機構"

    private val OkColor = Color(0xFF2E7D32)
    private val LostColor = Color(0xFFD32F2F)
    private val UnsupportedColor = Color(0xFFEF6C00)
    private val NoneColor = Color(0xFF757575)

    @Composable
    override fun Content(telemetry: Telemetry, modifier: Modifier) {
        val sections = listOf(
            "昇降" to Mech.LIFT,
            "発射" to Mech.SHOT,
            "足回り" to Mech.WHEEL,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            modifier = modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { (title, mech) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-$title") {
                    SectionHeader(title, mech, telemetry)
                }
                items(DebugSpec.units.filter { it.mech == mech }, key = { it.prefix }) { u ->
                    UnitCard(u, telemetry)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "note") {
                Text(
                    "10Hz の状態監視用。瞬間的な変化は見逃すことがあります。",
                    Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String, mech: Mech, t: Telemetry) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (mech == Mech.LIFT) {
                Spacer(Modifier.weight(1f))
                Text(
                    liftSpread(t),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    /** 3 軸の昇降率の最大－最小 (診断用。安全判定には使わない) */
    private fun liftSpread(t: Telemetry): String {
        val rates = DebugSpec.units.filter { it.mech == Mech.LIFT }.map { u ->
            val r = DebugSpec.Reader(t, u.prefix).int("rate")
            if (DebugSpec.link(t, u) == Link.Ok && r != null && r <= 1000) r else null
        }
        if (rates.any { it == null }) return "率の差: --"
        val v = rates.filterNotNull()
        return "率の差 (max-min): %.1f%%".format((v.max() - v.min()) / 10.0)
    }

    @Composable
    private fun UnitCard(u: DebugSpec.Unit, t: Telemetry) {
        val link = DebugSpec.link(t, u)
        val frame = t.frame(u.canId)
        val hasValues = t.value("${u.prefix}.ver") != null
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${u.label}  0x%03X".format(u.canId),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    LinkBadge(link)
                }
                if (!hasValues) {
                    Text("--", fontFamily = FontFamily.Monospace)
                } else {
                    val stale = link != Link.Ok
                    if (stale) {
                        Text("前回値", style = MaterialTheme.typography.labelSmall, color = LostColor)
                    }
                    Column(
                        Modifier.alpha(if (stale) 0.5f else 1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val r = DebugSpec.Reader(t, u.prefix)
                        when (u.mech) {
                            Mech.LIFT -> LiftBody(r)
                            Mech.SHOT -> ShotBody(r)
                            Mech.WHEEL -> WheelBody(r)
                        }
                        CommonFooter(r)
                    }
                }
                if (frame != null && (frame.seq != null || frame.rejected > 0)) {
                    Text(
                        buildString {
                            append("SEQ ${frame.seq ?: "--"}  欠落 ${frame.seqLost}")
                            if (frame.rejected > 0) append("  不正 ${frame.rejected}")
                            append("  %.0fHz".format(frame.hz))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }

    @Composable
    private fun LinkBadge(link: Link) {
        val (color, text) = when (link) {
            Link.NotReceived -> NoneColor to "未受信"
            Link.Ok -> OkColor to "受信中"
            is Link.Lost -> LostColor to "通信途絶"
            is Link.Unsupported -> UnsupportedColor to "未対応"
        }
        Column(horizontalAlignment = Alignment.End) {
            Chip(text, color, Color.White)
            if (link is Link.Unsupported) {
                Text(link.reason, style = MaterialTheme.typography.labelSmall, color = UnsupportedColor)
            }
        }
    }

    // ---- 昇降 ----
    @Composable
    private fun LiftBody(r: DebugSpec.Reader) {
        val rate = r.int("rate")
        val rateText = when {
            rate == null || rate == DebugSpec.U16_INVALID -> "--"
            rate > 1000 -> "範囲外($rate)"
            else -> "%.1f%%".format(rate / 10.0)
        }
        val homed = r.has(DebugSpec.F_HOMED)
        MainLine(
            rateText,
            DebugSpec.stateName(Mech.LIFT, r.int("state") ?: -1),
            "PWM ${DebugSpec.pwmText(r.int("pwm"))}",
            if (homed) "HOME済" else "未原点",
        )
        val pos = r.long("pos")
        val ref = r.long("ref")
        val posText =
            if (pos == null || !r.has(DebugSpec.F_MEASURED) || pos == DebugSpec.I32_INVALID) "未計測"
            else if (homed) "$pos" else "$pos (未原点)"
        val refText =
            if (ref == null || !r.has(DebugSpec.F_TARGET) || ref == DebugSpec.I32_INVALID) "未計測" else "$ref"
        Mono("POS $posText   REF $refText")
        if (r.has(DebugSpec.F_SENSOR)) {
            BitChips("リミット", DebugSpec.liftLimitNames(r.int("limit") ?: 0), "なし", UnsupportedColor)
        } else {
            Mono("リミット: 未対応")
        }
    }

    // ---- 発射 ----
    @Composable
    private fun ShotBody(r: DebugSpec.Reader) {
        MainLine(
            DebugSpec.stateName(Mech.SHOT, r.int("state") ?: -1),
            "PWM ${DebugSpec.pwmText(r.int("pwm"))}",
        )
        val target = r.int("target_rpm")
        val targetText =
            if (target == null || !r.has(DebugSpec.F_TARGET) || target == DebugSpec.U16_INVALID) "未計測"
            else "$target rpm"
        Mono("目標 $targetText   実測 ${DebugSpec.rpmText(r.flags, r.long("rpm10"))}")
        val shots = r.long("shots")
        Mono(
            "発射回数 " +
                if (shots == null || !r.has(DebugSpec.F_SHOTS) || shots == DebugSpec.U32_INVALID) "未計測"
                else "$shots"
        )
        if (r.has(DebugSpec.F_SENSOR)) {
            val s = r.int("sensor") ?: 0
            Mono(
                listOf(
                    if (s and 1 != 0) "弾あり" else "弾なし",
                    if (s and 2 != 0) "原点ON" else "原点OFF",
                    if (s and 4 != 0) "回転数OK" else "回転数範囲外",
                ).joinToString("  ") + if (s and 0xFFF8 != 0) "  予約bit(0x%04X)".format(s and 0xFFF8) else ""
            )
        } else {
            Mono("センサー: 未対応")
        }
    }

    // ---- 足回り ----
    @Composable
    private fun WheelBody(r: DebugSpec.Reader) {
        val cmd = r.int("cmd")
        MainLine(
            DebugSpec.stateName(Mech.WHEEL, r.int("state") ?: -1),
            "CMD ${cmd?.let { "%+d".format(it) } ?: "--"}",
            "PWM ${DebugSpec.pwmText(r.int("pwm"))}",
        )
        Mono("RPM ${DebugSpec.rpmText(r.flags, r.long("rpm10"))}   RX ${r.long("rx") ?: "--"}")
        Mono("STOP理由 ${DebugSpec.stopReason(r.int("stop") ?: -1)}")
    }

    // ---- 共通: 指令受信状態 / 異常ビット / 稼働時間 ----
    @Composable
    private fun CommonFooter(r: DebugSpec.Reader) {
        Mono(
            (if (r.has(DebugSpec.F_CMD_FRESH)) "指令受信OK" else "指令受信なし") +
                "   稼働 ${DebugSpec.uptimeText(r.long("uptime"))}"
        )
        BitChips("異常", DebugSpec.errorNames(r.int("err") ?: 0), "検出なし", LostColor)
    }

    @Composable
    private fun MainLine(vararg parts: String) {
        Text(
            parts.joinToString("   "),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }

    @Composable
    private fun Mono(text: String) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun BitChips(label: String, names: List<String>, empty: String, color: Color) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$label:", style = MaterialTheme.typography.bodyMedium)
            if (names.isEmpty()) {
                Text(empty, style = MaterialTheme.typography.bodyMedium)
            } else {
                names.forEach { Chip(it, color, Color.White) }
            }
        }
    }

    @Composable
    private fun Chip(text: String, bg: Color, fg: Color) {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
            color = fg,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
