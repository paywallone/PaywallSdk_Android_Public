#!/usr/bin/env bash
# Kullanım: ./run.sh
# Bağlı cihaz ya da açık emülatör yoksa emülatörü başlatır, ardından uygulamayı kurup açar.
set -euo pipefail

TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"

# Gradle için Java: önce JDK 17, yoksa Android Studio'nun içindeki Java.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in "$HOME"/Library/Java/JavaVirtualMachines/jbr-17*/Contents/Home \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi

if [[ -z "$("$ADB" devices | awk 'NR > 1 && $2 == "device"')" ]]; then
    AVD="$("$EMULATOR" -list-avds | head -1)"
    if [[ -z "$AVD" ]]; then
        echo "Emülatör bulunamadı. Android Studio > Device Manager'dan bir sanal cihaz oluşturun." >&2
        exit 1
    fi
    echo "Emülatör açılıyor: $AVD (ilk açılış 1-2 dakika sürebilir)"
    # Herkese açık DNS: Mac'in DNS'i (ör. VPN'den kalan) emülatörden erişilemez olabiliyor.
    nohup "$EMULATOR" -avd "$AVD" -dns-server 8.8.8.8,1.1.1.1 >/dev/null 2>&1 &
    "$ADB" wait-for-device
    until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
fi

echo "Uygulama kuruluyor..."
"$TOOL_DIR/gradlew" -p "$TOOL_DIR" -q :app:installDebug
"$ADB" shell am start -n paywall.testtool/.MainActivity >/dev/null
echo "Hazır: emülatörde \"Paywall SDK Test\" uygulaması açıldı."
