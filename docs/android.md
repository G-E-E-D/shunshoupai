# Android 客户端（v1.4.0）

启动直接进入原生本机日程，首次安装不连接电脑也可新增、编辑、完成和删除。第一次关联电脑后，本机日程合并进当前账号；之后按服务和账号隔离保存。前台及回到应用时自动同步，断网继续本地保存。日程重叠保留双方内容并提示；同一日程同时修改时保留手机稿，提供版本选择。智能整理和账号设置仍通过在线 WebView。

[下载 APK](https://github.com/G-E-E-D/shunshoupai/releases/download/v1.4.0/shunshoupai-android-v1.4.0.apk) · [发布页](https://github.com/G-E-E-D/shunshoupai/releases/tag/v1.4.0)

## 恢复纯 Java，保留新功能

本版以最初 Java 工程为基线，移除 Android 的 NDK、JNI、C++ 动态库和 CMake 配置。服务地址规范化、同源判断、扫码协议、Activity、相机、Cookie 与 WebView 生命周期均由 Java 处理。保留个人模型、固定提示词、扫码自动登录和日程同步。

应用 ID 仍为 `com.shunshoupai.app`，versionName 为 1.4.0、versionCode 为 5；沿用正式签名，可覆盖旧版正式安装，保留应用数据。最低 Android 8.0 / API 26；桌面端继续使用原生 C++ / Qt。

## 扫码后白页的处理

用户反馈红米 K80 / Android 16 在 1.2.0 扫码后白页，而 1.1.0 正常。当前没有该实体设备的远程调试记录，不能把具体故障直接归因于 C++ 或宣称已在 K80 真机复现。

已确认并可用自动化复现的缺陷：旧版在进度 100% 或 `onPageFinished` 时撤销加载超时；HTTP 200 空文档、界面未完成初始化或未绘制都可能被误当作成功，持续留在白页。

本版：

- 普通扫码切换复用原 XML WebView，避免相机返回时重建显示上下文。
- 使用显式 `/mobile` 入口，不依赖手机厂商对 User-Agent 的处理。
- 显示原生加载面板，等待界面初始化和可绘制帧后才撤下。
- 空页或加载异常保留超时保护，显示原生错误及重试；可切换软件兼容渲染。
- 旧服务没有 `/mobile` 时，仅在同来源 404 回退根入口一次；令牌只短暂保留在内存，不放入查询参数或偏好存储。
- 返回前台、刷新、同来源重新扫码、渲染进程异常都有恢复路径。

K80 的具体系统渲染兼容性仍需安装新版后实机复验。请优先一起更新桌面端和 APK，以获得完整功能；旧服务仅兼容其已有接口。

## 安装、连接与登录

当前源码构建的 APK 安装后直接使用本机日程；以下步骤只在需要关联和同步电脑时执行。首页点“连接电脑”即可进入扫码/手动地址界面。

1. 电脑安装并打开 [Qt 桌面客户端](desktop.md)，注册或登录自己的账号。
2. 手机安装 APK，两台设备连接同一可信 Wi-Fi。
3. 电脑点击“手机登录”，选择可访问的局域网地址，例如 `http://192.168.1.20:43127`，生成一次性码。
4. 安卓点击“扫码连接”，允许相机并扫描，自动登录同一账号。
5. 同步时保持电脑应用运行，允许可信局域网访问端口 43127。电脑离线时，当前源码版本可返回“本机日程”继续使用，恢复连接后自动同步。

登录码有效 90 秒、只可使用一次，是短期登录凭据，不要截图转发。普通地址二维码仍可只连接，再使用密码登录；没有相机或拒绝权限时可手工填写服务地址。手机的 localhost 指手机本身，不能代替电脑 IP。

切换服务先确认并清理原站点 Cookie / 网页存储。同来源扫码也会切换至本次授权账号；扫码 token 不进入 SharedPreferences 或 Activity 保存状态。

当前源码的本地兑换获得独立 30 天会话，活跃使用自动续期；Supabase 只授权至源 access JWT / 会话上限到期，届时重新扫码或密码登录，不复制 refresh token。登录失效期间本机日程照常可用，等待重新登录再同步。模型设置中的 Base URL 是服务商接口，安卓连接设置中的地址是顺手排服务，两者用途不同。

## 原生与应用内界面

连接页、加载/错误页和工具栏使用蓝白 Material 风格；WebView 内的日程、登录、模型设置、按钮、日期条和事件卡片同步更新。当前源码提供原生日程离线编辑，提醒仍只在在线工作台前台活跃时工作，未提供可靠后台提醒。

## 从源码构建

需要 JDK 17、Android SDK Platform 36、Build Tools 35.0.0。使用 Gradle 8.13 Wrapper、AGP 8.13.2；**不需要 NDK 或 CMake**。

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。正式发布通过环境变量读取签名：

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

设置后运行 `./gradlew :app:assembleRelease :app:lintRelease`。调试包与正式包签名不同，不应混装；正式覆盖升级使用 Releases APK。不要把证书、口令、APK、数据库或模型密钥提交仓库。

## 验证范围

当前源码更新已实际构建 debug APK 和测试 APK，通过 Java 编译和 lint（0 errors）；独立 Android 35 模拟器 19 项 instrumentation 全部通过，另补验手机离线先记日程、扫码后与电脑的重叠日程自动合并，两端保留全部内容。调试包使用开发签名；此结果不代表已经重新发布正式 APK。详见 [当前验收](acceptance.md)。

本机纯 Java 协议测试覆盖安全来源、IPv6、端口与扫码 token；移动界面集成覆盖显式入口、可交互标记、失败恢复和扫码同账号登录。

模拟器回归新增 HTTP 200 空白页面、超时后重试恢复、同来源扫码后返回前台/刷新、WebView 实例保留和旧服务回退。相机 Activity 的进入/退出可以验证生命周期，不能替代实体相机对焦与光学识别。最终设备测试统计见 [验收记录](acceptance.md)。
