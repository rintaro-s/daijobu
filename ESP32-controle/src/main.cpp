// ESP32 : STM32 (CAN FD 受信ブリッジ) -> Wi-Fi AP -> スマホアプリ
//
//   STM32 --UART(Serial2)--> ESP32 --TCP 3333 (テキスト行)--> Android
//
// ESP32 は CAN の中身を解釈しない「転送だけ」の役割。仕様 (ID・並び・検査・途絶判定) が
// 変わっても ESP32 は書き換え不要で、読み替えはすべてスマホアプリ側 (can/CanDecoder.kt) で行う。
//
// スマホへ送る行 (1 行 = 1 メッセージ, 末尾 \n):
//   F,<ID 16進>,<flags>,<データ 16進>   CAN フレーム 1 つ。flags: bit0=拡張ID bit1=FD bit2=BRS
//   S,<稼働ms>,<UART受信OK数>,<CRCエラー数>,<起動理由>,<空きヒープ>   1 秒ごとの ESP32 状態
//
// - ESP32 が AP になる (SSID/パスワードは config.h)。スマホは 192.168.4.1:TCP_PORT に接続
// - UART は先頭 0xA5 0x5A と CRC-8 で検査し、壊れたフレームは捨てる (CRC エラー数に数える)
// - USB シリアル (115200) に 1 秒ごとに状態を表示
#include <Arduino.h>
#include <WiFi.h>
#include <errno.h>
#include <esp_system.h>
#include <lwip/sockets.h>

#include "bridge_rx.h"
#include "config.h"

static WiFiServer server(TCP_PORT);
static BridgeRx bridge;

// ---- スマホとの TCP 接続 ----
// 送信は必ずノンブロッキング (MSG_DONTWAIT)。詰まった相手のせいで UART 受信や他のスマホへの
// 配信が止まらないようにする。送るのは常に「完結した行」の塊で、途中で切れた行を作らない。
// 相手が遅いとき、送り残しが CLIENT_BACKLOG_MAX を超える分の新しい塊は捨てる (その分はアプリ側で
// SEQ の欠落として見える)。
struct PhoneClient {
  WiFiClient sock;
  String pending;             // 送り残し (完結した行の連なり)
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
      c.stuckSinceMs = 0;
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

// 完結した行の塊を相手に渡す。詰まりすぎていれば捨てる
static void sendChunk(PhoneClient &c, const String &chunk, uint32_t now) {
  if (!c.active || chunk.length() == 0) return;
  size_t remaining = c.pending.length() - c.pendingOff;
  if (remaining + chunk.length() > CLIENT_BACKLOG_MAX) {
    flushPending(c, now);  // 捨てる塊の代わりに、送り残しを進める
    return;
  }
  if (remaining == 0) {
    c.pending = chunk;
    c.pendingOff = 0;
  } else {
    c.pending += chunk;
  }
  flushPending(c, now);
}

static String statLine(uint32_t now) {
  char buf[96];
  snprintf(buf, sizeof(buf), "S,%lu,%lu,%lu,%d,%lu\n", (unsigned long)now,
           (unsigned long)bridge.okCount, (unsigned long)bridge.crcErrors, (int)esp_reset_reason(),
           (unsigned long)ESP.getFreeHeap());
  return String(buf);
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
      sendChunk(c, statLine(now), now);  // 繋がった直後に状態を 1 行送る
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

static void broadcast(const String &chunk, uint32_t now) {
  for (auto &c : clients) {
    if (!c.active) continue;
    if (!c.sock.connected()) {
      dropClient(c, "disconnected");
      continue;
    }
    sendChunk(c, chunk, now);
  }
}

// ---- UART で受けたフレームを転送用の行にして溜める ----
static String outBuf;
static uint32_t fwdCount = 0;

static void appendFrame(const CanFrame &f) {
  static const char *kHex = "0123456789ABCDEF";
  char head[24];
  uint8_t flags = (f.extended ? 1 : 0) | (f.fd ? 2 : 0) | (f.brs ? 4 : 0);
  int n = snprintf(head, sizeof(head), "F,%lX,%u,", (unsigned long)f.id, flags);
  outBuf.concat(head, n);
  char hex[2];
  for (uint8_t i = 0; i < f.len; i++) {
    hex[0] = kHex[f.data[i] >> 4];
    hex[1] = kHex[f.data[i] & 0x0F];
    outBuf.concat(hex, 2);
  }
  outBuf += '\n';
  fwdCount++;
}

void setup() {
  Serial.begin(115200);
  Serial2.setRxBufferSize(4096);
  Serial2.begin(BRIDGE_BAUD, SERIAL_8N1, BRIDGE_RX_PIN, BRIDGE_TX_PIN);
  outBuf.reserve(4096);

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL, 0, MAX_CLIENTS);
  WiFi.setSleep(false);  // 遅延を減らす
  server.begin();
  server.setNoDelay(true);

  Serial.printf("\n=== CAN monitor (forwarder) ===\nreset reason: %d (1=電源投入 3=SW 4=panic 5-7=WDT 9=ブラウンアウト)\n"
                "AP  : %s / %s (ch %d, 2.4GHz)\nIP  : %s:%d\nUART: %d bps RX=%d TX=%d\n",
                (int)esp_reset_reason(), AP_SSID, AP_PASS, AP_CHANNEL, WiFi.softAPIP().toString().c_str(),
                TCP_PORT, BRIDGE_BAUD, BRIDGE_RX_PIN, BRIDGE_TX_PIN);
}

void loop() {
  uint32_t now = millis();

  CanFrame f;
  while (Serial2.available() > 0) {
    if (bridge.feed((uint8_t)Serial2.read(), f)) appendFrame(f);
    // 溜めすぎて heap を食わないように、送る相手がいなければ捨てる
    if (outBuf.length() > 8192) outBuf.remove(0);  // 領域は使い回す (確保し直さない)
  }

  acceptClients(now);
  // 送り残しがある相手は毎ループ少しずつ送る
  for (auto &c : clients)
    if (c.active && c.pendingOff < c.pending.length()) flushPending(c, now);

  static uint32_t lastFlush = 0;
  if (now - lastFlush >= FLUSH_PERIOD_MS) {
    lastFlush = now;
    if (activeClients() > 0 && outBuf.length() > 0) broadcast(outBuf, now);
    outBuf.remove(0);  // 長さだけ 0 に戻す (確保した領域は使い回す)
  }

  static uint32_t lastStat = 0;
  if (now - lastStat >= STAT_PERIOD_MS) {
    lastStat = now;
    if (activeClients() > 0) broadcast(statLine(now), now);
  }

  static uint32_t lastDebug = 0;
  if (now - lastDebug >= DEBUG_PRINT_PERIOD_MS) {
    lastDebug = now;
    Serial.printf("up=%lus heap=%lu/min%lu uart ok=%lu crc_err=%lu fwd=%lu sta=%d tcp=%d\n",
                  (unsigned long)(now / 1000), (unsigned long)ESP.getFreeHeap(),
                  (unsigned long)ESP.getMinFreeHeap(), (unsigned long)bridge.okCount,
                  (unsigned long)bridge.crcErrors, (unsigned long)fwdCount,
                  WiFi.softAPgetStationNum(), activeClients());
  }

  delay(1);
}
