/**
 * CAN FD -> UART(ESP32) bridge
 *
 * FDCAN1 で受信した全フレームを (ID 問わず、中身を解釈せず) USART2 で ESP32 に垂れ流す。
 * パースは ESP32 側で行うので、読み取る CAN を変えても STM32 側は変更不要。
 *
 * 安全方針 (既存の機構に影響を与えない / 取得して表示するだけ):
 *   - FDCAN はバスモニタ (リッスンオンリー) モード。ACK・エラーフレーム・送信を一切出さない
 *   - CAN 送信 API は一切呼ばない
 *   - USART2 は送信のみ (ESP32 からの入力は読まない)
 *   - 触る GPIO は FDCAN / USART1 / USART2 のピンと、基板上の LED のみ
 *
 * 配線 (TX-kiban / Cosmic_Juggernaut 回路図より):
 *   USART2 PA2(TX) -> ESP32 GPIO17 / PA3(RX) <- ESP32 GPIO16   115200bps (CubeMX 設定のまま)
 *   USART1 PA9/PA10 = ST-Link VCP                                デバッグログ
 *   LED1: PA6=G (CAN 受信中), PA5=R (CAN エラー), PA4=B (転送バッファ溢れ)
 *
 * UART フレーム形式 (バイナリ, ESP32-controle/src/bridge_rx.cpp と一致させること):
 *   [0]    0xA5              SOF1
 *   [1]    0x5A              SOF2
 *   [2]    flags             bit0: 拡張ID, bit1: FDフレーム, bit2: BRS
 *   [3..6] CAN ID            uint32 little endian
 *   [7]    len               データ長 0..64
 *   [8..]  data[len]
 *   [last] CRC-8 (poly 0x07, init 0x00)   flags ～ data 末尾が対象
 */
#ifndef CAN_UART_BRIDGE_H
#define CAN_UART_BRIDGE_H

#include "main.h"

HAL_StatusTypeDef Bridge_Start(void);
void Bridge_Poll(void);

#endif /* CAN_UART_BRIDGE_H */
