# AI Passport 门户（Android 通知桥 + 固件玩法库）

**简体中文** | [English](APP_README.en.md)

本项目参照 [FoloToy AI Passport 创建玩法教程](https://ai-passport.folotoy.cn/guides/create-a-play-with-agent/) 的开发、编译和实机验证流程，将“随身消息”做成常驻门户：普通 Android 手机把已选择应用的系统通知通过加密 BLE 发送到 AI Passport；Android APK 还可保存多个兼容玩法固件，并把其中一个安装到设备上的用户玩法槽。项目不读取短信数据库、不接入飞书服务端 API，BLE 广播名为 `PassportNotify`。

## 本机环境与构建

- ESP-IDF 5.5.3：`~/esp/esp-idf-v5.5.3`
- 目标芯片：ESP32-C3，8 MB Flash，无 PSRAM
- Android SDK：`~/Library/Android/sdk`
- JDK 17：`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`

门户固件构建：

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
idf.py -B build-slot-portal build
```

安全的分段刷写命令如下。它只写 bootloader、分区表、OTA 元数据和 factory 门户，不执行 `erase_flash`，也不写 `nvs`、`phy_init`、`cardid`：

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

> 不要把以上分段文件简单合并成从 `0x0` 写入的稠密镜像；中间填充的 `0xFF` 会覆盖 NVS、PHY、用户玩法槽和 `cardid`。需要网页刷写包时，应使用能表达多段偏移的 manifest，而不是单个 full.bin。

Android Release APK 构建与签名验证：

```sh
cd android-app
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME="$HOME/Library/Android/sdk" ./tools/package_release.sh
```

正式通用 APK：`android-app/dist/AI-Passport-Portal-Android-1.4.0.apk`，版本 `1.4.0`（versionCode 6），支持 Android 8.0 / API 26 及以上，SHA-256：`47fc1710eea610b7b1318a2d2b6eda6de69d2f3c4679f22ab91588cdafab46d9`。私有密钥和 `release-signing.properties` 均被 Git 忽略；发布方法见 `android-app/RELEASING.md`。

中文字体来自 Noto Sans SC，16 px / 1 bpp，包含 GB2312 及部分界面字。授权见 `main/fonts/OFL-NotoSansSC.txt`；GB2312 以外文字和表情可能缺字。

## 分区、备份与恢复

当前门户分区表：

| 分区 | 偏移 | 大小 | 用途 |
|---|---:|---:|---|
| `nvs` | `0x9000` | 24 KiB | BLE 绑定、偏好等 |
| `phy_init` | `0xF000` | 4 KiB | RF 数据 |
| `user_app` | `0x10000` | 4 MiB | 唯一可替换用户玩法，OTA subtype 0 |
| `cardid` | `0x410000` | 16 KiB | 保留原设备数据位置 |
| `otadata` | `0x414000` | 8 KiB | factory / 用户玩法切换状态 |
| `factory` | `0x420000` | 1408 KiB | 常驻通知门户 |
| `storage` | `0x580000` | 2560 KiB | 保留数据区 |

- 原机 8 MB 全量备份：`.local-backups/ai-passport-original-flash-2026-10-08.bin`
- 原机备份 SHA-256：`b3dce15e5737a0ac85da2194717aa2b5285b28058a04388d0ce367dfb97243c4`
- 上游示例构建备份：`.local-backups/ai-passport-upstream-main-2026-10-08.bin`
- 变更分区前另行读取了 NVS 和 `cardid`。门户分段刷写完成后再次回读，两者均逐字节一致：
  - NVS SHA-256：`302abda49717baa52519f022df2a0f9408e7d725eee4ddbb12e693dfcd2d5908`
  - `cardid` SHA-256：`34ae30647e00e78ad2c01682d6629267ba53783ee81ade0306da72f01dd9ea8d`

`.local-backups/` 已被 Git 忽略。原始闪存可能包含 Wi-Fi 密码、BLE 密钥等敏感资料，不要上传、分享或提交仓库。完整恢复会覆盖设备现状，只能在再次明确确认后执行：

```sh
source ~/esp/esp-idf-v5.5.3/export.sh
python -m esptool --chip esp32c3 -p /dev/cu.usbmodem1101 -b 460800 \
  write_flash 0x0 .local-backups/ai-passport-original-flash-2026-10-08.bin
```

## Android 连接与通知逻辑

1. 安装 APK 后点击“快速启用”。应用会依次检查附近设备/定位权限、蓝牙、通知使用权、通知应用选择和设备连接，并自动跳过已完成步骤。Android 仍要求用户本人确认权限、通知使用权和首次安全配对，APK 无法静默绕过。应用不依赖三星专用 SDK，Samsung S25 Ultra 按普通 Android 设备处理。
2. 可一键勾选本机已安装的微信、飞书/Lark、默认短信、QQ、钉钉、WhatsApp、Telegram，也可在“管理应用”中手动调整。当前 32 条上限是**所有应用合计**，不是每个应用各 32 条；列表满时淘汰最早一条。
3. APK 1.4.0 默认开启自动连接。首次安全配对后，应用启动、通知监听器连接、APK 更新、手机开机和蓝牙重新开启都会尝试启动前台服务并自动扫描、连接 `PassportNotify`。界面的“暂停自动连接”会明确关闭该行为；不要用 Android 的“强行停止”测试自动连接，因为系统会阻止该应用继续接收启动广播，直到用户再次打开应用。
4. 手机发送的正文第一行是 Android 应用标签，后续行为按应用来源分组。消息保存在 AI Passport RAM 中，重启后清空，不会因超时自动删除。
5. 消息页按键：
   - 短按上 / 下：在当前筛选范围内浏览上一条 / 下一条。
   - 短按 OK：在“全部”和当前已有的微信、飞书、短信等应用筛选之间循环，**不删除消息**。
   - 长按上 3 秒：进入“清空当前应用”确认页；短按 OK 确认，只清空当前筛选应用；短按上 / 下取消。位于“全部”时会以当前消息所属应用作为清空对象。
   - 长按下 3 秒：切换静音。静音状态只显示为顶部图标。
   - 长按 OK 3 秒：进入屏幕、按键和声音自检。
   - 双击 OK：进入或退出玩法门户。
6. 无消息时才显示操作说明；有消息时隐藏说明，把空间让给消息卡片。新消息、配对码和固件传输会自动亮屏。60 秒无按键、无新通知且不处于配对/传输时自动熄屏；双击下可立即熄屏。熄屏只关闭 LCD 输出和背光，CPU、BLE 和通知接收继续运行。
7. “显示短信正文和验证码”默认关闭。关闭时隐藏短信正文，并遮盖普通通知中的 4–8 位独立数字；需要时再主动打开，用后建议关闭。
8. 手机待发队列有容量限制，断线期间不能保证通知补发。BLE 文本正文最多 160 字节 UTF-8；不传送通知图片、头像或附件，屏幕上的来源标识由固件本地绘制。

## 固件玩法库与 Radio

手机可以保存多个 `.bin`，但当前 8 MB AI Passport 只有 **1 个 4 MiB 用户玩法槽**。安装另一玩法会替换设备槽里的旧玩法，不会替换 factory 门户。

APK 导入时检查 ESP 应用头、应用描述、ESP32-C3 芯片标识、4 MiB 大小上限和 SHA-256。芯片匹配不等于板级兼容，只应安装明确复用 AI Passport BSP 的应用固件；不要导入 `full.bin`、bootloader 或分区表。

已针对用户槽构建 `weibaohui/aipassport-radio`：

- 上游提交：`e3bd972f13c761fb6f66823e76f1ea922e903afa`
- framework 子模块：`026ffd90aa98ba4818491d4413f50ca311809f32`
- 应用固件：`build/user-firmware/AI-Passport-Radio-e3bd972-user-slot.bin`
- 大小：4,051,696 字节（3.86 MiB），距离 4 MiB 上限还剩 142,608 字节
- SHA-256：`1b7c3b3beecbf62bc59dfe60e4ced627983f9fdbfae95f2639b4d6ca35bb6a4b`

Radio 为满足 4 MiB 槽位使用 size optimization、关闭运行日志、关闭断言和 LVGL 示例，保留其中文字体、网络电台和主要界面。该文件是**应用镜像**，不是从 `0x0` 刷写的整机镜像。

门户中短按上 / 下选择“随身消息”或自定义玩法，选中后长按 OK 3 秒启动。Radio 自身按键来自其上游实现：播放页上 / 下切台、OK 暂停或继续，长按 OK 进入电台列表；Radio 已占用双击 OK 等组合，因此门户无法在 Radio 运行时继续接管按键。

用户玩法首次启动被设置为 OTA 待确认状态。像当前 Radio 这样不调用 `esp_ota_mark_app_valid_cancel_rollback()` 的玩法，预期在下一次重启时由 bootloader 回滚到 factory 门户。这个“重启回门户”机制仍必须以真实启动和重启日志为准；第三方玩法若主动确认 OTA、改写 OTA 元数据、使用不兼容分区或无法启动，可能不能自动返回，需要 USB 恢复。

固件 BLE 特征与通知位于同一加密服务：控制 `...c010`、数据 `...c011`、状态 `...c012`，均要求 MITM 认证加密。控制命令支持开始、结束、取消和清空用户槽；断线会中止未完成传输。传输期间 Android 前台服务持有临时 WakeLock。

## 已验证与未验证边界（2026-10-09）

已验证：

- 最终门户 `1.2.1-portal` 用 ESP-IDF 5.5.3 构建成功；应用镜像大小 1,321,360 字节，factory 分区余量 120,432 字节，SHA-256：`e795a1f4dc1647e01f4871ee4e4d1e25b892f68b2fea0a13eb46cf510e82f383`。
- 门户分区表和前一实机版门户已按分段偏移刷入当前 ESP32-C3；串口日志确认从 `0x420000` factory 分区启动、分区表正确、显示/音频/按键/BLE 初始化成功。最终 `1.2.1-portal` 仍需设备重新接入 USB 后只覆写 `0x420000` 应用段。
- NVS 与 `cardid` 在刷写前后回读逐字节一致。
- Android APK 1.3（versionCode 4）曾在 Samsung S25 Ultra（SM-S9380）完成实机链路验证。当前 1.4.0（versionCode 6）已构建为独立发布密钥签名的通用 APK，通过 `assembleDebug`、`lintDebug`、`assembleRelease`、`lintRelease` 与 `apksigner` 验证；最低 API 26、目标 API 36。该精确 1.4.0 包尚未在真机重新安装。
- 实测关闭再开启手机蓝牙后，无需在 APK 中手动点击连接：手机重新扫描，设备日志确认重新连接并达到 `encrypted=1 authenticated=1`。
- Radio 应用镜像通过 ESP32-C3 image 校验并满足 4 MiB 槽位，已导入手机固件库；APK 报告 4,051,696 字节传输达到 100% 并返回“安装完成”。用户随后反馈实机测试无异常。
- `message_store` 主机单元测试、UI 像素计算测试和 `git diff --check` 通过。

仍需实机完成或补证：

- Radio 用户槽回读在 72% 时因设备 USB 断开而中止，因此尚未取得设备端完整 SHA-256；不能把手机端“安装完成”当作逐字节回读校验。
- 最终门户 `1.2.1-portal` 与 Android APK 1.4.0 的真机安装；旧 Debug 签名与新 Release 签名不同，测试手机需要先卸载旧 Debug 版再安装正式版。
- 重启 Radio 后确认 OTA 回滚确实返回 factory 门户。
- 实体按键验证“短按 OK 只切换应用”和“长按上 + OK 仅清空当前应用”；源码和主机单测通过不等于按键手感已验收。

## BLE 消息协议

通知包头为 4 字节：`A5 <1=短信|2=其他|3=连接提示> <UTF-8 长度低位> <高位>`，正文最多 160 字节 UTF-8。Android 根据 GATT MTU 分块，每块等待写回调；未完成包在断线时丢弃。通知内容不写入 NVS 或串口；NVS 只保存静音偏好、BLE 绑定和用户槽元数据。
