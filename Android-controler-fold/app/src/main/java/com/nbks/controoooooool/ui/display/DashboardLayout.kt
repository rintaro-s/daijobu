package com.nbks.controoooooool.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.ui.display.DebugSpec.Link
import com.nbks.controoooooool.ui.display.DebugSpec.Mech

/**
 * 現場用のダッシュボード。全機構の状態を 1 画面にコンパクトに並べる (コントローラー情報は出さない)。
 *
 *   - 通信: 全 ID の受信状態を色付きの点で一覧
 *   - 昇降: 昇降率バー + PWM バー (中央 0) + 状態 / 原点 / 異常
 *   - 足回り: 4 輪を車体配置どおり 2×2 で、PWM バー + CMD + 状態
 *   - 発射・装填: PWM バー、角度、指令、センサー
 *   - 制御: 昇降の禁止指令、装填センサー
 *
 * 途絶・未対応中は薄く表示し、値は前回値のまま (詳細は「機構」タブ)。
 */
object DashboardLayout : DisplayLayout {
    override val title = "ダッシュボード"

    private val Ok = Color(0xFF2E7D32)
    private val Warn = Color(0xFFEF6C00)
    private val Bad = Color(0xFFD32F2F)
    private val None = Color(0xFF9E9E9E)
    private val BarPos = Color(0xFF1E88E5)
    private val BarNeg = Color(0xFFE53935)

    private fun unit(prefix: String) = DebugSpec.units.first { it.prefix == prefix }

    @Composable
    override fun Content(telemetry: Telemetry, modifier: Modifier) {
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinkSummary(telemetry)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth > 600.dp) {
                    // 横画面・タブレット: 2 列
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiftPanel(telemetry)
                            ShotPanel(telemetry)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WheelPanel(telemetry)
                            GyroPanel(telemetry)
                            ControlPanel(telemetry)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiftPanel(telemetry)
                        WheelPanel(telemetry)
                        GyroPanel(telemetry)
                        ShotPanel(telemetry)
                        ControlPanel(telemetry)
                    }
                }
            }
        }
    }

    // ---- 通信状態 ----
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun LinkSummary(t: Telemetry) {
        Panel("通信") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DebugSpec.units.forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(linkColor(DebugSpec.link(t, u)))
                        Spacer(Modifier.width(3.dp))
                        Text("%03X".format(u.canId), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    // ---- 昇降 ----
    @Composable
    private fun LiftPanel(t: Telemetry) {
        Panel("昇降") {
            listOf("LIFT1", "LIFT2", "LIFT3").forEachIndexed { i, p ->
                val u = unit(p)
                val r = DebugSpec.Reader(t, p)
                val rate = r.int("rate")?.takeIf { it != DebugSpec.U16_INVALID && it <= 1000 }
                val pwm = r.int("pwm")?.takeIf { it != DebugSpec.I16_INVALID }
                val err = r.int("err") ?: 0
                Stale(DebugSpec.link(t, u)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("${i + 1}", 16.dp)
                            Bar(rate?.let { it / 1000f }, BarPos, Modifier.weight(1f))
                            Value(rate?.let { "%.1f%%".format(it / 10.0) } ?: "--", 58.dp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(16.dp))
                            CenterBar(pwm?.let { it / 1000f }, Modifier.weight(1f))
                            Value(DebugSpec.pwmText(r.int("pwm")), 58.dp)
                        }
                        Tags(
                            DebugSpec.stateName(Mech.LIFT, r.int("state")) to None,
                            (if (r.has(DebugSpec.F_HOMED)) "HOME済" else "未原点") to
                                (if (r.has(DebugSpec.F_HOMED)) Ok else Warn),
                            *DebugSpec.liftLimitNames(r.int("limit") ?: 0).map { it to Warn }.toTypedArray(),
                            *(if (r.has(DebugSpec.F_HOLD_ACTIVE)) arrayOf("上限保持" to Ok) else emptyArray()),
                            *DebugSpec.errorNamesFor(Mech.LIFT, err).map { it to Bad }.toTypedArray(),
                        )
                    }
                }
            }
        }
    }

    // ---- 足回り (車体配置どおり 2×2) ----
    @Composable
    private fun WheelPanel(t: Telemetry) {
        Panel("足回り") {
            listOf(listOf("WFL", "WFR"), listOf("WRL", "WRR")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p -> Wheel(t, p, Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun Wheel(t: Telemetry, p: String, modifier: Modifier) {
        val u = unit(p)
        val r = DebugSpec.Reader(t, p)
        val pwm = r.int("pwm")?.takeIf { it != DebugSpec.I16_INVALID }
        val stop = r.int("stop") ?: 0
        val err = r.int("err") ?: 0
        Stale(DebugSpec.link(t, u), modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(linkColor(DebugSpec.link(t, u)))
                    Spacer(Modifier.width(4.dp))
                    Text(u.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "CMD ${r.int("cmd")?.let { "%+d".format(it) } ?: "--"}",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                CenterBar(pwm?.let { it / 1000f }, Modifier.fillMaxWidth())
                Text(
                    DebugSpec.pwmText(r.int("pwm")) + "  " + DebugSpec.stateName(Mech.WHEEL, r.int("state")),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Tags(
                    *(if (stop != 0) arrayOf(DebugSpec.stopReason(stop) to Warn) else emptyArray()),
                    *DebugSpec.errorNamesFor(Mech.WHEEL, err).map { it to Bad }.toTypedArray(),
                )
            }
        }
    }

    // ---- ジャイロ (0x200) ----
    @Composable
    private fun GyroPanel(t: Telemetry) {
        val r = DebugSpec.Reader(t, "GYRO")
        val flags = r.int("flags") ?: 0
        val valid = DebugSpec.gyroValid(flags)
        Panel("ジャイロ") {
            Stale(DebugSpec.link(t, unit("GYRO"))) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Tags(
                        (if (valid) "測定VALID" else "IMU測定無効") to (if (valid) Ok else Warn),
                        DebugSpec.calText(flags, r.int("cal")) to None,
                    )
                    Text(
                        "Y ${DebugSpec.degText(r.dbl("yaw"), valid)}  R ${DebugSpec.degText(r.dbl("roll"), valid)}  " +
                            "P ${DebugSpec.degText(r.dbl("pitch"), valid)}",
                        fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Gz ${DebugSpec.degText(r.dbl("gz"), valid, "dps")}   " +
                            "STATUS ${r.int("sys_status") ?: "--"} ERR ${r.int("sys_err") ?: "--"}",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    // ---- 発射・装填 (0x310: 操作mainの指令状態) ----
    @Composable
    private fun ShotPanel(t: Telemetry) {
        val u = unit("SHOT")
        val r = DebugSpec.Reader(t, "SHOT")
        val pwm = r.int("pwm")
        Panel("発射・装填 (指令)") {
            Stale(DebugSpec.link(t, u)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("PWM", 40.dp)
                        Bar(pwm?.let { it / 1000f }, BarPos, Modifier.weight(1f))
                        Value(pwm?.toString() ?: "--", 58.dp)
                    }
                    Text(
                        "角度 ${r.int("angle")?.let { "$it°" } ?: "--"}   " +
                            "プリセット ${DebugSpec.presetName(r.int("preset"))}   " +
                            "段階 ${r.int("stage") ?: "--"}",
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    )
                    Tags(
                        *DebugSpec.cmd310Names(r.int("cmd") ?: 0).map {
                            it to if (it == "HALT") Bad else Ok
                        }.toTypedArray(),
                        ("センサー " + DebugSpec.sensorName(r.int("sensor"))) to None,
                        *DebugSpec.errorNamesFor(Mech.SHOT, r.int("err") ?: 0).map { it to Bad }.toTypedArray(),
                    )
                }
            }
        }
    }

    // ---- 制御用 ID ----
    @Composable
    private fun ControlPanel(t: Telemetry) {
        Panel("制御") {
            val lc = DebugSpec.Reader(t, "LCMD")
            Stale(DebugSpec.link(t, unit("LCMD"))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("昇降指令", 92.dp)
                    val names = DebugSpec.lift130Names(lc.int("flags") ?: 0)
                    if (names.isEmpty()) Text("制限なし", fontSize = 13.sp)
                    else Tags(*names.map { it to Warn }.toTypedArray())
                }
            }
            val ls = DebugSpec.Reader(t, "LSEN")
            Stale(DebugSpec.link(t, unit("LSEN"))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("S1 / S2", 92.dp)
                    val s1 = (ls.int("s1") ?: 0) != 0
                    val s2 = (ls.int("s2") ?: 0) != 0
                    Tags(
                        (if (s1) "S1 検出" else "S1 非検出") to (if (s1) Ok else None),
                        (if (s2) "S2 検出" else "S2 非検出") to (if (s2) Ok else None),
                        *(if ((ls.int("boot") ?: 0) != 0) arrayOf("起動報告中" to Warn) else emptyArray()),
                    )
                }
            }
            val lr = DebugSpec.Reader(t, "LREV")
            Stale(DebugSpec.link(t, unit("LREV"))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("装填逆転", 92.dp)
                    val allow = ((lr.int("flags") ?: 0) and 2) != 0
                    Tags((if (allow) "逆転 許可" else "逆転 OFF") to (if (allow) Warn else None))
                }
            }
            val hd = DebugSpec.Reader(t, "HDG")
            Stale(DebugSpec.link(t, unit("HDG"))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("方位補正", 92.dp)
                    val f = hd.int("flags") ?: 0
                    Tags(
                        (if (f and 2 != 0) "補正適用許可" else "補正なし") to (if (f and 2 != 0) Ok else None),
                        "指令 ${hd.int("corr")?.let { "%+d".format(it) } ?: "--"}" to None,
                        "誤差 ${hd.dbl("err")?.let { "%+.1f°".format(it) } ?: "--"}" to None,
                    )
                }
            }
        }
    }

    // ---- 部品 ----
    private fun linkColor(l: Link) = when (l) {
        Link.Ok -> Ok
        is Link.Lost -> Bad
        is Link.Unsupported -> Warn
        Link.NotReceived -> None
    }

    @Composable
    private fun Panel(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }

    /** 受信中以外は薄く表示 (値は前回値) */
    @Composable
    private fun Stale(link: Link, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        Box(modifier.alpha(if (link == Link.Ok) 1f else 0.4f)) { content() }
    }

    @Composable
    private fun Dot(color: Color) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
    }

    @Composable
    private fun Label(text: String, width: Dp) {
        Text(text, Modifier.width(width), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun Value(text: String, width: Dp) {
        Text(
            text, Modifier.width(width).padding(start = 6.dp),
            fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        )
    }

    /** 0～1 の横バー。null は未計測 (枠だけ) */
    @Composable
    private fun Bar(ratio: Float?, color: Color, modifier: Modifier) {
        Box(
            modifier.height(12.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            if (ratio != null) {
                Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight().background(color))
            }
        }
    }

    /** -1～+1 の中央ゼロのバー (正は青で右へ、負は赤で左へ) */
    @Composable
    private fun CenterBar(ratio: Float?, modifier: Modifier) {
        BoxWithConstraints(
            modifier.height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            val half = maxWidth / 2
            if (ratio != null) {
                val v = ratio.coerceIn(-1f, 1f)
                val w = half * kotlin.math.abs(v)
                Box(
                    Modifier.offset(x = if (v >= 0) half else half - w)
                        .width(w).fillMaxHeight().background(if (v >= 0) BarPos else BarNeg)
                )
            }
            Box(Modifier.offset(x = half - 0.5.dp).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Tags(vararg tags: Pair<String, Color>) {
        if (tags.isEmpty()) return
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tags.forEach { (text, color) ->
                Text(
                    text,
                    Modifier.clip(RoundedCornerShape(4.dp)).background(color)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    color = Color.White, fontSize = 11.sp,
                )
            }
        }
    }
}
