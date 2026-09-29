// 動作パラメータ (platformio.ini の build_flags で -D 上書き可)
#pragma once

// ---- Wi-Fi AP (Android アプリ側の ConnectionConfig.kt と一致させること) ----
#ifndef AP_SSID
#define AP_SSID "CTRL-MONITOR"
#endif
#ifndef AP_PASS
#define AP_PASS "loligodrequiem"  // 8 文字以上
#endif
#ifndef AP_CHANNEL
#define AP_CHANNEL 6
#endif
#ifndef TCP_PORT
#define TCP_PORT 3333
#endif
#ifndef MAX_CLIENTS
#define MAX_CLIENTS 4
#endif

// ---- STM32 との UART ----
// TX-kiban 回路図: STM32 PA2(USART2_TX) - ESP32 GPIO17, PA3(USART2_RX) - GPIO16
// Serial2 の標準 (RX=16, TX=17) とは逆なので注意。受信できないときは RX/TX を入れ替えて試す。
#ifndef BRIDGE_BAUD
#define BRIDGE_BAUD 921600  // STM32 の ESP_UART_BAUD (Core/Src/main.c) と一致させる
#endif
#ifndef BRIDGE_RX_PIN
#define BRIDGE_RX_PIN 17
#endif
#ifndef BRIDGE_TX_PIN
#define BRIDGE_TX_PIN 16
#endif

// ---- 配信 ----
#ifndef SEND_PERIOD_MS
#define SEND_PERIOD_MS 50  // 20 Hz (1 行約 3.5KB)
#endif
#ifndef CLIENT_STALL_TIMEOUT_MS
#define CLIENT_STALL_TIMEOUT_MS 2000  // この時間送れない相手は切断する (スマホが消えた等)
#endif
#ifndef DEBUG_PRINT_PERIOD_MS
#define DEBUG_PRINT_PERIOD_MS 1000
#endif
