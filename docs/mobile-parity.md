# 手机独立体验与验收矩阵（v2.2.0）

手机日常功能在手机本机完成，配对仅用于日程交换。没有电脑、没有配对或电脑关闭时，手动安排、本机规则、草稿、日历视图和后台提醒仍可使用；自配模型由手机直接连接，所需网络取决于该模型服务。

当前 Android 为 Java 应用，版本 `2.2.0`、`versionCode=10`。本轮新增悬浮捕获与文字确认同样使用 Java，中文 OCR 使用随包 ML Kit 模型。当前 `main` 的日程后端为 **Next.js/Node**，本轮手机与电脑联调使用该服务及隔离数据库。Windows 保留原有主体，新增截图功能使用 **Java/Tess4J**。

## 能力与使用约定

| 流程 | 手机当前行为 | 自动化验证 |
| --- | --- | --- |
| 第一次打开 | 自动建立本机身份，无需账号或电脑地址 | 全新应用数据下进入原生日程 |
| 手动安排 | 新建、编辑、删除、完成、恢复先保存在手机 | 无电脑保存、重建后读取、过期编辑保留原版本 |
| 一句话整理 | 无模型可用有限规则；有模型时手机直连；“今天/明天”参考实际上海日期 | 本机规则、含糊追问、浏览未来日历后仍按实际日期解析 |
| 批量草稿 | 修改标题与时间、移除或取消勾选条目，显式保存 | 整批原子写入、重复提交幂等、无效条目或时间冲突整批回滚 |
| 草稿恢复 | 原文、批量条目与手动编辑分别保存，恢复不自动提交 | 实际原生控件操作、旋转、关闭重开、核对正式日程数 |
| 模型设置 | 测试、保存、掩码、同源空 Key 保留、移除；不借用配对电脑 | 真实 HTTP fixture、Keystore、来源切换、重定向和错误恢复 |
| 模型失败 | 保留输入与未提交草稿，可明确改用规则 | 断网、无效响应、权限错误和连接失败 |
| 日历与搜索 | 当天、选中日期起七天、月历和快速跳转；已完成查看全部历史 | 今天、第六天、第七天边界、月历选择、完成后恢复 |
| 后台与锁屏提醒 | 系统闹钟、通知跳转、持久回执、重启恢复；不依赖配对 | 真实系统通知、取消与去重、进程回收、Doze、重启 |
| 后台同步 | 独立连接服务、网络恢复重试、系统任务补偿、暂停入口 | 关闭页面和锁屏双向合并、系统任务、暂停与恢复 |
| 屏幕取字 | 主动开启、系统授权、框选、本机 OCR、编辑文字确认、生成草稿 | 真正 MediaProjection 授权、整屏及单应用分享、连续两次捕获 |
| OCR 与原稿 | 不上传图片；确认文字才可整理；已有原稿先询问追加或稍后 | 私有待处理 ID、重复导入、超长不截断、旧草稿不覆盖、普通查看不自动解析 |
| 多设备 | 每端独立副本，已确认日程重连后合并，冲突显式处理 | 双手机真 HTTP、手机与 Node 服务、身份钉住、换地址、撤销、三节点传播 |
| 数据边界 | 模型 Key、未提交草稿与截图不随日程同步 | 独立模型直连、解除配对保留本机内容、同步请求边界 |

手机搜索有意覆盖更大的范围：“日程/七天”页搜索全部未完成项目，“已完成”页搜索全部已完成项目，不受当前浏览日期限制。功能对照不意味着手机和桌面布局完全相同。

## 断网、后台与授权边界

- **电脑关闭、手机可连接模型服务**：手机日程、规则、草稿、视图、OCR 和自配模型均可独立使用。
- **手机完全断网**：日程、有限规则、草稿、视图、随包 OCR 和系统提醒可用；不能请求不可达的模型服务，也没有离线大模型承诺。
- **重新互通**：交换已确认的日程及修订；未提交草稿、截图和模型配置不传播。
- **退到后台或锁屏**：系统闹钟负责提醒，独立后台服务与系统任务负责同步。首次按提示允许通知和锁屏运行；系统强行停止和厂商省电限制不能绕过，见[后台说明](background-operation.md)。取字服务独立运行。
- **屏幕捕获**：同一授权会话可连续框选，并有前台通知与停止入口。停止、锁屏或系统撤销后需重新授权，不缓存授权供下一次重用；受保护画面不绕过系统限制。
- **识别文字**：OCR 在本机进行；只有用户确认后才进入整理。当手机配置模型时，确认后的文字会发往该服务，图片不会发送。ML Kit 可能发送性能和使用统计，不能宣称组件零联网。

## 当前执行证据

以下保留 v2.1.0 的历史验收；v2.2.0 后台与重启结果见[验收记录](acceptance.md)。2026-10-28， `main` 2.1.0/code9 在 API 35/36 使用同一调试 APK 验收；首次断网 OCR 记录单列，并在文末说明构建时点。模型服务使用受控 HTTP fixture，没有调用个人 API Key 或付费模型；日程和截图均为虚构内容。

| 执行 | 结果 | 证据范围 |
| --- | --- | --- |
| API 36 完整 instrumentation | **110/110**，189.917 秒 | 手机独立工作台、规则和模型、草稿、提醒、迁移、复制、权限、OCR 与真实捕获流程；电脑联调使用隔离 Next.js/Node 服务 |
| API 35 捕获与几何定向 | **12/12**，59.73 秒 | 7 个真实捕获流程、5 个裁切/内存上限/授权状态测试 |
| 首次断网 OCR | **5/5**，1.428 秒 | 本轮 OCR 模块首次安装后关闭 Wi-Fi 和移动数据，断言无活动网络，验证中文/英文识别和取消；模型随 APK 提供，无需首次下载 |
| 构建与静态检查 | 通过 | `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug`、版本元数据；Lint 为 0 errors、70 warnings |
| 16 KB 页面兼容检查 | 通过 | APK 中 arm64-v8a/x86_64 OCR 库 ELF 对齐及 APK `zipalign -P 16`，不等同于已在每种 16 KB 真机上运行 |

API 35/36 最终运行的崩溃缓冲区和事件日志均未发现应用崩溃或 ANR。正式签名包及发布结果由同一提交的发布流水线另行验收；本地调试 APK 的通过不能替代该步骤。

关键测试实现：

- [StandaloneExperienceInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/StandaloneExperienceInstrumentedTest.java)：5 项，批量草稿恢复与显式保存、固定可见保存按钮、月历/七天/已完成、模型表单旋转与密钥脱敏、实际参考日期。
- [LocalDraftCommitterInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/LocalDraftCommitterInstrumentedTest.java)：6 项，原子保存、幂等、冲突、回滚和输入边界。
- [LocalPlanningRulesInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/LocalPlanningRulesInstrumentedTest.java)与 [ModelDirectInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/ModelDirectInstrumentedTest.java)：各 8 项，有限规则、严格响应校验、手机模型直连、配置隔离和错误恢复。连接回退先验证全部 DNS 地址，仅在未发出凭据的 TCP 连接失败时换地址，最多四个地址共用 25 秒时限；TLS 或已发送请求不会自动重放。
- [ReminderSchedulerInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/ReminderSchedulerInstrumentedTest.java)：2 项，可控时间下的五分钟窗口、状态过滤、前后台、去重与持久开关。
- [LocalOcrInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/LocalOcrInstrumentedTest.java)与 [OcrImportInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/OcrImportInstrumentedTest.java)：分别 5、6 项，真实随包 OCR、取消、私有文字队列、来源隔离、导入去重、原稿保护和超长文字处理。完整 110 项执行已覆盖最后增加的“普通查看待处理文字不沿用自动整理标志”断言。
- [ScreenCaptureFlowInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/ScreenCaptureFlowInstrumentedTest.java)：7 项，真实系统同意/拒绝、同会话连续截图、本机识别后进入草稿、外部任意文字不能绕过私有队列、旋转复用同一显示、悬浮权限撤销、通知停止后重新授权、受保护画面和单应用分享。
- [CaptureGeometryInstrumentedTest](../android/app/src/androidTest/java/com/shunshoupai/app/CaptureGeometryInstrumentedTest.java)：5 项，实际图像边界、横竖屏留白映射、无效选区、分配上限和授权结果不能重用。

## 日志、截图和历史范围

本地隔离证据目录为 `artifacts/ocr-beta3/`。目录名沿用本轮开发阶段，**最终 `main` 结果和 APK 版本是 2.1.0/code9**：

- `main-all-instrumentation.log`、`main-all-crashes.log`、`main-all-events.log`：API 36 最终完整结果。
- `final-api35-capture.log`、`final-api35-crashes.log`、`final-api35-events.log`：API 35 最终捕获结果。
- `build-final-main.log`、`release-metadata-main.json`、`native-alignment-main.log`、`zipalign-main.log`、`SHA256SUMS-main.txt`：构建、版本及产物校验。
- `api35/` 和 `api36/` 各有 `android-ocr-selection.png`、`android-ocr-review.png`、`android-ocr-drafts.png`，显示真实虚构画面框选、可编辑文字核对和待保存草稿，不包含个人安排、API Key 或配对凭据。
- `ocr-first-offline.log`：首次断网 5 项证据，执行于恢复到 `main` 前的开发构建；对应 OCR 引擎及测试源码与当前版本一致。它证明随包 OCR 首用离线，不替代当前版本的完整回归。

CI 中这三张 OCR 截图在测试内复制到固定目录 `/data/local/tmp/shunshoupai-ocr/`，避免 Gradle 卸载测试应用后丢失，再收集为构建证据。测试源码里的独立画面 Activity 只存在于调试或测试 APK，不随正式应用发布。

`artifacts/mobile-standalone/` 中 84 项完整、13 项定向及 8 项模型回退记录属于早先 **2.1.0-beta.2/code8** 的历史验收，不能当作当前 APK 的发布结果。当前 API 36 的 110 项已重新覆盖这些独立手机能力。厂商真机、实拍相机、离线大模型和所有第三方模型服务不在该历史记录的已验证范围内。
