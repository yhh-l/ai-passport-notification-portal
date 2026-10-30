# AI Passport Bluetooth Firmware Installer

**English** | [简体中文](README.zh_CN.md) | [Detailed setup](APP_README.en.md) | [中文安装说明](APP_README.md)

## Development test release 3.0

[Download firmware and Android APK](https://github.com/yhh-l/ai-passport-notification-portal/releases/tag/dynamic-library-v3.0.0) — prerelease; not hardware-accepted.

The installer now allocates storage from image sizes instead of reserving three fixed slots. Import local BINs or download verified official/community plays, then tap **Auto allocate and install** in the matching Android app. Existing plays are never replaced automatically.

The two protected play regions provide 6.75 MiB in total; each allocation rounds up to 64 KiB. A single play requires contiguous space and can be at most 3.875 MiB. The practical play count depends on sizes. Deletion releases space; automatic compaction is not implemented.

Press Up/Down to select an installed play and hold OK to launch it. Hold Down, then press OK to delete. Ordinary reboot returns to the installer using a one-shot boot request. No device Wi-Fi setup is required; catalog downloads need Internet on the phone.

**Hardware acceptance is pending.** See [setup, upgrade and compatibility](APP_README.en.md) before using the new bootloader. Preserve old plays using a segmented upgrade, not a merged flash. The published [2.1.0 release](https://github.com/yhh-l/ai-passport-notification-portal/releases/tag/bluetooth-installer-v2.1.0) still uses fixed slots; it does not contain this local work.

## Build

Firmware requires ESP-IDF 5.5.x:

```sh
source /path/to/esp-idf-v5.5.3/export.sh
tools/build_community_release.sh
```

The merged installer is written to:

```text
build-dynamic/AI-Passport-Dynamic-Library-3.0.0-merged-0x0.bin
```

Android requires JDK 17 and Android SDK 36:

```sh
cd android-app
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew testDebugUnitTest assembleDebug lintDebug
```

For a signed release, configure the private signing file described in [android-app/RELEASING.md](android-app/RELEASING.md), then run `android-app/tools/package_release.sh`.

## Compatibility boundary

The installer accepts compatible application images, not arbitrary full-flash backups. Official merged packages are normalized by extracting the application at `0x10000`; local files may be either that merged format or a raw application image.

A custom bootloader launches the chosen image once and normally returns to the installer on reboot. Third-party self-updates, partition assumptions and additional resource partitions can be incompatible. Read the detailed setup guide; image validation does not establish runtime compatibility.

## Repository layout

```text
android-app/                 Android firmware library and Bluetooth installer
components/bsp/              Display and button support
main/                        Resident installer, paged BLE protocol and play manager
partitions.csv               Installer, two dynamic pools and protected metadata
tools/build_community_release.sh
APP_README*.md               Flashing, recovery and verification details
```

This community project is derived from the [FoloToy AI Passport](https://gitee.com/FoloToy/ai-passport) BSP and is not an official FoloToy release. Source is released under the [MIT License](LICENSE); imported third-party firmware remains under its own license.
