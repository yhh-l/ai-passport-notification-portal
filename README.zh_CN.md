# AI Passport 通知门户

[English](README.md) | **简体中文** · [详细部署说明](APP_README.md) · [English setup](APP_README.en.md)

这是一个面向 FoloToy AI Passport 的 ESP32-C3 固件与 Android 配套应用，把设备改造成兼顾隐私的随身通知屏和固件玩法门户。

手机只会把用户勾选应用的系统通知（例如微信、飞书/Lark、短信）通过经过认证和加密的 BLE 连接发送到设备。AI Passport 会保留最近消息供手动查看；新消息自动亮屏；首次安全配对完成后自动重连；还可以在不替换常驻门户的前提下安装一个兼容玩法固件。

> 本项目基于 [FoloToy AI Passport](https://gitee.com/FoloToy/ai-passport) BSP 二次开发，属于社区衍生项目，并非 FoloToy 官方发行版。

## 主要功能

- **Android 通知桥**：只转发用户明确勾选的应用。
- **不经过云端**：通知正文和固件数据只在手机与 AI Passport 之间通过 BLE 传输。
- **消息不会超时消失**：设备 RAM 中最多保留所有应用合计最近 32 条消息，可手动前后浏览。
- **按应用筛选**：在“全部消息”和各应用之间切换，只清空当前应用的消息。
- **自动重连**：应用启动、手机开机、APK 更新、蓝牙重新开启后，前台服务会继续扫描并重连。
- **自动亮屏与熄屏**：新消息自动亮屏；60 秒无操作后只关闭 LCD/背光，BLE 仍保持工作。
- **手机固件库**：Android 应用可以保存多个兼容 ESP32-C3 应用镜像，并把其中一个安装到设备的 4 MiB 用户玩法槽。
- **常驻 factory 门户**：正常安装用户玩法不会覆盖通知门户。

## 按键说明

### 通知界面

| 操作 | 功能 |
|---|---|
| 短按上 | 当前应用筛选内的上一条消息 |
| 短按下 | 当前应用筛选内的下一条消息 |
| 短按 OK | 在全部消息和各应用筛选之间切换，不删除消息 |
| 长按上 3 秒 | 请求清空当前应用消息，再短按 OK 确认 |
| 长按下 3 秒 | 开启或关闭通知提示音静音 |
| 长按 OK 3 秒 | 运行设备自检 |
| 双击下 | 立即熄屏 |
| 双击 OK | 打开或关闭固件玩法门户 |

### 固件玩法门户

| 操作 | 功能 |
|---|---|
| 短按上 / 下 | 在常驻通知门户与已安装用户玩法之间选择 |
| 短按 OK | 返回通知界面 |
| 长按 OK 3 秒 | 启动选中的用户玩法 |

屏幕处于熄灭状态时，第一次按键只负责唤醒，不会误触原功能；新通知会直接自动亮屏。

## 系统结构

```text
Android 通知使用权
        │
        │ 已选择通知 / 固件数据分块
        ▼
Android 前台 BLE 服务
        │ 经过认证和加密的 GATT
        ▼
AI Passport factory 常驻门户
        ├── 消息列表与界面
        ├── 声音、按键与屏幕休眠
        └── 4 MiB 用户应用槽
```

Android 端使用标准系统 API。开发测试所用的 Samsung S25 Ultra 与普通 Android 手机采用同一套逻辑，不依赖三星专用 SDK。

## 快速开始

### 环境要求

- FoloToy AI Passport：ESP32-C3、8 MiB Flash、无 PSRAM
- ESP-IDF 5.5.x；本项目已用 ESP-IDF 5.5.3 构建
- Android Studio/SDK，compile SDK 36
- JDK 17
- Android 8.0（API 26）及以上，并支持 BLE

### 构建常驻门户

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
idf.py set-target esp32c3
idf.py -B build-slot-portal build
```

**不要**把各分段产物拼成带空洞填充的镜像后从 `0x0` 整体烧录，否则可能覆盖 NVS、BLE 配对密钥、`cardid` 和用户玩法槽。请严格使用 [APP_README.md](APP_README.md) 中带偏移的安全刷写命令和备份说明。

### 安装或构建 Android APK

可直接从 [GitHub Releases](https://github.com/yhh-l/ai-passport-notification-portal/releases/latest) 下载已签名的通用 APK。应用不包含按 CPU 架构区分的原生库，ARM 和 x86 Android 设备使用同一个安装包。

需要自行发布时，先按照 [android-app/RELEASING.md](android-app/RELEASING.md) 配置私有签名，再运行：

```sh
cd android-app
JAVA_HOME=/path/to/jdk17 \
ANDROID_HOME=/path/to/android-sdk \
./tools/package_release.sh
```

Release APK 输出位置：

```text
android-app/dist/AI-Passport-Portal-Android-1.5.0.apk
```

### 首次连接

1. 安装并打开 APK，点击“快速启用”中的“继续完成设置”。
2. 按引导完成附近设备、蓝牙、通知使用权和通知应用选择；已完成的步骤会自动跳过。
3. 打开 AI Passport，并在 Android 弹窗中确认第一次安全蓝牙配对。
4. 先点击“发送测试消息”，确认链路正常后再测试真实通知。

Android 强制要求用户本人确认运行时权限、通知使用权和首次蓝牙配对，APK 无法静默绕过这些系统安全确认。

首次安全配对完成后，应用通常会自行重连。Android 的“强行停止”会禁止后台接收器继续启动，直到用户再次手动打开应用，因此不要用“强行停止”测试自动连接。

## 通知与隐私规则

- 32 条上限是**所有已选择应用合计**，不是每个应用各 32 条。
- 消息只保存在 AI Passport RAM 中，重启或明确执行按应用清空后才会消失。
- “显示短信正文和验证码”默认关闭。关闭时隐藏短信正文，并遮盖普通通知中的 4–8 位独立数字。
- BLE 通知正文最多 160 字节 UTF-8；不传输图片、头像和附件。
- 手机与设备断开期间错过的通知不保证重新补发。
- 全量 Flash 备份可能包含 Wi-Fi 密码、BLE 绑定密钥和设备标识；`.local-backups/` 已明确从 Git 排除。

## 用户固件玩法槽

手机可以保存多个 `.bin`，但当前设备分区只提供 **1 个 4 MiB 用户应用槽**。安装另一个玩法只会替换该槽，不会替换常驻通知门户。

APK 1.5.0 不再要求用户手动选择任意本地 `.bin`。应用通过 HTTPS 读取 FoloToy 官方已发布玩法，只显示不超过 4 MiB、格式为 `esp-merged-0x0` 的官方包；下载后核对官网记录的大小和 SHA-256。官网包是从 `0x0` 开始的合并镜像，APK 会安全提取 `0x10000` 处的应用段，检查 ESP32-C3 应用头和描述信息，再把标准化的 `*-user-slot.bin` 保存到手机 `Download/AI-Passport`，之后才允许通过 BLE 安装。超过 4 MiB 或元数据不支持的条目不会显示。

这些校验仍不能证明板级完全兼容。常驻门户继续作为恢复入口，每个下载玩法仍应分别进行真机启动和返回门户测试。

本项目实机测试过基于 [weibaohui/aipassport-radio](https://github.com/weibaohui/aipassport-radio) 改造的用户槽镜像。第三方源码和二进制仍受其上游许可证与兼容性约束。

## 目录结构

```text
android-app/                 Android 通知桥和固件管理器
components/bsp/              显示、按键、音频、电池、I²C 等可复用 BSP
main/                        常驻门户、BLE 协议、消息存储和界面
tests/                       消息存储与 UI 计算的轻量主机测试
tools/macos_ble_test.swift   电脑端 BLE 测试消息工具
docs/                        硬件参考和故障排查文档
partitions.csv               factory 门户 + 4 MiB 用户槽分区表
APP_README*.md               构建、刷写、恢复和验证边界详细说明
```

## 验证状态

截至 **2026 年 10 月 9 日**：

- 常驻门户 `1.2.1-portal` 已使用 ESP-IDF 5.5.3 构建成功。
- Android 应用 `1.5.0`（`versionCode 7`）已构建为适用于 API 26–36 的签名通用 APK，并通过 `lintDebug` / `lintRelease`（0 error），签名和 ZIP 对齐验证也已通过。
- Samsung S25 Ultra 上实测关闭再开启手机蓝牙后，能够自动恢复经过认证和加密的连接。
- 通知浏览、自动亮屏/熄屏、应用筛选、按应用清空和用户固件传输已进行真机体验，用户反馈当前测试流程正常。
- `message_store` 与 UI 计算主机测试通过。2026-10-09 的目录快照筛出 12 个不超过 4 MiB 的官方已发布包，并排除 2 个超限条目；12 个源文件均通过官网大小/SHA-256 核对，且在 `0x10000` 检出 ESP32-C3 应用镜像。

仍需严格区分：

- 手机显示“安装完成”不等于设备端逐字节 Flash 回读验证。
- Radio 用户槽最终回读曾在完成前因 USB 断开而中止。
- 任意下载玩法能否在重启后自动回到 factory 门户，取决于该玩法的 OTA 行为，必须逐个真机验证；APK 1.5.0 的“官网下载 → BLE 安装”完整流程尚未在已连接 Android 真机上复测。

精确镜像大小、SHA-256、分区偏移与尚未覆盖的验证项见 [APP_README.md](APP_README.md)。

## 开发约定

修改硬件相关代码前请先阅读 [AGENTS.md](AGENTS.md) 和 [docs/AI_HARDWARE_DEVELOPMENT_GUIDE.md](docs/AI_HARDWARE_DEVELOPMENT_GUIDE.md)。可复用硬件逻辑放在 `components/bsp`，应用和界面行为放在 `main`；干净构建结果与真实硬件测试结果必须分开报告。

固件最低验证：

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
idf.py -B build-slot-portal build
```

Android 验证：

```sh
cd android-app
./gradlew assembleDebug lintDebug
./tools/package_release.sh
```

## 许可证与致谢

本仓库采用 [MIT License](LICENSE)，基于 FoloToy AI Passport 项目二次开发。Noto Sans SC 字体数据使用 [main/fonts/OFL-NotoSansSC.txt](main/fonts/OFL-NotoSansSC.txt) 中的许可证。第三方固件默认不属于本仓库内容，仍遵循各自上游许可证。
