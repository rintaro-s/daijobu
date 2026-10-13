package com.nbks.controoooooool.ui.display

import com.nbks.controoooooool.model.FrameStatus
import com.nbks.controoooooool.model.Telemetry

/**
 * ロボット CAN FD 引き継ぎ仕様 (CANFD_Handover_20261012) の表示用解釈。
 *
 * ESP32 はフレームの形式・長さ・バージョンを確認し、各項目を生値のまま
 * 「接頭辞.項目」(例: LIFT1.rate) で送ってくる。無効値・有効フラグ・状態番号・異常ビットの
 * 意味付けはここで行う。接頭辞は ESP32-controle/include/can_signals.h と一致させること。
 */
object DebugSpec {
    enum class Mech {
        LIFT, SHOT, WHEEL, GYRO,
        // 制御・安全用 ID (表示のみ)
        LIFT_CMD, LIFT_ST, SHOT_CMD, LOAD_SENS, LOAD_REV, HEADING;

        val isControl: Boolean get() = ordinal >= LIFT_CMD.ordinal
    }

    data class Unit(val canId: Long, val prefix: String, val label: String, val mech: Mech)

    val units = listOf(
        Unit(0x301, "LIFT1", "昇降1", Mech.LIFT),
        Unit(0x302, "LIFT2", "昇降2", Mech.LIFT),
        Unit(0x303, "LIFT3", "昇降3", Mech.LIFT),
        Unit(0x310, "SHOT", "発射・装填指令", Mech.SHOT),
        Unit(0x321, "WFL", "左前", Mech.WHEEL),
        Unit(0x322, "WFR", "右前", Mech.WHEEL),
        Unit(0x323, "WRL", "左後", Mech.WHEEL),
        Unit(0x324, "WRR", "右後", Mech.WHEEL),
        Unit(0x200, "GYRO", "ジャイロ (BNO055)", Mech.GYRO),
        Unit(0x130, "LCMD", "昇降システム指令", Mech.LIFT_CMD),
        Unit(0x141, "LST1", "昇降1 リミット状態", Mech.LIFT_ST),
        Unit(0x142, "LST2", "昇降2 リミット状態", Mech.LIFT_ST),
        Unit(0x143, "LST3", "昇降3 リミット状態", Mech.LIFT_ST),
        Unit(0x150, "SCMD", "発射・正転装填・サーボ指令", Mech.SHOT_CMD),
        Unit(0x151, "LREV", "装填 手動逆転", Mech.LOAD_REV),
        Unit(0x201, "HDG", "足回り 方位補正", Mech.HEADING),
        Unit(0x210, "LSEN", "S1・S2 センサー", Mech.LOAD_SENS),
    )

    // 0x310 byte12 / 0x150 byte1 の指令フラグ
    private val cmd310Bits = listOf("発射RUN", "装填1正転", "装填2正転", "クランプ", "HALT", "調整")
    private val cmd150Bits = listOf("入力fresh", "発射RUN", "調整モード", "装填1正転", "装填2正転", "クランプ", "HALT")
    private val lift130Bits = listOf("ZERO", "UP禁止", "DOWN禁止", "全動作禁止", "上限保持許可")
    private val heading201Bits = listOf("基準有効", "補正適用許可", "手動旋回", "並進", "IMU fresh", "GYR校正OK", "軸学習済", "負極性")

    fun bitNames(names: List<String>, bits: Int, width: Int): List<String> {
        val out = names.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toMutableList()
        val reserved = bits and ((1 shl width) - 1) and ((1 shl names.size) - 1).inv()
        if (reserved != 0) out += "予約bit(0x%X)".format(reserved)
        return out
    }

    fun cmd310Names(bits: Int) = bitNames(cmd310Bits, bits, 8)
    fun cmd150Names(bits: Int) = bitNames(cmd150Bits, bits, 8)
    fun lift130Names(bits: Int) = bitNames(lift130Bits, bits, 8)
    fun heading201Names(bits: Int) = bitNames(heading201Bits, bits, 8)

    fun adjustName(v: Int?) = when (v) { null -> "--"; 0 -> "なし"; 1 -> "PWM"; 2 -> "角度"; else -> "未対応($v)" }
    fun presetName(v: Int?) = when (v) { null -> "--"; 0 -> "上"; 1 -> "下"; 2 -> "左"; 3 -> "右"; 255 -> "初期/手動"; else -> "$v" }
    /** 0x310 byte2 の指令状態 */
    fun shotStateName(v: Int?) = when (v) {
        null -> "--"; 0 -> "待機"; 1 -> "B装填"; 2 -> "Y準備・センサー待ち"; 3 -> "RUN"; 4 -> "HALT"; else -> "未対応($v)"
    }
    /** 0x310 byte16 の内部 phase */
    fun shotPhaseName(v: Int?) = when (v) {
        null -> "--"; 0 -> "通常"; 1 -> "B装填"; 2 -> "Y・S1検出済"; 3 -> "Y・S1検出待ち"; else -> "未対応($v)"
    }
    /** 0x141-143 byte4 の fault */
    fun liftFaultName(v: Int?) = when (v) {
        null -> "--"; 0 -> "なし"; 1 -> "stall"; 2 -> "保持誤差"; 3 -> "制御遅れ"; else -> "未対応($v)"
    }
    /** 0x201 byte3 (1=底・中立coast / 2=上昇または不明・PWM低減。3 以上は不正) */
    fun headingModeName(v: Int?) = when (v) {
        null -> "--"; 1 -> "底・中立coast"; 2 -> "上昇/不明・PWM低減"; else -> "不正($v)"
    }
    fun sensorName(v: Int?) = when (v) { null -> "--"; 0 -> "非検出"; 1 -> "検出"; 255 -> "無効"; else -> "未対応($v)" }
    fun ageText(v: Int?) = when (v) { null -> "--"; 65535 -> "未受信"; else -> "$v ms" }

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
    // 昇降診断 (0x301-0x303) byte3: b0 encoder有効 / b1 HOME / b2 下限SW機能 / b3 固定1 / b5 指令fresh / b6 上限保持ACTIVE
    const val F_LOWER_SW = 1 shl 2     // 昇降: 下限スイッチ機能
    const val F_HOLD_ACTIVE = 1 shl 6  // 昇降: 上限保持 ACTIVE

    // 昇降診断 byte2: 0停止 / 1正PWM / 2負PWM / 4故障・無効 (3 は未使用)
    private val liftStates = listOf("停止", "正PWM(UP)", "負PWM(DOWN)", "未使用(3)", "故障・無効")
    // 足回り診断 byte2: 0停止 / 1正PWM / 2負PWM / 3反転待ち / 4無効・制御遅れ
    private val wheelStates = listOf("停止", "正PWM", "負PWM", "反転待ち", "無効・制御遅れ")
    private val stopReasons = listOf("通常/中立", "通信古い", "反転待ち", "制御遅れ")

    private val errorBits = listOf(
        "指令タイムアウト", "エンコーダーストール", "CAN受信FIFOあふれ", "制御周期超過",
        "ドライバ異常", "非常停止", "センサー異常", "その他の異常",
    )
    private val liftLimitBits = listOf("下限SW", "上限", "UP禁止", "DOWN禁止", "全動作禁止")

    fun stateName(mech: Mech, state: Int?): String {
        if (state == null) return "--"
        if (mech == Mech.SHOT) return shotStateName(state)
        val list = when (mech) {
            Mech.LIFT -> liftStates
            Mech.WHEEL -> wheelStates
            else -> emptyList()
        }
        return list.getOrNull(state) ?: "未対応($state)"
    }

    fun stopReason(v: Int?): String = if (v == null) "--" else stopReasons.getOrNull(v) ?: "未対応($v)"

    // 診断 byte22-23 の異常ビットは ID 系統ごとに意味が違う (定義の無いビットは予約として表示)
    private val liftErrorBits = mapOf(
        0 to "通信古い", 1 to "stall", 2 to "FIFO欠落", 3 to "制御遅れ", 4 to "保持誤差", 7 to "RX fault",
    )
    private val wheelErrorBits = mapOf(0 to "通信古い", 2 to "FIFO欠落", 3 to "制御遅れ")
    private val shotErrorBits = mapOf(0 to "入力古い", 6 to "センサー古い")

    private fun namedBits(defs: Map<Int, String>, bits: Int): List<String> {
        val names = defs.entries.sortedBy { it.key }.filter { bits and (1 shl it.key) != 0 }.map { it.value }.toMutableList()
        val unknown = bits and 0xFFFF and defs.keys.fold(0) { acc, b -> acc or (1 shl b) }.inv()
        if (unknown != 0) names += "予約bit(0x%04X)".format(unknown)
        return names
    }

    fun errorNamesFor(mech: Mech, bits: Int): List<String> = when (mech) {
        Mech.LIFT -> namedBits(liftErrorBits, bits)
        Mech.WHEEL -> namedBits(wheelErrorBits, bits)
        Mech.SHOT -> namedBits(shotErrorBits, bits)
        else -> errorNames(bits)
    }

    // ---- ジャイロ 0x200 ----
    /** 測定値が有効なのは I2C 成功 (bit0) と融合測定有効 (bit1) の両方が立っているとき */
    fun gyroValid(flags: Int) = flags and 0x03 == 0x03

    /** 校正 byte2: SYS=bit7-6 GYR=bit5-4 ACC=bit3-2 MAG=bit1-0。I2C 失敗時の 0xFF は「不明」 */
    fun calText(flags: Int, cal: Int?): String = when {
        cal == null -> "--"
        flags and 0x01 == 0 -> "校正情報不明"
        else -> "SYS ${(cal shr 6) and 3}  GYR ${(cal shr 4) and 3}  ACC ${(cal shr 2) and 3}  MAG ${cal and 3}"
    }

    fun degText(v: Double?, valid: Boolean, unit: String = "°") =
        if (!valid || v == null) "--" else "%.1f%s".format(v, unit)

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
        // PWM は生の値 (permille、-1000～+1000) のまま表示する
        else -> "%+d".format(raw)
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
        fun dbl(key: String): Double? = t.value("$prefix.$key")
        val flags: Int get() = int("flags") ?: 0
        fun has(flag: Int) = flags and flag != 0
    }
}
