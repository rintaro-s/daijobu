// ============================================================================
//  ESP32 : STM32 (CAN FD 受信ブリッジ) -> Wi-Fi AP -> スマホアプリ
//  Arduino IDE にそのまま貼り付けて書き込める単一ファイル版
//  (PlatformIO 版 src/main.cpp + bridge_rx.cpp + config.h と同じ動作・同じ通信形式)
//
//    STM32 --UART(Serial2)--> ESP32 --TCP 3333 (テキスト行)--> Android アプリ
//
//  ESP32 は CAN の中身を解釈せず「検査して転送するだけ」。読み替えはアプリ側で行う。
//
//  スマホへ送る行 (1 行 = 1 メッセージ, 末尾 \n):
//    F,<ID 16進>,<flags>,<データ 16進>   CAN フレーム 1 つ。flags: bit0=拡張ID bit1=FD bit2=BRS
//    S,<稼働ms>,<UART受信OK数>,<CRCエラー数>,<起動理由>,<空きヒープ>   1 秒ごと
//
//  Arduino IDE の設定: ボード = "ESP32 Dev Module" (esp32 by Espressif Systems)
//  シリアルモニタ 115200bps で状態が見える
//
//  注意 (Arduino IDE の自動プロトタイプ生成対策): 独自の型を引数に取る「単独の関数」を作らない。
//  型は関数より前に定義し、操作はその型のメソッドにする。
// ============================================================================
#include <WiFi.h>
#include <errno.h>
#include <esp_system.h>
#include <lwip/sockets.h>

// ---- 設定 (アプリの ConnectionConfig.kt と一致させること) ----
#define AP_SSID "CTRL-MONITOR"
#define AP_PASS "loligodrequiem"  // 8 文字以上
#define AP_CHANNEL 6              // 2.4GHz。混んでいれば 1 か 11
#define TCP_PORT 3333
#define MAX_CLIENTS 4

// STM32 の PA2(TX) -> ESP32 GPIO17、PA3(RX) <- ESP32 GPIO16
// 受信できないときは RX/TX を入れ替えて試す
#define BRIDGE_BAUD 921600  // STM32 側 (ESP_UART_BAUD) と一致させる
#define BRIDGE_RX_PIN 17
#define BRIDGE_TX_PIN 16

#define FLUSH_PERIOD_MS 10         // 受けたフレームをこの周期でまとめて送る
#define STAT_PERIOD_MS 1000        // ESP32 の状態を送る周期
#define CLIENT_BACKLOG_MAX 12288   // 1 台あたりの送り残しの上限 (バイト)。超えた分は捨てる
#define CLIENT_STALL_TIMEOUT_MS 2000  // この時間送れない相手は切断
#define DEBUG_PRINT_PERIOD_MS 1000

// ---- 型 (関数より前にすべて定義する) ----

// STM32 から UART で届く 1 フレーム
//   UART 上: [0] 0xA5 [1] 0x5A [2] flags [3..6] ID(u32 LE) [7] len [8..] data [last] CRC-8
//   CRC-8: poly 0x07, init 0, flags からデータ末尾まで
struct CanFrame {
  uint32_t id;
  bool extended;
  bool fd;
  bool brs;
  uint8_t len;
  uint8_t data[64];

  // スマホへ送る 1 行 "F,<ID 16進>,<flags>,<データ 16進>\n" を out の末尾に追加する
  void appendLine(String &out) const {
    static const char kHex[] = "0123456789ABCDEF";
    char head[24];
    uint8_t flags = (extended ? 1 : 0) | (fd ? 2 : 0) | (brs ? 4 : 0);
    int n = snprintf(head, sizeof(head), "F,%lX,%u,", (unsigned long)id, (unsigned)flags);
    out.concat(head, (unsigned int)n);
    char hex[2];
    for (uint8_t i = 0; i < len; i++) {
      hex[0] = kHex[data[i] >> 4];
      hex[1] = kHex[data[i] & 0x0F];
      out.concat(hex, 2);
    }
    out += '\n';
  }
};

// UART のバイト列からフレームを切り出す (SOF 探索 + CRC-8 検査)
class BridgeRx {
 public:
  uint32_t okCount = 0;
  uint32_t crcErrors = 0;

  // 1 バイト投入。フレームが完成したら true
  bool feed(uint8_t b, CanFrame &out) {
    switch (state_) {
      case SOF1:
        if (b == 0xA5) state_ = SOF2;
        return false;
      case SOF2:
        if (b == 0x5A) {
          state_ = BODY;
          pos_ = 0;
        } else {
          state_ = (b == 0xA5) ? SOF2 : SOF1;
        }
        return false;
      case BODY:
        break;
    }

    buf_[pos_++] = b;
    if (pos_ < 6) return false;  // flags + id(4) + len

    uint8_t len = buf_[5];
    if (len > 64) {  // 壊れたヘッダ
      crcErrors++;
      state_ = SOF1;
      return false;
    }
    if (pos_ < (size_t)6 + len + 1) return false;

    state_ = SOF1;
    if (crc8(buf_, 6 + len) != buf_[6 + len]) {
      crcErrors++;
      return false;
    }
    out.extended = (buf_[0] & 0x01) != 0;
    out.fd = (buf_[0] & 0x02) != 0;
    out.brs = (buf_[0] & 0x04) != 0;
    out.id = (uint32_t)buf_[1] | ((uint32_t)buf_[2] << 8) | ((uint32_t)buf_[3] << 16) | ((uint32_t)buf_[4] << 24);
    out.len = len;
    memcpy(out.data, &buf_[6], len);
    okCount++;
    return true;
  }

 private:
  enum State { SOF1, SOF2, BODY };
  State state_ = SOF1;
  uint8_t buf_[1 + 4 + 1 + 64 + 1];
  size_t pos_ = 0;

  static uint8_t crc8(const uint8_t *p, size_t n) {
    uint8_t crc = 0;
    while (n--) {
      crc ^= *p++;
      for (int i = 0; i < 8; i++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
    }
    return crc;
  }
};

// スマホ 1 台分の TCP 接続。
// 送信は必ずノンブロッキング。詰まった相手のせいで UART 受信や他のスマホへの配信が止まらないようにする。
// 送るのは常に「完結した行」の塊で、途中で切れた行を作らない。
struct PhoneClient {
  WiFiClient sock;
  String pending;             // 送り残し (完結した行の連なり)
  size_t pendingOff = 0;
  uint32_t stuckSinceMs = 0;  // 送り残しが出始めた時刻 (0 = 詰まっていない)
  bool active = false;

  void drop(const char *why) {
    if (active) Serial.printf("client dropped (%s): %s\n", why, sock.remoteIP().toString().c_str());
    sock.stop();
    pending = String();
    pendingOff = 0;
    stuckSinceMs = 0;
    active = false;
  }

  // pending を送れるだけ送る。true = 送り切った
  bool flush(uint32_t now) {
    while (pendingOff < pending.length()) {
      int fd = sock.fd();
      if (fd < 0) {
        drop("closed");
        return false;
      }
      int n = lwip_send(fd, pending.c_str() + pendingOff, pending.length() - pendingOff, MSG_DONTWAIT);
      if (n > 0) {
        pendingOff += (size_t)n;
        stuckSinceMs = 0;
        continue;
      }
      if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        if (stuckSinceMs == 0) stuckSinceMs = now;
        if (now - stuckSinceMs > CLIENT_STALL_TIMEOUT_MS) drop("stalled");
        return false;
      }
      drop("send error");  // 相手が消えた等
      return false;
    }
    pending = String();
    pendingOff = 0;
    stuckSinceMs = 0;
    return true;
  }

  // 完結した行の塊を渡す。詰まりすぎていれば捨てる
  void push(const String &chunk, uint32_t now) {
    if (!active || chunk.length() == 0) return;
    size_t remaining = pending.length() - pendingOff;
    if (remaining + chunk.length() > CLIENT_BACKLOG_MAX) {
      flush(now);  // 詰まりすぎ: この塊は捨てて、送り残しを進める
      return;
    }
    if (remaining == 0) {
      pending = chunk;
      pendingOff = 0;
    } else {
      pending += chunk;
    }
    flush(now);
  }

  bool hasBacklog() const { return active && pendingOff < pending.length(); }
};

// ---- グローバル ----
static WiFiServer server(TCP_PORT);
static BridgeRx bridge;
static PhoneClient clients[MAX_CLIENTS];
static String outBuf;            // UART で受けたフレームを行にして溜める
static uint32_t fwdCount = 0;

// ---- 関数 (引数・戻り値は基本型と String だけ) ----
static String statLine(uint32_t now) {
  char buf[96];
  snprintf(buf, sizeof(buf), "S,%lu,%lu,%lu,%d,%lu\n", (unsigned long)now, (unsigned long)bridge.okCount,
           (unsigned long)bridge.crcErrors, (int)esp_reset_reason(), (unsigned long)ESP.getFreeHeap());
  return String(buf);
}

static int activeClients() {
  int n = 0;
  for (int i = 0; i < MAX_CLIENTS; i++) n += clients[i].active ? 1 : 0;
  return n;
}

static void broadcast(const String &chunk, uint32_t now) {
  for (int i = 0; i < MAX_CLIENTS; i++) {
    if (!clients[i].active) continue;
    if (!clients[i].sock.connected()) {
      clients[i].drop("disconnected");
      continue;
    }
    clients[i].push(chunk, now);
  }
}

static void acceptClients(uint32_t now) {
  WiFiClient s = server.available();
  if (!s) return;
  s.setNoDelay(true);
  IPAddress ip = s.remoteIP();

  // 同じスマホから繋ぎ直してきたら古い接続は捨てる (変な切れ方をした接続で枠が埋まるのを防ぐ)
  for (int i = 0; i < MAX_CLIENTS; i++)
    if (clients[i].active && clients[i].sock.remoteIP() == ip) clients[i].drop("reconnected");

  for (int i = 0; i < MAX_CLIENTS; i++) {
    if (!clients[i].active) {
      clients[i].sock = s;
      clients[i].active = true;
      Serial.printf("client connected: %s\n", ip.toString().c_str());
      clients[i].push(statLine(now), now);
      return;
    }
  }
  Serial.println("client rejected (full)");
  s.stop();
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

  Serial.printf("\n=== CAN monitor (forwarder) ===\nreset reason: %d (1=power-on 3=SW 4=panic 5-7=WDT 9=brownout)\n"
                "AP  : %s / %s (ch %d, 2.4GHz)\nIP  : %s:%d\nUART: %d bps RX=%d TX=%d\n",
                (int)esp_reset_reason(), AP_SSID, AP_PASS, AP_CHANNEL, WiFi.softAPIP().toString().c_str(),
                TCP_PORT, BRIDGE_BAUD, BRIDGE_RX_PIN, BRIDGE_TX_PIN);
}

void loop() {
  uint32_t now = millis();

  CanFrame f;
  while (Serial2.available() > 0) {
    if (bridge.feed((uint8_t)Serial2.read(), f)) {
      f.appendLine(outBuf);
      fwdCount++;
    }
    if (outBuf.length() > 8192) outBuf.remove(0);  // 溜めすぎ防止 (領域は使い回す)
  }

  acceptClients(now);
  for (int i = 0; i < MAX_CLIENTS; i++)
    if (clients[i].hasBacklog()) clients[i].flush(now);

  static uint32_t lastFlush = 0;
  if (now - lastFlush >= FLUSH_PERIOD_MS) {
    lastFlush = now;
    if (activeClients() > 0 && outBuf.length() > 0) broadcast(outBuf, now);
    outBuf.remove(0);
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
                  (unsigned long)bridge.crcErrors, (unsigned long)fwdCount, WiFi.softAPgetStationNum(),
                  activeClients());
  }

  delay(1);
}
