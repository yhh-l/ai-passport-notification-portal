# AI Passport Portal: Android Notification Bridge and Firmware Library

[简体中文](APP_README.md) | **English**

This project follows the build and physical-device validation workflow from the [FoloToy AI Passport play-development guide](https://ai-passport.folotoy.cn/guides/create-a-play-with-agent/). It turns “Pocket Messages” into the resident portal: an ordinary Android phone forwards notifications from selected apps through encrypted BLE, while the Android APK can store several compatible application images and install one of them into the device's user slot.

The project does not read the SMS database and does not call Feishu/Lark server APIs. It consumes Android system notifications after the user grants Notification access. The BLE advertising name is `PassportNotify`.

## Local environment and builds

The verified local toolchain was:

- ESP-IDF 5.5.3: `~/esp/esp-idf-v5.5.3`
- Target: ESP32-C3, 8 MiB flash, no PSRAM
- Android SDK: `~/Library/Android/sdk`
- JDK 17: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`

Build the resident portal:

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
idf.py -B build-slot-portal build
```

The safe segmented flash command below writes only the bootloader, partition table, OTA metadata and factory portal. It does not erase the chip and does not write `nvs`, `phy_init`, `cardid` or the user slot:

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
python -m esptool --chip esp32c3 -p /dev/cu.usbmodem1101 -b 460800 \
  --before default_reset --after hard_reset write_flash \
  --flash_mode dio --flash_size detect --flash_freq 80m \
  0x0      build-slot-portal/bootloader/bootloader.bin \
  0x8000   build-slot-portal/partition_table/partition-table.bin \
  0x414000 build-slot-portal/ota_data_initial.bin \
  0x420000 build-slot-portal/FoloToy-AI-Passport.bin
```

> Never turn these segments into a dense image written from `0x0`. Gap-filling bytes can overwrite NVS, PHY data, the user slot and `cardid`. A browser flasher package must express multiple offsets in a manifest rather than use a single `full.bin`.

Build the Android app:

```sh
cd android-app
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew clean assembleDebug lintDebug
```

Current debug APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`, version `1.3.1` (`versionCode 5`), SHA-256 `155fb708acbdbe9758bc4a132f6fa3964ef56da946ab46f5fb100e021003d4e4`.

The 16 px, 1 bpp Chinese font is derived from Noto Sans SC and includes GB2312 plus interface-specific characters. See `main/fonts/OFL-NotoSansSC.txt`. Characters outside the generated coverage and emoji may be missing.

## Partition map, backup and recovery

| Partition | Offset | Size | Purpose |
|---|---:|---:|---|
| `nvs` | `0x9000` | 24 KiB | BLE bonding, preferences and related state |
| `phy_init` | `0xF000` | 4 KiB | RF calibration data |
| `user_app` | `0x10000` | 4 MiB | Replaceable user application, OTA subtype 0 |
| `cardid` | `0x410000` | 16 KiB | Preserved original device data location |
| `otadata` | `0x414000` | 8 KiB | Factory/user-app boot selection state |
| `factory` | `0x420000` | 1408 KiB | Resident notification portal |
| `storage` | `0x580000` | 2560 KiB | Reserved data area |

Local safety backups were created in `.local-backups/`, including the original 8 MiB flash image. The directory is ignored by Git because raw flash may contain Wi-Fi credentials, BLE keys and device identifiers. Never upload or share it.

The original local full-flash backup had SHA-256 `b3dce15e5737a0ac85da2194717aa2b5285b28058a04388d0ce367dfb97243c4`. NVS and `cardid` were read before and after the partition migration and matched byte-for-byte:

- NVS SHA-256: `302abda49717baa52519f022df2a0f9408e7d725eee4ddbb12e693dfcd2d5908`
- `cardid` SHA-256: `34ae30647e00e78ad2c01682d6629267ba53783ee81ade0306da72f01dd9ea8d`

A full restore overwrites the current device state and must only be performed with an explicit decision to restore that exact device:

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
python -m esptool --chip esp32c3 -p /dev/cu.usbmodem1101 -b 460800 \
  write_flash 0x0 .local-backups/ai-passport-original-flash-2026-10-08.bin
```

## Android connection and notification behavior

1. Install the APK, allow Nearby devices and app notifications, then enable **AI Passport Portal** in Android's Notification access settings. The Samsung S25 Ultra is handled as a standard Android device.
2. Select WeChat, Feishu/Lark, SMS or other apps under **Manage apps**. The 32-entry limit is shared by all apps. When full, the oldest entry is removed.
3. Auto-connect is enabled by default in APK 1.3.1. App launch, notification-listener connection, APK replacement, phone boot and Bluetooth being turned back on can all start the foreground service and resume scanning for `PassportNotify`. **Pause auto-connect** explicitly disables this behavior. Android Force stop blocks background starts until the app is opened again.
4. The first line sent by the phone is the Android app label; the remaining text is the notification content. Messages are kept in device RAM and do not expire automatically.
5. Up and Down browse messages. OK cycles through all messages and app-specific filters and never deletes. Hold Up for three seconds to ask for deletion of the current app's messages, then press OK to confirm. Hold Down toggles mute; hold OK runs self-test; double-press OK opens the firmware portal.
6. A new message wakes the screen. Sixty seconds of inactivity turns off the LCD output and backlight while the CPU, BLE connection and notification receiver remain active. Double-press Down sleeps immediately.
7. SMS body and OTP display is disabled by default. When disabled, SMS content is hidden and standalone 4–8 digit numbers in ordinary notifications are masked.
8. The phone queue is bounded and notifications missed while disconnected are not guaranteed to be replayed. Notification text is limited to 160 UTF-8 bytes. Images, avatars and attachments are not transferred; source badges are drawn locally by the firmware.

## Firmware library and Radio test image

The phone can store several `.bin` files, but the 8 MiB partition map has only **one 4 MiB user application slot**. Installing another app replaces that slot without replacing the resident factory portal.

The importer validates the ESP application header, application description, ESP32-C3 chip identifier, 4 MiB limit and SHA-256. Chip matching is not board compatibility. Import only application images explicitly built with the AI Passport BSP; never import a `full.bin`, bootloader or partition table.

A user-slot image was built from `weibaohui/aipassport-radio` for testing:

- Upstream commit: `e3bd972f13c761fb6f66823e76f1ea922e903afa`
- Framework submodule: `026ffd90aa98ba4818491d4413f50ca311809f32`
- Local artifact: `build/user-firmware/AI-Passport-Radio-e3bd972-user-slot.bin`
- Size: 4,051,696 bytes (3.86 MiB), leaving 142,608 bytes below the 4 MiB limit
- SHA-256: `1b7c3b3beecbf62bc59dfe60e4ced627983f9fdbfae95f2639b4d6ca35bb6a4b`

The Radio build used size optimization and disabled runtime logs, assertions and LVGL examples to fit the slot while retaining its Chinese font, station playback and main interface. It is an **application image**, not a whole-device image for offset `0x0`. This repository does not redistribute that third-party binary.

In the portal, select Pocket Messages or the custom app with Up/Down and hold OK for three seconds to start it. Radio itself keeps its upstream controls: Up/Down change stations, OK pauses/resumes, and hold OK opens the station list. The factory portal cannot intercept buttons while Radio is running.

A newly selected user image is started in OTA pending-verification state. An application that does not call `esp_ota_mark_app_valid_cancel_rollback()` is expected to roll back to the factory portal on the following restart. This must be verified with real boot logs. A third-party app that confirms itself, changes OTA metadata, uses an incompatible partition layout or fails very early may require USB recovery instead.

Firmware-transfer characteristics share the authenticated service used by notifications: control `...c010`, data `...c011`, and status `...c012`. All require MITM-authenticated encryption. Control commands support begin, finish, cancel and clear-slot operations. Disconnecting aborts an unfinished transfer. The Android foreground service holds a temporary WakeLock while transferring.

## Verification boundaries as of October 9, 2026

Verified:

- Resident portal `1.2.1-portal` builds with ESP-IDF 5.5.3. Its application image is 1,321,360 bytes, leaving 120,432 bytes in the factory partition; SHA-256 is `e795a1f4dc1647e01f4871ee4e4d1e25b892f68b2fea0a13eb46cf510e82f383`.
- The partition table and an earlier physical-device portal build were flashed with segmented offsets. Serial logs confirmed boot from factory offset `0x420000`, correct partitions, and successful display/audio/button/BLE initialization.
- NVS and `cardid` matched byte-for-byte before and after segmented flashing.
- Android APK 1.3 was installed on a Samsung S25 Ultra (`SM-S9380`) with Bluetooth, notifications and Notification access retained. APK 1.3.1 builds and passes `assembleDebug` and `lintDebug`.
- Turning phone Bluetooth off and back on triggered an automatic reconnect; device logs reached `encrypted=1 authenticated=1` without manually pressing Connect in the app.
- The Radio image passes ESP32-C3 image validation and fits the slot. The APK reported a complete 4,051,696-byte transfer, and the user later reported the tested physical flow working normally.
- Host tests for `message_store`, UI pixel calculations and `git diff --check` pass.

Not equivalent to completed verification:

- The final Radio slot readback stopped at 72% when USB disconnected, so there is no complete device-side SHA-256 readback. A phone-side “installation complete” status is not byte-for-byte verification.
- The exact final `1.2.1-portal` image and APK 1.3.1 were built after the earlier installed versions; their installation state should be checked before claiming those exact artifacts are deployed.
- Restarting Radio and observing OTA rollback to the factory portal still needs a captured boot/restart verification for that exact image.
- Physical button feel and timing should still be checked for “OK cycles sources only” and “hold Up, then OK, clears only the active app”; source code and host tests do not replace that check.

## BLE notification protocol

A notification packet starts with four bytes:

```text
A5 <1=SMS | 2=other | 3=connection status> <UTF-8 length low> <length high>
```

The payload is limited to 160 UTF-8 bytes. Android splits the packet according to the negotiated GATT MTU and waits for each write callback. An incomplete packet is discarded on disconnect. Notification text is not written to NVS or serial logs; NVS stores only mute preference, BLE bonding and user-slot metadata.
