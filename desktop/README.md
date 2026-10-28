# 原生 Windows 桌面版

主界面使用 C++17 / Qt 6 Widgets 与 Qt Network；界面不嵌入浏览器。二维码图片解码单独使用 C++20 静态模块。
安装包自带 Node.js 24 和 Next standalone API 服务，用户无需安装开发环境。
无需注册或登录，打开即可使用本机日程。电脑可以与另一台电脑或手机互配，每台设备始终读写自己的本地日程库，连接恢复后自动合并。

## 安装与数据

从 GitHub Releases 下载 `shunshoupai-desktop-v<版本>-setup.exe`，运行即可安装并打开。
默认按当前用户安装到 `%LOCALAPPDATA%\Programs\Shunshoupai`，不需要管理员权限。
桌面与开始菜单均有“顺手排”快捷方式。

数据与程序分开保存：

- 数据库：`%LOCALAPPDATA%\Shunshoupai\data\app.sqlite`
- 模型密钥加密主密钥：同目录的 `model-encryption.key`，首次保存模型配置时自动创建
- 设备授权凭据：`%LOCALAPPDATA%\Shunshoupai\sessions\`，使用 Windows DPAPI 加密，仅当前 Windows 用户可解密，按设备地址和端口分别保存
- 升级、重新安装或卸载程序会保留该数据目录；备份时同时备份数据库和加密主密钥

本地服务监听 `0.0.0.0:43127`，桌面通过 `127.0.0.1:43127` 使用服务。
配对与同步时两台设备须位于可以互通的局域网；电脑退出后，本次启动的 API 服务也会退出，手机仍能独立使用，重新连接后再合并。
端口被占用会明确报告错误，不会连接到未知占用服务。

普通启动始终使用自己的本地日程服务，不需要用户管理账号。点击“配对设备”可显示、复制或保存一次性二维码；“扫描/粘贴对方配对码”支持识别剪贴板图片、选择二维码图片或粘贴链接。电脑侧图片在本机解码；手机可用相机扫描。配对只建立对等连接，不会把本机界面切换到对方的服务或数据库。

配对会交换全部日程；后续修改也可能经对方传播到其其他已配对设备。点击“本机日程”可查看连接状态、解除配对；解除只停止后续交换，不会撤回其他设备已经收到的副本。升级时会接续有效旧会话的日程；旧库有多份日程且无法自动判断时，仅需选择一次，其他数据不会删除。

未整理的文字按日程库自动保留，提醒开关和日程视图会记住上次选择；输入框中按 `Ctrl+Enter` 即可整理。后台补拉没有新数据时保留当前滚动位置和焦点。只有存在未提交草稿或待确认保存时才显示恢复入口。

解析结果表格和手动编辑也会自动保存草稿，包括改过的标题、时间和移除的行。关闭弹窗或重新打开应用后，点击“继续未提交草稿”或“继续编辑日程”即可继续；恢复本身不会提交。已经发起保存但尚未确认结果的内容显示“确认上次保存结果”，始终保留原请求编号和内容，避免重复创建。草稿按日程库隔离，编辑现有日程会保留原版本，避免覆盖另一台设备的更新。

遇到另一端更新时，会同时展示最新日程与本机修改，由你选择采用另一端内容或明确保留本机修改后保存。若另一端已删除，可将本机修改保留为新日程；不会静默覆盖或复活已删除的记录。

多台设备分别修改同一项时保留各个版本，日程卡片会显示“处理 N 个版本”。进入后没有默认选项，必须明确选择要保留的内容或删除版本；修改时间仅用于核对，不用于自动覆盖。

## 从源码构建

Windows x64 开发环境：CMake 3.21+、Visual Studio 2022 C++ 工具、Qt **6.8.3**
`msvc2022_64`（Widgets、Network）、Node.js **24**、NSIS **3.11**。
使用 Visual Studio x64 开发终端，把 Qt 的 `bin` 加到 PATH，并设置 CMAKE_PREFIX_PATH。

桌面显示版本、请求标识、EXE 文件属性和安装器版本统一读取根目录 `package.json`。打包前会检查原生应用和内置后端版本一致，版本更新后需重新构建两者。

CMake 首次配置会下载固定版本 ZXing-C++ 3.1.1 的完整源码归档并校验 SHA-256，只启用 QR 解码并静态链接。ZXing 和解码适配模块使用 C++20，现有 MSVC 2022 / MinGW 13 工具链均可构建，主应用保持 C++17。许可证随安装包放入 `licenses/ZXing/`；运行时不下载解码组件。

```powershell
npm ci
npm run build
cmake -S desktop -B desktop/build -G "Visual Studio 17 2022" -A x64
cmake --build desktop/build --config Release --parallel 2
./desktop/scripts/package.ps1
```

`package.ps1` 要求全新的 staging 目录，防止旧文件混进安装包。重复打包时可用
`-StageDir desktop/stage-next` 指定新目录。脚本调用 `windeployqt`，打包 Qt 插件、
应用目录内的 MSVC 运行时、Node 和最小 API 依赖，并生成 NSIS 安装器与 SHA256 文件。
源码仓库不收录生成的 exe、依赖、数据库或密钥。

安装包内布局：

```text
Shunshoupai.exe
Qt6*.dll, platforms/, tls/, ...
runtime/node.exe
resources/backend/server.js
resources/backend/.next/, node_modules/, public/
licenses/
Uninstall.exe
```

Qt 启动 API 时设置 `NODE_ENV=production`、`HOSTNAME=0.0.0.0`、`PORT=43127`、
`APP_STORAGE=local`、`APP_DB_PATH=<上述数据库>`、`COOKIE_SECURE=false`、`APP_ORIGIN=`。
使用 `Shunshoupai.exe --backend http://服务器:端口` 可连接已有服务。

本机首次身份通过 Qt 启动 Node 时生成的随机 `APP_DEVICE_BOOTSTRAP_KEY` 建立。该值只在进程内存及子进程环境中传递，仅发送到本次启动的完整回环地址的 `/api/devices/bootstrap`，不会进入 URL、启动参数、磁盘或其他 API 请求。局域网访问不能凭 Host、Origin 或 User-Agent 绕过设备授权。

源码调试可显式为本机 Node 和 `--backend` 客户端设置同一个随机 `APP_DEVICE_BOOTSTRAP_KEY` 环境变量；原生客户端只向字面回环 IP 发送它。未持有本机设备授权时，不能读取日程或管理对等连接。所有配对、身份校验和对等交换都由当前本机 Node 完成，配对不会改变客户端 API 地址。

## 安装包验证

```powershell
$version = (Get-Content package.json -Raw | ConvertFrom-Json).version
./desktop/scripts/smoke.ps1 `
  -Installer "desktop/dist/shunshoupai-desktop-v$version-setup.exe" `
  -TestRoot "$env:TEMP/shunshoupai-installer-smoke-new"
```

验证使用全新隔离数据目录，先静默安装，再由安装目录中自带的 Node 启动独立 API，
运行安装后的原生程序验证无需账号的设备启动、未配对访问拒绝、解析、日程保存和修改、扫码配对、解除授权、草稿恢复和截图。
`test-peers.ps1` 另外启动两个独立 Node/SQLite，验证真实二维码图片输入、本机地址不变、双方原有日程合并、并发版本显式处理，以及两个进程重启后的续传和双向解除。测试数据库与密钥位于隔离目录，发布报告只包含虚构日程、通用设备名称、截图和日志。
它还验证普通启动时自带服务的启动与退出，以及重装、卸载保留数据库。
GitHub Actions 的 `Windows Desktop Installer` 执行同样的流程；只有通过后才上传
可发布的安装器、SHA256 和单独的测试报告。发布由仓库维护者将通过验证的产物上传至 Releases。

Qt、Node、二维码库及后端依赖的许可证随安装包分发，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
