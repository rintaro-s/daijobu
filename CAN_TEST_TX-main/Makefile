# STM32G441KB  CAN FD -> UART(ESP32) bridge (Core/Src)
#   make            : build/<TARGET>.elf/.hex/.bin を生成
#   make flash      : st-flash で書き込み
#   make clean
#
# arm-none-eabi-gcc は STM32CubeIDE 同梱 (GCC 13.3) -> PATH -> PlatformIO 同梱 の順で探す。
# 明示したい場合: make GCC_PATH=/path/to/bin

TARGET    := curearcanashadowTX
BUILD_DIR := build

CUBEIDE_GCC := /home/rinta/st/stm32cubeide_1.19.0/plugins/com.st.stm32cube.ide.mcu.externaltools.gnu-tools-for-stm32.13.3.rel1.linux64_1.0.0.202410170706/tools/bin

ifdef GCC_PATH
PREFIX := $(GCC_PATH)/arm-none-eabi-
else ifneq ($(wildcard $(CUBEIDE_GCC)/arm-none-eabi-gcc),)
PREFIX := $(CUBEIDE_GCC)/arm-none-eabi-
else ifneq ($(shell command -v arm-none-eabi-gcc 2>/dev/null),)
PREFIX := arm-none-eabi-
else
PREFIX := $(HOME)/.platformio/packages/toolchain-gccarmnoneeabi/bin/arm-none-eabi-
endif

CC      := $(PREFIX)gcc
AS      := $(PREFIX)gcc -x assembler-with-cpp
OBJCOPY := $(PREFIX)objcopy
SIZE    := $(PREFIX)size

# CubeMX 生成の Core/Src (処理はすべて main.c の USER CODE 内) をビルドする。
# src/main.c は pio 用の旧 TX テストなので使わない。
C_SOURCES := $(wildcard Core/Src/*.c) $(wildcard Drivers/STM32G4xx_HAL_Driver/Src/*.c)
ASM_SOURCES := Core/Startup/startup_stm32g441kbtx.s
LDSCRIPT := STM32G441KBTX_FLASH.ld

MCU  := -mcpu=cortex-m4 -mthumb -mfpu=fpv4-sp-d16 -mfloat-abi=hard
DEFS := -DUSE_HAL_DRIVER -DSTM32G441xx
INCS := -ICore/Inc \
        -IDrivers/STM32G4xx_HAL_Driver/Inc \
        -IDrivers/STM32G4xx_HAL_Driver/Inc/Legacy \
        -IDrivers/CMSIS/Device/ST/STM32G4xx/Include \
        -IDrivers/CMSIS/Include
OPT ?= -Os -g

CFLAGS  := $(MCU) $(DEFS) $(INCS) $(OPT) -std=gnu11 -Wall -ffunction-sections -fdata-sections -MMD -MP
ASFLAGS := $(MCU) $(OPT)
LDFLAGS := $(MCU) -T$(BUILD_DIR)/linker.ld --specs=nano.specs --specs=nosys.specs \
           -Wl,-Map=$(BUILD_DIR)/$(TARGET).map,--cref -Wl,--gc-sections \
           -Wl,--start-group -lc -lm -Wl,--end-group

OBJECTS := $(addprefix $(BUILD_DIR)/,$(C_SOURCES:.c=.o)) \
           $(addprefix $(BUILD_DIR)/,$(ASM_SOURCES:.s=.o))

all: $(BUILD_DIR)/$(TARGET).elf $(BUILD_DIR)/$(TARGET).hex $(BUILD_DIR)/$(TARGET).bin

$(BUILD_DIR)/%.o: %.c Makefile
	@mkdir -p $(dir $@)
	$(CC) -c $(CFLAGS) $< -o $@

$(BUILD_DIR)/%.o: %.s Makefile
	@mkdir -p $(dir $@)
	$(AS) -c $(ASFLAGS) $< -o $@

# CubeIDE の ld スクリプトは (READONLY) を使うが、古い binutils (gcc 11 以前) は非対応なので除去して使う
GCC_MAJOR := $(shell $(CC) -dumpversion | cut -d. -f1)
$(BUILD_DIR)/linker.ld: $(LDSCRIPT) Makefile
	@mkdir -p $(dir $@)
	if [ $(GCC_MAJOR) -lt 12 ]; then sed 's/(READONLY)//' $< > $@; else cp $< $@; fi

$(BUILD_DIR)/$(TARGET).elf: $(OBJECTS) $(BUILD_DIR)/linker.ld
	$(CC) $(OBJECTS) $(LDFLAGS) -o $@
	$(SIZE) $@

$(BUILD_DIR)/%.hex: $(BUILD_DIR)/%.elf
	$(OBJCOPY) -O ihex $< $@

$(BUILD_DIR)/%.bin: $(BUILD_DIR)/%.elf
	$(OBJCOPY) -O binary -S $< $@

flash: $(BUILD_DIR)/$(TARGET).bin
	st-flash --connect-under-reset --reset write $< 0x08000000

clean:
	rm -rf $(BUILD_DIR)

-include $(OBJECTS:.o=.d)

.PHONY: all flash clean
