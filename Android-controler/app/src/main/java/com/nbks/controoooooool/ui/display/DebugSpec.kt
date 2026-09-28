package com.nbks.controoooooool.ui.display

import com.nbks.controoooooool.model.FrameStatus
import com.nbks.controoooooool.model.Telemetry

/**
 * デバッグ用 CAN FD 通信仕様 V1 (2026-09-27) の表示用解釈。
 *
 * ESP32 はフレームの形式・長さ・バージョンを確認し、各項目を生値のまま
 * 「接頭辞.項目」(例: LIFT1.rate) で送ってくる。無効値・有効フラグ・状態番号・異常ビットの
 * 意味付けはここで行う。接頭辞は ESP32-controle/include/can_signals.h と一致させること。
 */
object DebugSpec {
    enum class Mech { LIFT, SHOT, WHEEL }

    data class Unit(val canId: Long, val prefix: String, val label: String, val mech: Mech)

    val units = listOf(
        Unit(0x301, "LIFT1", "昇降1", Mech.LIFT),
        Unit(0x302, "LIFT2", "昇降2", Mech.LIFT),
        Unit(0x303, "LIFT3", "昇降3", Mech.LIFT),
        Unit(0x310, "SHOT", "発射", Mech.SHOT),
        Unit(0x321, "WFL", "左前", Mech.WHEEL),
        Unit(0x322, "WFR", "右前", Mech.WHEEL),
        Unit(0x323, "WRL", "左後", Mech.WHEEL),
        Unit(0x324, "WRR", "右後", Mech.WHEEL),
    )

    // ---- 無効値 ----
    const val U16_INVALID = 0xFFFF
    const val I16_INVALID = -32768             // PWM 取得不可
    const val I32_INVALID = -2147483648L       // INT32_MIN
    const val U32_INVALID = 0xFFFFFFFFL

    // ---- byte3 有効性フラグ ----
    const val F_MEASURED = 1 shl 0     // 実測値 (昇降: エンコーダー位置 / 発射・足回り: 実測RPM)
    const val F_HOMED = 1 shl 1        // 昇降の原点確定済み
    const val F_SENSOR = 1 shl 2       // byte20-21 のリミット/センサー情報が有効
    const val F_TARGET = 1 shl 3       // 目標値 (昇降: 目標位置 / 発射: 目標RPM)
    const val F_SHOTS = 1 shl 4        // 発射回数
    const val F_CMD_FRESH = 1 shl 5    // 必要な操作指令を新鮮な状態で受信できている

    private val liftStates = listOf("停止", "UP", "DOWN", "原点復帰中", "異常停止")
    private val shotStates = listOf("停止", "準備中", "発射可能", "発射動作中", "復帰/補充中", "異常停止")
    private val wheelStates = listOf("出力停止", "正方向", "逆方向", "反転待ち", "異常停止")
    private val stopReasons = listOf("通常/中立", "操作CAN受信無効", "反転待ち", "制御ループ遅延")

    private val errorBits = listOf(
        "指令タイムアウト", "エンコーダーストール", "CAN受信FIFOあふれ", "制御周期超過",
        "ドライバ異常", "非常停止", "センサー異常", "その他の異常",
    )
    private val liftLimitBits = listOf("下限SW", "上限", "UP禁止", "DOWN禁止", "全動作禁止")

    fun stateName(mech: Mech, state: Int): String {
        val list = when (mech) {
            Mech.LIFT -> liftStates
            Mech.SHOT -> shotStates
            Mech.WHEEL -> wheelStates
        }
        return list.getOrNull(state) ?: "未対応($state)"
    }

    fun stopReason(v: Int): String = stopReasons.getOrNull(v) ?: "未対応($v)"

    /** 異常ビットの名前一覧。予約ビット (8-15) が立っていればそれも示す */
    fun errorNames(bits: Int): List<String> {
        val names = errorBits.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toMutableList()
        if (bits and 0xFF00 != 0) names += "予約bit(0x%04X)".format(bits and 0xFF00)
        return names
    }

    fun liftLimitNames(bits: Int): List<String> {
        val names = liftLimitBits.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toMutableList()
        if (bits and 0xFFE0 != 0) names += "予約bit(0x%04X)".format(bits and 0xFFE0)
        return names
    }

    fun pwmText(raw: Int?): String = when {
        raw == null -> "--"
        raw == I16_INVALID -> "取得不可"
        else -> "%+.1f%%".format(raw / 10.0)
    }

    /** 実測RPM×10。有効フラグと無効値の両方が有効なときだけ値を出す (0rpm と未計測を区別) */
    fun rpmText(flags: Int, rpm10: Long?): String =
        if (rpm10 == null || flags and F_MEASURED == 0 || rpm10 == I32_INVALID) "未計測"
        else "%.1f rpm".format(rpm10 / 10.0)

    fun uptimeText(ms: Long?): String {
        if (ms == null) return "--"
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    fun rejectText(rej: String): String = when {
        rej == "fmt" -> "形式不正 (拡張ID / 非FD / BRS ON)"
        rej.startsWith("len:") -> "長さ不正 (${rej.removePrefix("len:")} byte)"
        rej.startsWith("ver:") -> "未対応バージョン (${rej.removePrefix("ver:")})"
        else -> rej
    }

    // ---- 受信状態 (仕様 8 節) ----
    sealed interface Link {
        data object NotReceived : Link
        data object Ok : Link
        data class Lost(val ageMs: Long) : Link
        data class Unsupported(val reason: String) : Link
    }

    fun link(t: Telemetry, u: Unit): Link {
        val f: FrameStatus = t.frame(u.canId) ?: return Link.NotReceived
        return when {
            f.reject != null -> Link.Unsupported(rejectText(f.reject))
            f.ageMs < 0 -> Link.NotReceived
            f.ageMs > t.schema.timeoutMs -> Link.Lost(f.ageMs)
            else -> Link.Ok
        }
    }

    /** 値を読み出すための小道具 */
    class Reader(private val t: Telemetry, private val prefix: String) {
        fun int(key: String): Int? = t.value("$prefix.$key")?.toInt()
        fun long(key: String): Long? = t.value("$prefix.$key")?.toLong()
        val flags: Int get() = int("flags") ?: 0
        fun has(flag: Int) = flags and flag != 0
    }
}
