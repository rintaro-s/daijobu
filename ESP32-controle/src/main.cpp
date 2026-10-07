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
#include <errno.h>
#include <esp_system.h>
#include <lwip/sockets.h>

#include "bridge_rx.h"
#include "can_signals.h"
#include "config.h"
#include "signal_decoder.h"

static WiFiServer server(TCP_PORT);
static BridgeRx bridge;

// ---- スマホとの TCP 接続 ----
// 送信は必ずノンブロッキング (MSG_DONTWAIT)。詰まった相手のせいで UART 受信や他のスマホへの
// 配信が止まらないようにする。1 行 (JSON) を途中で切らないよう、送り残しは pending に持ち、
// 送り終わるまで新しい行はその相手には送らない (古いデータを捨てるだけで行は壊さない)。
struct PhoneClient {
  WiFiClient sock;
  String pending;           // 送り残し
  size_t pendingOff = 0;
  uint32_t stuckSinceMs = 0;  // 送り残しが出始めた時刻 (0 = 詰まっていない)
  bool active = false;
};
static PhoneClient clients[MAX_CLIENTS];

static void dropClient(PhoneClient &c, const char *why) {
  if (c.active) Serial.printf("client dropped (%s): %s\n", why, c.sock.remoteIP().toString().c_str());
  c.sock.stop();
  c.pending = String();
  c.pendingOff = 0;
  c.stuckSinceMs = 0;
  c.active = false;
}

// pending を送れるだけ送る。true = 送り切った
static bool flushPending(PhoneClient &c, uint32_t now) {
  while (c.pendingOff < c.pending.length()) {
    int fd = c.sock.fd();
    if (fd < 0) {
      dropClient(c, "closed");
      return false;
    }
    int n = ::send(fd, c.pending.c_str() + c.pendingOff, c.pending.length() - c.pendingOff, MSG_DONTWAIT);
    if (n > 0) {
      c.pendingOff += (size_t)n;
      continue;
    }
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
      if (c.stuckSinceMs == 0) c.stuckSinceMs = now;
      if (now - c.stuckSinceMs > CLIENT_STALL_TIMEOUT_MS) dropClient(c, "stalled");
      return false;
    }
    dropClient(c, "send error");  // 相手が消えた等
    return false;
  }
  c.pending = String();
  c.pendingOff = 0;
  c.stuckSinceMs = 0;
  return true;
}

static void sendLine(PhoneClient &c, const String &line, uint32_t now) {
  if (!c.active) return;
  if (!flushPending(c, now)) return;  // まだ前の行を送っている -> この行は捨てる
  if (!c.active) return;
  c.pending = line;
  c.pendingOff = 0;
  flushPending(c, now);
}

static void acceptClients(uint32_t now) {
  WiFiClient s = server.available();
  if (!s) return;
  s.setNoDelay(true);
  IPAddress ip = s.remoteIP();

  // 同じスマホから繋ぎ直してきたら古い接続は捨てる (変な切れ方をした接続で枠が埋まるのを防ぐ)
  for (auto &c : clients)
    if (c.active && c.sock.remoteIP() == ip) dropClient(c, "reconnected");

  for (auto &c : clients) {
    if (!c.active) {
      c.sock = s;
      c.active = true;
      Serial.printf("client connected: %s\n", ip.toString().c_str());
      sendLine(c, decoder::schemaJson(), now);
      return;
    }
  }
  Serial.println("client rejected (full)");
  s.stop();
}

static int activeClients() {
  int n = 0;
  for (auto &c : clients) n += c.active ? 1 : 0;
  return n;
}

static void broadcast(const String &line, uint32_t now) {
  for (auto &c : clients) {
    if (!c.active) continue;
    if (!c.sock.connected()) {
      dropClient(c, "disconnected");
      continue;
    }
    sendLine(c, line, now);
  }
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

  decoder::setBootReason((int)esp_reset_reason());
  Serial.printf("\n=== CAN monitor ===\nreset reason: %d (1=電源投入 3=SW 4=panic 5-7=WDT 9=ブラウンアウト)\nAP  : %s / %s\nIP  : %s:%d\nUART: %d bps RX=%d TX=%d\n",
                (int)esp_reset_reason(), AP_SSID, AP_PASS, WiFi.softAPIP().toString().c_str(), TCP_PORT, BRIDGE_BAUD,
                BRIDGE_RX_PIN, BRIDGE_TX_PIN);
}

void loop() {
  uint32_t now = millis();

  CanFrame f;
  while (Serial2.available() > 0) {
    if (bridge.feed((uint8_t)Serial2.read(), f)) decoder::onFrame(f, now);
  }

  acceptClients(now);
  // 送り残しがある相手は毎ループ少しずつ送る
  for (auto &c : clients)
    if (c.active && c.pendingOff < c.pending.length()) flushPending(c, now);

  static uint32_t lastSend = 0;
  if (now - lastSend >= SEND_PERIOD_MS) {
    lastSend = now;
    if (activeClients() > 0) broadcast(decoder::dataJson(now, bridge.okCount, bridge.crcErrors), now);
  }

  static uint32_t lastDebug = 0;
  if (now - lastDebug >= DEBUG_PRINT_PERIOD_MS) {
    lastDebug = now;
    decoder::updateRates();
    int nClients = activeClients();
    Serial.printf("up=%lus heap=%lu/min%lu uart ok=%lu crc_err=%lu | %s| sta=%d tcp=%d\n",
                  (unsigned long)(now / 1000), (unsigned long)ESP.getFreeHeap(),
                  (unsigned long)ESP.getMinFreeHeap(), (unsigned long)bridge.okCount,
                  (unsigned long)bridge.crcErrors, decoder::summary(now).c_str(),
                  WiFi.softAPgetStationNum(), nClients);
  }

  delay(1);
}
