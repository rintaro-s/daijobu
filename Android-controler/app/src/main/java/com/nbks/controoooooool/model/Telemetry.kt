package com.nbks.controoooooool.model

import org.json.JSONObject

/** ESP32 が接続直後に送ってくる信号定義 (ESP32-controle/include/can_signals.h 由来) */
data class SignalInfo(
    val name: String,
    val canId: Long,
    val type: String,
    val isBool: Boolean,
    val min: Double,
    val max: Double,
    val unit: String,
    val group: String,
)

data class Schema(
    val signals: List<SignalInfo> = emptyList(),
    /** この時間 (ms) 以上フレームが来ていなければ途絶扱い */
    val timeoutMs: Long = 300,
) {
    fun find(name: String): SignalInfo? = signals.firstOrNull { it.name == name }
}

data class FrameStatus(
    val canId: Long,
    /** 最後に「有効な」フレームを受信してからの経過 ms。-1 は未受信 */
    val ageMs: Long,
    val hz: Double,
    /** 最後の有効フレームのシーケンス番号 (無い ID は null) */
    val seq: Int? = null,
    /** シーケンスの飛びから数えた欠落数の累計 (手掛かりであり保証ではない) */
    val seqLost: Long = 0,
    /** 条件を満たさず捨てたフレーム数 */
    val rejected: Long = 0,
    /** 直近フレームが不正だった理由。"fmt" / "len:N" / "ver:N"。正常なら null */
    val reject: String? = null,
)

/** 定義 (can_signals.h) に無いが受信した ID。何がバスに流れているかの診断用 */
data class UnknownFrame(
    val canId: Long,
    val extended: Boolean,
    val fd: Boolean,
    val brs: Boolean,
    val len: Int,
    val count: Long,
    val ageMs: Long,
)

/** 表示に使う現在の状態 */
data class Telemetry(
    val schema: Schema = Schema(),
    val values: Map<String, Double> = emptyMap(),
    val frames: List<FrameStatus> = emptyList(),
    val unknown: List<UnknownFrame> = emptyList(),
    val uartOk: Long = 0,
    val uartCrcErrors: Long = 0,
    /** アプリがこのデータを受け取った時刻 (SystemClock.elapsedRealtime) */
    val receivedAt: Long = 0,
) {
    fun value(name: String): Double? = values[name]
    fun pressed(name: String): Boolean = (values[name] ?: 0.0) != 0.0

    /** その信号を運ぶ CAN フレームが途絶していないか */
    fun isFresh(name: String): Boolean {
        val id = schema.find(name)?.canId ?: return false
        val f = frames.firstOrNull { it.canId == id } ?: return false
        return f.ageMs in 0..schema.timeoutMs
    }

    fun frame(canId: Long): FrameStatus? = frames.firstOrNull { it.canId == canId }

    /** その信号を運ぶ CAN フレームの受信状態 */
    fun frameOf(name: String): FrameStatus? = schema.find(name)?.let { frame(it.canId) }

    val anyFresh: Boolean get() = frames.any { it.ageMs in 0..schema.timeoutMs }
}

object TelemetryParser {
    fun parseSchema(o: JSONObject): Schema {
        val arr = o.getJSONArray("signals")
        val list = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SignalInfo(
                name = s.getString("name"),
                canId = s.getLong("id"),
                type = s.optString("type"),
                isBool = s.optBoolean("bool"),
                min = s.optDouble("min", 0.0),
                max = s.optDouble("max", 1.0),
                unit = s.optString("unit"),
                group = s.optString("group"),
            )
        }
        return Schema(list, o.optLong("timeout", 300))
    }

    fun parseData(o: JSONObject, schema: Schema, now: Long): Telemetry {
        val v = o.getJSONObject("v")
        val values = HashMap<String, Double>(v.length())
        for (key in v.keys()) {
            val d = v.optDouble(key)
            if (!d.isNaN()) values[key] = d
        }

        val fa = o.getJSONArray("frames")
        val frames = (0 until fa.length()).map { i ->
            val f = fa.getJSONObject(i)
            FrameStatus(
                canId = f.getLong("id"),
                ageMs = f.getLong("age"),
                hz = f.optDouble("hz", 0.0),
                seq = if (f.isNull("seq")) null else f.optInt("seq"),
                seqLost = f.optLong("lost"),
                rejected = f.optLong("bad"),
                reject = if (f.isNull("rej")) null else f.optString("rej"),
            )
        }
        val ua = o.optJSONArray("unknown")
        val unknown = if (ua == null) emptyList() else (0 until ua.length()).map { i ->
            val u = ua.getJSONObject(i)
            UnknownFrame(
                canId = u.getLong("id"),
                extended = u.optBoolean("ext"),
                fd = u.optBoolean("fd"),
                brs = u.optBoolean("brs"),
                len = u.optInt("len"),
                count = u.optLong("count"),
                ageMs = u.optLong("age"),
            )
        }
        val link = o.optJSONObject("link")
        return Telemetry(
            schema = schema,
            values = values,
            frames = frames,
            unknown = unknown,
            uartOk = link?.optLong("ok") ?: 0,
            uartCrcErrors = link?.optLong("crc") ?: 0,
            receivedAt = now,
        )
    }
}
