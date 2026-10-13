# AI Passport 蓝牙多固件安装器

[简体中文] | [English](README.md) | [详细安装说明](APP_README.md) | [Detailed setup](APP_README.en.md)

## 下载

[直接下载 Android APK](https://github.com/yhh-l/ai-passport-notification-portal/releases/download/bluetooth-installer-v2.0.0/AI-Passport-Bluetooth-Installer-2.0.0-debug.apk)，或打开 [2.0.0 发布页](https://github.com/yhh-l/ai-passport-notification-portal/releases/tag/bluetooth-installer-v2.0.0) 获取 APK、社区固件 ZIP、合并 BIN 和 SHA-256 校验值。当前 APK 是可安装的 Debug 签名版本。

这是一套精简的常驻安装器固件和 Android 配套 APK，用来在同一台 AI Passport 中保留多个兼容玩法。手机可一次导入多个本地 BIN，也可从 FoloToy 官网目录下载并校验固件，然后比较固件大小与真实槽位容量，通过安全蓝牙写入。

安装器自身常驻在 factory 分区，提供 3 个独立玩法槽：

| 槽位 | 容量 | 用途 |
|---|---:|---|
| 1 | 1.4375 MiB | 小型玩法 |
| 2 | 1.4375 MiB | 小型玩法 |
| 3 | 3.875 MiB | 较大玩法 |

APK 会分别显示“空槽总容量”和“最大空槽”。每个固件必须完整放入一个槽位，不能把多个槽位的空间合并使用。

## 主要功能

- 一次多选导入本地应用镜像或合并固件。
- 查看并下载 FoloToy 官网中支持的已发布固件。
- 校验官网固件的应用头、目标芯片、记录大小和 SHA-256。
- 显示每个设备槽位、已安装名称与大小、总剩余容量和最大单槽容量。
- 安装到空槽，或在明确确认后覆盖已占用槽位。
- 在设备上选择、清空并启动不同玩法。
- 使用经过认证和加密的蓝牙配对，固件数据不经过中转服务器。

## 使用方法

1. 先通过 USB 或兼容的网页刷写器一次性安装常驻安装器，再安装并打开 Android APK。
2. 授予“附近设备”和通知权限，打开蓝牙，并在手机与设备上确认相同的配对码。
3. 多选导入 BIN，或从官网列表下载玩法；选择目标槽位后安装，APK 会拦截超出该槽容量的固件。
4. 设备上用上/下键选择槽位，长按 OK 启动；长按下键后短按 OK 可清空当前槽位。

本玩法无需配网。

## 构建

固件需要 ESP-IDF 5.5.x：

```sh
source /path/to/esp-idf-v5.5.3/export.sh
tools/build_community_release.sh
```

合并固件输出到：

```text
build-installer/AI-Passport-Bluetooth-Installer-merged-0x0.bin
```

Android 端需要 JDK 17 和 Android SDK 36：

```sh
cd android-app
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew assembleDebug lintDebug
```

签名发布包的私有配置方法见 [android-app/RELEASING.md](android-app/RELEASING.md)。

## 兼容性边界

安装器接受兼容的应用镜像，不接受任意全闪存备份。官网合并包会从 `0x10000` 提取应用段；本地文件可以是这种合并格式，也可以是原始应用镜像。

固件已启用 OTA 回滚，未主动确认为永久版本的第三方玩法，通常会在下次重启时返回常驻安装器。如果玩法自行确认 OTA、改写 OTA 元数据、假定了不兼容的分区表，或无法正常启动，则可能无法自动返回，需要 USB 恢复。依赖无人切换前，应逐个真机验证第三方镜像。

## 仓库结构

```text
android-app/                 Android 固件库与蓝牙安装器
components/bsp/              显示和按键支持
main/                        常驻安装器、BLE 协议和槽位管理
partitions.csv               常驻安装器 + 3 个玩法槽
tools/build_community_release.sh
APP_README*.md               刷写、恢复和验证详情
```

本社区项目源自 [FoloToy AI Passport](https://gitee.com/FoloToy/ai-passport) BSP，不是 FoloToy 官方发行版。本仓库源码使用 [MIT License](LICENSE)；用户导入的第三方固件仍遵循其各自许可证。
