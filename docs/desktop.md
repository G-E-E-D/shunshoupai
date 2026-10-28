# Windows 原生桌面端（v2.1.0-beta.2）

桌面使用 C++17 / Qt 6 Widgets 和 Qt Network，不嵌入浏览器。安装后打开即可使用本机日程，无需注册、登录或维持账号会话。

[Windows 安装器](https://github.com/G-E-E-D/shunshoupai/releases/download/v2.1.0-beta.2/shunshoupai-desktop-v2.1.0-beta.2-setup.exe) · [发布页](https://github.com/G-E-E-D/shunshoupai/releases/tag/v2.1.0-beta.2)

## 安装与数据

安装器为当前 Windows 用户安装，包含 Qt 动态库、Go 服务、SQLite 与静态界面。启动时自动生成本机授权并启动服务；授权不根据 Host、Origin 或客户端名称放行。桌面访问 `127.0.0.1:43127`，手机通过可信局域网访问电脑。退出桌面会结束由本次应用启动的服务；手机仍可独立使用。

数据库位于 `%LOCALAPPDATA%/Shunshoupai/data/app.sqlite`，模型加密主密钥为同目录的 `model-encryption.key`。备份应先关闭服务或使用一致性备份，并保留数据库和密钥文件；未提交草稿保存在当前 Windows 用户的应用设置中，完整备份也应保留这些设置。升级、重装和卸载保留电脑用户数据，手工删除数据则不会保留。

Windows 设备授权 Cookie 使用当前系统用户的 DPAPI 加密。它是本机或显式配对设备的访问凭据，不是邮箱密码登录。网络错误不会清除草稿或配对资料；本机服务可用后恢复工作。

首次升级时，旧有效本机会话或唯一旧资料会沿用原空间；存在多份旧资料时，仅在本机选择继续使用的一份，其余数据不删除。选择也包含只有客户端草稿的旧资料。事件 ID、版本、原保存编号和草稿键保持兼容。旧 Supabase 数据不能直接作为本机 SQLite 使用，见[迁移边界](cloud-setup.md)。

## 日常操作

输入一段安排，点击“整理日程”或按 Ctrl+Enter，核对草稿后保存。草稿表格及手动编辑内容会自动保留；“暂不保存”不会提交到正式日程，明确放弃才删除草稿。保存结果未知时，通过恢复入口核实原请求，避免重复创建。

右侧支持当天、七天、已完成筛选、搜索、编辑、完成/恢复和删除，采用上海时区。另一端修改同一条日程时，保留本地编辑稿，展示冲突后再明确选择。模型配置方法见[模型设置](model-settings.md)。

点击“配对设备”展示或复制二维码和链接，也可识别二维码图片、粘贴图片或另一台设备的配对链接；桌面不含相机实时扫码。支持电脑↔电脑、电脑↔安卓及多设备连接，配对码 90 秒有效且只能使用一次。确认前会说明：本机全部日程及未来修改可能经对方继续传播至其他已配设备，模型密钥不传播。设备列表可解除连接，已传播的日程副本不能撤回。

设备地址变化后需重新生成二维码，通过新码核对原设备并继续同步，不向新地址发送旧凭据。安卓接收同步时需保持前台。身份签名不等于 TLS；当前同步仅面向可信局域网，不提供端到端加密。

## 源码构建与本机授权

需要 Node.js 24、Go 1.26+、Qt 6.8+ Widgets/Network、CMake 3.21+ 和支持 C++20 的编译器。主应用使用 C++17，ZXing 二维码桥需要 C++20。设好 Qt 的 `CMAKE_PREFIX_PATH` 后：

```sh
npm ci
cmake -S desktop -B desktop/build
cmake --build desktop/build --config Release
ctest --test-dir desktop/build -C Release --output-on-failure
```

源码开发需生成至少 32 字符的随机 `APP_DEVICE_BOOTSTRAP_KEY`，将**同一个值**只传给本机 Go 进程和 Qt `--backend` 进程。下面在项目根目录的同一 PowerShell 会话中启动两者；可执行文件路径以 MSVC Release 构建为例：

```powershell
$env:APP_STORAGE = 'local'
$env:APP_DEVICE_BOOTSTRAP_KEY = node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64url'))"
$devServer = Start-Process -FilePath '.\backend\bin\shunshoupai-server.exe' -ArgumentList @('--web', 'out', '--host', '0.0.0.0', '--port', '3000') -WindowStyle Hidden -PassThru
.\desktop\build\Release\Shunshoupai.exe --backend http://127.0.0.1:3000
```

Qt 仅向指定的 **IP 字面量环回地址**发送此密钥，例如 `127.0.0.1` 或 `::1`；不要用 `localhost`、局域网地址或公网地址代替此开发入口。不要把密钥写进 URL、命令行参数、浏览器或仓库。这个源码 Go 进程由开发者自行管理，Qt `--backend` 不会代为停止它。

安装版自动生成并传递授权密钥，不需要上述环境配置。默认不带 `--backend` 的启动依赖安装布局：主程序旁有 `resources/backend/shunshoupai-server.exe` 和 `resources/web/`。

## 生成安装器与验证

还需 Qt 的 `windeployqt`、NSIS 3.11 和 PowerShell 7。运行 `npm run build`、构建桌面 Release 后执行：

```powershell
./desktop/scripts/package.ps1
```

产物在忽略目录 `desktop/dist/`，包含安装器和 SHA256SUMS；工作流见 [desktop.yml](../.github/workflows/desktop.yml)。Windows 安装器未使用商业代码签名证书，可核对 Release 校验文件。

测试覆盖本机自动授权、设备隔离与撤销、日程保存防重、草稿恢复和原生界面。安装器还需检查随包运行库、升级及数据保留。具体结果以[验收记录](acceptance.md)为准；不将模拟器或受控模型测试视为实体相机、真实模型或公网验收。
