# AI Passport Notification Portal

**English** | [简体中文](README.zh_CN.md) · [Detailed setup](APP_README.en.md) · [中文部署说明](APP_README.md)

An ESP32-C3 firmware and Android companion app that turn the FoloToy AI Passport into a private notification display and a small firmware portal.

Selected Android notifications—such as WeChat, Feishu/Lark and SMS—are forwarded over an authenticated, encrypted BLE connection. The device keeps recent messages for manual browsing, wakes its screen when a new message arrives, reconnects automatically after the initial pairing, and can install one compatible user application without replacing the resident portal.

> This is a community derivative built on the [FoloToy AI Passport](https://gitee.com/FoloToy/ai-passport) BSP. It is not an official FoloToy release.

## Highlights

- **Android notification bridge**: forwards only the apps selected by the user.
- **No cloud relay**: notification text and firmware data travel directly between the phone and the AI Passport over BLE.
- **Persistent browsing session**: up to 32 recent notifications are retained in device RAM across all apps; messages do not disappear on a timer.
- **Source filters**: cycle between all messages and individual apps, then clear only the current app when needed.
- **Automatic reconnect**: the foreground service retries after app launch, phone boot, APK update and Bluetooth being turned back on.
- **Screen power management**: new notifications wake the display; 60 seconds of inactivity turns off the LCD/backlight while BLE stays active.
- **Firmware library**: the Android app can import multiple compatible ESP32-C3 application images and install one image into the device's 4 MiB user slot.
- **Resident factory portal**: the notification portal lives in the factory partition and is not overwritten by normal user-slot installation.

## Controls

### Notification view

| Input | Action |
|---|---|
| Up | Previous message in the active source filter |
| Down | Next message in the active source filter |
| OK | Cycle through all messages and per-app filters; never deletes a message |
| Hold Up for 3 seconds | Ask to clear messages for the active app; press OK to confirm |
| Hold Down for 3 seconds | Toggle notification sound mute |
| Hold OK for 3 seconds | Run the device self-test |
| Double-press Down | Turn off the screen immediately |
| Double-press OK | Open or close the firmware portal |

### Firmware portal

| Input | Action |
|---|---|
| Up / Down | Select the resident notification portal or the installed user app |
| OK | Return to notifications |
| Hold OK for 3 seconds | Start the selected user app |

A sleeping screen consumes the first button action only to wake. A new notification wakes it automatically.

## System architecture

```text
Android notification listener
        │
        │ selected notifications / firmware chunks
        ▼
Android foreground BLE service
        │  authenticated + encrypted GATT
        ▼
AI Passport factory portal
        ├── notification list and UI
        ├── sound, buttons and screen sleep
        └── 4 MiB user application slot
```

The Android app uses standard Android APIs. Samsung phones, including the S25 Ultra used during development, are treated as ordinary Android devices; no Samsung-specific SDK is required.

## Quick start

### Requirements

- FoloToy AI Passport with ESP32-C3, 8 MiB flash and no PSRAM
- ESP-IDF 5.5.x; this project was built with ESP-IDF 5.5.3
- Android Studio/SDK with compile SDK 36
- JDK 17
- Android 9 (API 28) or newer with BLE

### Build the resident portal

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
idf.py set-target esp32c3
idf.py -B build-slot-portal build
```

Do **not** convert the partitioned output into a gap-padded image and flash it from `0x0`. That can overwrite NVS, BLE keys, `cardid` and the user slot. Use the offset-aware command and backup guidance in [APP_README.en.md](APP_README.en.md).

### Build the Android app

```sh
cd android-app
JAVA_HOME=/path/to/jdk17 \
ANDROID_HOME=/path/to/android-sdk \
./gradlew clean assembleDebug lintDebug
```

Debug APK output:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

### First connection

1. Install the APK and allow **Nearby devices** and app notifications.
2. Open Android's **Notification access** settings and enable **AI Passport Portal**.
3. Turn on the AI Passport and complete the first secure Bluetooth pairing.
4. In **Manage apps**, select the apps whose notifications may be forwarded.
5. Use **Send test message** before relying on real notifications.

After the first secure pairing, the app normally reconnects by itself. Android's **Force stop** disables background receivers until the app is opened again, so it should not be used as an auto-connect test.

## Notification and privacy behavior

- The 32-message limit is **shared by all selected apps**, not 32 per app.
- Messages live only in AI Passport RAM and are cleared by reboot or explicit per-app deletion.
- SMS body and one-time-code display is disabled by default. When disabled, SMS content is hidden and standalone 4–8 digit numbers in ordinary notifications are masked.
- BLE notification payloads are limited to 160 UTF-8 bytes. Images, avatars and attachments are not transferred.
- Notifications missed while the phone and device are disconnected are not guaranteed to be replayed.
- Full-flash backups may contain Wi-Fi credentials, BLE bonding keys and device identifiers. `.local-backups/` is intentionally excluded from Git.

## User firmware slot

The phone may store several `.bin` files, but the current partition map exposes **one 4 MiB user application slot** on the device. Installing another app replaces only that slot, not the resident notification portal.

The importer checks the ESP application header, ESP32-C3 chip identifier, image size and SHA-256. This does not prove board-level compatibility: install only application images built for the AI Passport BSP. Never select a `full.bin`, bootloader or partition-table image.

The project was tested with a user-slot build derived from [weibaohui/aipassport-radio](https://github.com/weibaohui/aipassport-radio). Third-party source code and binaries remain subject to their respective upstream licenses and compatibility constraints.

## Repository layout

```text
android-app/                 Android notification bridge and firmware manager
components/bsp/              Reusable display, button, audio, battery and I²C BSP
main/                        Resident portal, BLE protocol, message store and UI
tests/                       Lightweight host-side message/UI tests
tools/macos_ble_test.swift   Desktop BLE test sender
docs/                        Hardware reference and troubleshooting notes
partitions.csv               Factory portal plus 4 MiB user-slot layout
APP_README*.md               Detailed build, flash, recovery and validation notes
```

## Validation snapshot

As of **October 9, 2026**:

- The resident portal `1.2.1-portal` builds successfully with ESP-IDF 5.5.3.
- Android app `1.3.1` (`versionCode 5`) builds and passes `lintDebug`.
- Automatic encrypted reconnect after toggling phone Bluetooth was observed on a Samsung S25 Ultra.
- Notification navigation, display sleep/wake, app filtering, per-app clearing and user-firmware transfer were exercised on the physical device; the user reported the tested flow working normally.
- Host tests for the message store and UI calculations pass.

Still treat these as separate checks:

- A phone-side “installation complete” result is not a byte-for-byte flash readback.
- The final Radio slot readback was interrupted before completion.
- Automatic rollback from every third-party user app to the factory portal depends on that app's OTA behavior and must be verified on hardware.

See [APP_README.en.md](APP_README.en.md) for exact image sizes, hashes, partition offsets and remaining verification boundaries.

## Development

Read [AGENTS.md](AGENTS.md) and [docs/AI_HARDWARE_DEVELOPMENT_GUIDE.md](docs/AI_HARDWARE_DEVELOPMENT_GUIDE.md) before changing hardware-facing code. Keep reusable hardware logic in `components/bsp`, application/UI behavior in `main`, and report clean-build results separately from physical-device results.

Minimum firmware check:

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
idf.py -B build-slot-portal build
```

Android check:

```sh
cd android-app
./gradlew assembleDebug lintDebug
```

## License and attribution

The repository is released under the [MIT License](LICENSE). It is derived from the FoloToy AI Passport project and includes Noto Sans SC font data under the license in [main/fonts/OFL-NotoSansSC.txt](main/fonts/OFL-NotoSansSC.txt). Third-party firmware is not part of this repository unless explicitly stated and remains governed by its upstream license.
