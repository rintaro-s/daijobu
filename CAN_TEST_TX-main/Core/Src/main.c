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

/* USER CODE END Header */

/* Includes ------------------------------------------------------------------*/

#include "main.h"

/* Private includes ----------------------------------------------------------*/

/* USER CODE BEGIN Includes */
#include <string.h>

#include "can_uart_bridge.h"
/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/

/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/

/* USER CODE BEGIN PD */

/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/

/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/

FDCAN_HandleTypeDef hfdcan1;

UART_HandleTypeDef huart1;

UART_HandleTypeDef huart2;

/* USER CODE BEGIN PV */

/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/

void SystemClock_Config(void);

static void MX_GPIO_Init(void);

static void MX_FDCAN1_Init(void);

static void MX_USART1_UART_Init(void);

static void MX_USART2_UART_Init(void);

/* USER CODE BEGIN PFP */
static void DebugPrint(const char *str);
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

  DebugPrint("ESP  : USART2 PA2/PA3 115200\r\n");

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
