# 原生 Windows 桌面版

前端使用 C++17 / Qt 6 Widgets 与 Qt Network；界面不嵌入浏览器。
安装包自带 Node.js 24 和 Next standalone API 服务，用户无需安装开发环境。
Android 使用同一台电脑的 API 服务及账号。

## 安装与数据

从 GitHub Releases 下载 `shunshoupai-desktop-v<版本>-setup.exe`，运行即可安装并打开。
默认按当前用户安装到 `%LOCALAPPDATA%\Programs\Shunshoupai`，不需要管理员权限。
桌面与开始菜单均有“顺手排”快捷方式。

数据与程序分开保存：

- 数据库：`%LOCALAPPDATA%\Shunshoupai\data\app.sqlite`
- 模型密钥加密主密钥：同目录的 `model-encryption.key`，首次保存模型配置时自动创建
- 升级、重新安装或卸载程序会保留该数据目录；备份时同时备份数据库和加密主密钥

本地服务监听 `0.0.0.0:43127`，桌面通过 `127.0.0.1:43127` 使用服务。
安卓扫码时两台设备须能够访问同一服务；电脑退出后，本次启动的 API 服务也会退出。
端口被占用会明确报告错误，不会连接到未知占用服务。

## 从源码构建

Windows x64 开发环境：CMake 3.21+、Visual Studio 2022 C++ 工具、Qt **6.8.3**
`msvc2022_64`（Widgets、Network）、Node.js **24**、NSIS **3.11**。
使用 Visual Studio x64 开发终端，把 Qt 的 `bin` 加到 PATH，并设置 CMAKE_PREFIX_PATH。

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

## 安装包验证

```powershell
./desktop/scripts/smoke.ps1 `
  -Installer desktop/dist/shunshoupai-desktop-v1.3.0-setup.exe `
  -TestRoot "$env:TEMP/shunshoupai-installer-smoke-new"
```

验证使用全新隔离数据目录，先静默安装，再由安装目录中自带的 Node 启动独立 API，
运行安装后的原生程序进行注册、解析、日程保存和修改、扫码授权、截图验证。
它还验证普通启动时自带服务的启动与退出，以及重装、卸载保留数据库。
GitHub Actions 的 `Windows Desktop Installer` 执行同样的流程；只有通过后才上传
可发布的安装器、SHA256 和单独的测试报告。发布由仓库维护者将通过验证的产物上传至 Releases。

Qt、Node、二维码库及后端依赖的许可证随安装包分发，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
