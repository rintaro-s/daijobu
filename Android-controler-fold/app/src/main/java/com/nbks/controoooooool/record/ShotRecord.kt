package com.nbks.controoooooool.record

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 記録に付けるタグ (設定から切り替える) */
enum class RecordMode(val label: String, val short: String) {
    BUCKET("バケツモード", "バケツ"),
    TAG("タグモード", "タグ");

    companion object {
        fun parse(name: String?): RecordMode = entries.firstOrNull { it.name == name } ?: BUCKET
    }
}

/**
 * 「入った」ボタンを押した瞬間の記録。
 *
 * @param angleDeg サーボ角度 (投擲角度の設定、度)。0x310 を優先、無ければ 0x150
 * @param pwmPermille 速度 = 発射 PWM の設定 (permille、生の値)。RPM は未計測なので PWM を使う
 * @param stale 押した時点で、その値が「前回値」(通信途絶・未受信) だった
 */
data class ShotRecord(
    val timeMs: Long,
    val mode: RecordMode,
    val angleDeg: Int?,
    val pwmPermille: Int?,
    val preset: Int?,
    val stale: Boolean,
)

/** 保存形式 (1 行 1 件) と CSV 出力。Android に依存しないのでユニットテストできる */
object RecordCodec {
    fun encode(list: List<ShotRecord>): String = list.joinToString("\n") {
        listOf(
            it.timeMs, it.mode.name, it.angleDeg ?: "", it.pwmPermille ?: "", it.preset ?: "",
            if (it.stale) 1 else 0,
        ).joinToString(",")
    }

    fun decode(text: String?): List<ShotRecord> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 6) return@mapNotNull null
            val t = p[0].toLongOrNull() ?: return@mapNotNull null
            ShotRecord(
                timeMs = t,
                mode = RecordMode.parse(p[1]),
                angleDeg = p[2].toIntOrNull(),
                pwmPermille = p[3].toIntOrNull(),
                preset = p[4].toIntOrNull(),
                stale = p[5] == "1",
            )
        }.toList()
    }

    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun timeText(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** 表計算ソフトに貼れる CSV。値が無いものは空欄 */
    fun csv(list: List<ShotRecord>, zone: ZoneId = ZoneId.systemDefault()): String {
        val head = "no,time,mode,servo_angle_deg,speed_pwm_permille,preset,stale"
        val rows = list.mapIndexed { i, r ->
            listOf(
                i + 1, timeText(r.timeMs, zone), r.mode.short, r.angleDeg ?: "", r.pwmPermille ?: "",
                r.preset ?: "", if (r.stale) "stale" else "",
            ).joinToString(",")
        }
        return (listOf(head) + rows).joinToString("\n")
    }
}
