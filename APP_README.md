# AI Passport 随身消息（Android 通知桥）

本项目参照 [FoloToy AI Passport 创建玩法教程](https://ai-passport.folotoy.cn/guides/create-a-play-with-agent/) 的开发/编译/验证流程，围绕一个目标：将**普通 Android 手机**上选定应用发布的系统通知，通过 BLE 传给 AI Passport，在设备屏幕常驻显示并播放普通提示音。没有木鱼、计数、飞书服务端接口，也不读取短信数据库。BLE 广播名称 `PassportNotify`。

## 本机开发环境与构建

- ESP-IDF 5.5.3：`~/esp/esp-idf-v5.5.3`；CMake/Ninja 已装在其 Python 环境。固件构建：
  ```sh
  source ~/esp/esp-idf-v5.5.3/export.sh
  idf.py build
  # 本机 ESP-IDF 5.5.3 的 idf.py merge-bin 会因 --flash_size detect 报错，
  # 改用同环境的 esptool 显式指定已验证的 8 MB 闪存：
  python -m esptool --chip esp32c3 merge_bin \
    -o build/FoloToy-AI-Passport-full.bin --format raw \
    --flash_mode dio --flash_freq 80m --flash_size 8MB \
    0x0 build/bootloader/bootloader.bin \
    0x8000 build/partition_table/partition-table.bin \
    0x10000 build/FoloToy-AI-Passport.bin
  ```
- Android SDK：`~/Library/Android/sdk`；JDK 17：`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`。手机应用编译：
  ```sh
  cd android-app
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug
  ```
- 中文字体来自 Noto Sans SC，16px / 1bpp，包含 GB2312 及部分界面字。授权见 `main/fonts/OFL-NotoSansSC.txt`；GB2312 以外文字和表情可能缺字。

## 开发基线与恢复

- 上游默认分支 `origin/main`（提交 `33d3d1d`）已在独立干净目录 `/tmp/ai-passport-upstream-baseline-2026-10-08` 用 ESP-IDF 5.5.3 构建通过。可恢复的上游 0x0 合并镜像为 `.local-backups/ai-passport-upstream-main-2026-10-08.bin`。它**只验证了编译，未在本设备刷写验收**。
- 当前开发分支 `codex/passport-notifications` 源于仓库的 `demo/blufi-provisioning`（`9c039cc`），按原有 BSP 实现通知版；与独立构建的 `origin/main` 默认玩法镜像是两条不同基线。
- 已连接测试板确认是 ESP32-C3（8 MB XMC 闪存，USB 串口 `/dev/cu.usbmodem1101`），安全启动/闪存加密均关闭。原机分区为 `nvs/phy_init/factory(4 MB)/cardid/storage`；通知固件的分区表按实机布局设置，固件本身不主动格式化 `cardid/storage`。**但从 0x0 写入整份合并镜像会把镜像空洞中的 0xFF 一并写到 NVS / phy_init 分区，可能清除原机 Wi-Fi 配网和其他设置；不能声称完整保留原机数据。**其当前全部 8 MB 内容保存在 `.local-backups/ai-passport-original-flash-2026-10-08.bin`，SHA-256：`b3dce15e5737a0ac85da2194717aa2b5285b28058a04388d0ce367dfb97243c4`。镜像中的应用描述识别为 `passport-os 0.2.0-rc.10`（构建时间 2026-10-06），镜像校验有效。此备份是刷写前的实际原机镜像，**仍未完成显示/按键实测或试恢复，不声称它等同官方公开发行包**。
- `.local-backups/` 被 Git 忽略并且文件权限仅当前用户可读；**原始闪存可能包含 Wi-Fi 密码、BLE 密钥等敏感资料，不要上传分享或提交仓库**。构建目录清理不会删除此文件。
- 如确需恢复原机全部内容，先核对目标设备、文件 SHA-256、8 MB 容量及串口，再执行（**这会覆盖现有数据，仅在明确同意后操作**）：
  ```sh
  source ~/esp/esp-idf-v5.5.3/export.sh
  python -m esptool --chip esp32c3 -p /dev/cu.usbmodem1101 -b 460800 \
    write_flash 0x0 .local-backups/ai-passport-original-flash-2026-10-08.bin
  ```
- 单独恢复上游示例玩法而不复制设备原有数据时，改用 `.local-backups/ai-passport-upstream-main-2026-10-08.bin`；这不是原机完整备份。USB 线需能传数据，刷写期间保持连接。

## 安装与操作（烧录前先确认）

固件输出位于 `build/FoloToy-AI-Passport-full.bin`（合并镜像，偏移 0x0）。教程的本地安装工具写入此文件时**会覆盖 NVS 空洞**；已先保存整片闪存作为可恢复备份，但没有试恢复。若希望尽量保留原机 NVS，请在核对串口、板型和备份后，优先执行 `idf.py -p /dev/cu.usbmodem1101 flash monitor`（分段写入 bootloader / 分区表 / app，不调用整片擦除）；这仍会替换原固件，且现有 NVS 内容与新固件可能不兼容。**无论哪种方式都先征得用户确认再刷机**。串口名称按实际检测为准，不应硬编码。

手机 APK 位于 `android-app/app/build/outputs/apk/debug/app-debug.apk`（本地 Debug 签名）。安装到 Android 手机后：

1. 设备顶部显示“等待连接”；在手机上开启蓝牙。BLE 广播名称仍为 `PassportNotify`。
2. 打开“随身消息通知桥”，授权附近设备（蓝牙扫描/连接），并在系统“通知使用权”中启用本应用。授权通知显示权限，以便前台连接服务显示状态。
3. 在应用列表中**主动勾选**需要转发的短信应用、飞书等应用；点击“启动并连接”。设备显示六位配对码时，在系统弹窗输入对应数字。软件不依赖三星专用 SDK。
4. 设备在 RAM 中保留最近 32 条真实通知，**不再按时间自动消失**；新消息到达后显示最新一条，并显示来源彩色标识和当前位置（例如 `2/2`）。短按上键查看前一条、短按下键查看后一条、短按 OK 将当前消息标记为已处理并移除；到达列表边界或执行操作时会显示短暂浮层。同一条 Android 通知被系统重复发布且内容未变化时会被手机端合并；不同通知即使文字相同也会分别保留。列表满时只移除最早一条。
5. 长按采用 3 秒防误触：长按上键先进入“清空全部”确认页，随后短按 OK 才会清空，短按上/下键取消；长按下键切换静音，状态压缩为顶部右侧的扬声器/静音图标，静音偏好会保存在 NVS；长按 OK 进行屏幕、按键和声音自检。**有消息时按键说明自动隐藏，消息卡片扩展到屏幕底部；只有消息列表为空时才显示操作说明。**重启会清除消息内容，但不会重置静音偏好。连接提示是短暂状态，不占用消息列表。
6. **短信正文/验证码默认隐藏**。确有需要再主动勾选“在设备屏幕显示短信内容/验证码”；任何能看见设备屏幕的人都可能看到内容，建议用后关闭。未开启时，普通应用通知仍保留上下文，只把独立的 4–8 位数字替换为星号，不再隐藏整条消息。
7. 连接后可在手机上点击“发送测试消息到设备”检查链路；这条测试通知也会进入消息列表，需短按 OK 移除。点击“停止连接”结束转发。Android 的厂商省电策略可能让前台服务中断；允许应用必要的后台运行权限。服务断开时未送达消息不会作为通知历史同步。

## Mac 电脑 BLE 冒烟测试

源码位于 `tools/macos_ble_test.swift`。Mac 开启蓝牙后可编译运行：

```sh
xcrun swiftc -framework Foundation -framework CoreBluetooth \
  tools/macos_ble_test.swift -o /tmp/passport_ble_test
/tmp/passport_ble_test "电脑蓝牙测试 · AI Passport 正常"
# 第二个参数可选：保持加密连接的秒数，便于观察“已安全配对”状态
/tmp/passport_ble_test "电脑蓝牙保持连接测试" 300
# 第三个参数可选：app / sms / connect，模拟对应类别的消息
/tmp/passport_ble_test "微信 · 文件传输助手：测试通知" 300 app
```

首次连接使用 BLE Secure Connections + MITM：AI Passport 显示六位码，由 macOS 完成配对；之后使用已保存的绑定密钥自动重连。测试程序只连接广播名为 `PassportNotify` 的设备，发现指定服务/写入特征后发送测试消息；第三个参数可指定应用、短信或连接提示类别。

2026-10-08 已在当前 ESP32-C3 实机上完成三次电脑 BLE 写入：首次安全配对、绑定后自动重连和事件顺序修复后的回归均返回 `PASS`；设备日志确认 `encrypted=1 authenticated=1`、消息触发音频并在断开后恢复广播。仍需人工确认屏幕中文字、提示音听感及三键行为。

## 隐私、协议与验证边界

- 自定义 BLE GATT 服务 `4a17d400-34ad-4d7b-93f8-84237aacc001`，写入特征 `...c002` 要求有 MITM 的认证加密配对。传输不经过互联网服务。只有勾选应用且手机桥接服务运行时才会转发。
- 应用不请求 SMS 权限或飞书 API 登录；Android 12 以下的蓝牙扫描需要旧版定位授权，Android 12 及以上请求附近设备权限，只接收 Android 系统已发布的通知；能否读取短信验证码、飞书内容取决于该应用的通知文本、工作资料和系统策略，**不能保证**所有通知都能转发。
- 硬件不主动写入通知内容到 NVS 或串口；最近 32 条消息只保留在 RAM，用户短按 OK 移除、确认清空或设备重启后删除。NVS 只保存静音偏好和 BLE 配对信息。此设备不是可信安全显示器，验证码等敏感操作优先用手机。
- 单台连接、硬件合计最多保留最近 32 条消息（微信、飞书、短信等共用一个按到达时间排序的列表）；未连接时手机桥接服务的待发队列也有容量限制，无法保证送达或跨断线恢复。当前 BLE 协议只传最多 160 字节的 UTF-8 文本，**不会传送通知图片、头像或附件**；屏幕上的微信/飞书/短信标识由固件本地绘制，其他文字还受小屏尺寸和字库覆盖限制。
- 截至 2026-10-08，基础“消息常驻、前后浏览、3 秒长按”版本已刷入当前 AI Passport；Samsung S25 Ultra 已完成 BLE 安全配对，Android 前台服务和通知使用权已启用，并实测两条飞书真实通知写入成功。当前源码中的紧凑布局、顶部静音图标和“仅空列表显示说明”仍需重新刷入设备后做屏幕观感与按键回归。

协议：消息头 4 字节 `A5 <1=短信|2=其他|3=连接提示> <UTF-8字节数低位> <高位>`，消息正文最多 160 字节 UTF-8。根据 GATT MTU 分块，每块等待写回调；未完成包在断线时丢弃。
