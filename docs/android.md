# Android 客户端

顺手排提供可安装的 Android 客户端，支持 Android 8.0（API 26）及以上版本。客户端使用系统 WebView 连接你自己的顺手排服务，提供独立应用入口、服务地址设置、返回、刷新和连接失败重试。

**APK 不包含数据库服务或模型密钥。** 首次安装需要填写可访问的顺手排服务地址；同一服务、同一账号才会与电脑端共享日程。当前仓库没有默认公开后端，不会把手机上的 `localhost` 当作电脑地址。

## 下载与安装

1. 打开 [GitHub Releases](https://github.com/G-E-E-D/shunshoupai/releases)，下载 Android 版本附带的 `.apk` 文件。
2. 如版本同时提供 SHA-256 文件，可先核对下载文件的校验值。
3. 在 Android 的文件管理器中打开 APK，按系统提示允许该文件管理器安装此来源的应用，再完成安装。
4. 打开“顺手排”，输入服务地址，点击连接。进入工作台后注册或登录自己的账号。

更新时安装同一项目后续发布的 APK，可保留应用配置。安装包的应用 ID、签名密钥必须保持一致，`versionCode` 必须递增。自行构建的调试包使用不同签名，不能直接覆盖官方签名包；如需切换，应先确认服务端账号和日程仍在，再卸载旧包。

Windows 可用以下命令核对下载文件，结果应与 Release 中给出的 SHA-256 一致：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath .\shunshoupai-android.apk
```

实际文件名以 Release 附件为准。

## 连接电脑上的本地服务

让电脑和手机连接同一个可信 Wi-Fi，按 [README](../README.md)安装 Node.js 依赖后，在电脑项目目录运行：

```sh
npm run build
npm run start
```

在 Windows 使用 `ipconfig` 查到电脑的局域网 IPv4，例如 `192.168.1.20`，然后在 Android 客户端填写：

```text
http://192.168.1.20:3000
```

电脑浏览器也访问这个地址、登录同一账号，就能共享同一份服务端数据。电脑需要保持运行，防火墙只对可信局域网放行服务端口。HTTP 局域网演示保持 `COOKIE_SECURE=false`，`APP_ORIGIN` 留空或填写手机实际使用的完整服务来源；不要固定成 `http://localhost:3000`。

手机里的 `localhost` / `127.0.0.1` 指向手机自身。Android 模拟器访问运行它的电脑时通常使用 `http://10.0.2.2:3000`，这也不是实体手机的电脑地址。若连接失败，先用手机浏览器打开相同地址，确认网络、防火墙和电脑服务正常，再回到应用重试。

## 连接公网服务

如果已经按 [云端配置](cloud-setup.md)部署自己的顺手排服务，在 Android 中填写实际的 HTTPS 根地址，例如 `https://your-schedule.example`。地址必须使用 HTTP 或 HTTPS，不能包含用户名、密码、查询参数或片段；应用不接收 `file:`、`javascript:` 等地址。

HTTP 明文访问用于兼容局域网演示，连接页面会提示它不加密传输。公网登录应使用 HTTPS。应用不会绕过证书错误，也不会允许 HTTPS 页面加载 HTTP 混合内容。

客户端的“更换服务”入口可返回地址设置。确认连接另一个服务前，会清理 WebView Cookie 和站点存储；这会退出当前 WebView 中的登录，并清除当前客户端保留的输入草稿，不会删除服务端日程。

## 功能与边界

- 日程解析、可编辑草稿、确认保存、完成、修改、删除和账号隔离沿用服务端工作台。
- 同一账号在电脑与 Android 前台页面间约 8 秒同步，并在页面恢复前台或重连时补拉。
- 未配置服务端模型密钥时，工作台会明确显示规则体验；APK 本身不保存模型或数据库管理密钥。
- 前台站内提醒需要应用页面活跃。关闭应用、锁屏或切到后台，不保证准点提醒。
- 断网不会被显示为保存成功；客户端不提供完整离线数据库、离线编辑合并或独立后台服务。
- 外部网页通过系统浏览器打开；本应用不会通过 JavaScript 桥向网页暴露 Android 原生接口。

WebView 是应用中显示工作台的组件，运行能力仍来自现有顺手排服务。安装 APK 不等于自动发布了一个云端后端。

## 从源码构建

Android 项目在 `android/`，可用 Android Studio 打开，或使用 Gradle Wrapper。按项目 Gradle 配置准备 JDK 和 Android SDK，然后运行：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/`。运行设备测试时连接模拟器或开发手机，再运行 `:app:connectedDebugAndroidTest`。测试使用独立的本机 HTTP 夹具，不依赖付费模型或外部账号。

正式 APK 使用 release 构建及独立签名。CI 通过 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个加密仓库 Secrets 读取签名材料；密钥文件和密码不进入源码仓库。发布者应在安全位置备份签名文件与密码，后续更新必须使用相同签名。

## 验证记录

Android 自动验证覆盖首次连接、地址校验、本机 HTTP 页面加载、连接配置持久化、WebView 限制及失败重试。最终 APK 的构建、签名验证和设备测试结果以对应 [Android CI](https://github.com/G-E-E-D/shunshoupai/actions/workflows/android.yml)与 Release 说明为准。

当前文档不将模拟器测试称作实体手机测试；不同厂商系统、系统 WebView 版本及真实手机网络仍应实际验证。

参考：[Android WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview)、[网络安全配置](https://developer.android.com/privacy-and-security/security-config)、[应用签名](https://developer.android.com/studio/publish/app-signing)、[AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test)。
