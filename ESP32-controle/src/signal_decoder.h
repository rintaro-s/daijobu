// can_signals.h のテーブルに従って CAN フレームを信号値に変換し、JSON を組み立てる
#pragma once
#include <Arduino.h>

#include "bridge_rx.h"

namespace decoder {

// 受け取ったフレームを保存 (テーブルに無い ID は無視)
void onFrame(const CanFrame &f, uint32_t nowMs);

// 接続直後に 1 回送る信号定義 (1 行 JSON, 末尾改行付き)
String schemaJson();

// 現在値 (1 行 JSON, 末尾改行付き)
String dataJson(uint32_t nowMs, uint32_t uartOk, uint32_t uartCrcErr);

// 1 秒ごとに呼んで受信レートを更新
void updateRates();

// ESP32 の起動理由 (esp_reset_reason) を JSON に載せる。再起動やブラウンアウトの切り分け用
void setBootReason(int reason);

// デバッグ表示用
String summary(uint32_t nowMs);

}  // namespace decoder
