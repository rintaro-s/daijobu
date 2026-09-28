#!/usr/bin/env bash
# CAN モニタ一式のビルド / 書き込み
#
#   ./build.sh        メニューから選ぶ
#   ./build.sh 1      番号を直接指定してもよい
#
#   1) STM32   (CAN_TEST_TX-main)   make                    -> st-flash
#   2) ESP32   (ESP32-controle)     pio run                 -> pio run -t upload
#   3) Android (Android-controler)  gradlew assembleDebug   -> adb install
#
# ビルド後に「書き込みもする？」と聞くので、y で書き込み (Enter だけならビルドのみ)。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

STM_DIR="$ROOT/CAN_TEST_TX-main"
ESP_DIR="$ROOT/ESP32-controle"
AND_DIR="$ROOT/Android-controler"

choice="${1:-}"
if [[ -z "$choice" ]]; then
  echo "何をビルドする？"
  echo "  1) STM32   (CAN 受信 -> UART)"
  echo "  2) ESP32   (UART -> Wi-Fi)"
  echo "  3) Android (表示アプリ)"
  read -rp "番号 [1-3]: " choice
fi

ask_flash() {
  local ans
  read -rp "$1 [y/N]: " ans
  [[ "$ans" == "y" || "$ans" == "Y" ]]
}

case "$choice" in
  1)
    echo "=== STM32 ビルド ==="
    make -C "$STM_DIR" -j"$(nproc)"
    if ask_flash "ST-Link で書き込む？"; then
      st-flash --connect-under-reset --reset write "$STM_DIR/build/curearcanashadowTX.bin" 0x08000000
    fi
    ;;
  2)
    echo "=== ESP32 ビルド ==="
    (cd "$ESP_DIR" && pio run)
    if ask_flash "USB で書き込む？"; then
      (cd "$ESP_DIR" && pio run -t upload)
      if ask_flash "シリアルモニタを開く？"; then
        (cd "$ESP_DIR" && pio device monitor)
      fi
    fi
    ;;
  3)
    echo "=== Android ビルド ==="
    (cd "$AND_DIR" && ./gradlew assembleDebug)
    echo "APK: $AND_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if ask_flash "USB 接続したスマホにインストールして起動する？"; then
      (cd "$AND_DIR" && ./gradlew installDebug)
      adb shell am start -n com.nbks.controoooooool/.MainActivity
    fi
    ;;
  *)
    echo "1-3 で選んでね" >&2
    exit 1
    ;;
esac
