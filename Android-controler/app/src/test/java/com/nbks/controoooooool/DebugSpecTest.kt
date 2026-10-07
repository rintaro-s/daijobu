package com.nbks.controoooooool

import com.nbks.controoooooool.model.FrameStatus
import com.nbks.controoooooool.model.Schema
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.ui.display.DebugSpec
import com.nbks.controoooooool.ui.display.DebugSpec.Link
import com.nbks.controoooooool.ui.display.DebugSpec.Mech
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugSpecTest {
    private val lift1 = DebugSpec.units.first { it.prefix == "LIFT1" }

    private fun telemetry(vararg frames: FrameStatus) =
        Telemetry(schema = Schema(timeoutMs = 300), frames = frames.toList())

    @Test
    fun link_status() {
        assertEquals(Link.NotReceived, DebugSpec.link(telemetry(), lift1))
        assertEquals(Link.NotReceived, DebugSpec.link(telemetry(FrameStatus(0x301, -1, 0.0)), lift1))
        assertEquals(Link.Ok, DebugSpec.link(telemetry(FrameStatus(0x301, 300, 10.0)), lift1))
        assertEquals(Link.Lost(301), DebugSpec.link(telemetry(FrameStatus(0x301, 301, 10.0)), lift1))
        // 直近フレームが未対応バージョンなら、途絶より先に「未対応」
        assertEquals(
            Link.Unsupported("未対応バージョン (2)"),
            DebugSpec.link(telemetry(FrameStatus(0x301, 50, 10.0, reject = "ver:2")), lift1),
        )
        // 一度も有効受信していなくても不正フレームが来ていれば「未対応」
        assertEquals(
            Link.Unsupported("長さ不正 (12 byte)"),
            DebugSpec.link(telemetry(FrameStatus(0x301, -1, 0.0, reject = "len:12")), lift1),
        )
    }

    @Test
    fun rpm_distinguishes_zero_and_unmeasured() {
        assertEquals("0.0 rpm", DebugSpec.rpmText(DebugSpec.F_MEASURED, 0))
        assertEquals("123.4 rpm", DebugSpec.rpmText(DebugSpec.F_MEASURED, 1234))
        assertEquals("未計測", DebugSpec.rpmText(0, 1234))                         // フラグ無効
        assertEquals("未計測", DebugSpec.rpmText(DebugSpec.F_MEASURED, DebugSpec.I32_INVALID)) // 無効値
        assertEquals("未計測", DebugSpec.rpmText(DebugSpec.F_MEASURED, null))
    }

    @Test
    fun pwm_text() {
        assertEquals("+375", DebugSpec.pwmText(375))
        assertEquals("-1000", DebugSpec.pwmText(-1000))
        assertEquals("+0", DebugSpec.pwmText(0))
        assertEquals("取得不可", DebugSpec.pwmText(-32768))
        assertEquals("--", DebugSpec.pwmText(null))
    }

    @Test
    fun states_and_bits() {
        assertEquals("PWMゼロ", DebugSpec.stateName(Mech.LIFT, 0))
        assertEquals("未使用(3)", DebugSpec.stateName(Mech.LIFT, 3))
        assertEquals("未対応(5)", DebugSpec.stateName(Mech.LIFT, 5))
        assertEquals("状態 4", DebugSpec.stateName(Mech.SHOT, 4))
        assertEquals(listOf("発射RUN", "MINUS調整"), DebugSpec.cmd310Names(0x21))
        assertEquals(listOf("入力有効", "予約bit(0x80)"), DebugSpec.cmd150Names(0x81))
        assertEquals("未受信", DebugSpec.ageText(65535))
        assertEquals("反転待ち", DebugSpec.stateName(Mech.WHEEL, 3))
        assertEquals("未対応(4)", DebugSpec.stopReason(4))
        assertEquals(listOf("指令タイムアウト", "非常停止"), DebugSpec.errorNames(0x0021))
        assertEquals(listOf("その他の異常", "予約bit(0x0100)"), DebugSpec.errorNames(0x0180))
        assertEquals(listOf("下限SW", "DOWN禁止"), DebugSpec.liftLimitNames(0x0009))
    }
}

class GyroSpecTest {
    @Test
    fun gyro_valid_needs_both_bits() {
        assertEquals(true, DebugSpec.gyroValid(0x03))
        assertEquals(false, DebugSpec.gyroValid(0x01))   // I2C 成功でも測定無効
        assertEquals(false, DebugSpec.gyroValid(0x02))
        assertEquals(false, DebugSpec.gyroValid(0x00))
    }

    @Test
    fun calibration_text() {
        assertEquals("SYS 0  GYR 3  ACC 0  MAG 0", DebugSpec.calText(0x03, 0x30)) // 仕様書の確認用パケット
        assertEquals("校正情報不明", DebugSpec.calText(0x00, 0xFF))               // I2C 失敗の初期値
        assertEquals("SYS 3  GYR 3  ACC 3  MAG 3", DebugSpec.calText(0x01, 0xFF)) // I2C 成功時の 0xFF は本当に全 3
        assertEquals("--", DebugSpec.calText(0x03, null))
    }

    @Test
    fun invalid_measurement_is_not_shown_as_zero() {
        assertEquals("--", DebugSpec.degText(0.0, false))
        assertEquals("0.0°", DebugSpec.degText(0.0, true))
        assertEquals("-84.0°", DebugSpec.degText(-84.0, true))
        assertEquals("-1.0dps", DebugSpec.degText(-1.0, true, "dps"))
    }

    @Test
    fun lift_v30_error_bits() {
        assertEquals(
            listOf("逆方向カウント", "カウント飛び/範囲異常", "3台位置差継続"),
            DebugSpec.errorNamesFor(DebugSpec.Mech.LIFT, 0x0070),
        )
        // 足回りは従来の共通定義のまま
        assertEquals(listOf("ドライバ異常"), DebugSpec.errorNamesFor(DebugSpec.Mech.WHEEL, 0x0010))
    }
}
