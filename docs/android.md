# Android 客户端

[下载 v1.2.0 APK](https://github.com/G-E-E-D/shunshoupai/releases/download/v1.2.0/shunshoupai-android-v1.2.0.apk) · [发布页](https://github.com/G-E-E-D/shunshoupai/releases/tag/v1.2.0)

## Java 工程中的 C++ 核心

本版在现有 Java Android 工程上重构，不另建一套 Qt 安卓应用。Java 保留 Activity、WebView、系统权限、相机和 ZXing 入口；NDK / JNI 把真实运行中的连接地址规范化、同源校验和扫码登录协议交给 `shared/core/connection.cpp`。

`ServerAddress.normalize/sameOrigin` 和 `ScannedConnection` 直接调用 `NativeCore`，C++ 结果决定手工连接、扫码连接、WebView 导航和外部地址处理。桌面 Qt 也使用同一 C++ 核心。这不是整个应用或服务端都改写为 C++：日程界面仍在应用内 WebView 中，模型和数据库业务仍在共用服务端。

APK 包含 arm64-v8a、armeabi-v7a 和 x86_64 原生库，支持 Android 8.0 / API 26 及以上。应用 ID 为 `com.shunshoupai.app`，v1.2.0 的 versionCode 为 3，与 v1.1.0 使用相同正式签名，支持覆盖升级。

## 安装和扫码登录

1. 电脑安装并打开 [Qt 桌面客户端](desktop.md)，注册或登录自己的账号。
2. 手机安装 APK，电脑和手机连接同一可信 Wi-Fi。
3. 电脑点击“手机扫码登录”，选择手机可访问的局域网地址，如 `http://192.168.1.20:43127`，生成登录码。
4. 安卓首页点击“扫码连接”，允许相机，扫描电脑上的码，自动连接并登录当前电脑账号。
5. 使用同一账号查看日程，修改或保存后另一端前台约 8 秒补拉，也可手动刷新。

登录码有效 90 秒、只可使用一次，是临时授权凭据，请勿截图转发。扫码 token 在 URL fragment 中短暂传递，兑换后立即清除，不写入 SharedPreferences。C++ 将服务根地址与 token 分开处理；普通地址二维码仍支持只连接、再手工登录。

源电脑在未兑换前退出登录，登录码会失效。本地成功兑换后手机获得独立会话，电脑退出不会强制手机退出。Supabase 模式仅授权至源 access JWT / 会话上限到期，届时重新扫码或使用密码登录。

切换服务会先确认并清理原站点 Cookie 和网页存储；同一来源的扫码授权也会清理旧登录状态，确保使用本次扫描账号。取消扫码、拒绝相机权限或没有相机时可继续手工输入地址。

## 手工连接

填写顺手排服务的 HTTP(S) 根地址，不是模型 API 的 Base URL。安装版桌面默认端口为 43127；源码后端默认 3000。手机上的 localhost 指手机自身，应使用电脑局域网 IP 或公网 HTTPS 地址。

电脑需保持服务运行，并允许可信局域网访问对应端口。公网连接使用 HTTPS；本地 HTTP 仅用于可信局域网。登录后“偏好与账号 → 模型设置”填写自己的模型配置，提示词由服务端固定。仅升级 APK 不会替代已有远程后端升级。

原生工具栏提供返回、刷新、服务设置；断网和加载失败可重试。提醒只在应用页面前台活跃时工作，没有承诺后台或锁屏提醒、离线编辑。

## 从源码构建

要求 JDK 17、Android SDK Platform 36、Build Tools 35.0.0、NDK **28.2.13676358**、CMake 3.22.1。仓库提供 Gradle 8.13 Wrapper 和 AGP 8.13.2。配置本地 Android SDK 路径后：

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Windows 可执行 `gradlew.bat`。调试包和发布包证书不同，不能混装覆盖；正式升级应使用 Releases 正式签名 APK。

正式构建通过环境变量读取签名，不在源码保存证书或密码：

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

设置后运行 `./gradlew :app:assembleRelease :app:lintRelease`。不要提交签名文件、口令、APK、本地数据库或模型密钥。仓库的 [Android CI](../.github/workflows/android.yml)使用加密 Secrets 签名、校验，再运行模拟器。

## 验证

共享 C++ 测试覆盖规范化、默认端口、IPv6、危险协议、凭据、路径/参数、登录码格式和来源越界。Java → JNI → C++ 另有本机实际调用验证。

设备测试在 Android 15 模拟器上运行，覆盖连接、WebView 约束、断线恢复、真实后端日程读写、模型设置入口、扫码地址结果与同账号一次性登录。真实相机光学识别、实体手机与蜂窝网络需外部环境验证；直接调用扫描结果的测试不等同于相机实测。最终运行结果和下载校验见 [验收记录](acceptance.md)及 Release 附件。
