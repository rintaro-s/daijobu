package com.nbks.controoooooool.ui.display

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.ui.display.DebugSpec.Link
import com.nbks.controoooooool.ui.display.DebugSpec.Mech
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 調整用の表示: 調整モードの有無 / サーボ角度 / 発射 PWM / 昇降のエンコーダー。
 *
 *   - 調整モード: 0x310 byte12 bit5 (MINUS 調整) を優先、無ければ 0x150 byte1 bit2。調整対象は 0x310 byte13
 *   - サーボ角度 (投擲角度の設定) と発射 PWM の設定: 0x310 を優先、無ければ 0x150
 *   - エンコーダー: 昇降 3 軸の POS / REF (0x301-0x303)。HOME・有効フラグ・エンコーダー関連の異常を併記
 *
 * 途絶・未受信の ID の値は薄く表示し、値は前回値のまま (「前回値」と明記)。
 */
object TuningLayout : DisplayLayout {
    override val title = "調整"

    /** サーボ角度ゲージの目盛 (度)。実際の可動範囲に合わせて変える */
    private const val ANGLE_MAX = 180f

    private val Ok = Color(0xFF2E7D32)
    private val Warn = Color(0xFFEF6C00)
    private val Bad = Color(0xFFD32F2F)
    private val None = Color(0xFF9E9E9E)
    private val Accent = Color(0xFF1E88E5)

    private fun unit(prefix: String) = DebugSpec.units.first { it.prefix == prefix }

    @Composable
    override fun Content(telemetry: Telemetry, modifier: Modifier) {
        BoxWithConstraints(modifier) {
            if (maxWidth > maxHeight && maxHeight < 480.dp) {
                Landscape(telemetry, maxHeight.value)
            } else {
                Portrait(telemetry)
            }
        }
    }

    @Composable
    private fun Portrait(telemetry: Telemetry) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AdjustBanner(telemetry)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth > 600.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ServoCard(telemetry)
                            PwmCard(telemetry)
                        }
                        Column(Modifier.weight(1f)) { EncoderCard(telemetry) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ServoCard(telemetry)
                        PwmCard(telemetry)
                        EncoderCard(telemetry)
                    }
                }
            }
        }
    }

    // ---- 横画面: スクロールなしで 1 画面に収める (3 列) ----
    // 左: 調整モード + 発射 PWM / 中: サーボ角度 / 右: 昇降エンコーダー 3 軸
    // 高さが小さい機種でも収まるよう、文字サイズを高さに比例して縮める (基準 300dp)
    @Composable
    private fun Landscape(t: Telemetry, heightDp: Float) {
        val k = (heightDp / 300f).coerceIn(0.6f, 1.2f)
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactBanner(t, k)
                CompactPwm(t, k, Modifier.weight(1f))
            }
            CompactServo(t, k, Modifier.weight(1f).fillMaxHeight())
            CompactEncoders(t, k, Modifier.weight(1.7f).fillMaxHeight())
        }
    }

    @Composable
    private fun CompactBanner(t: Telemetry, k: Float) {
        val a = adjustState(t)
        val (bg, text) = adjustStyle(a.on)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = (6 * k).dp)) {
                Text(text, color = Color.White, fontSize = (20 * k).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val sub = buildString {
                    if (a.target != null) append("対象: ${a.target}  ")
                    if (a.mismatch) append("0x310/0x150 不一致  ")
                    append(a.source)
                }
                Text(sub, color = Color.White.copy(alpha = 0.9f), fontSize = (11 * k).sp, maxLines = 2)
            }
        }
    }

    @Composable
    private fun CompactServo(t: Telemetry, k: Float, modifier: Modifier) {
        val src = pick(t, "angle", "angle")
        val preset = DebugSpec.Reader(t, "SHOT").int("preset")
        CompactCard("サーボ角度", src, k, modifier) {
            Gauge(src.value?.toFloat(), Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text("0°", fontSize = (10 * k).sp)
                Spacer(Modifier.weight(1f))
                Text("${ANGLE_MAX.toInt()}°", fontSize = (10 * k).sp)
            }
            Big(src.value?.let { "%.0f°".format(it) } ?: "--", (30 * k).toInt())
            Text("プリセット ${DebugSpec.presetName(preset)}", fontSize = (12 * k).sp)
            CompactMismatch(src, "°", k)
        }
    }

    @Composable
    private fun CompactPwm(t: Telemetry, k: Float, modifier: Modifier) {
        val src = pick(t, "pwm", "pwm")
        CompactCard("発射 PWM", src, k, modifier) {
            Big(src.value?.let { "%.0f".format(it) } ?: "--", (30 * k).toInt())
            Bar(src.value?.let { (it / 1000.0).toFloat() }, Accent, Modifier.fillMaxWidth().height((14 * k).dp))
            CompactMismatch(src, "‰", k)
        }
    }

    @Composable
    private fun CompactMismatch(src: Source, unit: String, k: Float) {
        val v = src.value ?: return
        val o = src.other ?: return
        if (abs(v - o) > 0.5) {
            Text("0x150: ${"%.0f".format(o)}$unit (不一致)", color = Warn, fontSize = (11 * k).sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun CompactCard(
        title: String, src: Source, k: Float, modifier: Modifier,
        content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    ) {
        Card(modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy((3 * k).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = (13 * k).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (src.value != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(linkColor(src.link)))
                        Text(" ${src.from}${if (src.link != Link.Ok) " 前回値" else ""}", fontSize = (10 * k).sp)
                    }
                }
                Column(
                    Modifier.weight(1f).alpha(if (src.link == Link.Ok) 1f else 0.45f),
                    verticalArrangement = Arrangement.spacedBy((3 * k).dp),
                ) { content() }
                if (src.value == null) Text("データなし", color = None, fontSize = (12 * k).sp)
            }
        }
    }

    @Composable
    private fun CompactEncoders(t: Telemetry, k: Float, modifier: Modifier) {
        Card(modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                val lifts = listOf("LIFT1", "LIFT2", "LIFT3")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("昇降エンコーダー", fontSize = (13 * k).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "3軸 POS差 " + (posSpread(t, lifts)?.let { "%,d".format(it) } ?: "--"),
                        fontSize = (11 * k).sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline, maxLines = 1,
                    )
                }
                lifts.forEachIndexed { i, p -> CompactEncoderRow(t, p, i + 1, k) }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun CompactEncoderRow(t: Telemetry, prefix: String, no: Int, k: Float) {
        val link = DebugSpec.link(t, unit(prefix))
        val r = DebugSpec.Reader(t, prefix)
        if (r.dbl("ver") == null) {
            Text("昇降$no  --  (${linkText(link)})", fontFamily = FontFamily.Monospace, fontSize = (13 * k).sp)
            return
        }
        val pos = encoder(r, "pos", DebugSpec.F_MEASURED)
        val ref = encoder(r, "ref", DebugSpec.F_TARGET)
        val rate = r.int("rate")?.takeIf { it != DebugSpec.U16_INVALID && it <= 1000 }
        val homed = r.has(DebugSpec.F_HOMED)
        // 横画面は 1 行に収めるため、エンコーダー関連の異常は短い名前にする (詳細は縦画面 / 機構タブ)
        val e = r.int("err") ?: 0
        val encErr = buildList {
            if (e and 0x02 != 0) add("停滞")
            if (e and 0x10 != 0) add("逆転")
            if (e and 0x20 != 0) add("飛び")
        }
        // 1 軸 = 2 行 (1 行目: 軸名・タグ・率 / 2 行目: POS・REF・差)。3 軸が確実に 1 画面へ収まるようにする
        Column(Modifier.alpha(if (link == Link.Ok) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy((2 * k).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(linkColor(link)))
                Text("昇降$no", fontWeight = FontWeight.Bold, fontSize = (13 * k).sp)
                if (link != Link.Ok) Text("前回値", color = Bad, fontSize = (10 * k).sp)
                // 正常時は HOME済 のみ。未原点・無効・エンコーダー異常のときだけ追加で出す
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Tag(if (homed) "HOME済" else "未原点", if (homed) Ok else Warn, k)
                    if (!r.has(DebugSpec.F_MEASURED)) Tag("無効", Warn, k)
                    encErr.forEach { Tag(it, Bad, k) }
                }
                Text(rate?.let { "%.1f%%".format(it / 10.0) } ?: "率 --", fontFamily = FontFamily.Monospace, fontSize = (12 * k).sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                NumberText("POS", pos?.let { "%,d".format(it) } ?: "未計測", k)
                NumberText("REF", ref?.let { "%,d".format(it) } ?: "未計測", k)
                NumberText("差", if (pos != null && ref != null) "%+,d".format(ref - pos) else "--", k)
            }
        }
    }

    /** 「POS 12,345」のように小さい見出しと太字の数値を 1 行に並べる */
    @Composable
    private fun NumberText(label: String, value: String, k: Float) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = (10 * k).sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(3.dp))
            Text(value, fontSize = (17 * k).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }

    // ---- 値の取得 (0x310 優先、無ければ 0x150) ----
    private class Source(val value: Double?, val from: String, val link: Link, val other: Double?)

    private fun pick(t: Telemetry, key310: String, key150: String): Source {
        val a = DebugSpec.Reader(t, "SHOT").dbl(key310)
        val b = DebugSpec.Reader(t, "SCMD").dbl(key150)
        val linkA = DebugSpec.link(t, unit("SHOT"))
        val linkB = DebugSpec.link(t, unit("SCMD"))
        return when {
            a != null -> Source(a, "0x310", linkA, b)
            b != null -> Source(b, "0x150", linkB, null)
            else -> Source(null, "", Link.NotReceived, null)
        }
    }

    // ---- 調整モード ----
    private class Adjust(val on: Boolean?, val mismatch: Boolean, val target: String?, val source: String)

    private fun adjustState(t: Telemetry): Adjust {
        val shot = DebugSpec.Reader(t, "SHOT")
        val scmd = DebugSpec.Reader(t, "SCMD")
        val ok310 = shot.dbl("ver") != null && DebugSpec.link(t, unit("SHOT")) == Link.Ok
        val ok150 = scmd.dbl("ver") != null && DebugSpec.link(t, unit("SCMD")) == Link.Ok
        val on310 = (shot.int("cmd") ?: 0) and 0x20 != 0
        val on150 = (scmd.int("flags") ?: 0) and 0x04 != 0
        // 新鮮なデータだけで判断する (途絶中の前回値で「ON」と言い続けない)
        val on: Boolean? = when {
            ok310 -> on310
            ok150 -> on150
            else -> null
        }
        return Adjust(
            on = on,
            mismatch = ok310 && ok150 && on310 != on150,
            target = if (on == true && ok310) DebugSpec.adjustName(shot.int("adjust")) else null,
            source = when {
                ok310 -> "出典 0x310"
                ok150 -> "出典 0x150"
                else -> "0x310/0x150 受信なし"
            },
        )
    }

    private fun adjustStyle(on: Boolean?) = when (on) {
        true -> Warn to "調整モード ON"
        false -> Ok to "調整モード OFF (通常)"
        null -> None to "調整モード: データなし"
    }

    @Composable
    private fun AdjustBanner(t: Telemetry) {
        val a = adjustState(t)
        val (bg, text) = adjustStyle(a.on)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().background(bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (a.target != null) Text("調整対象: ${a.target}", color = Color.White, fontSize = 18.sp)
                if (a.mismatch) {
                    Text("0x310 と 0x150 で調整モードが食い違っています", color = Color.White, fontSize = 13.sp)
                }
                Text(a.source, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
    }

    // ---- サーボ角度 ----
    @Composable
    private fun ServoCard(t: Telemetry) {
        val src = pick(t, "angle", "angle")
        val preset = DebugSpec.Reader(t, "SHOT").int("preset")
        Panel("サーボ角度 (投擲角度の設定)", src) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Gauge(src.value?.toFloat(), Modifier.fillMaxWidth().aspectRatio(2f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                        Text("0°", fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${ANGLE_MAX.toInt()}°", fontSize = 11.sp)
                    }
                }
                Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                    Big(src.value?.let { "%.0f°".format(it) } ?: "--")
                    Text("プリセット ${DebugSpec.presetName(preset)}", fontSize = 14.sp)
                }
            }
            Mismatch(src, "°")
        }
    }

    /** 半円のゲージ。0° が左端、ANGLE_MAX が右端 */
    @Composable
    private fun Gauge(angle: Float?, modifier: Modifier) {
        val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        val needle = MaterialTheme.colorScheme.onSurface
        Canvas(modifier) {
            val stroke = 18.dp.toPx()
            val d = minOf(size.width, size.height * 2f) - stroke
            val topLeft = Offset((size.width - d) / 2f, stroke / 2f)
            val arcSize = Size(d, d)
            drawArc(track, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (angle != null) {
                val ratio = (angle / ANGLE_MAX).coerceIn(0f, 1f)
                drawArc(Accent, 180f, 180f * ratio, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val center = Offset(topLeft.x + d / 2f, topLeft.y + d / 2f)
                val rad = Math.toRadians((180f + 180f * ratio).toDouble())
                val r = d / 2f - stroke * 1.4f
                drawLine(
                    needle, center,
                    Offset(center.x + (cos(rad) * r).toFloat(), center.y + (sin(rad) * r).toFloat()),
                    strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
    }

    // ---- 発射 PWM ----
    @Composable
    private fun PwmCard(t: Telemetry) {
        val src = pick(t, "pwm", "pwm")
        Panel("発射 PWM (設定・生の値 0〜1000)", src) {
            Big(src.value?.let { "%.0f".format(it) } ?: "--")
            Bar(src.value?.let { (it / 1000.0).toFloat() }, Accent, Modifier.fillMaxWidth().height(22.dp))
            Row {
                Text("0", fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("1000", fontSize = 11.sp)
            }
            Mismatch(src, "‰")
        }
    }

    @Composable
    private fun Mismatch(src: Source, unit: String) {
        val v = src.value ?: return
        val o = src.other ?: return
        if (abs(v - o) > 0.5) {
            Text(
                "0x150 の値は ${"%.0f".format(o)}$unit (不一致)",
                color = Warn, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
    }

    // ---- 昇降のロータリーエンコーダー ----
    @Composable
    private fun EncoderCard(t: Telemetry) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("昇降エンコーダー", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val lifts = listOf("LIFT1", "LIFT2", "LIFT3")
                lifts.forEachIndexed { i, p -> EncoderRow(t, p, i + 1) }
                Spread(t, lifts)
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun EncoderRow(t: Telemetry, prefix: String, no: Int) {
        val u = unit(prefix)
        val link = DebugSpec.link(t, u)
        val r = DebugSpec.Reader(t, prefix)
        val hasData = r.dbl("ver") != null
        val pos = encoder(r, "pos", DebugSpec.F_MEASURED)
        val ref = encoder(r, "ref", DebugSpec.F_TARGET)
        val rate = r.int("rate")?.takeIf { it != DebugSpec.U16_INVALID && it <= 1000 }
        val homed = r.has(DebugSpec.F_HOMED)
        val err = r.int("err") ?: 0
        // エンコーダーに関する故障のビット (停滞 / 逆方向カウント / カウント飛び)
        val encErr = DebugSpec.errorNamesFor(Mech.LIFT, err and 0x0032)

        Column(Modifier.alpha(if (link == Link.Ok) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(linkColor(link)))
                Spacer(Modifier.width(6.dp))
                Text("昇降$no", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (link != Link.Ok && hasData) {
                    Text("  前回値", color = Bad, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    rate?.let { "%.1f%%".format(it / 10.0) } ?: "率 --",
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                )
            }
            if (!hasData) {
                Text("--  (${linkText(link)})", fontFamily = FontFamily.Monospace)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("POS", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Big(pos?.let { "%,d".format(it) } ?: "未計測", 26)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("REF", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Big(ref?.let { "%,d".format(it) } ?: "未計測", 26)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("差 (REF-POS)", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Text(
                            if (pos != null && ref != null) "%+,d".format(ref - pos) else "--",
                            fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                        )
                    }
                }
                Bar(rate?.let { it / 1000f }, Accent, Modifier.fillMaxWidth().height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Tag(if (r.has(DebugSpec.F_MEASURED)) "エンコーダー有効" else "エンコーダー無効",
                        if (r.has(DebugSpec.F_MEASURED)) Ok else Warn)
                    Tag(if (homed) "HOME済" else "未原点 (POSは高さではない)", if (homed) Ok else Warn)
                    encErr.forEach { Tag(it, Bad) }
                }
            }
        }
    }

    /** 3 軸の POS の差 (max-min)。全軸が受信中かつ原点確定のときだけ計算。監視側の推定 */
    private fun posSpread(t: Telemetry, prefixes: List<String>): Long? {
        val vals = prefixes.map { p ->
            val r = DebugSpec.Reader(t, p)
            if (DebugSpec.link(t, unit(p)) == Link.Ok && r.has(DebugSpec.F_HOMED)) encoder(r, "pos", DebugSpec.F_MEASURED) else null
        }
        if (vals.any { it == null }) return null
        return vals.filterNotNull().let { it.max() - it.min() }
    }

    @Composable
    private fun Spread(t: Telemetry, prefixes: List<String>) {
        val v = posSpread(t, prefixes)
        val text = if (v == null) "3軸の POS 差: --  (全軸が受信中かつ原点確定のときだけ計算)"
        else "3軸の POS 差 (max-min): %,d  [監視側の推定]".format(v)
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }

    /** エンコーダー値。有効フラグ OFF や INT32_MIN は null (未計測) */
    private fun encoder(r: DebugSpec.Reader, key: String, flag: Int): Long? {
        val v = r.long(key) ?: return null
        return if (!r.has(flag) || v == DebugSpec.I32_INVALID) null else v
    }

    // ---- 部品 ----
    @Composable
    private fun Panel(title: String, src: Source, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (src.value != null) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(linkColor(src.link)))
                        Text("  ${src.from}${if (src.link != Link.Ok) " 前回値" else ""}", fontSize = 12.sp)
                    }
                }
                Column(Modifier.alpha(if (src.link == Link.Ok) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    content()
                }
                if (src.value == null) Text("データなし", color = None)
            }
        }
    }

    private fun linkColor(l: Link) = when (l) {
        Link.Ok -> Ok
        is Link.Lost -> Bad
        is Link.Unsupported -> Warn
        Link.NotReceived -> None
    }

    private fun linkText(l: Link) = when (l) {
        Link.Ok -> "受信中"
        is Link.Lost -> "通信途絶"
        is Link.Unsupported -> "未対応"
        Link.NotReceived -> "未受信"
    }

    @Composable
    private fun Big(text: String, size: Int = 36) {
        Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }

    @Composable
    private fun Bar(ratio: Float?, color: Color, modifier: Modifier) {
        Box(modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))) {
            if (ratio != null) {
                Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight().background(color))
            }
        }
    }

    @Composable
    private fun Tag(text: String, color: Color, k: Float = 1f) {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
            color = Color.White, fontSize = (11 * k).sp,
        )
    }
}
