package com.nbks.controoooooool.can

/**
 * 読み取る CAN FD の定義。仕様 (ID・バイト並び・検査条件) が変わったら、このファイルだけを直す。
 * 出典: ロボット CAN FD 引き継ぎ仕様 (CANFD_Handover_20261012)。
 *
 * ESP32 は CAN の中身を解釈せずに転送するだけなので、ESP32 の書き換えは不要。
 * 信号名は「接頭辞.項目」(例: LIFT1.pwm)。画面側 (ui/display/DebugSpec.kt) はこの名前で値を引く。
 */
enum class SigType { U8, I8, U16, I16, U32, I32, F32, BOOL, BIT }

data class SignalDef(
    val canId: Int,
    val name: String,
    val type: SigType,
    val offset: Int,
    val bit: Int = 0,
    val scale: Double = 1.0,
    val bias: Double = 0.0,
    val min: Double,
    val max: Double,
    val unit: String = "",
    val group: String,
) {
    val size: Int
        get() = when (type) {
            SigType.U16, SigType.I16 -> 2
            SigType.U32, SigType.I32, SigType.F32 -> 4
            else -> 1
        }
    val isBool: Boolean get() = type == SigType.BOOL || type == SigType.BIT
}

/** 受信条件。条件を満たすフレームだけを「有効」として値・受信時刻・SEQ を更新する */
data class FrameRule(
    val canId: Int,
    val len: Int,
    /** true: 長さが len と完全一致 / false: len 以上 */
    val exactLen: Boolean,
    val requireFd: Boolean,
    val requireNoBrs: Boolean,
    /** バージョンを確認するバイト位置 (-1 = 確認しない) */
    val versionByte: Int = -1,
    val version: Int = 0,
    /** シーケンスのバイト位置 (-1 = 無し) */
    val seqByte: Int = -1,
    /** true: SEQ は uint16 LE (ジャイロ) / false: uint8 */
    val seqWide: Boolean = false,
    /** 送信元の稼働時間 (uint32 LE) のバイト位置 (-1 = 無し) */
    val uptimeByte: Int = -1,
)

object CanDefs {
    /** この時間 (ms) 有効フレームが来なければ途絶 */
    const val TIMEOUT_MS = 300L

    const val ID_PAD = 0x123
    const val ID_GYRO = 0x200

    // ---- 受信条件 ----
    private fun dbg(id: Int) = FrameRule(id, 24, true, true, true, versionByte = 0, version = 2, seqByte = 1, uptimeByte = 4)
    private fun ctrl(id: Int, version: Int, seqByte: Int) =
        FrameRule(id, 8, true, true, true, versionByte = 0, version = version, seqByte = seqByte)

    val rules: List<FrameRule> = listOf(
        // コントローラー: 20 byte 以上 (固定配置・バージョン/SEQ なし)
        FrameRule(ID_PAD, 20, false, false, false),
        // ジャイロ 0x200: 24B / V1 / SEQ は byte4-5 の uint16 / 稼働時間 byte20-23
        FrameRule(ID_GYRO, 24, true, true, true, versionByte = 0, version = 1, seqByte = 4, seqWide = true, uptimeByte = 20),
        // 制御用
        ctrl(0x130, 1, 2),                              // 昇降共有安全・原点
        ctrl(0x141, 1, 5), ctrl(0x142, 1, 5), ctrl(0x143, 1, 5),  // 昇降リミット状態
        ctrl(0x150, 2, 2),                              // 発射・正転装填・サーボ指令
        ctrl(0x151, 1, 2),                              // 装填 手動逆転
        ctrl(0x201, 2, 2),                              // 足回り方位補正
        ctrl(0x210, 3, 2),                              // S1・S2 センサー
        // 診断 (表示専用)
        dbg(0x301), dbg(0x302), dbg(0x303), dbg(0x310),
        dbg(0x321), dbg(0x322), dbg(0x323), dbg(0x324),
    )

    // ---- 信号の定義 ----
    private val out = ArrayList<SignalDef>()

    private fun num(id: Int, name: String, type: SigType, off: Int, min: Double, max: Double, group: String) {
        out += SignalDef(id, name, type, off, min = min, max = max, group = group)
    }
    private fun u8(id: Int, n: String, o: Int, g: String, min: Double = 0.0, max: Double = 255.0) = num(id, n, SigType.U8, o, min, max, g)
    private fun i8(id: Int, n: String, o: Int, g: String, min: Double, max: Double) = num(id, n, SigType.I8, o, min, max, g)
    private fun u16(id: Int, n: String, o: Int, g: String, min: Double = 0.0, max: Double = 65535.0) = num(id, n, SigType.U16, o, min, max, g)
    private fun i16(id: Int, n: String, o: Int, g: String, min: Double, max: Double) = num(id, n, SigType.I16, o, min, max, g)
    private fun u32(id: Int, n: String, o: Int, g: String) = num(id, n, SigType.U32, o, 0.0, 4294967295.0, g)
    private fun i32(id: Int, n: String, o: Int, g: String) = num(id, n, SigType.I32, o, -2147483648.0, 2147483647.0, g)
    private fun bool(id: Int, n: String, o: Int, g: String) {
        out += SignalDef(id, n, SigType.BOOL, o, min = 0.0, max = 1.0, group = g)
    }
    private fun scaled(id: Int, n: String, type: SigType, o: Int, scale: Double, min: Double, max: Double, unit: String, g: String) {
        out += SignalDef(id, n, type, o, scale = scale, min = min, max = max, unit = unit, group = g)
    }

    /** 診断の共通ヘッダー byte0-7 */
    private fun header(id: Int, p: String) {
        u8(id, "$p.ver", 0, p); u8(id, "$p.seq", 1, p); u8(id, "$p.state", 2, p); u8(id, "$p.flags", 3, p)
        u32(id, "$p.uptime", 4, p)
    }

    val signals: List<SignalDef> by lazy {
        out.clear()
        // コントローラー 0x123
        i8(ID_PAD, "LX", 0, "Stick", -127.0, 127.0); i8(ID_PAD, "LY", 1, "Stick", -127.0, 127.0)
        i8(ID_PAD, "RX", 2, "Stick", -127.0, 127.0); i8(ID_PAD, "RY", 3, "Stick", -127.0, 127.0)
        listOf("A", "B", "X", "Y").forEachIndexed { i, n -> bool(ID_PAD, n, 4 + i, "Button") }
        listOf("UP", "DOWN", "LEFT", "RIGHT").forEachIndexed { i, n -> bool(ID_PAD, n, 8 + i, "D-Pad") }
        listOf("L1", "L2", "R1", "R2").forEachIndexed { i, n -> bool(ID_PAD, n, 12 + i, "Shoulder") }
        listOf("PLUS", "MINUS", "HOME", "CAPTURE").forEachIndexed { i, n -> bool(ID_PAD, n, 16 + i, "System") }

        // ジャイロ 0x200 (角度 raw/16 = 度、角速度 raw/16 = 度/秒)
        val g = "GYRO"
        u8(ID_GYRO, "GYRO.ver", 0, g); u8(ID_GYRO, "GYRO.flags", 1, g); u8(ID_GYRO, "GYRO.cal", 2, g)
        u8(ID_GYRO, "GYRO.addr", 3, g); u16(ID_GYRO, "GYRO.seq", 4, g)
        u8(ID_GYRO, "GYRO.sys_status", 6, g); u8(ID_GYRO, "GYRO.sys_err", 7, g)
        scaled(ID_GYRO, "GYRO.yaw", SigType.I16, 8, 1.0 / 16, -360.0, 360.0, "deg", g)
        scaled(ID_GYRO, "GYRO.roll", SigType.I16, 10, 1.0 / 16, -360.0, 360.0, "deg", g)
        scaled(ID_GYRO, "GYRO.pitch", SigType.I16, 12, 1.0 / 16, -360.0, 360.0, "deg", g)
        scaled(ID_GYRO, "GYRO.gx", SigType.I16, 14, 1.0 / 16, -2000.0, 2000.0, "dps", g)
        scaled(ID_GYRO, "GYRO.gy", SigType.I16, 16, 1.0 / 16, -2000.0, 2000.0, "dps", g)
        scaled(ID_GYRO, "GYRO.gz", SigType.I16, 18, 1.0 / 16, -2000.0, 2000.0, "dps", g)
        u32(ID_GYRO, "GYRO.uptime", 20, g)

        // 制御用 ID
        u8(0x130, "LCMD.ver", 0, "LCMD"); u8(0x130, "LCMD.flags", 1, "LCMD"); u8(0x130, "LCMD.seq", 2, "LCMD")
        listOf(0x141 to "LST1", 0x142 to "LST2", 0x143 to "LST3").forEach { (id, p) ->
            u8(id, "$p.ver", 0, p); u8(id, "$p.board", 1, p); u8(id, "$p.down_latch", 2, p, 0.0, 1.0)
            u8(id, "$p.lower", 3, p, 0.0, 1.0); u8(id, "$p.fault", 4, p); u8(id, "$p.seq", 5, p)
            u8(id, "$p.raw_lower", 6, p, 0.0, 1.0); u8(id, "$p.flags", 7, p)
        }
        val sc = "SCMD"
        u8(0x150, "SCMD.ver", 0, sc); u8(0x150, "SCMD.flags", 1, sc); u8(0x150, "SCMD.seq", 2, sc)
        u8(0x150, "SCMD.preset", 3, sc); u16(0x150, "SCMD.pwm", 4, sc, 0.0, 1000.0); u16(0x150, "SCMD.angle", 6, sc, 0.0, 180.0)
        val lr = "LREV"
        u8(0x151, "LREV.ver", 0, lr); u8(0x151, "LREV.flags", 1, lr); u8(0x151, "LREV.seq", 2, lr); u8(0x151, "LREV.mask", 3, lr)
        val hd = "HDG"
        u8(0x201, "HDG.ver", 0, hd); u8(0x201, "HDG.flags", 1, hd); u8(0x201, "HDG.seq", 2, hd); u8(0x201, "HDG.mode", 3, hd)
        i16(0x201, "HDG.corr", 4, hd, -40.0, 40.0)
        scaled(0x201, "HDG.err", SigType.I16, 6, 1.0 / 16, -180.0, 180.0, "deg", hd)
        val ls = "LSEN"
        u8(0x210, "LSEN.ver", 0, ls); u8(0x210, "LSEN.s1", 1, ls, 0.0, 1.0); u8(0x210, "LSEN.seq", 2, ls)
        u8(0x210, "LSEN.mask", 3, ls); u8(0x210, "LSEN.s2", 4, ls, 0.0, 1.0)
        u8(0x210, "LSEN.s2_det", 5, ls); u8(0x210, "LSEN.s2_rel", 6, ls); u8(0x210, "LSEN.boot", 7, ls, 0.0, 1.0)

        // 診断 0x301-0x303 昇降 (byte22-23 の異常ビットの意味は DebugSpec.kt)
        listOf(0x301 to "LIFT1", 0x302 to "LIFT2", 0x303 to "LIFT3").forEach { (id, p) ->
            header(id, p)
            u16(id, "$p.rate", 8, p, 0.0, 1000.0); i16(id, "$p.pwm", 10, p, -1000.0, 1000.0)
            i32(id, "$p.pos", 12, p); i32(id, "$p.ref", 16, p)
            u16(id, "$p.limit", 20, p); u16(id, "$p.err", 22, p)
        }
        // 診断 0x310 発射・装填の希望指令 (main1 の内部状態。実出力ではない)
        header(0x310, "SHOT")
        u16(0x310, "SHOT.pwm", 8, "SHOT", 0.0, 1000.0); u16(0x310, "SHOT.angle", 10, "SHOT", 0.0, 180.0)
        u8(0x310, "SHOT.cmd", 12, "SHOT"); u8(0x310, "SHOT.adjust", 13, "SHOT"); u8(0x310, "SHOT.preset", 14, "SHOT")
        u8(0x310, "SHOT.sensor", 15, "SHOT"); u8(0x310, "SHOT.stage", 16, "SHOT")
        u16(0x310, "SHOT.sensor_age", 18, "SHOT"); u16(0x310, "SHOT.ctrl_age", 20, "SHOT"); u16(0x310, "SHOT.err", 22, "SHOT")
        // 診断 0x321-0x324 足回り (RPM は未計測で 0x80000000 固定)
        listOf(0x321 to "WFL", 0x322 to "WFR", 0x323 to "WRL", 0x324 to "WRR").forEach { (id, p) ->
            header(id, p)
            i16(id, "$p.cmd", 8, p, -127.0, 127.0); i16(id, "$p.pwm", 10, p, -1000.0, 1000.0)
            i32(id, "$p.rpm10", 12, p); u32(id, "$p.rx", 16, p)
            u16(id, "$p.stop", 20, p); u16(id, "$p.err", 22, p)
        }
        out.toList()
    }
}
