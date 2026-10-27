# AI Passport Bluetooth Firmware Installer

**English** | [简体中文](README.zh_CN.md) | [Detailed setup](APP_README.en.md) | [中文安装说明](APP_README.md)

## Download

[Download the Android APK](https://github.com/yhh-l/ai-passport-notification-portal/releases/download/bluetooth-installer-v2.0.0/AI-Passport-Bluetooth-Installer-2.0.0-debug.apk) or open the [2.0.0 release page](https://github.com/yhh-l/ai-passport-notification-portal/releases/tag/bluetooth-installer-v2.0.0) for the APK, community firmware ZIP, merged BIN, and SHA-256 checksums. The current APK is an installable debug-signed build.

A small resident firmware and Android companion app for keeping several compatible AI Passport plays on one device. The phone can import multiple local BIN files or download a verified package from the FoloToy catalog, compare each image with the real device-slot capacity, then install it over secure Bluetooth.

The installer itself remains in a protected factory partition. Three independent play slots are available:

| Slot | Capacity | Intended use |
|---|---:|---|
| 1 | 1.4375 MiB | Small play |
| 2 | 1.4375 MiB | Small play |
| 3 | 3.875 MiB | Large play |

Total free capacity and the largest empty slot are shown separately. A firmware image must fit completely inside one slot; free space cannot be combined across slots.

## Features

- Import several local raw application images or merged images in one selection.
- Search compatible official and community plays by Chinese or English name, category, and tag.
- Download catalog firmware while validating its application header, target chip, recorded size, and SHA-256.
- Display every device slot, installed image name and size, total free capacity, and maximum single-slot capacity.
- Install into an empty slot or deliberately replace an occupied slot.
- Select, clear, and start installed plays on the device.
- Use authenticated and encrypted Bluetooth pairing; firmware data does not pass through a relay server.

## Use

1. Install the resident installer once by USB or a compatible web flasher, then install and open the Android APK.
2. Grant Nearby devices and notification permission, enable Bluetooth, and confirm the same pairing code on the phone and device.
3. Import one or more BIN files, or search the FoloToy catalog and download a supported play. Select a target slot; the APK blocks images that exceed that slot.
4. On the device, use Up/Down to select a slot and hold OK to start it. Hold Down, then press OK, to clear the selected slot.

No Wi-Fi setup is required.

## Build

Firmware requires ESP-IDF 5.5.x:

```sh
source /path/to/esp-idf-v5.5.3/export.sh
tools/build_community_release.sh
```

The merged installer is written to:

```text
build-installer/AI-Passport-Bluetooth-Installer-merged-0x0.bin
```

Android requires JDK 17 and Android SDK 36:

```sh
cd android-app
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew assembleDebug lintDebug
```

For a signed release, configure the private signing file described in [android-app/RELEASING.md](android-app/RELEASING.md), then run `android-app/tools/package_release.sh`.

## Compatibility boundary

The installer accepts compatible application images, not arbitrary full-flash backups. Official merged packages are normalized by extracting the application at `0x10000`; local files may be either that merged format or a raw application image.

Rollback is enabled so an unconfirmed third-party play normally returns to the resident installer after its next reboot. A play that confirms itself as the permanent OTA image, rewrites OTA metadata, assumes an incompatible partition map, or cannot boot cleanly may not return automatically and can require USB recovery. Test each third-party image before relying on unattended switching.

## Repository layout

```text
android-app/                 Android firmware library and Bluetooth installer
components/bsp/              Display and button support
main/                        Resident installer, BLE protocol and slot manager
partitions.csv               Factory installer plus three play slots
tools/build_community_release.sh
APP_README*.md               Flashing, recovery and verification details
```

This community project is derived from the [FoloToy AI Passport](https://gitee.com/FoloToy/ai-passport) BSP and is not an official FoloToy release. Source is released under the [MIT License](LICENSE); imported third-party firmware remains under its own license.
