# Windows 原生桌面端

桌面界面由 C++17 / Qt 6 Widgets 编写，通过 Qt Network 调用共用服务 API，没有嵌入浏览器或 Qt WebEngine。安卓沿用 Java 工程，其 C++ 协议核心与桌面复用。

## 安装与数据

从 [v1.2.0 发布页](https://github.com/G-E-E-D/shunshoupai/releases/tag/v1.2.0)下载 `shunshoupai-desktop-v1.2.0-setup.exe`，双击安装。安装器为当前用户安装，不要求管理员权限，包含 Qt 动态库、Node.js 和应用后端。运行“顺手排”即可自动启动本地服务。

本地服务监听 43127，桌面使用 127.0.0.1，安卓使用电脑局域网 IP。端口占用时明确报错，不会连接到来源未知的进程。退出桌面应用会结束本次启动的后端；已有其他服务不受影响。

数据位于 `%LOCALAPPDATA%/Shunshoupai/data/app.sqlite`，个人模型加密主密钥位于同目录 `model-encryption.key`。备份需保留两者。重装、升级和卸载保留用户数据；手工清理数据意味着删除账号与日程，请自行备份。

桌面 Cookie 仅保存在进程内存，关闭后再次打开需重新登录；密码和模型 Key 不写入桌面设置。对结果未知的保存会按账号和服务记录原编号及草稿，以便恢复时确认而不重复写入。

当前 Windows 安装器没有商业代码签名证书；下载后可将文件 SHA-256 与 Release 的校验文件比较。

## 使用

注册后输入一段安排，点击“帮我整理一下”。草稿表格可以修改标题和起止时间，移除某项或暂不保存；确认后保存正式日程。右侧支持当天/七天、已完成筛选、搜索、手工添加、修改、完成/恢复和删除，时间使用上海时区。

“模型设置”填写兼容 Chat Completions 的 Base URL、模型名、API Key。测试连接会实际发出一个小请求，测试成功后仍需保存。提示词固定在服务端，跨端共享个人配置；界面不读回原始 Key。

“手机扫码登录”选择电脑局域网 IP，生成一次性登录码。手机安装新版安卓客户端，在首页点击“扫码连接”。登录码 90 秒有效且只能使用一次；扫描后自动连接并登录当前电脑账号，请勿转发给他人。手机与电脑保持同一可信局域网并允许端口 43127；电脑应用需保持运行。

若已有公网服务，可从“服务设置”连接其 HTTPS 根地址。切换服务会清除内存登录状态。连接另一个服务不会搬迁原服务的数据。

## 源码构建

安装 Qt 6.8+ Widgets / Network、CMake 3.21+ 和支持 C++17 的编译器。Windows CI 使用 Qt 6.8.3、MSVC 2022 x64；本地也支持匹配的 Qt MinGW 套件。设好 Qt 的 CMAKE_PREFIX_PATH 后：

```sh
cmake -S desktop -B desktop/build
cmake --build desktop/build --config Release
ctest --test-dir desktop/build -C Release --output-on-failure
```

源码调试先启动 Node 后端，然后使用：

```sh
Shunshoupai.exe --backend http://127.0.0.1:3000
```

默认不带参数的启动需要安装布局：主程序旁有 `runtime/node.exe` 和 `resources/backend/server.js`。仓库不保存这类构建产物。

## 生成安装器

需要 Node.js 24、Qt 的 windeployqt、NSIS 3.11 和 PowerShell 7。根目录执行 `npm ci`、`npm run build` 生成 standalone 后端，再构建桌面 Release，执行：

```powershell
./desktop/scripts/package.ps1
```

产物在忽略的 `desktop/dist/` 中。打包脚本复制 Qt 动态库、对应 Node 运行时、后端和许可证，生成安装器及 SHA256SUMS。CI 使用与此相同的 [工作流](../.github/workflows/desktop.yml)。

## 验证范围

原生烟测通过独立数据库注册虚构账号，调用真实解析、保存、防重、修改、模型元数据、扫码兑换及同账号查询，并渲染原生 QWidget 截图。安装器验收还验证随包 Node/Qt、默认服务启动/退出、重装和卸载保留数据。具体完成状态见 [验收记录](acceptance.md)。

真实手机相机、真实模型账号、托管 Supabase 和公网网络仍需要各自外部环境验证。
