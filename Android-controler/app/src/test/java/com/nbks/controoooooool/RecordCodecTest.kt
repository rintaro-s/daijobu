package com.nbks.controoooooool

import com.nbks.controoooooool.record.RecordCodec
import com.nbks.controoooooool.record.RecordMode
import com.nbks.controoooooool.record.ShotRecord
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordCodecTest {
    private val utc = ZoneId.of("UTC")

    private val sample = listOf(
        ShotRecord(1_700_000_000_000, RecordMode.BUCKET, 45, 650, 2, false),
        ShotRecord(1_700_000_005_000, RecordMode.TAG, null, null, null, true),  // 値が取れなかった記録
    )

    @Test
    fun encode_decode_roundtrip() {
        assertEquals(sample, RecordCodec.decode(RecordCodec.encode(sample)))
    }

    @Test
    fun decode_ignores_broken_lines_and_unknown_mode() {
        val text = "garbage\n1700000000000,UNKNOWN,10,500,3,0\n1,2,3"
        val r = RecordCodec.decode(text)
        assertEquals(1, r.size)
        assertEquals(RecordMode.BUCKET, r[0].mode)   // 不明なタグは既定 (バケツ) に戻す
        assertEquals(10, r[0].angleDeg)
    }

    @Test
    fun empty_input() {
        assertEquals(emptyList<ShotRecord>(), RecordCodec.decode(null))
        assertEquals(emptyList<ShotRecord>(), RecordCodec.decode(""))
    }

    @Test
    fun csv_has_header_blank_cells_and_stale_mark() {
        val lines = RecordCodec.csv(sample, utc).lines()
        assertEquals("no,time,mode,servo_angle_deg,speed_pwm_permille,preset,stale", lines[0])
        assertEquals("1,2023-11-14 22:13:20,バケツ,45,650,2,", lines[1])
        assertEquals("2,2023-11-14 22:13:25,タグ,,,,stale", lines[2])
    }
}
