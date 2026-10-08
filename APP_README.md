# AI Passport 随身消息（Android 通知桥）

本项目参照 [FoloToy AI Passport 创建玩法教程](https://ai-passport.folotoy.cn/guides/create-a-play-with-agent/) 的开发/编译/验证流程，围绕一个目标：将**普通 Android 手机**上选定应用发布的系统通知，通过 BLE 传给 AI Passport，在设备屏幕短暂显示并播放普通提示音。没有木鱼、计数、飞书服务端接口，也不读取短信数据库。BLE 广播名称 `PassportNotify`。

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

1. 设备显示“等待连接 · PassportNotify”；在手机上开启蓝牙。
2. 打开“随身消息通知桥”，授权附近设备（蓝牙扫描/连接），并在系统“通知使用权”中启用本应用。授权通知显示权限，以便前台连接服务显示状态。
3. 在应用列表中**主动勾选**需要转发的短信应用、飞书等应用；点击“启动并连接”。设备显示六位配对码时，在系统弹窗输入对应数字。软件不依赖三星专用 SDK。
4. 新通知会显示最多约 12 秒，短信约 20 秒，随后自动清空。**短按**上键立即隐藏、下键切换提示音静音、确定键播放自检提示并显示“屏幕与按键正常”；三个键的**长按和双击都不执行额外操作**，防止误触导致敏感消息重新显示。
5. **短信正文/验证码默认隐藏**。确有需要再主动勾选“在设备屏幕显示短信内容/验证码”；任何能看见设备屏幕的人都可能看到内容，建议用后关闭。未开启时非短信通知中的独立 4–8 位数字也会整体遮蔽。
6. 连接后可在手机上点击“发送测试消息到设备”检查链路；点击“停止连接”结束转发。Android 的厂商省电策略可能让前台服务中断；允许应用必要的后台运行权限。服务断开时未送达消息不会作为通知历史同步。

## 隐私、协议与验证边界

- 自定义 BLE GATT 服务 `4a17d400-34ad-4d7b-93f8-84237aacc001`，写入特征 `...c002` 要求有 MITM 的认证加密配对。传输不经过互联网服务。只有勾选应用且手机桥接服务运行时才会转发。
- 应用不请求 SMS 权限或飞书 API 登录；Android 12 以下的蓝牙扫描需要旧版定位授权，Android 12 及以上请求附近设备权限，只接收 Android 系统已发布的通知；能否读取短信验证码、飞书内容取决于该应用的通知文本、工作资料和系统策略，**不能保证**所有通知都能转发。
- 硬件不主动写入通知内容到 NVS 或串口；消息在屏幕显示期间留于内存，关闭/到期后清除界面。此设备不是可信安全显示器，验证码等敏感操作优先用手机。
- 单台连接、最大 8 条手机待发消息；未连接时直接丢弃新通知，无法保证送达、保序跨断线恢复或查看附件。屏幕预览受尺寸和字库限制。
- 编译通过不等于手机和硬件联调通过；截至 2026-10-08，ADB 未发现已连接的 Android 手机且未刷写通知固件。待授权后应依次验证：设备启动/本机 OK 自检，手机 BLE 配对及屏幕六位码，手机测试消息，短信默认遮蔽/显式开启后显示验证码，飞书新通知，设备上键隐藏/下键静音，断线重连与后台运行；记录问题后再小步迭代。

协议：消息头 4 字节 `A5 <1=短信|2=其他|3=连接提示> <UTF-8字节数低位> <高位>`，消息正文最多 160 字节 UTF-8。根据 GATT MTU 分块，每块等待写回调；未完成包在断线时丢弃。
