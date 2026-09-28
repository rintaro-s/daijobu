#include "bridge_rx.h"

#include <string.h>

static constexpr uint8_t kSof1 = 0xA5;
static constexpr uint8_t kSof2 = 0x5A;
static constexpr uint8_t kHeaderLen = 6;  // flags + id(4) + len

static uint8_t crc8(const uint8_t *p, size_t n) {
  uint8_t crc = 0;
  while (n--) {
    crc ^= *p++;
    for (int i = 0; i < 8; i++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
  }
  return crc;
}

bool BridgeRx::feed(uint8_t b, CanFrame &out) {
  switch (state_) {
    case SOF1:
      if (b == kSof1) state_ = SOF2;
      return false;
    case SOF2:
      if (b == kSof2) {
        state_ = BODY;
        pos_ = 0;
      } else {
        state_ = (b == kSof1) ? SOF2 : SOF1;
      }
      return false;
    case BODY:
      break;
  }

  buf_[pos_++] = b;
  if (pos_ < kHeaderLen) return false;

  uint8_t len = buf_[5];
  if (len > 64) {  // 壊れたヘッダ
    crcErrors++;
    state_ = SOF1;
    return false;
  }
  if (pos_ < (size_t)kHeaderLen + len + 1) return false;

  state_ = SOF1;
  if (crc8(buf_, kHeaderLen + len) != buf_[kHeaderLen + len]) {
    crcErrors++;
    return false;
  }

  out.extended = buf_[0] & 0x01;
  out.fd = buf_[0] & 0x02;
  out.brs = buf_[0] & 0x04;
  out.id = (uint32_t)buf_[1] | ((uint32_t)buf_[2] << 8) | ((uint32_t)buf_[3] << 16) |
           ((uint32_t)buf_[4] << 24);
  out.len = len;
  memcpy(out.data, &buf_[kHeaderLen], len);
  okCount++;
  return true;
}
