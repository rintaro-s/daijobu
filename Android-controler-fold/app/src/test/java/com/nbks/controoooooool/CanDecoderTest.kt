package com.nbks.controoooooool

import com.nbks.controoooooool.can.CanDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 引き継ぎ資料 (CANFD_Handover_20261012) の生データ例と、受信条件の検査 */
class CanDecoderTest {
    private val FD = 2          // flags: FD, BRS なし, 標準ID
    private val FD_BRS = 2 or 4
    private val FD_EXT = 2 or 1

    private fun hex(s: String): ByteArray =
        s.trim().split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private fun CanDecoder.feed(id: Int, bytes: String, now: Long = 1000, flags: Int = FD) =
        onFrame(id, flags, hex(bytes), now)

    @Test
    fun handover_examples() {
        val d = CanDecoder()
        d.feed(0x150, "02 01 05 03 F4 01 28 00")
        d.feed(0x151, "01 03 06 03 00 00 00 00")
        d.feed(0x130, "01 06 08 00 00 00 00 00")
        d.feed(0x201, "02 13 09 01 FD FF 20 00")          // byte4/5 = FD FF -> -3 (65533 ではない)
        d.feed(0x200, "01 03 30 28 2A 00 05 00 00 01 C0 FA 20 00 00 00 00 00 F0 FF E8 03 00 00")
        d.feed(0x321, "02 01 01 20 E8 03 00 00 50 00 F4 01 00 00 00 80 0A 00 00 00 00 00 00 00")
        val t = d.snapshot(1050)

        assertEquals(500.0, t.value("SCMD.pwm")!!, 0.0)       // 0x01F4 = 500 (50%)
        assertEquals(40.0, t.value("SCMD.angle")!!, 0.0)
        assertEquals(3.0, t.value("SCMD.preset")!!, 0.0)
        assertEquals(3.0, t.value("LREV.flags")!!, 0.0)
        assertEquals(6.0, t.value("LCMD.flags")!!, 0.0)       // 0x06 を「6 番目の状態」と誤読しない
        assertEquals(-3.0, t.value("HDG.corr")!!, 0.0)
        assertEquals(16.0, t.value("GYRO.yaw")!!, 1e-9)       // raw 256 / 16
        assertEquals(-84.0, t.value("GYRO.roll")!!, 1e-9)     // 負数
        assertEquals(-1.0, t.value("GYRO.gz")!!, 1e-9)
        assertEquals(1000.0, t.value("GYRO.uptime")!!, 0.0)
        assertEquals(-2147483648.0, t.value("WFL.rpm10")!!, 0.0) // RPM 未計測の番兵値
    }

    @Test
    fun lift_diag_example_from_spec() {
        val d = CanDecoder()
        d.feed(0x301, "02 07 01 EF E8 03 00 00 FF FF 77 01 39 30 00 00 70 30 00 00 00 00 00 00")
        val t = d.snapshot(1010)
        assertEquals(7.0, t.value("LIFT1.seq")!!, 0.0)
        assertEquals(65535.0, t.value("LIFT1.rate")!!, 0.0)   // 無効値
        assertEquals(375.0, t.value("LIFT1.pwm")!!, 0.0)
        assertEquals(12345.0, t.value("LIFT1.pos")!!, 0.0)
        assertEquals(12400.0, t.value("LIFT1.ref")!!, 0.0)
        assertEquals(10L, t.frame(0x301)!!.ageMs)
    }

    @Test
    fun rejects_wrong_version_length_and_format() {
        val d = CanDecoder()
        d.feed(0x210, "01 01 0A 03 00 00 00 00")              // V1 (現仕様は V3)
        d.feed(0x301, "02 07 01 EF E8 03 00 00", flags = FD)  // 長さ不正 (8B)
        d.feed(0x302, "02 07 01 EF E8 03 00 00 FF FF 77 01 39 30 00 00 70 30 00 00 00 00 00 00", flags = FD_BRS)
        d.feed(0x303, "02 07 01 EF E8 03 00 00 FF FF 77 01 39 30 00 00 70 30 00 00 00 00 00 00", flags = FD_EXT)
        val t = d.snapshot(1100)
        assertEquals("ver:1", t.frame(0x210)!!.reject)
        assertEquals("len:8", t.frame(0x301)!!.reject)
        assertEquals("fmt", t.frame(0x302)!!.reject)
        assertEquals("fmt", t.frame(0x303)!!.reject)
        // 不正フレームでは値も受信時刻も更新しない
        assertEquals(-1L, t.frame(0x301)!!.ageMs)
        assertNull(t.value("LIFT1.seq"))
    }

    @Test
    fun seq_gap_duplicate_wrap_and_restart() {
        val d = CanDecoder()
        fun lift(seq: Int, up: Int) = "02 %02X 01 EF %02X %02X %02X %02X %s".format(
            seq, up and 0xFF, (up shr 8) and 0xFF, (up shr 16) and 0xFF, (up shr 24) and 0xFF,
            List(16) { "00" }.joinToString(" "),
        )
        d.feed(0x301, lift(10, 1000), now = 1000)
        d.feed(0x301, lift(10, 1000), now = 1010)             // 完全な重複
        d.feed(0x301, lift(13, 1300), now = 1100)             // 11, 12 が欠落
        d.feed(0x301, lift(255, 5000), now = 1200)            // 大きく飛ぶ (欠落に数える)
        d.feed(0x301, lift(0, 5100), now = 1300)              // 255 -> 0 の折り返しは欠落ではない
        d.feed(0x301, lift(7, 50), now = 1400)                // 稼働時間が巻き戻り = 再起動 (欠落に数えない)
        val f = d.snapshot(1450).frame(0x301)!!
        assertEquals(1L, f.duplicates)
        assertEquals(2L + 241L, f.seqLost)                    // 13->255: 241 / 3->..: 欠落 2 + 241
        assertEquals(7, f.seq)
        assertEquals(1450L - 1400L, f.ageMs)
    }

    @Test
    fun gyro_seq_is_16bit_and_wraps() {
        val d = CanDecoder()
        fun gyro(seq: Int, up: Int) = "01 03 30 28 %02X %02X 05 00 %s %02X 00 00 00".format(
            seq and 0xFF, (seq shr 8) and 0xFF, List(12) { "00" }.joinToString(" "), up and 0xFF,
        )
        d.feed(0x200, gyro(65534, 1))
        d.feed(0x200, gyro(65535, 2), now = 1020)
        d.feed(0x200, gyro(0, 3), now = 1040)                 // 65535 -> 0 は連続
        val f = d.snapshot(1050).frame(0x200)!!
        assertEquals(0L, f.seqLost)
        assertEquals(0, f.seq)
    }

    @Test
    fun unknown_ids_are_listed_and_age_advances() {
        val d = CanDecoder()
        d.feed(0x7FF, "01 02 03", now = 1000)
        d.onFrame(0x18FF0001, FD_EXT, hex("00 01"), 1000)
        d.feed(0x123, "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", now = 1000)
        val t = d.snapshot(1500)
        assertEquals(2, t.unknown.size)
        assertEquals(true, t.unknown.any { it.canId == 0x18FF0001L && it.extended })
        // 受信がなくても経過時間は進む (途絶が表示に出る)
        val age = t.frame(0x123)!!.ageMs
        assertEquals(500L, age)
        assertNotNull(t.frameOf("LX"))
    }
}
