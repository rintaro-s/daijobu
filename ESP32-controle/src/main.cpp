// ESP32 : STM32 (CAN FD 受信ブリッジ) -> Wi-Fi AP -> スマホアプリ
//
//   STM32 --UART(Serial2)--> ESP32 --TCP 3333 (JSON Lines)--> Android
//
// - ESP32 が AP になる (SSID/パスワードは config.h)。スマホは 192.168.4.1:TCP_PORT に接続
// - 接続直後に {"type":"schema",...} を 1 行、以降 SEND_PERIOD_MS ごとに {"type":"data",...} を送る
// - 読み取る CAN の定義は include/can_signals.h
// - USB シリアル (115200) に 1 秒ごとに状態を表示
#include <Arduino.h>
#include <WiFi.h>

#include "bridge_rx.h"
#include "can_signals.h"
#include "config.h"
#include "signal_decoder.h"

static WiFiServer server(TCP_PORT);
static WiFiClient clients[MAX_CLIENTS];
static BridgeRx bridge;

static void acceptClients() {
  WiFiClient c = server.available();
  if (!c) return;
  for (auto &slot : clients) {
    if (!slot || !slot.connected()) {
      slot = c;
      slot.setNoDelay(true);
      String schema = decoder::schemaJson();
      slot.write((const uint8_t *)schema.c_str(), schema.length());
      Serial.printf("client connected: %s\n", c.remoteIP().toString().c_str());
      return;
    }
  }
  Serial.println("client rejected (full)");
  c.stop();
}

static int broadcast(const String &line) {
  int n = 0;
  for (auto &c : clients) {
    if (!c) continue;
    if (!c.connected()) {
      c.stop();
      continue;
    }
    c.write((const uint8_t *)line.c_str(), line.length());
    n++;
  }
  return n;
}

void setup() {
  Serial.begin(115200);
  Serial2.setRxBufferSize(4096);
  Serial2.begin(BRIDGE_BAUD, SERIAL_8N1, BRIDGE_RX_PIN, BRIDGE_TX_PIN);

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL, 0, MAX_CLIENTS);
  WiFi.setSleep(false);  // 遅延を減らす
  server.begin();
  server.setNoDelay(true);

  Serial.printf("\n=== CAN monitor ===\nAP  : %s / %s\nIP  : %s:%d\nUART: %d bps RX=%d TX=%d\n",
                AP_SSID, AP_PASS, WiFi.softAPIP().toString().c_str(), TCP_PORT, BRIDGE_BAUD,
                BRIDGE_RX_PIN, BRIDGE_TX_PIN);
}

void loop() {
  uint32_t now = millis();

  CanFrame f;
  while (Serial2.available() > 0) {
    if (bridge.feed((uint8_t)Serial2.read(), f)) decoder::onFrame(f, now);
  }

  acceptClients();

  static uint32_t lastSend = 0;
  if (now - lastSend >= SEND_PERIOD_MS) {
    lastSend = now;
    broadcast(decoder::dataJson(now, bridge.okCount, bridge.crcErrors));
  }

  static uint32_t lastDebug = 0;
  if (now - lastDebug >= DEBUG_PRINT_PERIOD_MS) {
    lastDebug = now;
    decoder::updateRates();
    int nClients = 0;
    for (auto &c : clients) nClients += (c && c.connected()) ? 1 : 0;
    Serial.printf("uart ok=%lu crc_err=%lu | %s| sta=%d tcp=%d\n", (unsigned long)bridge.okCount,
                  (unsigned long)bridge.crcErrors, decoder::summary(now).c_str(),
                  WiFi.softAPgetStationNum(), nClients);
  }

  delay(1);
}
