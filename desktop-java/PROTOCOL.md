# Java 截图模块与主程序协议（1）

安装后的启动程序：`<安装根>/capture-java/ShunshoupaiCapture.exe --ipc`。开发启动：`javaw -cp <classes+依赖> com.shunshoupai.capture.Main --ipc`。模型目录由 `-Dshunshoupai.models=<目录>` 指定；打包启动器自带该设置。不要在参数传入凭据。模块不 bootstrap，不接收 token，不访问日程或模型服务。

stdin/stdout 为 UTF-8 JSONL；每行最多 512 KiB。stdout 仅协议消息；stderr 不记录文字、图片或凭据。stdin EOF 和 `shutdown` 均退出并清理快捷键、窗口与 OCR 子进程。主程序应使用隐藏子进程并在退出时关闭 stdin；安装路径含空格时必须传结构化进程参数。首次 `init` 前不会注册快捷键或允许截图。

主程序 → Java：

```json
{"type":"init","protocol":1,"scope":"64位小写hex","context_id":"1","version":"2.1.0","prior_text":"已有输入","has_review":false,"has_pending":false}
{"type":"context","scope":"64位小写hex","context_id":"2","prior_text":"新输入","has_review":false,"has_pending":false}
{"type":"capture"}
{"type":"show-review"}
{"type":"settings"}
{"type":"capture-ready","id":"截图UUID"}
{"type":"capture-rejected","id":"截图UUID","message":"请先完成当前确认，再开始截图。"}
{"type":"accepted","id":"文字UUID"}
{"type":"rejected","id":"文字UUID","message":"输入已改变，请重新确认。"}
{"type":"shutdown"}
```

`scope = sha256(UTF8(origin + "\n" + owner))`，origin 使用主程序当前规范化 origin。`context_id` 使用每次主程序启动的新 UUID 加递增计数（`UUID:counter`），不能在重启后重复使用旧数字；Java 原样回传字符串。主程序在 owner/composer/review/pending 状态变化时递增。scope 变化立即取消当前截图/OCR，旧 scope 的未确认文字保留；若旧内容保存失败则保留编辑窗口，禁用截图/提交，直到能安全切换。主程序必须核对 recognized-text 中 scope 与 context_id 后再执行，不能以 Java 的本机来源代替上下文校验。

Java → 主程序：

```json
{"type":"ready","protocol":1}
{"type":"status","hotkey_available":true,"floating_enabled":false,"pending_count":0}
{"type":"capture-request","id":"截图UUID"}
{"type":"capture-finished","id":"截图UUID","cancelled":false}
{"type":"recognized-text","scope":"64位小写hex","context_id":"2","id":"文字UUID","text":"已编辑文字","mode":"append"}
{"type":"error","code":"CAPTURE_FAILED","message":"可展示的简短错误"}
```

`ready` 表示协议可用；模型是否完整在启动与识别时检查，错误会明确提示。`status` 可用于主程序恢复入口状态。默认 Ctrl+Alt+S；注册冲突不妨碍按钮截图。浮窗默认关闭，在 Java 的 `settings` 中启用/停用。

截图顺序：Java 发 `capture-request` → 主程序记住自身状态并隐藏 → 回 `capture-ready` → Java 隐藏自己的浮窗/对话框，等待绘制后捕获 → 框选结束或取消发 `capture-finished`。主程序有模态确认时可回 `capture-rejected`，Java 立即终止该次等待、回 cancelled 的 capture-finished 并显示原因，不再等待超时；兼容旧主程序用 rejected 携相同 capture id。主程序只恢复此前需要恢复的自身窗口状态，不能强制还原原先最小化的主窗。握手 5 秒超时；过期 id、快速取消重开的旧回包均忽略。锁屏/无可交互桌面不视为成功。

文字确认顺序：Java 本机 OCR → DPAPI 持久化原文 → 用户可编辑（最多提交 2000 个 UTF-16 单元，超长原文完整保留，不截断）→ 明确选择追加/替换 → 发 `recognized-text`。有 prior_text 默认追加；无原输入显示“整理成草稿”。存在 `has_pending` 禁止提交，提示先核对上次保存结果；存在 review 时提示先回主程序处理草稿。主程序仍须再次执行相同保护。

主程序必须先原子持久化新 composer 和此 id 的 receipt，之后回 `accepted`，再进入已有解析流程。重复 id 只回 accepted，不再次追加或解析。Java 收到 accepted 才删除该条待处理文字。未知结果超时仍保存同一 id 的已发送 payload；再次点击重试必须原样重发该 payload。收到 rejected 后保留编辑内容，待 context 更新后允许重新确认。Java 不自动解析或保存日程。

识别文字目录：`%LOCALAPPDATA%/Shunshoupai/capture-java/<scope>.bin`，DPAPI 以 scope 作为附加熵；无明文回退。待处理列表最多 20 条，满时要求先处理旧内容，绝不覆盖。浮窗开关与位置独立保存，不含识别文字。图片仅保留在内存和 OCR worker stdin 管道中。

诊断启动参数：`--memory-smoke --output <目录>` 只生成虚构文字图片并调用真实 OCR 与 Swing review 渲染，不调用 Robot 或物理输入；`--capture-smoke --output <目录>` 必须在可交互 CI 桌面运行真实捕获/热键测试，环境无法支持时失败，不能算通过。

`--ipc-memory-smoke --output <目录>` 需要主程序发送真实 `init`（必须使用隔离本机服务、隔离 QSettings，并把子进程 `LOCALAPPDATA` 设到隔离目录）。它生成两行虚构中文图片、真实 OCR、真实 DPAPI 暂存和 Swing review，使用按钮 `doClick` 确认并发送正常 recognized-text；收到第一次 accepted 后原样重发 payload，收到第二次 accepted 后保存 `java-ipc-memory-smoke.json` 并退出 0。拒绝、超时或流程失败退出 1。此诊断模式没有 Robot/物理输入；生产 `--ipc` 从不自动确认。主程序应断言仅解析一次、生成两行未保存草稿、没有 commit。
