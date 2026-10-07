// ============================================================================
//  読み取る CAN FD の定義 (★ 読み取る CAN を変えるときはこのファイルだけ編集する)
//
//  1 行 = 1 信号。スマホには「信号名 -> 値」で届き、アプリ側は信号名で表示を引く。
//    SIG_I8  (CAN ID, "名前", バイト位置, 最小, 最大, "グループ")
//    SIG_U8  (CAN ID, "名前", バイト位置, 最小, 最大, "グループ")
//    SIG_I16 / SIG_U16 / SIG_I32 / SIG_U32 / SIG_F32 (リトルエンディアン, 同じ引数)
//    SIG_BOOL(CAN ID, "名前", バイト位置, "グループ")          1 バイト = 1 ボタン (0 以外で ON)
//    SIG_BIT (CAN ID, "名前", バイト位置, ビット番号, "グループ")  ビット詰めの場合
//    SIG_SCALED(CAN ID, "名前", 型, バイト位置, 倍率, オフセット, 最小, 最大, "単位", "グループ")
//                                                     物理値 = 生値 * 倍率 + オフセット
//  「グループ」は汎用表示画面での見出しに使うだけ (任意の文字列)。
//  CAN_TIMEOUT_MS 以上フレームが来なければアプリ側で「途絶」表示になる。
//
//  受信する CAN ID と、その ID を「有効」とみなす条件は CAN_FRAMES に書く。
//  条件を満たさないフレームは値を更新せず、不正理由だけをアプリに伝える。
// ============================================================================
#pragma once
#include <stdint.h>

enum class SigType : uint8_t { U8, I8, U16, I16, U32, I32, F32, BOOL, BIT };

struct SignalDef {
  uint32_t canId;
  const char *name;
  SigType type;
  uint8_t byteOffset;
  uint8_t bit;  // SigType::BIT のときのみ使用
  float scale;
  float offset;
  float min;
  float max;
  const char *unit;
  const char *group;
};

// 受信条件 (条件を満たすフレームだけを有効として値・受信時刻・SEQ を更新する)
struct FrameRule {
  uint32_t canId;
  uint8_t len;        // 期待するデータ長
  bool exactLen;      // true: len と完全一致を要求 / false: len 以上あればよい
  bool requireFd;     // CAN FD フレームであること
  bool requireNoBrs;  // BRS OFF であること
  int8_t versionByte; // バージョンを確認するバイト位置 (-1 = 確認しない)
  uint8_t version;    // 対応するバージョン
  int8_t seqByte;     // シーケンス (uint8, 循環) のバイト位置 (-1 = 無し)
  int8_t uptimeByte;  // 送信元稼働時間 (uint32 LE) のバイト位置 (-1 = 無し)
                      //   SEQ と稼働時間が前回と同じ -> 重複として捨てる
                      //   稼働時間が巻き戻った     -> 送信元の再起動とみなし欠落に数えない
  bool seqWide;       // true: SEQ は uint16 LE (ジャイロ 0x200) / false: uint8
};

#define SIG_SCALED(id, name, type, byte, scale, ofs, mn, mx, unit, group) \
  {(id), (name), SigType::type, (byte), 0, (scale), (ofs), (mn), (mx), (unit), (group)}
#define SIG_NUM(type, id, name, byte, mn, mx, group) \
  SIG_SCALED(id, name, type, byte, 1.0f, 0.0f, mn, mx, "", group)
#define SIG_I8(id, name, byte, mn, mx, group)  SIG_NUM(I8, id, name, byte, mn, mx, group)
#define SIG_U8(id, name, byte, mn, mx, group)  SIG_NUM(U8, id, name, byte, mn, mx, group)
#define SIG_I16(id, name, byte, mn, mx, group) SIG_NUM(I16, id, name, byte, mn, mx, group)
#define SIG_U16(id, name, byte, mn, mx, group) SIG_NUM(U16, id, name, byte, mn, mx, group)
#define SIG_I32(id, name, byte, mn, mx, group) SIG_NUM(I32, id, name, byte, mn, mx, group)
#define SIG_U32(id, name, byte, mn, mx, group) SIG_NUM(U32, id, name, byte, mn, mx, group)
#define SIG_F32(id, name, byte, mn, mx, group) SIG_NUM(F32, id, name, byte, mn, mx, group)
#define SIG_BOOL(id, name, byte, group) \
  {(id), (name), SigType::BOOL, (byte), 0, 1.0f, 0.0f, 0.0f, 1.0f, "", (group)}
#define SIG_BIT(id, name, byte, bit, group) \
  {(id), (name), SigType::BIT, (byte), (bit), 1.0f, 0.0f, 0.0f, 1.0f, "", (group)}

// この時間フレームが来なければ途絶扱い (アプリ側に伝えて表示を変える)
#define CAN_TIMEOUT_MS 300

// ---------------------------------------------------------------------------
//  コントローラ (ID 0x123, 20 byte) : 既存の操作指令。仕様変更なし
// ---------------------------------------------------------------------------
#define ID_PAD 0x123

// ---------------------------------------------------------------------------
//  ゾウキンナゲルマン CAN FD ID台帳 V2 (2026-09-29)
//  デバッグ用 (0x301-0x324): 標準ID / CAN FD / BRS OFF / 24 byte / LE / byte0 = バージョン 2
//    信号名は「機構名.項目」。表示の解釈 (無効値・有効フラグ・状態番号) はアプリ側で行う
// ---------------------------------------------------------------------------
#define DBG_LEN     24
#define DBG_VERSION 2

// 全 ID 共通ヘッダー byte0～7
#define DBG_HEADER(id, p)                                  \
  SIG_U8(id, p ".ver", 0, 0, 255, p),                      \
  SIG_U8(id, p ".seq", 1, 0, 255, p),                      \
  SIG_U8(id, p ".state", 2, 0, 255, p),                    \
  SIG_U8(id, p ".flags", 3, 0, 255, p),                    \
  SIG_U32(id, p ".uptime", 4, 0, 4294967295.0f, p)

// 昇降 0x301～0x303 (V30: 20ms 周期。byte3 bit6/7 と故障 bit4/5/6 は V30 で追加。他軸との位置共有にも使う)
#define DBG_LIFT(id, p)                                    \
  DBG_HEADER(id, p),                                       \
  SIG_U16(id, p ".rate", 8, 0, 1000, p),        /* 0xFFFF=無効 */ \
  SIG_I16(id, p ".pwm", 10, -1000, 1000, p),    /* INT16_MIN=取得不可 */ \
  SIG_I32(id, p ".pos", 12, -2147483648.0f, 2147483647.0f, p), \
  SIG_I32(id, p ".ref", 16, -2147483648.0f, 2147483647.0f, p), \
  SIG_U16(id, p ".limit", 20, 0, 65535, p),                \
  SIG_U16(id, p ".err", 22, 0, 65535, p)

// 発射・装填・サーボ指令状態 0x310 (送信元は操作main1。機構が実際に動いた応答ではない)
#define DBG_CMD(id, p)                                     \
  DBG_HEADER(id, p),                                       \
  SIG_U16(id, p ".pwm", 8, 0, 1000, p),        /* 発射PWM設定 permille */ \
  SIG_U16(id, p ".angle", 10, 0, 65535, p),    /* 投擲角度設定 度 */ \
  SIG_U8(id, p ".cmd", 12, 0, 255, p),         /* 指令フラグ */ \
  SIG_U8(id, p ".adjust", 13, 0, 255, p),      /* 調整対象 0=なし 1=PWM 2=角度 */ \
  SIG_U8(id, p ".preset", 14, 0, 255, p),      /* 2=左 3=右 255=初期/手動 */ \
  SIG_U8(id, p ".sensor", 15, 0, 255, p),      /* 0=非検出 1=検出 255=無効 */ \
  SIG_U8(id, p ".stage", 16, 0, 255, p),       /* 装填シーケンス段階 */ \
  SIG_U16(id, p ".sensor_age", 18, 0, 65535, p), /* 65535=未受信/飽和 */ \
  SIG_U16(id, p ".ctrl_age", 20, 0, 65535, p),   /* 65535=未受信/飽和 */ \
  SIG_U16(id, p ".err", 22, 0, 65535, p)

// 足回り 0x321～0x324
#define DBG_WHEEL(id, p)                                   \
  DBG_HEADER(id, p),                                       \
  SIG_I16(id, p ".cmd", 8, -127, 127, p),                  \
  SIG_I16(id, p ".pwm", 10, -1000, 1000, p),               \
  SIG_I32(id, p ".rpm10", 12, -2147483648.0f, 2147483647.0f, p), \
  SIG_U32(id, p ".rx", 16, 0, 4294967295.0f, p),           \
  SIG_U16(id, p ".stop", 20, 0, 65535, p),                 \
  SIG_U16(id, p ".err", 22, 0, 65535, p)

#define DBG_RULE(id) {(id), DBG_LEN, true, true, true, 0, DBG_VERSION, 1, 4, false}

// ---------------------------------------------------------------------------
//  ジャイロ 0x200 (BNO055 基板 -> main1 / 監視main2)  24 byte / バージョン 1 / 50Hz
//    昇降 (0x301-0x303) とは別形式: SEQ は byte4-5 の uint16、稼働時間は byte20-23、
//    有効性フラグは byte1 (bit0=I2C成功, bit1=融合測定有効。測定有効は (flags & 3) == 3)。
//    角度・角速度は i16 を 16 で割って度 / 度毎秒。測定無効のときこれらは 0 で送られてくるが
//    実際の 0 ではないので、表示側 (アプリ) が「無効」として扱う。
//    校正 byte2: SYS=bit7-6 GYR=bit5-4 ACC=bit3-2 MAG=bit1-0 (I2C失敗時は 0xFF = 不明)
// ---------------------------------------------------------------------------
#define ID_GYRO 0x200
#define GYRO_RULE {ID_GYRO, 24, true, true, true, 0, 1, 4, 20, true}
#define GYRO_DEG(name, byte) \
  SIG_SCALED(ID_GYRO, name, I16, byte, 0.0625f, 0.0f, -360.0f, 360.0f, "deg", "GYRO")
#define GYRO_DPS(name, byte) \
  SIG_SCALED(ID_GYRO, name, I16, byte, 0.0625f, 0.0f, -2000.0f, 2000.0f, "dps", "GYRO")
#define DBG_GYRO                                           \
  SIG_U8(ID_GYRO, "GYRO.ver", 0, 0, 255, "GYRO"),          \
  SIG_U8(ID_GYRO, "GYRO.flags", 1, 0, 255, "GYRO"),        \
  SIG_U8(ID_GYRO, "GYRO.cal", 2, 0, 255, "GYRO"),          \
  SIG_U8(ID_GYRO, "GYRO.addr", 3, 0, 255, "GYRO"),         \
  SIG_U16(ID_GYRO, "GYRO.seq", 4, 0, 65535, "GYRO"),       \
  SIG_U8(ID_GYRO, "GYRO.sys_status", 6, 0, 255, "GYRO"),   \
  SIG_U8(ID_GYRO, "GYRO.sys_err", 7, 0, 255, "GYRO"),      \
  GYRO_DEG("GYRO.yaw", 8), GYRO_DEG("GYRO.roll", 10), GYRO_DEG("GYRO.pitch", 12), \
  GYRO_DPS("GYRO.gx", 14), GYRO_DPS("GYRO.gy", 16), GYRO_DPS("GYRO.gz", 18),      \
  SIG_U32(ID_GYRO, "GYRO.uptime", 20, 0, 4294967295.0f, "GYRO")

// ---------------------------------------------------------------------------
//  制御・安全用 ID (表示のみ。ここで何かを判断・停止はしない)
// ---------------------------------------------------------------------------
#define ID_LIFT_CMD   0x130  // 昇降システム指令 (main1 -> 昇降)       8B ver1
#define ID_LIFT_ST1   0x141  // 昇降ID1 状態 (中身の並びは台帳に記載なし -> 生バイト表示)
#define ID_LIFT_ST2   0x142
#define ID_LIFT_ST3   0x143
#define ID_SHOT_CMD   0x150  // 発射・装填・サーボ指令 (main1 -> 各機構) 8B ver2
#define ID_LOAD_SENS  0x210  // 装填センサー状態 (サーボ基板 -> main1) 4B ver1

#define RAW8(id, p)                                                        \
  SIG_U8(id, p ".b0", 0, 0, 255, p), SIG_U8(id, p ".b1", 1, 0, 255, p),     \
  SIG_U8(id, p ".b2", 2, 0, 255, p), SIG_U8(id, p ".b3", 3, 0, 255, p),     \
  SIG_U8(id, p ".b4", 4, 0, 255, p), SIG_U8(id, p ".b5", 5, 0, 255, p),     \
  SIG_U8(id, p ".b6", 6, 0, 255, p), SIG_U8(id, p ".b7", 7, 0, 255, p)

// 受信する CAN ID と有効条件
static const FrameRule CAN_FRAMES[] = {
    // コントローラ: 20 byte 以上あれば受け付ける (既存フレームなので条件は緩め)
    {ID_PAD, 20, false, false, false, -1, 0, -1, -1, false},
    GYRO_RULE,

    // 制御用 (長さ・バージョン・SEQ は台帳どおり)
    {ID_LIFT_CMD, 8, true, true, true, 0, 1, 2, -1, false},
    {ID_LIFT_ST1, 8, true, true, true, -1, 0, -1, -1, false},
    {ID_LIFT_ST2, 8, true, true, true, -1, 0, -1, -1, false},
    {ID_LIFT_ST3, 8, true, true, true, -1, 0, -1, -1, false},
    {ID_SHOT_CMD, 8, true, true, true, 0, 2, 2, -1, false},
    {ID_LOAD_SENS, 4, true, true, true, 0, 1, 2, -1, false},

    DBG_RULE(0x301), DBG_RULE(0x302), DBG_RULE(0x303),
    DBG_RULE(0x310),
    DBG_RULE(0x321), DBG_RULE(0x322), DBG_RULE(0x323), DBG_RULE(0x324),
};

static const SignalDef CAN_SIGNALS[] = {
    SIG_I8(ID_PAD, "LX", 0, -127, 127, "Stick"),
    SIG_I8(ID_PAD, "LY", 1, -127, 127, "Stick"),
    SIG_I8(ID_PAD, "RX", 2, -127, 127, "Stick"),
    SIG_I8(ID_PAD, "RY", 3, -127, 127, "Stick"),

    SIG_BOOL(ID_PAD, "A", 4, "Button"),
    SIG_BOOL(ID_PAD, "B", 5, "Button"),
    SIG_BOOL(ID_PAD, "X", 6, "Button"),
    SIG_BOOL(ID_PAD, "Y", 7, "Button"),

    SIG_BOOL(ID_PAD, "UP", 8, "D-Pad"),
    SIG_BOOL(ID_PAD, "DOWN", 9, "D-Pad"),
    SIG_BOOL(ID_PAD, "LEFT", 10, "D-Pad"),
    SIG_BOOL(ID_PAD, "RIGHT", 11, "D-Pad"),

    SIG_BOOL(ID_PAD, "L1", 12, "Shoulder"),
    SIG_BOOL(ID_PAD, "L2", 13, "Shoulder"),
    SIG_BOOL(ID_PAD, "R1", 14, "Shoulder"),
    SIG_BOOL(ID_PAD, "R2", 15, "Shoulder"),

    SIG_BOOL(ID_PAD, "PLUS", 16, "System"),
    SIG_BOOL(ID_PAD, "MINUS", 17, "System"),
    SIG_BOOL(ID_PAD, "HOME", 18, "System"),
    SIG_BOOL(ID_PAD, "CAPTURE", 19, "System"),

    // 制御用 ID
    SIG_U8(ID_LIFT_CMD, "LCMD.ver", 0, 0, 255, "LCMD"),
    SIG_U8(ID_LIFT_CMD, "LCMD.flags", 1, 0, 255, "LCMD"),
    SIG_U8(ID_LIFT_CMD, "LCMD.seq", 2, 0, 255, "LCMD"),
    RAW8(ID_LIFT_ST1, "LST1"),
    RAW8(ID_LIFT_ST2, "LST2"),
    RAW8(ID_LIFT_ST3, "LST3"),
    SIG_U8(ID_SHOT_CMD, "SCMD.ver", 0, 0, 255, "SCMD"),
    SIG_U8(ID_SHOT_CMD, "SCMD.flags", 1, 0, 255, "SCMD"),
    SIG_U8(ID_SHOT_CMD, "SCMD.seq", 2, 0, 255, "SCMD"),
    SIG_U8(ID_SHOT_CMD, "SCMD.preset", 3, 0, 255, "SCMD"),
    SIG_U16(ID_SHOT_CMD, "SCMD.pwm", 4, 0, 1000, "SCMD"),
    SIG_U16(ID_SHOT_CMD, "SCMD.angle", 6, 0, 65535, "SCMD"),
    SIG_U8(ID_LOAD_SENS, "LSEN.ver", 0, 0, 255, "LSEN"),
    SIG_U8(ID_LOAD_SENS, "LSEN.sensor", 1, 0, 1, "LSEN"),
    SIG_U8(ID_LOAD_SENS, "LSEN.seq", 2, 0, 255, "LSEN"),
    SIG_U8(ID_LOAD_SENS, "LSEN.ready", 3, 0, 1, "LSEN"),

    // 信号名の接頭辞はアプリ側 (DebugSpec.kt) と一致させること
    DBG_GYRO,
    DBG_LIFT(0x301, "LIFT1"),
    DBG_LIFT(0x302, "LIFT2"),
    DBG_LIFT(0x303, "LIFT3"),
    DBG_CMD(0x310, "SHOT"),  // 発射・装填・サーボ指令状態
    DBG_WHEEL(0x321, "WFL"),  // 左前
    DBG_WHEEL(0x322, "WFR"),  // 右前
    DBG_WHEEL(0x323, "WRL"),  // 左後
    DBG_WHEEL(0x324, "WRR"),  // 右後
};
