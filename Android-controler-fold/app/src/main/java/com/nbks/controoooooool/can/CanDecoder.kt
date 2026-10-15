package com.nbks.controoooooool.can

import com.nbks.controoooooool.model.FrameStatus
import com.nbks.controoooooool.model.Schema
import com.nbks.controoooooool.model.SignalInfo
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.model.UnknownFrame

/**
 * ESP32 から転送されてきた CAN フレームを検査し、項目 (信号) に読み替える。
 *
 *   - 形式 (標準ID / FD / BRS) ・長さ・バージョンを確認。条件に合わないフレームは値も受信時刻も更新せず、
 *     不正として数える (途絶判定を不正データで延命しない)
 *   - SEQ の飛びを欠落として数える (手掛かりであり配送保証ではない)。SEQ・稼働時間とも同じなら重複として捨てる。
 *     稼働時間が巻き戻ったら送信元の再起動とみなし、欠落に数えない
 *   - 受信時刻は常にアプリ自身の時計。基板間の HAL tick は比較しない
 *
 * 呼び出しは複数スレッドから来る (受信スレッド / 表示用の定期処理) ので全体を synchronized にしている。
 */
class CanDecoder {
    private enum class Reject { NONE, FORMAT, LENGTH, VERSION }

    private class IdState(val rule: FrameRule) {
        var received = false
        var len = 0
        val data = ByteArray(64)
        var lastMs = 0L
        var count = 0L
        var countAtRate = 0L
        var hz = 0.0
        var haveSeq = false
        var seq = 0
        var seqLost = 0L
        var duplicates = 0L
        var haveUptime = false
        var uptime = 0L
        var rejected = 0L
        var reject = Reject.NONE
        var rejectValue = 0
    }

    private class Unknown(val id: Int, val ext: Boolean) {
        var fd = false
        var brs = false
        var len = 0
        var count = 0L
        var lastMs = 0L
    }

    private val states = LinkedHashMap<Int, IdState>().apply {
        CanDefs.rules.forEach { put(it.canId, IdState(it)) }
    }
    private val signalsById = CanDefs.signals.groupBy { it.canId }
    private val unknowns = LinkedHashMap<Long, Unknown>()
    private var lastRateMs = 0L

    val schema: Schema by lazy {
        Schema(
            CanDefs.signals.map {
                SignalInfo(
                    name = it.name,
                    canId = it.canId.toLong(),
                    type = it.type.name.lowercase(),
                    isBool = it.isBool,
                    min = it.min,
                    max = it.max,
                    unit = it.unit,
                    group = it.group,
                )
            },
            CanDefs.TIMEOUT_MS,
        )
    }

    /**
     * @param flags bit0=拡張ID bit1=FD bit2=BRS (ESP32 が付けたもの)
     * @param nowMs アプリの時計 (SystemClock.elapsedRealtime)
     */
    @Synchronized
    fun onFrame(id: Int, flags: Int, data: ByteArray, nowMs: Long) {
        val ext = flags and 1 != 0
        val fd = flags and 2 != 0
        val brs = flags and 4 != 0
        val len = data.size.coerceAtMost(64)

        val st = states[id]
        if (st == null) {
            val key = (id.toLong() and 0xFFFFFFFFL) or (if (ext) 1L shl 32 else 0L)
            val u = unknowns[key] ?: if (unknowns.size < MAX_UNKNOWN) Unknown(id, ext).also { unknowns[key] = it } else return
            u.fd = fd; u.brs = brs; u.len = len; u.count++; u.lastMs = nowMs
            return
        }

        val r = st.rule
        var rej = Reject.NONE
        var rejVal = 0
        if (ext || (r.requireFd && !fd) || (r.requireNoBrs && brs)) {
            rej = Reject.FORMAT
        } else if (if (r.exactLen) len != r.len else len < r.len) {
            rej = Reject.LENGTH
            rejVal = len
        } else if (r.versionByte >= 0 && b(data, r.versionByte) != r.version) {
            rej = Reject.VERSION
            rejVal = b(data, r.versionByte)
        }
        if (rej != Reject.NONE) {
            st.rejected++
            st.reject = rej
            st.rejectValue = rejVal
            return
        }
        st.reject = Reject.NONE

        if (r.seqByte >= 0) {
            val seq = if (r.seqWide) le(data, r.seqByte, 2).toInt() else b(data, r.seqByte)
            val hasUp = r.uptimeByte >= 0
            val up = if (hasUp) le(data, r.uptimeByte, 4) else 0L
            if (st.haveSeq) {
                val mask = if (r.seqWide) 0xFFFF else 0xFF
                val diff = (seq - st.seq) and mask
                if (hasUp && st.haveUptime && diff == 0 && up == st.uptime) {
                    st.duplicates++  // 完全な重複: 新しい更新の証拠にならない
                    return
                }
                val restarted = hasUp && st.haveUptime && up < st.uptime
                if (diff > 1 && !restarted) st.seqLost += (diff - 1).toLong()
            }
            st.seq = seq
            st.haveSeq = true
            st.uptime = up
            st.haveUptime = hasUp
        }

        st.received = true
        st.len = len
        System.arraycopy(data, 0, st.data, 0, len)
        st.lastMs = nowMs
        st.count++
    }

    /** 現在の状態を表示用にまとめる。受信がなくても経過時間は進むので、途絶が正しく出る */
    @Synchronized
    fun snapshot(nowMs: Long): Telemetry {
        if (nowMs - lastRateMs >= 1000) {
            val dt = if (lastRateMs == 0L) 1000L else nowMs - lastRateMs
            states.values.forEach {
                it.hz = (it.count - it.countAtRate) * 1000.0 / dt
                it.countAtRate = it.count
            }
            lastRateMs = nowMs
        }

        val values = HashMap<String, Double>(256)
        for ((id, st) in states) {
            if (!st.received) continue
            for (s in signalsById[id].orEmpty()) {
                if (s.offset + s.size > st.len) continue
                val v = raw(s, st.data) ?: continue
                values[s.name] = if (s.isBool) v else v * s.scale + s.bias
            }
        }

        val frames = states.values.map { st ->
            FrameStatus(
                canId = st.rule.canId.toLong(),
                ageMs = if (st.received) nowMs - st.lastMs else -1,
                hz = st.hz,
                seq = if (st.haveSeq) st.seq else null,
                seqLost = st.seqLost,
                duplicates = st.duplicates,
                rejected = st.rejected,
                reject = when (st.reject) {
                    Reject.NONE -> null
                    Reject.FORMAT -> "fmt"
                    Reject.LENGTH -> "len:${st.rejectValue}"
                    Reject.VERSION -> "ver:${st.rejectValue}"
                },
            )
        }

        val unknown = unknowns.values.map {
            UnknownFrame(
                canId = it.id.toLong() and 0xFFFFFFFFL,
                extended = it.ext, fd = it.fd, brs = it.brs, len = it.len,
                count = it.count, ageMs = nowMs - it.lastMs,
            )
        }

        return Telemetry(schema = schema, values = values, frames = frames, unknown = unknown, receivedAt = nowMs)
    }

    private fun b(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

    private fun le(d: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((d[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** 生値。符号付きは 2 の補数で解釈する */
    private fun raw(s: SignalDef, d: ByteArray): Double? {
        val o = s.offset
        return when (s.type) {
            SigType.U8 -> b(d, o).toDouble()
            SigType.I8 -> d[o].toInt().toDouble()
            SigType.U16 -> le(d, o, 2).toDouble()
            SigType.I16 -> le(d, o, 2).toShort().toDouble()
            SigType.U32 -> le(d, o, 4).toDouble()
            SigType.I32 -> le(d, o, 4).toInt().toDouble()
            SigType.F32 -> java.lang.Float.intBitsToFloat(le(d, o, 4).toInt()).toDouble().takeIf { it.isFinite() }
            SigType.BOOL -> if (b(d, o) != 0) 1.0 else 0.0
            SigType.BIT -> ((b(d, o) shr (s.bit and 7)) and 1).toDouble()
        }
    }

    private companion object {
        const val MAX_UNKNOWN = 32
    }
}
