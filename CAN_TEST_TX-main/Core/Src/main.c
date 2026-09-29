/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.c
  * @brief          : Main program body
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2026 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/**
 * CAN FD -> UART(ESP32) bridge (この main.c 1 ファイルで完結)
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
 *   USART2 PA2(TX) -> ESP32 GPIO17 / PA3(RX) <- ESP32 GPIO16   921600bps (Bridge_Start で設定)
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
 *
 * 動作: 受信割り込みでリングバッファに積み、メインループで UART に吐き出す。
 *       バッファが溢れた場合は新しいフレームを捨てて drop をカウントする。
 *       1 秒ごとに受信統計と ID ごとの件数を USART1 に出す (診断用)。
 */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"
/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */
#include <stdio.h>
#include <string.h>
/* USER CODE END Includes */
/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */
/* USER CODE END PTD */
/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */
#define ESP_UART   huart2
#define DEBUG_UART huart1

/*
 * ESP32 への UART 速度。CubeMX の生成コードは 115200 だが、バス全体 (約 450 フレーム/秒 +
 * デバッグ ID) を流すには足りないので Bridge_Start() で上書きする (CubeMX 再生成でも消えない)。
 * ESP32-controle/include/config.h の BRIDGE_BAUD と一致させること。
 * 80MHz / 16 / 921600 -> 実際 919540bps (誤差 -0.2%)
 */
#define ESP_UART_BAUD 921600U

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

#define ID_STATS_MAX 24U /* 既存ID + デバッグ8IDを同時に診断 */
#define DEBUG_V2_COUNT 8U
#define DEBUG_V2_TIMEOUT_MS 300U
/* USER CODE END PD */
/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */
/* USER CODE END PM */
/* Private variables ---------------------------------------------------------*/
FDCAN_HandleTypeDef hfdcan1;
UART_HandleTypeDef huart1;
UART_HandleTypeDef huart2;
/* USER CODE BEGIN PV */
/* 型は MAX_PAYLOAD などの定数 (PD) を使うのでここに置く */
typedef struct {
  uint32_t id;
  uint8_t flags;
  uint8_t len;
  uint8_t data[MAX_PAYLOAD];
} CanFrame;

typedef struct {
  uint32_t id;     /* bit31 = 拡張ID */
  uint32_t count;
  uint8_t len;     /* 最後に受信したデータ長 */
  uint8_t flags;   /* 最後に受信したフレームの FLAG_* */
} IdStat;

typedef struct {
  uint32_t id;
  volatile uint32_t validCount;
  volatile uint32_t badCount;
  volatile uint32_t seqGapCount;
  volatile uint32_t duplicateCount;
  volatile uint32_t lastTick;
  volatile uint32_t lastUptime;
  volatile uint8_t lastSeq;
  volatile uint8_t seen;
} DebugV2Stat;

static CanFrame ring[RING_SIZE];
static volatile uint32_t ringHead = 0U; /* ISR が進める */
static volatile uint32_t ringTail = 0U; /* メインループが進める */

static volatile uint32_t rxCount = 0U;
static volatile uint32_t dropCount = 0U;
static volatile uint32_t canLostCount = 0U;
static volatile uint32_t lastRxTick = 0U;
static uint32_t fwdCount = 0U;
static uint32_t uartErrorCount = 0U;
static uint32_t lastStatsTick = 0U;
static uint32_t lastDropCount = 0U;

/* 診断用: 1 秒間に受信した ID ごとの件数 (ISR で集計、ログ出力後にクリア) */
static IdStat idStats[ID_STATS_MAX];
static volatile uint32_t idStatsNum = 0U;
static volatile uint32_t idStatsOverflow = 0U; /* 種類が多すぎて数えられなかった件数 */

static DebugV2Stat debugV2[DEBUG_V2_COUNT] = {
  {.id = 0x301U}, {.id = 0x302U}, {.id = 0x303U}, {.id = 0x310U},
  {.id = 0x321U}, {.id = 0x322U}, {.id = 0x323U}, {.id = 0x324U}
};

static const uint8_t dlcToLen[16] = {0, 1, 2, 3, 4, 5, 6, 7,
                                     8, 12, 16, 20, 24, 32, 48, 64};
/* USER CODE END PV */
/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_FDCAN1_Init(void);
static void MX_USART1_UART_Init(void);
static void MX_USART2_UART_Init(void);
/* USER CODE BEGIN PFP */
static void DebugPrint(const char *str);
static HAL_StatusTypeDef Bridge_Start(void);
static void Bridge_Poll(void);
/* USER CODE END PFP */
/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */
/* USER CODE END 0 */
/**
  * @brief  The application entry point.
  * @retval int
  */
int main(void)
{
  /* USER CODE BEGIN 1 */
  /* USER CODE END 1 */
  /* MCU Configuration--------------------------------------------------------*/
  /* Reset of all peripherals, Initializes the Flash interface and the Systick. */
  HAL_Init();
  /* USER CODE BEGIN Init */
  /* USER CODE END Init */
  /* Configure the system clock */
  SystemClock_Config();
  /* USER CODE BEGIN SysInit */
  /* USER CODE END SysInit */
  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_USART1_UART_Init();
  MX_USART2_UART_Init();
  DebugPrint("\r\nBOOT: UART INIT OK\r\n");
  MX_FDCAN1_Init();
  DebugPrint("BOOT: FDCAN INIT OK\r\n");
  /* USER CODE BEGIN 2 */
  if (Bridge_Start() != HAL_OK)
  {
    DebugPrint("BRIDGE ERROR: FDCAN start failed\r\n");
    Error_Handler();
  }
  DebugPrint("BOOT: FDCAN START OK\r\n");
  DebugPrint("============================\r\n");
  DebugPrint("CAN FD RX -> UART(ESP32) BRIDGE\r\n");
  DebugPrint("CAN  : FD without BRS, 2 Mbps, all IDs, LISTEN ONLY\r\n");
  DebugPrint("V2   : watch 301-303,310,321-324 / 24B / timeout 300ms\r\n");
  DebugPrint("ESP  : USART2 PA2/PA3 921600\r\n");
  DebugPrint("============================\r\n");
/* USER CODE END 2 */
  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  while (1)
  {
    Bridge_Poll();
    /* CAN 受信割り込み / SysTick まで寝る */
    __WFI();
    /* USER CODE BEGIN 3 */
  }
  /* USER CODE END 3 */
}
/**
  * @brief System Clock Configuration
  * @retval None
  */
void SystemClock_Config(void)
{
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};
  /** Configure the main internal regulator output voltage
  */
  HAL_PWREx_ControlVoltageScaling(PWR_REGULATOR_VOLTAGE_SCALE1);
  /** Initializes the RCC Oscillators according to the specified parameters
  * in the RCC_OscInitTypeDef structure.
  */
  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_HSI;
  RCC_OscInitStruct.HSIState = RCC_HSI_ON;
  RCC_OscInitStruct.HSICalibrationValue = RCC_HSICALIBRATION_DEFAULT;
  RCC_OscInitStruct.PLL.PLLState = RCC_PLL_ON;
  RCC_OscInitStruct.PLL.PLLSource = RCC_PLLSOURCE_HSI;
  RCC_OscInitStruct.PLL.PLLM = RCC_PLLM_DIV1;
  RCC_OscInitStruct.PLL.PLLN = 10;
  RCC_OscInitStruct.PLL.PLLP = RCC_PLLP_DIV2;
  RCC_OscInitStruct.PLL.PLLQ = RCC_PLLQ_DIV2;
  RCC_OscInitStruct.PLL.PLLR = RCC_PLLR_DIV2;
  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK)
  {
    Error_Handler();
  }
  /** Initializes the CPU, AHB and APB buses clocks
  */
  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK
                              |RCC_CLOCKTYPE_PCLK1|RCC_CLOCKTYPE_PCLK2;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV1;
  RCC_ClkInitStruct.APB2CLKDivider = RCC_HCLK_DIV1;
  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_2) != HAL_OK)
  {
    Error_Handler();
  }
}
/**
  * @brief FDCAN1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_FDCAN1_Init(void)
{
  /* USER CODE BEGIN FDCAN1_Init 0 */
  /* USER CODE END FDCAN1_Init 0 */
  /* USER CODE BEGIN FDCAN1_Init 1 */
  /* USER CODE END FDCAN1_Init 1 */
  hfdcan1.Instance = FDCAN1;
  hfdcan1.Init.ClockDivider = FDCAN_CLOCK_DIV1;
  hfdcan1.Init.FrameFormat = FDCAN_FRAME_FD_NO_BRS;
  hfdcan1.Init.Mode = FDCAN_MODE_BUS_MONITORING;
  hfdcan1.Init.AutoRetransmission = DISABLE;
  hfdcan1.Init.TransmitPause = DISABLE;
  hfdcan1.Init.ProtocolException = DISABLE;
  hfdcan1.Init.NominalPrescaler = 4;
  hfdcan1.Init.NominalSyncJumpWidth = 1;
  hfdcan1.Init.NominalTimeSeg1 = 7;
  hfdcan1.Init.NominalTimeSeg2 = 2;
  hfdcan1.Init.DataPrescaler = 1;
  hfdcan1.Init.DataSyncJumpWidth = 1;
  hfdcan1.Init.DataTimeSeg1 = 1;
  hfdcan1.Init.DataTimeSeg2 = 1;
  hfdcan1.Init.StdFiltersNbr = 0;
  hfdcan1.Init.ExtFiltersNbr = 0;
  hfdcan1.Init.TxFifoQueueMode = FDCAN_TX_FIFO_OPERATION;
  if (HAL_FDCAN_Init(&hfdcan1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN FDCAN1_Init 2 */
  /* USER CODE END FDCAN1_Init 2 */
}
/**
  * @brief USART1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_USART1_UART_Init(void)
{
  /* USER CODE BEGIN USART1_Init 0 */
  /* USER CODE END USART1_Init 0 */
  /* USER CODE BEGIN USART1_Init 1 */
  /* USER CODE END USART1_Init 1 */
  huart1.Instance = USART1;
  huart1.Init.BaudRate = 115200;
  huart1.Init.WordLength = UART_WORDLENGTH_8B;
  huart1.Init.StopBits = UART_STOPBITS_1;
  huart1.Init.Parity = UART_PARITY_NONE;
  huart1.Init.Mode = UART_MODE_TX_RX;
  huart1.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart1.Init.OverSampling = UART_OVERSAMPLING_16;
  huart1.Init.OneBitSampling = UART_ONE_BIT_SAMPLE_DISABLE;
  huart1.Init.ClockPrescaler = UART_PRESCALER_DIV1;
  huart1.AdvancedInit.AdvFeatureInit = UART_ADVFEATURE_NO_INIT;
  if (HAL_UART_Init(&huart1) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetTxFifoThreshold(&huart1, UART_TXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetRxFifoThreshold(&huart1, UART_RXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_DisableFifoMode(&huart1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN USART1_Init 2 */
  /* USER CODE END USART1_Init 2 */
}
/**
  * @brief USART2 Initialization Function
  * @param None
  * @retval None
  */
static void MX_USART2_UART_Init(void)
{
  /* USER CODE BEGIN USART2_Init 0 */
  /* USER CODE END USART2_Init 0 */
  /* USER CODE BEGIN USART2_Init 1 */
  /* USER CODE END USART2_Init 1 */
  huart2.Instance = USART2;
  huart2.Init.BaudRate = 115200;
  huart2.Init.WordLength = UART_WORDLENGTH_8B;
  huart2.Init.StopBits = UART_STOPBITS_1;
  huart2.Init.Parity = UART_PARITY_NONE;
  huart2.Init.Mode = UART_MODE_TX_RX;
  huart2.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart2.Init.OverSampling = UART_OVERSAMPLING_16;
  huart2.Init.OneBitSampling = UART_ONE_BIT_SAMPLE_DISABLE;
  huart2.Init.ClockPrescaler = UART_PRESCALER_DIV1;
  huart2.AdvancedInit.AdvFeatureInit = UART_ADVFEATURE_NO_INIT;
  if (HAL_UART_Init(&huart2) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetTxFifoThreshold(&huart2, UART_TXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetRxFifoThreshold(&huart2, UART_RXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_DisableFifoMode(&huart2) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN USART2_Init 2 */
  /* USER CODE END USART2_Init 2 */
}
/**
  * @brief GPIO Initialization Function
  * @param None
  * @retval None
  */
static void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};
  /* USER CODE BEGIN MX_GPIO_Init_1 */
  /* USER CODE END MX_GPIO_Init_1 */
  /* GPIO Ports Clock Enable */
  __HAL_RCC_GPIOF_CLK_ENABLE();
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOB_CLK_ENABLE();
  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOA, GPIO_PIN_4|GPIO_PIN_5|GPIO_PIN_6|GPIO_PIN_7
                          |GPIO_PIN_8, GPIO_PIN_RESET);
  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOB, GPIO_PIN_4|GPIO_PIN_5|GPIO_PIN_6, GPIO_PIN_RESET);
  /*Configure GPIO pins : PA4 PA5 PA6 PA7
                           PA8 */
  GPIO_InitStruct.Pin = GPIO_PIN_4|GPIO_PIN_5|GPIO_PIN_6|GPIO_PIN_7
                          |GPIO_PIN_8;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);
  /*Configure GPIO pins : PB4 PB5 PB6 */
  GPIO_InitStruct.Pin = GPIO_PIN_4|GPIO_PIN_5|GPIO_PIN_6;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);
  /* USER CODE BEGIN MX_GPIO_Init_2 */
  /* USER CODE END MX_GPIO_Init_2 */
}
/* USER CODE BEGIN 4 */
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

static HAL_StatusTypeDef Bridge_Start(void)
{
  ESP_UART.Init.BaudRate = ESP_UART_BAUD;
  if (HAL_UART_Init(&ESP_UART) != HAL_OK)
  {
    return HAL_ERROR;
  }

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
  if (HAL_FDCAN_ActivateNotification(&hfdcan1,
          FDCAN_IT_RX_FIFO0_NEW_MESSAGE | FDCAN_IT_RX_FIFO0_MESSAGE_LOST,
          0U) != HAL_OK)
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

static uint32_t ReadU32Le(const uint8_t *p)
{
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
         ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* V2 診断のみ。全フレームはこれとは無関係にそのまま転送する */
static void DebugV2_Observe(const FDCAN_RxHeaderTypeDef *hdr, const uint8_t *data)
{
  DebugV2Stat *stat = NULL;
  for (uint32_t i = 0U; i < DEBUG_V2_COUNT; i++)
  {
    if (debugV2[i].id == hdr->Identifier)
    {
      stat = &debugV2[i];
      break;
    }
  }
  if (stat == NULL)
  {
    return;
  }

  if (hdr->IdType != FDCAN_STANDARD_ID ||
      hdr->RxFrameType != FDCAN_DATA_FRAME ||
      hdr->FDFormat != FDCAN_FD_CAN ||
      hdr->BitRateSwitch != FDCAN_BRS_OFF ||
      hdr->DataLength != FDCAN_DLC_BYTES_24 ||
      data[0] != 2U)
  {
    stat->badCount++;
    return; /* 不正なデータで途絶判定を更新しない */
  }

  uint8_t sequence = data[1];
  uint32_t uptime = ReadU32Le(&data[4]);
  if (stat->seen != 0U)
  {
    uint8_t delta = (uint8_t)(sequence - stat->lastSeq);
    if (delta == 0U && uptime == stat->lastUptime)
    {
      stat->duplicateCount++;
      return; /* 完全な重複は新しい更新の証拠にならない */
    }
    if (delta > 1U && uptime >= stat->lastUptime)
    {
      stat->seqGapCount += (uint32_t)(delta - 1U);
    }
    /* 稼働時間の巻き戻りは送信元の再起動とみなし、欠落に数えない */
  }

  stat->lastSeq = sequence;
  stat->lastUptime = uptime;
  stat->lastTick = HAL_GetTick();
  stat->validCount++;
  stat->seen = 1U;
}

static void DebugV2_Log(void)
{
  char line[440];
  uint32_t now = HAL_GetTick();
  int used = snprintf(line, sizeof(line), "v2:");
  if (used < 0) return;

  for (uint32_t i = 0U; i < DEBUG_V2_COUNT && (size_t)used < sizeof(line); i++)
  {
    DebugV2Stat snap;
    __disable_irq();
    snap = debugV2[i];
    __enable_irq();

    const char *state = (snap.seen == 0U) ? "--" :
        ((uint32_t)(now - snap.lastTick) > DEBUG_V2_TIMEOUT_MS) ? "TO" : "OK";
    int written = snprintf(&line[used], sizeof(line) - (size_t)used,
        " %03lX:%s/s%u/v%lu/g%lu/b%lu/d%lu",
        (unsigned long)snap.id, state, (unsigned)snap.lastSeq,
        (unsigned long)snap.validCount, (unsigned long)snap.seqGapCount,
        (unsigned long)snap.badCount, (unsigned long)snap.duplicateCount);
    if (written < 0) return;
    if ((size_t)written >= sizeof(line) - (size_t)used)
    {
      used = (int)sizeof(line) - 1;
      break;
    }
    used += written;
  }
  if ((size_t)used + 2U < sizeof(line))
  {
    line[used++] = '\r';
    line[used++] = '\n';
    line[used] = '\0';
  }
  (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)line, (uint16_t)used, 100U);
}

/* 割り込みコンテキスト */
void HAL_FDCAN_RxFifo0Callback(FDCAN_HandleTypeDef *hfdcan, uint32_t RxFifo0ITs)
{
  FDCAN_RxHeaderTypeDef hdr;
  uint8_t buf[MAX_PAYLOAD];

  if ((RxFifo0ITs & FDCAN_IT_RX_FIFO0_MESSAGE_LOST) != 0U)
  {
    canLostCount++;
  }
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
    DebugV2_Observe(&hdr, buf);

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

static HAL_StatusTypeDef SendFrame(const CanFrame *f)
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

  return HAL_UART_Transmit(&ESP_UART, out, (uint16_t)n, 50U);
}

static void Bridge_Poll(void)
{
  while (ringTail != ringHead)
  {
    HAL_StatusTypeDef uartStatus =
        SendFrame(&ring[ringTail & (RING_SIZE - 1U)]);
    ringTail = ringTail + 1U;
    if (uartStatus == HAL_OK)
      fwdCount++;
    else
      uartErrorCount++;
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
    char msg[180];
    HAL_FDCAN_GetProtocolStatus(&hfdcan1, &ps);
    HAL_FDCAN_GetErrorCounters(&hfdcan1, &ec);

    HAL_GPIO_WritePin(LED_ERR_PORT, LED_ERR_PIN,
                      (ps.BusOff != 0U || ps.ErrorPassive != 0U) ? GPIO_PIN_SET : GPIO_PIN_RESET);
    uint32_t drops = dropCount;
    HAL_GPIO_WritePin(LED_OVF_PORT, LED_OVF_PIN,
                      (drops != lastDropCount) ? GPIO_PIN_SET : GPIO_PIN_RESET);
    lastDropCount = drops;

    snprintf(msg, sizeof(msg),
             "rx=%lu fwd=%lu ring_drop=%lu can_lost=%lu uart_err=%lu rec=%lu err_passive=%lu last_err=%lu data_err=%lu\r\n",
             (unsigned long)rxCount, (unsigned long)fwdCount, (unsigned long)drops,
             (unsigned long)canLostCount, (unsigned long)uartErrorCount,
             (unsigned long)ec.RxErrorCnt, (unsigned long)ps.ErrorPassive,
             (unsigned long)ps.LastErrorCode, (unsigned long)ps.DataLastErrorCode);
    (void)HAL_UART_Transmit(&DEBUG_UART, (uint8_t *)msg, (uint16_t)strlen(msg), 100U);
    LogIdStats();
    DebugV2_Log();
  }
}

static void DebugPrint(const char *str)
{
  /* ログは USART1 (ST-Link VCP) のみ。USART2 は ESP32 専用 */
  (void)HAL_UART_Transmit(&huart1, (uint8_t *)str, (uint16_t)strlen(str), 100U);
}
/* USER CODE END 4 */
/**
  * @brief  This function is executed in case of error occurrence.
  * @retval None
  */
void Error_Handler(void)
{
  /* USER CODE BEGIN Error_Handler_Debug */
  /* User can add his own implementation to report the HAL error return state */
  __disable_irq();
  while (1)
  {
  }
  /* USER CODE END Error_Handler_Debug */
}
#ifdef USE_FULL_ASSERT
/**
  * @brief  Reports the name of the source file and the source line number
  *         where the assert_param error has occurred.
  * @param  file: pointer to the source file name
  * @param  line: assert_param error line source number
  * @retval None
  */
void assert_failed(uint8_t *file, uint32_t line)
{
  /* USER CODE BEGIN 6 */
  /* User can add his own implementation to report the file name and line number,
     ex: printf("Wrong parameters value: file %s on line %d\r\n", file, line) */
  /* USER CODE END 6 */
}
#endif /* USE_FULL_ASSERT */
