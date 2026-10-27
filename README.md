# 顺手排 · 把想法排进生活

电脑端是 **C++ / Qt Widgets 原生应用**。安卓端使用纯 Java 原生日程首页，本机保存日程，无需连接电脑即可新增、修改、完成和删除；连接电脑后自动合并日程，WebView 保留智能整理和账号设置入口。两个客户端连接后使用同一账号和固定的日程提示词。

v1.4.0 包含保持登录、手机离线日程、恢复连接后自动合并、三端草稿恢复和更稳定的刷新。实现与验证范围见 [易用性改进](docs/usability.md)。

[Windows 一键安装 EXE](https://github.com/G-E-E-D/shunshoupai/releases/download/v1.4.0/shunshoupai-desktop-v1.4.0-setup.exe) · [Android APK](https://github.com/G-E-E-D/shunshoupai/releases/download/v1.4.0/shunshoupai-android-v1.4.0.apk) · [发布页](https://github.com/G-E-E-D/shunshoupai/releases/tag/v1.4.0)

![Qt 原生桌面实际渲染](https://github.com/G-E-E-D/shunshoupai/releases/download/v1.4.0/native-desktop.png)

## 安装后使用

手机安装后可直接使用“本机日程”，不必先完成下面的连接步骤。需要与电脑合并或使用智能整理时，再关联一次电脑账号。建议两端一起更新；旧服务没有同步接口时，本机内容仍保留。

1. Windows 运行安装器，打开“顺手排”。安装包包含 Qt 运行库、Node.js 和本地服务，应用会自动启动服务，无需另装 Node。
2. 点击“登录”，注册自己的账号。无预设账号，本地模式不发送验证邮件。
3. 写下一段安排，点击“整理日程”；检查可编辑草稿，确认后才保存。
4. “模型设置”中填写自己的 Base URL、模型名称和 API Key，测试连接后保存。日程提示词固定在服务端，用户不需要填写。
5. 电脑点击“手机登录”，选择局域网地址并生成一次性登录码。安卓点击“连接电脑”后选择“扫码连接”，即可连接并登录同一账号，无需再次输入密码。

手机日常使用无需电脑在线。首次关联和同步时，两台设备需能访问同一服务：本地模式连接同一 Wi-Fi，允许可信局域网访问端口 **43127**，电脑应用保持运行。关联后手机前台自动尝试同步，断线期间继续保存到本机。扫码登录码有效 **90 秒**、只能使用一次；它是临时登录凭据，请勿截图转发。地址二维码仍可用于仅连接、不登录。

Windows 数据保存在 `%LOCALAPPDATA%/Shunshoupai/data/`，升级和卸载保留用户数据。退出桌面应用会停止本次启动的本地服务。Windows 登录凭据使用当前用户的 DPAPI 加密保存在本机，启动自动恢复；主动退出会清除。默认本地会话 30 天有效，使用时每日续期，短暂断网不清除登录。

## 功能与边界

| 能力 | 当前行为 |
| --- | --- |
| 原生桌面 | C++17 / Qt 6 Widgets、Network；日程整理、草稿确认、日程增删改、完成/恢复、当天/七天、搜索 |
| 安卓 | 纯 Java 原生日程首页与 SQLite 离线存储；扫码关联、日期时间选择、搜索、冲突处理；智能整理使用在线 WebView |
| 个人模型 | 每账号保存兼容 Chat Completions 的 Base URL、模型名和 Key；Key 在服务端 AES-256-GCM 加密，仅读回掩码 |
| 固定提示词 | `src/lib/parser.ts` 固定日程系统提示词，模型设置接口拒绝提示词覆盖 |
| 扫码登录 | 高熵一次性码、90 秒有效、数据库原子兑换；二维码 fragment 携带，不含密码、长期会话或模型 Key |
| 跨端同步 | 同服务同账号；手机前台自动重连、上传离线操作并分页拉取含删除记录的日程；重试不重复创建，同条目冲突保留手机修改 |
| 保存保护 | 草稿确认、批量事务、防重编号、版本冲突和时间重叠检查；不确定结果保留原编号核实 |
| 无模型体验 | 明确显示规则解析；不调用付费模型，也不把规则结果当 AI |
| 时间与提醒 | 上海时区单次日程，2000–2100 年、最长 24 小时；提醒仅前台生效 |

未提供 iOS、可靠后台/锁屏提醒、循环日程、自动空档排期、外部日历双向同步或已托管公网 Demo。离线可使用原生日程，智能整理和模型设置仍需连接服务。

默认 SQLite 的手机登录是独立 30 天会话，活跃使用自动续期。可选 Supabase 模式的扫码授权只持续至源 access JWT 到期，到期重新扫码或密码登录；不会复制 refresh token。失效期间手机仍可离线使用。完整说明见 [模型与扫码](docs/model-settings.md)、[桌面安装与构建](docs/desktop.md)、[安卓说明](docs/android.md)、[验收记录](docs/acceptance.md)。

## 从源码运行

仓库保存源码、配置、文档和必要的构建引导文件；`.exe`、`.apk`、构建目录、运行数据和截图只放在忽略目录、CI 附件或 Releases。

后端需要 Node.js 24：

```sh
npm ci
npm run build
npm run start
```

源码后端默认监听 3000。它提供 API 和安卓工作台；普通电脑浏览器不会打开桌面工作台。

桌面需要 Qt 6.8+ Widgets/Network、CMake 3.21+、C++17 编译器：

```sh
cmake -S desktop -B desktop/build
cmake --build desktop/build --config Release
# Windows 示例；连接已启动的源码后端
desktop/build/Release/Shunshoupai.exe --backend http://127.0.0.1:3000
```

Android 使用 JDK 17、Android SDK 36 和仓库内 Gradle Wrapper，不再需要 NDK / CMake。Windows 安装器由 NSIS 构建；完整步骤见平台文档。

## 服务端配置

复制 `.env.example` 为 `.env.local`，按需配置，不要提交密钥。

| 变量 | 用途 |
| --- | --- |
| `APP_STORAGE` | 默认 local；可选 supabase |
| `APP_DB_PATH` | SQLite 路径；默认源码模式 data/shunshoupai.sqlite |
| `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL` | 可选服务端默认模型，个人配置优先 |
| `MODEL_SETTINGS_ENCRYPTION_KEY` | 云端/多实例必须设置一致的 32 字节 Base64 主密钥；本地自动生成私有文件 |
| `AI_ALLOWED_PRIVATE_HOSTS` | 管理员允许的本地模型主机；默认只允许公网 HTTPS |
| `NEXT_PUBLIC_SUPABASE_URL` / `NEXT_PUBLIC_SUPABASE_ANON_KEY` | 可选 Supabase 地址和公开密钥 |
| `APP_ORIGIN` / `COOKIE_SECURE` | 公网实际 HTTPS origin / true；本地局域网留空 / false |

新 Supabase 部署按顺序执行全部 [迁移](supabase/migrations/)；已有部署追加个人模型、扫码配对和离线同步迁移。见 [云端配置](docs/cloud-setup.md)。SQLite 单实例需持久可写磁盘；多实例使用 Supabase 和一致的加密主密钥。

## 检查与结构

```sh
npm run check
npm run test:e2e
ctest --test-dir desktop/build -C Release --output-on-failure
```

Windows CI 真实安装 EXE，验证随包 Node/Qt、原生控件渲染、真实 API、扫码登录、重装/卸载数据保留。Android CI 构建并签名 APK，安装到模拟器并运行纯 Java / WebView 联调与扫码白页恢复回归。测试使用虚构账号和隔离数据库，不调用付费模型。实际结果与外部环境边界见 [验收记录](docs/acceptance.md)。

```text
desktop/        C++ / Qt 原生界面、NSIS、Windows 打包与验收
shared/core/    桌面 C++ 连接协议及其测试
android/        纯 Java 工程、相机、连接校验与 WebView
src/app/api/    认证、日程、个人模型、一次性扫码登录
src/components/ 安卓 WebView 中的日程界面
src/lib/        规则/模型解析、SQLite / Supabase、保存保护
supabase/       迁移及权限/事务测试
tests/          服务、协议和安卓工作台集成测试
docs/           运行、构建、教程和验收文档
```

[使用教程](docs/tutorial.md) · [创作说明](docs/creation-note.md) · [演示脚本](docs/demo-script.md) · [原始技术方案](AI日程应用可行性与技术方案.md)

## 开源协议

本项目原创代码采用 [MIT License](LICENSE)，Copyright (c) 2026 G-E-E-D。

第三方组件继续遵循各自的许可证。第三方组件许可证和 Qt 动态链接/重新构建说明见 [desktop/THIRD_PARTY_NOTICES.md](desktop/THIRD_PARTY_NOTICES.md)。
