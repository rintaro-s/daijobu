package com.nbks.controoooooool.ui.display

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbks.controoooooool.model.Telemetry

/**
 * Switch Pro コントローラ風の表示。
 * どの信号をどの部品に割り当てるかは [Signals] で変えられる (名前は ESP32 の can_signals.h と一致させる)。
 */
object GamepadLayout : DisplayLayout {
    override val title = "コントローラ"

    object Signals {
        const val LX = "LX"
        const val LY = "LY"
        const val RX = "RX"
        const val RY = "RY"
        // 実機で確認した結果、A/B と X/Y は信号名と絵の位置が逆だったので入れ替えて割り当てる
        const val A = "B"
        const val B = "A"
        const val X = "Y"
        const val Y = "X"
        const val UP = "UP"
        const val DOWN = "DOWN"
        const val LEFT = "LEFT"
        const val RIGHT = "RIGHT"
        const val L1 = "L1"
        const val L2 = "L2"
        const val R1 = "R1"
        const val R2 = "R2"
        const val PLUS = "PLUS"
        const val MINUS = "MINUS"
        const val HOME = "HOME"

        /** スティックの値の範囲 (±この値で端まで振り切る) */
        const val STICK_MAX = 127.0
        /** true: LY/RY が正のとき上に倒した表示にする。実機確認で逆だったので false */
        const val STICK_Y_UP_POSITIVE = false
    }

    // 描画は 1000 x 640 の仮想座標で行い、画面に合わせて拡縮する
    private const val W = 1000f
    private const val H = 640f

    private val Body = Color(0xFF2B2D31)
    private val BodyEdge = Color(0xFF45484F)
    private val Part = Color(0xFF3A3D43)
    private val PartDark = Color(0xFF1C1D20)
    private val Label = Color(0xFFB8BCC4)
    private val On = Color(0xFFFF4D5E)
    private val OnText = Color.White

    @Composable
    override fun Content(telemetry: Telemetry, modifier: Modifier) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val sig = Signals
        val lText = "L\nX %4s\nY %4s".format(fmt(telemetry.value(sig.LX)), fmt(telemetry.value(sig.LY)))
        val rText = "R\nX %4s\nY %4s".format(fmt(telemetry.value(sig.RX)), fmt(telemetry.value(sig.RY)))

        if (landscape) {
            // 横画面: 高さを絵に全部使い、スティックの値は左右の余白に置く
            Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                StatusText(telemetry, Modifier.align(Alignment.CenterHorizontally))
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StickText(lText)
                    PadCanvas(telemetry, Modifier.weight(1f, fill = false).aspectRatio(W / H))
                    StickText(rText)
                }
            }
        } else {
            Column(
                modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                // 横幅に合わせて比率を保ったまま最大化する
                PadCanvas(telemetry, Modifier.weight(1f, fill = false).aspectRatio(W / H))
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    StickText(lText)
                    StickText(rText)
                }
                StatusText(telemetry)
            }
        }
    }

    @Composable
    private fun PadCanvas(telemetry: Telemetry, modifier: Modifier) {
        val tm = rememberTextMeasurer()
        val fresh = telemetry.isFresh(Signals.LX)
        // 途絶時は絵全体を 1 枚のレイヤーとして薄くする (部品ごとに薄くすると重なりが汚くなる)
        Canvas(
            modifier.graphicsLayer {
                alpha = if (fresh) 1f else 0.45f
                compositingStrategy = CompositingStrategy.Offscreen
            }
        ) {
            val s = minOf(size.width / W, size.height / H)
            withTransform({
                translate((size.width - W * s) / 2f, (size.height - H * s) / 2f)
                scale(s, s, Offset.Zero)
            }) {
                drawPad(telemetry, tm)
            }
        }
    }

    @Composable
    private fun StickText(text: String) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun StatusText(telemetry: Telemetry, modifier: Modifier = Modifier) {
        if (!telemetry.isFresh(Signals.LX)) {
            Text(
                if ((telemetry.frameOf(Signals.LX)?.ageMs ?: -1) < 0) "データ待ち…" else "CAN 途絶 (最後の値を表示中)",
                modifier,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    private fun fmt(v: Double?) = v?.toLong()?.toString() ?: "--"

    private fun DrawScope.drawPad(t: Telemetry, tm: TextMeasurer) {
        val alpha = 1f // 途絶時の薄さは PadCanvas の graphicsLayer でまとめてかける
        val sig = Signals

        // ---- トリガ (ZL/ZR = L2/R2) とショルダー (L/R = L1/R1) ----
        drawShoulder(t, sig.L2, "ZL", Offset(170f, 18f), Size(210f, 58f), tm, alpha)
        drawShoulder(t, sig.R2, "ZR", Offset(620f, 18f), Size(210f, 58f), tm, alpha)
        drawShoulder(t, sig.L1, "L", Offset(150f, 64f), Size(250f, 60f), tm, alpha)
        drawShoulder(t, sig.R1, "R", Offset(600f, 64f), Size(250f, 60f), tm, alpha)

        // ---- 本体 (上部 + 左右グリップ) ----
        rotate(18f, pivot = Offset(250f, 420f)) {
            drawRoundRect(Body, Offset(140f, 230f), Size(230f, 380f), CornerRadius(115f), alpha = alpha)
        }
        rotate(-18f, pivot = Offset(750f, 420f)) {
            drawRoundRect(Body, Offset(630f, 230f), Size(230f, 380f), CornerRadius(115f), alpha = alpha)
        }
        drawRoundRect(Body, Offset(110f, 120f), Size(780f, 330f), CornerRadius(150f), alpha = alpha)
        drawRoundRect(BodyEdge, Offset(110f, 120f), Size(780f, 330f), CornerRadius(150f), alpha = alpha, style = Stroke(3f))

        // ---- - / + / HOME / キャプチャ(信号なし) ----
        drawRoundButton(t, sig.MINUS, "−", Offset(400f, 185f), 24f, tm, alpha)
        drawRoundButton(t, sig.PLUS, "+", Offset(600f, 185f), 24f, tm, alpha)
        drawRoundButton(t, sig.HOME, "⌂", Offset(560f, 262f), 24f, tm, alpha)
        drawRoundRect(PartDark, Offset(424f, 244f), Size(36f, 36f), CornerRadius(6f), alpha = alpha)

        // ---- スティック ----
        drawStick(t, sig.LX, sig.LY, Offset(270f, 245f), alpha)
        drawStick(t, sig.RX, sig.RY, Offset(620f, 390f), alpha)

        // ---- 十字キー ----
        drawDpad(t, Offset(380f, 390f), tm, alpha)

        // ---- ABXY ----
        val c = Offset(740f, 255f)
        val d = 62f
        drawRoundButton(t, sig.X, "X", c + Offset(0f, -d), 30f, tm, alpha)
        drawRoundButton(t, sig.A, "A", c + Offset(d, 0f), 30f, tm, alpha)
        drawRoundButton(t, sig.B, "B", c + Offset(0f, d), 30f, tm, alpha)
        drawRoundButton(t, sig.Y, "Y", c + Offset(-d, 0f), 30f, tm, alpha)
    }

    private fun DrawScope.drawShoulder(
        t: Telemetry, name: String, label: String, topLeft: Offset, size: Size, tm: TextMeasurer, alpha: Float,
    ) {
        val on = t.pressed(name)
        drawRoundRect(if (on) On else Part, topLeft, size, CornerRadius(28f), alpha = alpha)
        drawRoundRect(BodyEdge, topLeft, size, CornerRadius(28f), alpha = alpha, style = Stroke(2f))
        drawLabel(tm, label, topLeft + Offset(size.width / 2f, size.height / 2f), if (on) OnText else Label, 26f)
    }

    private fun DrawScope.drawRoundButton(
        t: Telemetry, name: String, label: String, center: Offset, r: Float, tm: TextMeasurer, alpha: Float,
    ) {
        val on = t.pressed(name)
        drawCircle(if (on) On else PartDark, r, center, alpha)
        drawCircle(BodyEdge, r, center, alpha, Stroke(2f))
        drawLabel(tm, label, center, if (on) OnText else Label, r * 0.95f)
    }

    private fun DrawScope.drawStick(t: Telemetry, xName: String, yName: String, center: Offset, alpha: Float) {
        val sig = Signals
        val x = ((t.value(xName) ?: 0.0) / sig.STICK_MAX).coerceIn(-1.0, 1.0).toFloat()
        val yRaw = ((t.value(yName) ?: 0.0) / sig.STICK_MAX).coerceIn(-1.0, 1.0).toFloat()
        val y = if (sig.STICK_Y_UP_POSITIVE) -yRaw else yRaw
        val travel = 42f

        drawCircle(PartDark, 78f, center, alpha)
        drawCircle(BodyEdge, 78f, center, alpha, Stroke(2f))
        // 可動範囲の十字線
        drawLine(Part, center + Offset(-travel, 0f), center + Offset(travel, 0f), 2f, alpha = alpha)
        drawLine(Part, center + Offset(0f, -travel), center + Offset(0f, travel), 2f, alpha = alpha)

        val knob = center + Offset(x * travel, y * travel)
        val active = x != 0f || y != 0f
        drawCircle(Color(0xFF111214), 50f, knob, alpha)
        drawCircle(if (active) On else Part, 50f, knob, alpha, Stroke(6f))
        drawCircle(Part, 30f, knob, alpha)
    }

    private fun DrawScope.drawDpad(t: Telemetry, center: Offset, tm: TextMeasurer, alpha: Float) {
        val sig = Signals
        val arm = 38f
        val len = 52f
        drawCircle(PartDark, 92f, center, alpha * 0.6f)
        data class Arm(val name: String, val label: String, val dir: Offset)
        listOf(
            Arm(sig.UP, "▲", Offset(0f, -1f)),
            Arm(sig.DOWN, "▼", Offset(0f, 1f)),
            Arm(sig.LEFT, "◀", Offset(-1f, 0f)),
            Arm(sig.RIGHT, "▶", Offset(1f, 0f)),
        ).forEach { a ->
            val on = t.pressed(a.name)
            val c = center + a.dir * (len / 2f + arm / 2f)
            val sz = if (a.dir.x == 0f) Size(arm, len) else Size(len, arm)
            drawRoundRect(
                if (on) On else Part,
                Offset(c.x - sz.width / 2f, c.y - sz.height / 2f),
                sz, CornerRadius(6f), alpha = alpha,
            )
            drawLabel(tm, a.label, c, if (on) OnText else Label, 18f)
        }
        drawRect(Part, Offset(center.x - arm / 2f, center.y - arm / 2f), Size(arm, arm), alpha)
    }

    private fun DrawScope.drawLabel(tm: TextMeasurer, text: String, center: Offset, color: Color, px: Float) {
        // 仮想座標系で拡縮されているので、px を sp に換算せずそのまま使う
        val style = TextStyle(color = color, fontSize = (px / density / fontScale).sp, fontWeight = FontWeight.Bold)
        val layout = tm.measure(text, style)
        drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
}
