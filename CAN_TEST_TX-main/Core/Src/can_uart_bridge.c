/**
 * CAN FD -> UART(ESP32) bridge (仕様は can_uart_bridge.h 参照)
 *
 * 受信割り込みでリングバッファに積み、メインループで UART に吐き出す。
 * バッファが溢れた場合は新しいフレームを捨てて drop をカウントする。
 */
#include "can_uart_bridge.h"

#include <stdio.h>
#include <string.h>

extern FDCAN_HandleTypeDef hfdcan1;
extern UART_HandleTypeDef huart1;
extern UART_HandleTypeDef huart2;

#define ESP_UART   huart2
#define DEBUG_UART huart1

#define STATS_PERIOD_MS  1000U
#define RX_LED_HOLD_MS   100U

#define LED_RX_PORT  GPIOA
#define LED_RX_PIN   GPIO_PIN_6 /* LED1_G */
#define LED_ERR_PORT GPIOA
#define LED_ERR_PIN  GPIO_PIN_5 /* LED1_R */
#define LED_OVF_PORT GPIOA
#define LED_OVF_PIN  GPIO_PIN_4 /* LED1_B */

#define SOF1        0xA5U
#define SOF2        0x5AU
#define FLAG_EXT_ID 0x01U
#define FLAG_FD     0x02U
#define FLAG_BRS    0x04U

#define RING_SIZE   32U /* 2 の累乗 */
#define MAX_PAYLOAD 64U

typedef struct {
  uint32_t id;
  uint8_t flags;
  uint8_t len;
  uint8_t data[MAX_PAYLOAD];
} CanFrame;

static CanFrame ring[RING_SIZE];
static volatile uint32_t ringHead = 0U; /* ISR が進める */
static volatile uint32_t ringTail = 0U; /* メインループが進める */

static volatile uint32_t rxCount = 0U;
static volatile uint32_t dropCount = 0U;
static volatile uint32_t lastRxTick = 0U;
static uint32_t fwdCount = 0U;
static uint32_t lastStatsTick = 0U;
static uint32_t lastDropCount = 0U;

/* 診断用: 1 秒間に受信した ID ごとの件数 (ISR で集計、ログ出力後にクリア) */
#define ID_STATS_MAX 16U
typedef struct {
  uint32_t id;     /* bit31 = 拡張ID */
  uint32_t count;
  uint8_t len;     /* 最後に受信したデータ長 */
  uint8_t flags;   /* 最後に受信したフレームの FLAG_* */
} IdStat;
static IdStat idStats[ID_STATS_MAX];
static volatile uint32_t idStatsNum = 0U;
static volatile uint32_t idStatsOverflow = 0U; /* 種類が多すぎて数えられなかった件数 */

static const uint8_t dlcToLen[16] = {0, 1, 2, 3, 4, 5, 6, 7,
                                     8, 12, 16, 20, 24, 32, 48, 64};

static uint8_t Crc8(const uint8_t *p, uint32_t n)
{
  uint8_t crc = 0x00U;
  while (n-- > 0U)
  {
    crc ^= *p++;
    for (uint8_t i = 0U; i < 8U; i++)
    {
      crc = (crc & 0x80U) ? (uint8_t)((crc << 1) ^ 0x07U) : (uint8_t)(crc << 1);
    }
  }
  return crc;
}

HAL_StatusTypeDef Bridge_Start(void)
{
  /*
   * 既存の CAN バスに一切影響を与えないよう、バスモニタ (リッスンオンリー) モードに固定する。
   * このモードでは ACK・エラーフレーム・送信のいずれもハードウェアレベルで出ない。
   * CubeMX で再生成して Mode が戻っても、ここで強制的にモニタモードにする。
   */
  if (hfdcan1.Init.Mode != FDCAN_MODE_BUS_MONITORING)
  {
    hfdcan1.Init.Mode = FDCAN_MODE_BUS_MONITORING;
    if (HAL_FDCAN_Init(&hfdcan1) != HAL_OK)
    {
      return HAL_ERROR;
    }
  }
  /* 念のためレジスタでも確認し、モニタモードでなければ CAN を起動しない */
  if (READ_BIT(hfdcan1.Instance->CCCR, FDCAN_CCCR_MON) == 0U)
  {
    return HAL_ERROR;
  }

  /* フィルタ無し: 全 ID を FIFO0 へ。リモートフレームは捨てる */
  if (HAL_FDCAN_ConfigGlobalFilter(&hfdcan1, FDCAN_ACCEPT_IN_RX_FIFO0,
                                   FDCAN_ACCEPT_IN_RX_FIFO0, FDCAN_REJECT_REMOTE,
                                   FDCAN_REJECT_REMOTE) != HAL_OK)
  {
    return HAL_ERROR;
  }
  /* 取りこぼすなら古い方を捨てる */
  if (HAL_FDCAN_ConfigRxFifoOverwrite(&hfdcan1, FDCAN_RX_FIFO0,
                                      FDCAN_RX_FIFO_OVERWRITE) != HAL_OK)
  {
    return HAL_ERROR;
  }
  if (HAL_FDCAN_ActivateNotification(&hfdcan1, FDCAN_IT_RX_FIFO0_NEW_MESSAGE, 0U) != HAL_OK)
  {
    return HAL_ERROR;
  }
  if (HAL_FDCAN_Start(&hfdcan1) != HAL_OK)
  {
    return HAL_ERROR;
  }

  lastStatsTick = HAL_GetTick();
  return HAL_OK;
}

/* 割り込みコンテキスト: ID ごとの受信件数を数える */
static void CountId(const FDCAN_RxHeaderTypeDef *hdr)
{
  uint32_t key = hdr->Identifier | ((hdr->IdType == FDCAN_EXTENDED_ID) ? 0x80000000U : 0U);
  uint8_t flags = 0U;
  if (hdr->IdType == FDCAN_EXTENDED_ID) flags |= FLAG_EXT_ID;
  if (hdr->FDFormat == FDCAN_FD_CAN) flags |= FLAG_FD;
  if (hdr->BitRateSwitch == FDCAN_BRS_ON) flags |= FLAG_BRS;

  uint32_t n = idStatsNum;
  for (uint32_t i = 0U; i < n; i++)
  {
    if (idStats[i].id == key)
    {
      idStats[i].count++;
      idStats[i].len = dlcToLen[hdr->DataLength & 0x0FU];
      idStats[i].flags = flags;
      return;
    }
  }
  if (n < ID_STATS_MAX)
  {
    idStats[n].id = key;
    idStats[n].count = 1U;
    idStats[n].len = dlcToLen[hdr->DataLength & 0x0FU];
    idStats[n].flags = flags;
    idStatsNum = n + 1U;
  }
  else
  {
    idStatsOverflow++;
  }
}

/* 1 秒分の ID 集計をログに出してクリアする */
static void LogIdStats(void)
{
  IdStat snap[ID_STATS_MAX];
  uint32_t n;
  uint32_t overflow;

  __disable_irq();
  n = idStatsNum;
  memcpy(snap, idStats, n * sizeof(IdStat));
  overflow = idStatsOverflow;
  idStatsNum = 0U;
  idStatsOverflow = 0U;
  __enable_irq();

  char line[64];
  (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)"ids:", 4U, 100U);
  if (n == 0U)
  {
    (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)" (none)", 7U, 100U);
  }
  for (uint32_t i = 0U; i < n; i++)
  {
    /* 例: 0x301x10/24B/FD  (拡張IDは X、BRS ON は +BRS) */
    snprintf(line, sizeof(line), " 0x%03lX%sx%lu/%uB/%s%s",
             (unsigned long)(snap[i].id & 0x1FFFFFFFU),
             (snap[i].flags & FLAG_EXT_ID) ? "X" : "",
             (unsigned long)snap[i].count, (unsigned)snap[i].len,
             (snap[i].flags & FLAG_FD) ? "FD" : "CC",
             (snap[i].flags & FLAG_BRS) ? "+BRS" : "");
    (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)line, (uint16_t)strlen(line), 100U);
  }
  if (overflow != 0U)
  {
    snprintf(line, sizeof(line), " (+%lu other)", (unsigned long)overflow);
    (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)line, (uint16_t)strlen(line), 100U);
  }
  (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)"\r\n", 2U, 100U);
}

/* 割り込みコンテキスト */
void HAL_FDCAN_RxFifo0Callback(FDCAN_HandleTypeDef *hfdcan, uint32_t RxFifo0ITs)
{
  FDCAN_RxHeaderTypeDef hdr;
  uint8_t buf[MAX_PAYLOAD];

  if ((RxFifo0ITs & FDCAN_IT_RX_FIFO0_NEW_MESSAGE) == 0U)
  {
    return;
  }

  while (HAL_FDCAN_GetRxFifoFillLevel(hfdcan, FDCAN_RX_FIFO0) > 0U)
  {
    if (HAL_FDCAN_GetRxMessage(hfdcan, FDCAN_RX_FIFO0, &hdr, buf) != HAL_OK)
    {
      break;
    }
    rxCount++;
    lastRxTick = HAL_GetTick();
    CountId(&hdr);

    uint32_t head = ringHead;
    if ((uint32_t)(head - ringTail) >= RING_SIZE)
    {
      dropCount++;
      continue;
    }

    CanFrame *f = &ring[head & (RING_SIZE - 1U)];
    f->id = hdr.Identifier;
    f->flags = 0U;
    if (hdr.IdType == FDCAN_EXTENDED_ID) f->flags |= FLAG_EXT_ID;
    if (hdr.FDFormat == FDCAN_FD_CAN) f->flags |= FLAG_FD;
    if (hdr.BitRateSwitch == FDCAN_BRS_ON) f->flags |= FLAG_BRS;
    f->len = dlcToLen[hdr.DataLength & 0x0FU];
    memcpy(f->data, buf, f->len);

    ringHead = head + 1U;
  }
}

static void SendFrame(const CanFrame *f)
{
  uint8_t out[2 + 1 + 4 + 1 + MAX_PAYLOAD + 1];
  uint32_t n = 0U;

  out[n++] = SOF1;
  out[n++] = SOF2;
  out[n++] = f->flags;
  out[n++] = (uint8_t)(f->id);
  out[n++] = (uint8_t)(f->id >> 8);
  out[n++] = (uint8_t)(f->id >> 16);
  out[n++] = (uint8_t)(f->id >> 24);
  out[n++] = f->len;
  memcpy(&out[n], f->data, f->len);
  n += f->len;
  out[n] = Crc8(&out[2], n - 2U);
  n++;

  (void)HAL_UART_Transmit(&ESP_UART, out, (uint16_t)n, 50U);
}

void Bridge_Poll(void)
{
  while (ringTail != ringHead)
  {
    SendFrame(&ring[ringTail & (RING_SIZE - 1U)]);
    ringTail = ringTail + 1U;
    fwdCount++;
  }

  uint32_t now = HAL_GetTick();

  HAL_GPIO_WritePin(LED_RX_PORT, LED_RX_PIN,
                    ((uint32_t)(now - lastRxTick) < RX_LED_HOLD_MS && rxCount > 0U)
                        ? GPIO_PIN_SET : GPIO_PIN_RESET);

  if ((uint32_t)(now - lastStatsTick) >= STATS_PERIOD_MS)
  {
    lastStatsTick += STATS_PERIOD_MS;

    FDCAN_ProtocolStatusTypeDef ps;
    FDCAN_ErrorCountersTypeDef ec;
    char msg[128];
    HAL_FDCAN_GetProtocolStatus(&hfdcan1, &ps);
    HAL_FDCAN_GetErrorCounters(&hfdcan1, &ec);

    HAL_GPIO_WritePin(LED_ERR_PORT, LED_ERR_PIN,
                      (ps.BusOff != 0U || ps.ErrorPassive != 0U) ? GPIO_PIN_SET : GPIO_PIN_RESET);
    uint32_t drops = dropCount;
    HAL_GPIO_WritePin(LED_OVF_PORT, LED_OVF_PIN,
                      (drops != lastDropCount) ? GPIO_PIN_SET : GPIO_PIN_RESET);
    lastDropCount = drops;

    snprintf(msg, sizeof(msg),
             "rx=%lu fwd=%lu drop=%lu rec=%lu err_passive=%lu last_err=%lu data_err=%lu\r\n",
             (unsigned long)rxCount, (unsigned long)fwdCount, (unsigned long)drops,
             (unsigned long)ec.RxErrorCnt, (unsigned long)ps.ErrorPassive,
             (unsigned long)ps.LastErrorCode, (unsigned long)ps.DataLastErrorCode);
    (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)msg, (uint16_t)strlen(msg), 100U);
    LogIdStats();
  }
}
