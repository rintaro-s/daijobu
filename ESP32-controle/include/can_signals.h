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
//  デバッグ用 CAN FD 通信仕様 V1 (2026-09-27)
//    標準ID / CAN FD / BRS OFF / 24 byte / リトルエンディアン / byte0 = バージョン 1
//    信号名は「機構名.項目」。表示の解釈 (無効値・有効フラグ・状態番号) はアプリ側で行う
// ---------------------------------------------------------------------------
#define DBG_LEN     24
#define DBG_VERSION 1

// 全 ID 共通ヘッダー byte0～7
#define DBG_HEADER(id, p)                                  \
  SIG_U8(id, p ".ver", 0, 0, 255, p),                      \
  SIG_U8(id, p ".seq", 1, 0, 255, p),                      \
  SIG_U8(id, p ".state", 2, 0, 255, p),                    \
  SIG_U8(id, p ".flags", 3, 0, 255, p),                    \
  SIG_U32(id, p ".uptime", 4, 0, 4294967295.0f, p)

// 昇降 0x301～0x303
#define DBG_LIFT(id, p)                                    \
  DBG_HEADER(id, p),                                       \
  SIG_U16(id, p ".rate", 8, 0, 1000, p),        /* 0xFFFF=無効 */ \
  SIG_I16(id, p ".pwm", 10, -1000, 1000, p),    /* INT16_MIN=取得不可 */ \
  SIG_I32(id, p ".pos", 12, -2147483648.0f, 2147483647.0f, p), \
  SIG_I32(id, p ".ref", 16, -2147483648.0f, 2147483647.0f, p), \
  SIG_U16(id, p ".limit", 20, 0, 65535, p),                \
  SIG_U16(id, p ".err", 22, 0, 65535, p)

// 発射 0x310 (項目は担当者と要確定)
#define DBG_SHOT(id, p)                                    \
  DBG_HEADER(id, p),                                       \
  SIG_I16(id, p ".pwm", 8, -1000, 1000, p),                \
  SIG_U16(id, p ".target_rpm", 10, 0, 65534, p), /* 0xFFFF=無効 */ \
  SIG_I32(id, p ".rpm10", 12, -2147483648.0f, 2147483647.0f, p), \
  SIG_U32(id, p ".shots", 16, 0, 4294967295.0f, p), /* 0xFFFFFFFF=無効 */ \
  SIG_U16(id, p ".sensor", 20, 0, 65535, p),               \
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

#define DBG_RULE(id) {(id), DBG_LEN, true, true, true, 0, DBG_VERSION, 1}

// 受信する CAN ID と有効条件
static const FrameRule CAN_FRAMES[] = {
    // コントローラ: 20 byte 以上あれば受け付ける (既存フレームなので条件は緩め)
    {ID_PAD, 20, false, false, false, -1, 0, -1},

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
    // byte 19 は空き

    // 信号名の接頭辞はアプリ側 (DebugSpec.kt) と一致させること
    DBG_LIFT(0x301, "LIFT1"),
    DBG_LIFT(0x302, "LIFT2"),
    DBG_LIFT(0x303, "LIFT3"),
    DBG_SHOT(0x310, "SHOT"),
    DBG_WHEEL(0x321, "WFL"),  // 左前
    DBG_WHEEL(0x322, "WFR"),  // 右前
    DBG_WHEEL(0x323, "WRL"),  // 左後
    DBG_WHEEL(0x324, "WRR"),  // 右後
};
