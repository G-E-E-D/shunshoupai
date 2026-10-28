# Windows 原生桌面端（v2.2.0）

Windows 保留 C++17 / Qt 6 Widgets 和 Qt Network 主体，不嵌入浏览器；本轮新增截图、悬浮入口、OCR 和文字确认由 Java 模块提供。安装后打开即可使用本机日程，无需注册、登录或维护账号会话。

已发布安装器见[发布列表](https://github.com/G-E-E-D/shunshoupai/releases)。当前源码版本为 2.1.0，正式安装器的构建、签名与发布状态以对应 Release 和[验收记录](acceptance.md)为准。

## 安装与数据

安装器为当前 Windows 用户安装，包含 Qt 动态库、Node.js、Next.js/Node 本机日程服务，以及 Java 截图运行时和中英文 OCR 模型；不用另外安装 Node、Java 或下载语言包。启动时自动准备本机授权和服务，桌面访问 `127.0.0.1:43127`。局域网中的另一设备必须显式配对才能访问日程，不能凭地址或客户端名称获得本机权限。

退出主程序会结束由本次应用启动的服务、Java 截图进程、快捷键和浮窗；不会变成后台常驻应用。手机仍可独立使用自己的日程、模型和草稿。

数据位置：

- `%LOCALAPPDATA%/Shunshoupai/data/app.sqlite`：本机日程与设备资料。
- 同目录 `model-encryption.key`：模型与出站凭据的加密密钥。
- `%LOCALAPPDATA%/Shunshoupai/capture-java/`：按当前本机资料分区的未确认截图文字，使用当前 Windows 用户 DPAPI 加密；截图图片不落盘。
- 当前 Windows 用户的应用设置：普通输入、日程草稿及界面状态。

完整备份应先关闭服务或使用一致性备份，保留上述数据、应用设置以及仍存在的 SQLite `-wal`/`-shm` 文件。DPAPI 受当前 Windows 用户保护，不能假设把密文复制到另一个用户即可解密。升级、重装和卸载保留电脑用户数据；手工删除不在保留范围内。

首次升级沿用旧有效本机资料；多份旧资料只在本机选择一份，其余不删除，只有表单草稿的资料也保留。事件 ID、版本、未确认保存编号和草稿键保持兼容。旧 Supabase 数据不能直接作为本机 SQLite 使用，见[迁移边界](cloud-setup.md)。

## 安排、整理与查看

输入安排，点击“整理日程”或按 **Ctrl+Enter**，核对草稿后保存。可修改标题、时间或移除条目；“暂不保存”不会提交正式日程，草稿可继续恢复。保存结果未知时先核对原请求，不另起相同请求重复创建。

右侧支持当天、七天、已完成、搜索、编辑、完成/恢复和删除，统一采用上海时区。另一端修改同一条日程时保留本地编辑稿，明确处理冲突后再保存。不同日程重叠的同步副本会保留并提示。

“模型设置”配置当前电脑自己的兼容接口、模型和 API Key；测试会请求服务但不会自动保存。没有模型可使用有限规则，模型失败时原文和未提交草稿保留。手机有自己的设置入口与加密存储，配对不能使用或修改本电脑模型。详见[模型设置](model-settings.md)。

## 截图取字与可选浮窗

1. 点击侧栏“截图”，或在其他窗口按 **Ctrl+Alt+S**。主程序和自己的浮窗暂时隐藏，拖动框选需要识别的内容，松开后在本机识别；按 **Esc** 可取消。
2. 核对可编辑的识别文字。没有原输入时点击“整理成草稿”；已有输入时默认“追加并整理”，也可明确选择替换。超过 2000 字会要求缩短，原文不会自动截断。
3. 确认后交回 Qt 主界面的原有整理流程，得到可编辑日程草稿，仍须检查并确认保存。若已有未确认草稿或上次保存结果待核对，先处理它们，再导入截图文字。
4. 暂不处理可点“稍后继续”，以后从“继续识别文字”恢复。主程序尚未确认收到文字时，原文和同一个请求编号仍保留，重试不会重复追加。

**悬浮入口默认关闭**，在“截图”旁的设置图标中开启或关闭，开启后可拖动。全局快捷键冲突时仍可点击截图按钮。退出主程序后快捷键与浮窗停止，截图需要当前用户处于可交互桌面，锁屏时不能保证捕获。

截图和中英文 OCR 使用 Java/Tess4J/Tesseract，图片仅在内存和本机 OCR 子进程管道中处理，不上传、不进入设备同步。确认前不请求模型；确认后，文字按当前电脑模型设置整理，可能发送给用户配置的模型服务。没有识别引擎或模型文件时会报错，不回退到网络识别。模块说明和许可见 [Java 截图模块](../desktop-java/README.md)。

## 配对与日程同步

点击“配对设备”展示或复制二维码和链接，也可识别二维码图片、粘贴图片或另一台设备的链接；桌面不提供相机实时扫码。支持电脑↔电脑、电脑↔安卓及多设备连接。配对码 90 秒有效且只能使用一次，配对只授权交换日程。

确认共享前会说明：本机全部正式日程及未来修改可能经对方继续传播至其他已配设备；模型密钥、未确认草稿和截图不传播。设备列表可解除连接，已传播的副本不能撤回。

地址变化需在原设备重新生成二维码，通过新码核对稳定身份后继续同步，不向新地址发送旧凭据。手机接收同步时需保持应用前台。身份签名不等于 TLS，当前同步仅面向可信局域网，不提供端到端加密。参见[设备同步](device-sync.md)。

## 源码构建与本机授权

需要 Node.js 24、Qt 6.8+ Widgets/Network、CMake 3.21+ 和支持 C++20 的编译器。主应用使用 C++17，ZXing 二维码桥需要 C++20。设好 Qt 的 `CMAKE_PREFIX_PATH` 后：

```sh
npm ci
cmake -S desktop -B desktop/build
cmake --build desktop/build --config Release
ctest --test-dir desktop/build -C Release --output-on-failure
```

Java 截图源码兼容 Java 17，构建和发行使用 Java 21 x64。构建依赖与模型按锁文件下载并校验，具体步骤见[模块 README](../desktop-java/README.md)。主程序默认安装布局包含 `runtime/node.exe`、`resources/backend/server.js` 和完整 `capture-java/` 目录。

源码开发生成至少 32 字符的随机 `APP_DEVICE_BOOTSTRAP_KEY`，把**同一个值**只传给本机 Node 进程和 Qt `--backend` 进程。例如在项目根目录同一 PowerShell 会话中：

```powershell
$env:APP_STORAGE = 'local'
$env:APP_DEVICE_BOOTSTRAP_KEY = node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64url'))"
$devServer = Start-Process -FilePath (Get-Command node).Source -ArgumentList @('node_modules/next/dist/bin/next', 'dev', '--hostname', '0.0.0.0', '--port', '3000') -WindowStyle Hidden -PassThru
.\desktop\build\Release\Shunshoupai.exe --backend http://127.0.0.1:3000
```

Qt 仅向指定的 IP 字面量环回地址发送引导密钥，例如 `127.0.0.1` 或 `::1`；不要把密钥放进 URL、命令行参数、浏览器或仓库。这个源码 Node 进程由开发者自行管理，Qt `--backend` 不会代为停止它。安装版自动处理本机引导，不需要用户设置环境变量。

## 安装器与验证

还需 `windeployqt`、NSIS 3.11、PowerShell 7 和 Java 21 x64。先运行 `npm run build`、构建 Qt Release，再生成同版本 Java app-image，最后打包安装器：

```powershell
.\desktop-java\scripts\build.ps1 -JdkHome 'C:\path\to\jdk-21'
.\desktop-java\scripts\package.ps1 -JdkHome 'C:\path\to\jdk-21'
.\desktop\scripts\package.ps1
```

打包脚本检查 Qt、Node 后端和 Java app-image 的版本一致，产物位于忽略目录 `desktop/dist/`。工作流见 [desktop.yml](../.github/workflows/desktop.yml)。Windows 安装器未使用商业代码签名证书，可核对 Release 校验文件。

内存 OCR 与进程通信测试可在锁屏环境执行，但不能证明真实快捷键、鼠标框选或屏幕捕获通过；物理流程必须在可交互 Windows 桌面验收。安装器还需核对随包运行库、升级与数据保留。实际执行结果以[验收记录](acceptance.md)为准，不把本地测试或工作流配置当作已通过的 CI 结果。
