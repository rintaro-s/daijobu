#include "signal_decoder.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

#include "can_signals.h"

namespace decoder {
namespace {

constexpr size_t kNumSignals = sizeof(CAN_SIGNALS) / sizeof(CAN_SIGNALS[0]);
constexpr size_t kNumFrames = sizeof(CAN_FRAMES) / sizeof(CAN_FRAMES[0]);

// 最後に届いたフレームを不正と判定した理由
enum class Reject : uint8_t { None, Format, Length, Version };

struct IdState {
  bool received = false;  // 有効フレームを 1 回以上受信した
  uint8_t len = 0;
  uint8_t data[64] = {};
  uint32_t lastMs = 0;    // 最後の「有効」受信時刻
  uint32_t count = 0;     // 有効受信数
  uint32_t countAtLastRate = 0;
  float hz = 0;
  // SEQ (仕様: uint8 の差分で飛びを数える。欠落の手掛かりであり保証ではない)
  bool haveSeq = false;
  uint8_t seq = 0;
  uint32_t seqLost = 0;
  // 不正フレーム
  uint32_t rejected = 0;
  Reject lastReject = Reject::None;  // 直近フレームが不正なら None 以外
  uint8_t rejectValue = 0;           // Length: 実長 / Version: 受信したバージョン
};

IdState states[kNumFrames];

// 診断用: テーブルに無い ID (拡張IDを含む) の受信記録。何が実際にバスに流れているかを見る
struct UnknownId {
  uint32_t id;
  bool extended;
  bool fd;
  bool brs;
  uint8_t len;
  uint32_t count;
  uint32_t lastMs;
};
constexpr size_t kMaxUnknown = 16;
UnknownId unknowns[kMaxUnknown];
size_t numUnknown = 0;
uint32_t unknownOverflow = 0;

void recordUnknown(const CanFrame &f, uint32_t nowMs) {
  for (size_t i = 0; i < numUnknown; i++) {
    UnknownId &u = unknowns[i];
    if (u.id == f.id && u.extended == f.extended) {
      u.fd = f.fd;
      u.brs = f.brs;
      u.len = f.len;
      u.count++;
      u.lastMs = nowMs;
      return;
    }
  }
  if (numUnknown < kMaxUnknown) {
    unknowns[numUnknown++] = {f.id, f.extended, f.fd, f.brs, f.len, 1, nowMs};
  } else {
    unknownOverflow++;
  }
}

int findFrame(uint32_t id) {
  for (size_t i = 0; i < kNumFrames; i++)
    if (CAN_FRAMES[i].canId == id) return (int)i;
  return -1;
}

uint8_t typeSize(SigType t) {
  switch (t) {
    case SigType::U16:
    case SigType::I16: return 2;
    case SigType::U32:
    case SigType::I32:
    case SigType::F32: return 4;
    default: return 1;
  }
}

const char *typeName(SigType t) {
  switch (t) {
    case SigType::U8: return "u8";
    case SigType::I8: return "i8";
    case SigType::U16: return "u16";
    case SigType::I16: return "i16";
    case SigType::U32: return "u32";
    case SigType::I32: return "i32";
    case SigType::F32: return "f32";
    case SigType::BOOL: return "bool";
    case SigType::BIT: return "bit";
  }
  return "?";
}

bool isBool(SigType t) { return t == SigType::BOOL || t == SigType::BIT; }

uint32_t readLe(const uint8_t *p, uint8_t n) {
  uint32_t v = 0;
  for (uint8_t i = 0; i < n; i++) v |= (uint32_t)p[i] << (8 * i);
  return v;
}

// 生値を取り出す。範囲外なら false
bool rawValue(const SignalDef &s, const IdState &st, double &out) {
  if (s.byteOffset + typeSize(s.type) > st.len) return false;
  const uint8_t *p = &st.data[s.byteOffset];
  switch (s.type) {
    case SigType::U8: out = p[0]; break;
    case SigType::I8: out = (int8_t)p[0]; break;
    case SigType::U16: out = (uint16_t)readLe(p, 2); break;
    case SigType::I16: out = (int16_t)readLe(p, 2); break;
    case SigType::U32: out = readLe(p, 4); break;
    case SigType::I32: out = (int32_t)readLe(p, 4); break;
    case SigType::F32: {
      uint32_t u = readLe(p, 4);
      float f;
      memcpy(&f, &u, 4);
      out = f;
      break;
    }
    case SigType::BOOL: out = p[0] != 0 ? 1 : 0; break;
    case SigType::BIT: out = (p[0] >> (s.bit & 7)) & 1; break;
  }
  return true;
}

// JSON の数値。uint32 / int32 の全範囲を丸めずに出す。NaN/Inf は null
void appendNumber(String &js, double v) {
  char buf[32];
  if (!isfinite(v)) {
    js += "null";
    return;
  }
  if (v == floor(v) && fabs(v) < 9.0e15) {
    snprintf(buf, sizeof(buf), "%.0f", v);
  } else {
    snprintf(buf, sizeof(buf), "%.6g", v);
  }
  js += buf;
}

void appendReject(String &js, const IdState &st) {
  switch (st.lastReject) {
    case Reject::None: js += "null"; return;
    case Reject::Format: js += "\"fmt\""; return;  // 拡張ID / 非FD / BRS ON
    case Reject::Length: js += "\"len:"; break;
    case Reject::Version: js += "\"ver:"; break;
  }
  js += st.rejectValue;
  js += '"';
}

}  // namespace

void onFrame(const CanFrame &f, uint32_t nowMs) {
  int idx = findFrame(f.id);
  if (idx < 0) {  // テーブルに無い ID は診断用に記録だけする
    recordUnknown(f, nowMs);
    return;
  }
  const FrameRule &r = CAN_FRAMES[idx];
  IdState &st = states[idx];

  // ---- 解析前の確認 (ID / 標準・FD 形式 / BRS OFF / 実長 / バージョン) ----
  Reject rej = Reject::None;
  uint8_t rejVal = 0;
  if (f.extended || (r.requireFd && !f.fd) || (r.requireNoBrs && f.brs)) {
    rej = Reject::Format;
  } else if (r.exactLen ? (f.len != r.len) : (f.len < r.len)) {
    rej = Reject::Length;
    rejVal = f.len;
  } else if (r.versionByte >= 0 && f.data[r.versionByte] != r.version) {
    rej = Reject::Version;
    rejVal = f.data[r.versionByte];
  }
  if (rej != Reject::None) {
    // 不正フレームでは値・受信時刻・SEQ を更新しない (有効な受信が止まれば途絶になる)
    st.rejected++;
    st.lastReject = rej;
    st.rejectValue = rejVal;
    return;
  }
  st.lastReject = Reject::None;

  if (r.seqByte >= 0) {
    uint8_t seq = f.data[r.seqByte];
    if (st.haveSeq) {
      uint8_t diff = (uint8_t)(seq - st.seq);
      if (diff > 1) st.seqLost += diff - 1u;
    }
    st.seq = seq;
    st.haveSeq = true;
  }

  st.received = true;
  st.len = f.len;
  memcpy(st.data, f.data, f.len);
  st.lastMs = nowMs;
  st.count++;
}

void updateRates() {
  for (auto &st : states) {
    st.hz = (float)(st.count - st.countAtLastRate);
    st.countAtLastRate = st.count;
  }
}

String schemaJson() {
  String js;
  js.reserve(16384);
  js += "{\"type\":\"schema\",\"timeout\":";
  js += CAN_TIMEOUT_MS;
  js += ",\"signals\":[";
  for (size_t i = 0; i < kNumSignals; i++) {
    const SignalDef &s = CAN_SIGNALS[i];
    if (i) js += ',';
    js += "{\"name\":\"";
    js += s.name;
    js += "\",\"id\":";
    js += s.canId;
    js += ",\"type\":\"";
    js += typeName(s.type);
    js += "\",\"bool\":";
    js += isBool(s.type) ? "true" : "false";
    js += ",\"min\":";
    appendNumber(js, s.min);
    js += ",\"max\":";
    appendNumber(js, s.max);
    js += ",\"unit\":\"";
    js += s.unit;
    js += "\",\"group\":\"";
    js += s.group;
    js += "\"}";
  }
  js += "]}\n";
  return js;
}

String dataJson(uint32_t nowMs, uint32_t uartOk, uint32_t uartCrcErr) {
  String js;
  js.reserve(4096);
  js += "{\"type\":\"data\",\"t\":";
  js += nowMs;
  js += ",\"frames\":[";
  for (size_t i = 0; i < kNumFrames; i++) {
    const IdState &st = states[i];
    if (i) js += ',';
    js += "{\"id\":";
    js += CAN_FRAMES[i].canId;
    js += ",\"age\":";
    js += st.received ? (long)(nowMs - st.lastMs) : -1L;
    js += ",\"hz\":";
    appendNumber(js, st.hz);
    js += ",\"seq\":";
    if (st.haveSeq) js += st.seq; else js += "null";
    js += ",\"lost\":";
    js += st.seqLost;
    js += ",\"bad\":";
    js += st.rejected;
    js += ",\"rej\":";
    appendReject(js, st);
    js += '}';
  }
  js += "],\"v\":{";
  bool first = true;
  for (size_t i = 0; i < kNumSignals; i++) {
    const SignalDef &s = CAN_SIGNALS[i];
    int idx = findFrame(s.canId);
    double raw = 0;
    if (idx < 0 || !states[idx].received || !rawValue(s, states[idx], raw)) continue;
    if (!first) js += ',';
    first = false;
    js += '"';
    js += s.name;
    js += "\":";
    appendNumber(js, isBool(s.type) ? raw : raw * s.scale + s.offset);
  }
  // テーブルに無い ID (診断用)
  js += "},\"unknown\":[";
  for (size_t i = 0; i < numUnknown; i++) {
    const UnknownId &u = unknowns[i];
    if (i) js += ',';
    js += "{\"id\":";
    js += u.id;
    js += ",\"ext\":";
    js += u.extended ? "true" : "false";
    js += ",\"fd\":";
    js += u.fd ? "true" : "false";
    js += ",\"brs\":";
    js += u.brs ? "true" : "false";
    js += ",\"len\":";
    js += u.len;
    js += ",\"count\":";
    js += u.count;
    js += ",\"age\":";
    js += (long)(nowMs - u.lastMs);
    js += '}';
  }
  js += "],\"unknownOverflow\":";
  js += unknownOverflow;
  js += ",\"link\":{\"ok\":";
  js += uartOk;
  js += ",\"crc\":";
  js += uartCrcErr;
  js += "}}\n";
  return js;
}

String summary(uint32_t nowMs) {
  String s;
  if (numUnknown > 0) {
    s += "| other: ";
    for (size_t i = 0; i < numUnknown; i++) {
      char buf[48];
      snprintf(buf, sizeof(buf), "0x%03lX%s(%lu,%uB) ", (unsigned long)unknowns[i].id,
               unknowns[i].extended ? "X" : "", (unsigned long)unknowns[i].count,
               (unsigned)unknowns[i].len);
      s += buf;
    }
    s += "| ";
  }
  for (size_t i = 0; i < kNumFrames; i++) {
    const IdState &st = states[i];
    char buf[64];
    snprintf(buf, sizeof(buf), "0x%03lX:%.0fHz/%ld ", (unsigned long)CAN_FRAMES[i].canId, st.hz,
             st.received ? (long)(nowMs - st.lastMs) : -1L);
    s += buf;
  }
  return s;
}

}  // namespace decoder
