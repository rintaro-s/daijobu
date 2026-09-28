// STM32 から UART で届く CAN フレームのデコーダ
// フレーム形式は CAN_TEST_TX-main/Core/Inc/can_uart_bridge.h と一致させること
#pragma once
#include <stddef.h>
#include <stdint.h>

struct CanFrame {
  uint32_t id;
  bool extended;
  bool fd;
  bool brs;
  uint8_t len;
  uint8_t data[64];
};

class BridgeRx {
 public:
  // 1 バイト投入。フレームが完成したら true を返し out に格納
  bool feed(uint8_t b, CanFrame &out);

  uint32_t okCount = 0;
  uint32_t crcErrors = 0;

 private:
  enum State { SOF1, SOF2, BODY };
  State state_ = SOF1;
  uint8_t buf_[1 + 4 + 1 + 64 + 1];
  size_t pos_ = 0;
};
