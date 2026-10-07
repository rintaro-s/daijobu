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
 * デバッグ用 CAN FD 通信仕様 V2 の表示 (昇降 ×3 / 発射 / 足回り ×4)。
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
            "発射・装填 (指令状態)" to Mech.SHOT,
            "足回り" to Mech.WHEEL,
            "ジャイロ" to Mech.GYRO,
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
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-ctrl") {
                Text(
                    "制御・安全用 ID",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(DebugSpec.units.filter { it.mech.isControl }, key = { it.prefix }) { u ->
                UnitCard(u, telemetry)
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
        val hasValues = t.value("${u.prefix}.ver") != null || t.value("${u.prefix}.b0") != null
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
                            Mech.GYRO -> GyroBody(r)
                            Mech.LIFT_CMD -> LiftCmdBody(r)
                            Mech.LIFT_ST -> RawBody(r)
                            Mech.SHOT_CMD -> ShotCmdBody(r)
                            Mech.LOAD_SENS -> LoadSensBody(r)
                        }
                        if (!u.mech.isControl && u.mech != Mech.GYRO) CommonFooter(r, u.mech)
                    }
                }
                if (frame != null && (frame.seq != null || frame.rejected > 0)) {
                    Text(
                        buildString {
                            append("経過 ${if (frame.ageMs < 0) "--" else "${frame.ageMs}ms"}  SEQ ${frame.seq ?: "--"}  欠落 ${frame.seqLost}")
                            if (frame.duplicates > 0) append("  重複 ${frame.duplicates}")
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
            DebugSpec.stateName(Mech.LIFT, r.int("state")),
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
        // V30: リミット bit は有効性フラグを持たない (bit2 は「下限SW機能有効」の意味)
        BitChips("リミット", DebugSpec.liftLimitNames(r.int("limit") ?: 0), "なし", UnsupportedColor)
        Mono(
            "下限SW機能 ${if (r.has(DebugSpec.F_LOWER_SW)) "ON" else "OFF"}   " +
                if (!r.has(DebugSpec.F_V30)) "V30未対応(旧版)"
                else "他軸位置 ${if (r.has(DebugSpec.F_PEER_FRESH)) "受信OK" else "途絶"}"
        )
    }

    // ---- ジャイロ 0x200 ----
    @Composable
    private fun GyroBody(r: DebugSpec.Reader) {
        val flags = r.int("flags") ?: 0
        val valid = DebugSpec.gyroValid(flags)
        val addr = r.int("addr")
        MainLine(
            if (valid) "測定 VALID" else "IMU測定無効",
            "I2C ${if (flags and 1 != 0) "OK" else "NG"}",
            addr?.let { "0x%02X".format(it) } ?: "--",
        )
        // 測定無効のとき角度・角速度欄は 0 で送られてくるが、実際の 0 ではないので「--」にする
        Mono(
            "Yaw ${DebugSpec.degText(r.dbl("yaw"), valid)}  Roll ${DebugSpec.degText(r.dbl("roll"), valid)}" +
                "  Pitch ${DebugSpec.degText(r.dbl("pitch"), valid)}"
        )
        Mono(
            "Gyro X ${DebugSpec.degText(r.dbl("gx"), valid, "dps")}  Y ${DebugSpec.degText(r.dbl("gy"), valid, "dps")}" +
                "  Z ${DebugSpec.degText(r.dbl("gz"), valid, "dps")}"
        )
        Mono("校正 ${DebugSpec.calText(flags, r.int("cal"))}")
        Mono("SYS_STATUS ${r.int("sys_status") ?: "--"}   SYS_ERR ${r.int("sys_err") ?: "--"}")
        Mono("稼働 ${DebugSpec.uptimeText(r.long("uptime"))}")
    }

    // ---- 発射・装填・サーボ指令状態 0x310 (操作mainが把握している指令。機構の応答ではない) ----
    @Composable
    private fun ShotBody(r: DebugSpec.Reader) {
        MainLine(
            DebugSpec.stateName(Mech.SHOT, r.int("state")),
            "PWM ${r.int("pwm") ?: "--"}",
            "角度 ${r.int("angle")?.let { "${it}°" } ?: "--"}",
        )
        BitChips("指令", DebugSpec.cmd310Names(r.int("cmd") ?: 0), "なし", OkColor)
        Mono(
            "調整 ${DebugSpec.adjustName(r.int("adjust"))}   " +
                "プリセット ${DebugSpec.presetName(r.int("preset"))}   " +
                "装填段階 ${r.int("stage") ?: "--"}"
        )
        Mono("センサー ${DebugSpec.sensorName(r.int("sensor"))} (${DebugSpec.ageText(r.int("sensor_age"))})")
        Mono("コントローラー受信 ${DebugSpec.ageText(r.int("ctrl_age"))}")
    }

    // ---- 制御用 ID ----
    @Composable
    private fun LiftCmdBody(r: DebugSpec.Reader) {
        BitChips("指令", DebugSpec.lift130Names(r.int("flags") ?: 0), "なし", UnsupportedColor)
        Mono("ver ${r.int("ver") ?: "--"}   SEQ ${r.int("seq") ?: "--"}")
    }

    @Composable
    private fun RawBody(r: DebugSpec.Reader) {
        // 中身の並びが台帳に無いので生バイトで表示
        Mono((0..7).joinToString(" ") { i -> r.int("b$i")?.let { "%02X".format(it) } ?: "--" })
    }

    @Composable
    private fun ShotCmdBody(r: DebugSpec.Reader) {
        MainLine(
            "PWM ${r.int("pwm") ?: "--"}",
            "角度 ${r.int("angle")?.let { "${it}°" } ?: "--"}",
            "プリセット ${DebugSpec.presetName(r.int("preset"))}",
        )
        BitChips("指令", DebugSpec.cmd150Names(r.int("flags") ?: 0), "なし", OkColor)
    }

    @Composable
    private fun LoadSensBody(r: DebugSpec.Reader) {
        MainLine(
            "センサー ${if ((r.int("sensor") ?: 0) != 0) "検出" else "非検出"}",
            if ((r.int("ready") ?: 0) != 0) "準備完了" else "準備中",
        )
    }

    // ---- 足回り ----
    @Composable
    private fun WheelBody(r: DebugSpec.Reader) {
        val cmd = r.int("cmd")
        MainLine(
            DebugSpec.stateName(Mech.WHEEL, r.int("state")),
            "CMD ${cmd?.let { "%+d".format(it) } ?: "--"}",
            "PWM ${DebugSpec.pwmText(r.int("pwm"))}",
        )
        Mono("RPM ${DebugSpec.rpmText(r.flags, r.long("rpm10"))}   RX ${r.long("rx") ?: "--"}")
        Mono("STOP理由 ${DebugSpec.stopReason(r.int("stop"))}")
    }

    // ---- 共通: 指令受信状態 / 異常ビット / 稼働時間 ----
    @Composable
    private fun CommonFooter(r: DebugSpec.Reader, mech: Mech) {
        Mono(
            (if (r.has(DebugSpec.F_CMD_FRESH)) "指令受信OK" else "指令受信なし") +
                "   稼働 ${DebugSpec.uptimeText(r.long("uptime"))}"
        )
        BitChips("異常", DebugSpec.errorNamesFor(mech, r.int("err") ?: 0), "検出なし", LostColor)
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
