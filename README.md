# 顺手排 · 打开即用的本地日程

当前为独立 `beta` 分支的 v2.1.0-beta.1 Go 后端试用版；稳定版代码保留在 `main`。详见 [Go 构建与迁移](docs/go-backend.md)。应用无登录和注册。电脑与手机各自保存日程，通过同一局域网下的扫码配对同步，不依赖云端账号或统一的日程数据库。

[Windows 一键安装 EXE](https://github.com/G-E-E-D/shunshoupai/releases/download/v2.1.0-beta.1/shunshoupai-desktop-v2.1.0-beta.1-setup.exe) · [Android APK](https://github.com/G-E-E-D/shunshoupai/releases/download/v2.1.0-beta.1/shunshoupai-android-v2.1.0-beta.1.apk) · [发布页](https://github.com/G-E-E-D/shunshoupai/releases/tag/v2.1.0-beta.1)

## 日常使用

1. 安装后直接打开。电脑进入本机日程；手机无需连接电脑即可新增、编辑、完成、删除和搜索。
2. 需要同步时，两台电脑或安卓手机连接同一可信 Wi-Fi。一端展示配对码，另一端扫码或粘贴配对链接，确认共享范围后合并日程。
3. 之后连接可用时自动尝试同步。其他设备离线时继续本地保存；手机需保持应用在前台才能接收同步。
4. 输入一句安排可生成可编辑草稿，确认后保存。“模型设置”可配置自己的兼容 Chat Completions 接口，没有模型时明确使用规则解析。

二维码 90 秒有效、只能用一次。配对会共享本机全部日程及未来修改，内容也可能经对方继续传到其已配对设备；模型密钥不会随日程传播。解除配对停止后续授权，无法撤回已传播的副本。仅连接同一 Wi-Fi 或知道设备地址，不会获得日程访问权限。

支持电脑↔电脑、电脑↔安卓、安卓↔安卓两两配对，可连接多台设备，无需固定中心电脑。安卓后台同步和可靠锁屏提醒尚未实现；手机的 Web 智能整理仍需要配对电脑在线。详细规则和边界见 [设备同步](docs/device-sync.md)。

![Qt 原生桌面](https://github.com/G-E-E-D/shunshoupai/releases/download/v2.1.0-beta.1/native-desktop.png)

## 数据与升级

Windows 数据位于 `%LOCALAPPDATA%/Shunshoupai/data/`，升级和卸载保留数据。关闭服务后备份完整数据目录，包括仍存在的 SQLite `-wal`/`-shm` 文件和 `model-encryption.key`；未提交草稿还需保留当前用户的应用设置。手机日程存于应用自己的 SQLite，配对凭据使用 Android Keystore 保护。

旧版单份资料沿用，多份旧资料先在本机选择；保留原日程编号、版本、草稿和模型设置。其他历史资料保留，明确导入后才进入本机主日程，不因配对而全部混合。设备换 IP 后重新扫描同一设备的新配对码，核对稳定身份后继续同步；不会向新地址发送旧凭据。建议所有设备一起升级，v1.x 登录协议与 v2 设备协议不同。

旧 Supabase 模式不作为 v2 的运行后端，已有云端数据保持原样，需要先导出再迁移。历史迁移与回归脚本保留在仓库中。

## 技术与实现

| 部分 | 当前行为 |
| --- | --- |
| 电脑 | C++17 / Qt 6 Widgets 原生界面，随包 Go 本机服务 |
| 手机 | 纯 Java 原生日程、SQLite、本机草稿；在线智能整理使用受限 WebView |
| 设备授权 | 本机启动能力密钥、一次性扫码、每设备可撤销凭据，无邮箱密码接口 |
| 同步 | 每台设备保存主日程，不可变修订及完整祖先关系，分页交换、幂等合并及删除标记 |
| 冲突 | 重叠日程保留；同一项并发修改或删除保留全部版本，由用户明确选择 |
| 易用性 | 三端草稿恢复、时间选择、减少无变化重绘、手机日程优先 |
| 模型 | 自带接口与密钥，AES-256-GCM 加密存储，固定提示词 |

入站授权存储令牌哈希；用于回连的出站凭据加密保存。Go 使用数据库旁 `model-encryption.key`，Windows 客户端使用 DPAPI，安卓使用 Keystore。设备公钥核对用于身份识别，不代表局域网 HTTP 已加密。请在自己信任的局域网配对和同步。

## 从源码运行

先配置 Node.js 24、Go 1.26+、Qt 6.8+ Widgets/Network、CMake 3.21+ 与支持 C++20 的编译器。主应用保持 C++17，ZXing 二维码桥使用 C++20。源码开发的本机引导密钥需要在启动 Go 与 Qt 的同一受控进程环境中设置，长度至少 32 字符且随机；安装版自动处理这一步。

```powershell
$env:APP_DEVICE_BOOTSTRAP_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
npm ci
npm run build
npm run start
# 在继承相同环境的本机进程中启动编译后的 Qt：
# Shunshoupai.exe --backend http://127.0.0.1:3000
```

不要把引导密钥放进浏览器、二维码、日志或 Git；普通远程 `--backend` 地址不能自动得到本机授权。Android 使用 JDK 17+、SDK 36、Gradle Wrapper，无需 NDK。完整构建说明见 [桌面](docs/desktop.md) 与 [安卓](docs/android.md)。

## 检查与发布

```sh
npm run check
npm run test:e2e
ctest --test-dir desktop/build -C Release --output-on-failure
```

每轮更新按项目约定提交、推送，并在同一提交的服务、Windows 和 Android CI 验收通过后发布安装包。源码、配置和文档进入 Git，安装包与截图进入 Releases；密钥、运行数据和构建缓存不提交。

[使用教程](docs/tutorial.md) · [设备协议与迁移](docs/device-sync.md) · [验收记录](docs/acceptance.md) · [模型设置](docs/model-settings.md)

原创代码采用 [MIT License](LICENSE)，Copyright (c) 2026 G-E-E-D。第三方许可与 Qt 动态链接说明见 [第三方声明](desktop/THIRD_PARTY_NOTICES.md)。
