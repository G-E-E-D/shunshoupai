# 顺手排 · 打开即用的本地日程

顺手排 2.1.0 无需注册或登录。电脑和手机各自保存日程，都能独立安排、整理草稿、配置模型和查看日历；需要共享日程时，再在可信局域网扫码配对。**配对只用于同步日程，不借用对方模型或设置。**

[安装包与已发布版本](https://github.com/G-E-E-D/shunshoupai/releases) · [使用教程](docs/tutorial.md) · [Windows 说明](docs/desktop.md) · [Android 说明](docs/android.md)

## 日常使用

1. 安装后直接记录日程。手动新增、编辑、完成和删除先保存在当前设备，其他设备关闭时照常使用。
2. 写一句安排，先生成可编辑草稿；核对标题和时间、修改或移除条目，再确认保存。原文和未确认草稿会保留，重开不会自动提交。
3. 需要从其他页面取字时，电脑点击“截图”或按 **Ctrl+Alt+S**；手机点击“取字”并完成系统授权。框选、本机 OCR、核对文字后进入原有草稿流程，正式日程仍须确认保存。
4. 需要同步时，两台电脑或安卓手机连接同一可信局域网。一端展示配对码，另一端扫码或粘贴配对链接，确认共享范围后自动尝试合并。

Windows 的可拖动悬浮入口默认关闭，可在“截图”旁的设置中开启；退出主程序后快捷键和浮窗停止。手机取字默认关闭，主动开启才请求悬浮与系统截屏授权；同一会话可连续框选，停止后下次重新授权。

没有模型时可用本机有限规则；配置自己的兼容 Chat Completions 接口后，各设备直接请求各自模型服务。电脑关闭不影响手机模型直连；完全断网时，日程、规则、草稿和随包 OCR 仍可用，互联网模型不可用。本机规则不是离线大模型。

## 同步与数据

支持电脑↔电脑、电脑↔安卓、安卓↔安卓及多设备连接，无需固定中心电脑。配对码 **90 秒有效、只能使用一次**。配对会共享本机全部正式日程及未来修改，内容也可能经对方继续传到其已配设备；未确认草稿、模型密钥和截图不随日程传播。解除配对停止后续授权，保留已有副本，无法撤回已传播数据。

断线继续本机保存，手机在前台及回到应用时自动尝试同步。安卓接收服务仅在前台开放，不承诺后台同步或可靠锁屏提醒。悬浮取字服务不代替日程同步、提醒或模型服务在后台运行。详细规则见[设备同步](docs/device-sync.md)和[易用性边界](docs/usability.md)。

不同日程重叠时保留并提示；同一项并发修改或删除冲突时保留不同版本，由用户明确选择。编辑草稿继续使用打开时实际看到的版本，不按设备时间静默覆盖。

Windows 日程和模型数据位于 `%LOCALAPPDATA%/Shunshoupai/data/`，未确认截图文字位于同级 `capture-java/`，普通日程草稿保存在当前用户的应用设置中。升级和卸载保留这些电脑用户数据；完整备份需包含这些位置，并在服务关闭后保留仍存在的 SQLite `-wal`/`-shm` 文件和 `model-encryption.key`。手机日程存于应用自己的 SQLite，凭据及模型 Key 使用 Android Keystore 保护；正式覆盖升级应保留原签名，不要先卸载。

旧版单份资料沿用，多份旧资料先在本机选择；保留原日程编号、版本、草稿和模型设置。其他历史资料明确导入后才进入主日程，不因配对自动混合。换 IP 后重新扫描同一设备的新配对码，核对稳定身份后继续同步，不向新地址发送旧凭据。身份签名不等于 HTTP 加密，请只在可信局域网使用。

## 技术与实现

| 部分 | 当前实现 |
| --- | --- |
| Windows 主体 | 保留 C++17 / Qt 6 Widgets 原生界面，随包 Node.js 24 本机服务 |
| Windows 新增截图 | Java 全局快捷键、可选浮窗、框选与文字确认；Tess4J/Tesseract 本机 OCR，运行时和中英文模型随包 |
| Android | Java 原生工作台、SQLite、独立规则与模型设置、批量草稿、月历、前台提醒；MediaProjection 与随包 ML Kit 中文 OCR |
| 日程后端 | 当前 `main` 使用 Next.js/Node 与本机 SQLite |
| 设备授权 | 本机启动能力密钥、一次性配对码和每设备可撤销凭据，无邮箱密码依赖 |
| 同步 | 不可变修订和完整因果祖先，分页交换、幂等合并、保留删除标记与并发修改 |
| 图片与文字 | 图片仅本机处理；用户确认后的文字才进入整理，配置模型时可能发送给该模型服务 |

模型 Key 不进入配对消息。Node 加密密钥存于数据库旁的 `model-encryption.key`；Windows 客户端和 Java 待处理文字使用当前用户 DPAPI，Android 使用 Keystore。Android OCR 组件可能发送性能和使用统计，详见[Android 说明](docs/android.md)和[第三方许可](android/THIRD_PARTY_NOTICES.md)。

## 从源码运行

电脑主体需要 Node.js 24、Qt 6.8+ Widgets/Network、CMake 3.21+ 与支持 C++20 的编译器；主应用使用 C++17，ZXing 二维码桥使用 C++20。Java 截图模块源码兼容 Java 17，构建和发行使用 Java 21 x64，详见[模块构建说明](desktop-java/README.md)。安装版包含所需运行时，用户无需另外安装 Node 或 Java。

源码开发的本机引导密钥须在启动 Node 与 Qt 的同一受控进程环境中设置，至少 32 字符且随机；安装版自动处理：

```powershell
$env:APP_DEVICE_BOOTSTRAP_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
npm ci
npm run build
npm run start
# 在继承相同环境的本机进程中启动编译后的 Qt：
# Shunshoupai.exe --backend http://127.0.0.1:3000
```

不要把引导密钥放进浏览器、二维码、日志或 Git。普通远程 `--backend` 地址不能获得本机授权。Android 构建使用 JDK 17+、SDK 36、Gradle Wrapper，无需自行编译 OCR 原生库。完整步骤见[桌面构建](docs/desktop.md)和[安卓构建](docs/android.md)。

## 检查与发布

```sh
npm run check
npm run test:e2e
ctest --test-dir desktop/build -C Release --output-on-failure
```

Java 截图模块还需完成自身构建和进程通信检查；物理快捷键、框选和系统捕获必须在可交互 Windows 桌面验收，锁屏下的内存 OCR 测试不能替代。Android 当前本地执行范围见[手机能力矩阵](docs/mobile-parity.md)。

每轮更新按项目约定提交、推送，在同一提交的服务、Windows 和 Android 流水线验收通过后发布安装包。本地测试结果不代表正式包已经发布；具体版本和文件以[发布列表](https://github.com/G-E-E-D/shunshoupai/releases)及[验收记录](docs/acceptance.md)为准。密钥、运行数据、测试库、构建缓存和二进制不提交 Git。

[模型设置](docs/model-settings.md) · [设备协议与迁移](docs/device-sync.md) · [历史云端迁移边界](docs/cloud-setup.md)

原创代码采用 [MIT License](LICENSE)，Copyright (c) 2026 G-E-E-D。第三方许可见 [Qt 与桌面](desktop/THIRD_PARTY_NOTICES.md)、[Java 截图模块](desktop-java/THIRD_PARTY_NOTICES.md)和 [Android](android/THIRD_PARTY_NOTICES.md)。
