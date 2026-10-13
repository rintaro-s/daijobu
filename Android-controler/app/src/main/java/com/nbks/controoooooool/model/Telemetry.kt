package com.nbks.controoooooool.model

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
    /** SEQ・稼働時間とも前回と同じで捨てたフレーム数 */
    val duplicates: Long = 0,
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
    /** ESP32 の稼働時間 ms。巻き戻ったら ESP32 が再起動したと判断する */
    val espUptimeMs: Long = 0,
    /** 接続中に検出した ESP32 の再起動回数 (アプリ起動からの累計) */
    val espReboots: Int = 0,
    /** ESP32 の起動理由 (esp_reset_reason)。-1 = 不明 */
    val espResetReason: Int = -1,
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
