# Java 截图与本机 OCR

此模块为 Windows 主程序提供 Ctrl+Alt+S 全局快捷键、可选悬浮入口、多屏框选、中英文离线识别和可编辑文字确认。截图、OCR、浮窗与确认逻辑均使用 Java 编写；JNA/Tesseract/Leptonica 使用第三方原生 DLL。模块不连接配对设备、不登录、不获取主程序 token、不直接写日程。

主程序接入见 [PROTOCOL.md](PROTOCOL.md)。识别图像仅经过内存和 worker 标准输入管道。确认前不会请求模型服务；确认后的文字由原生日程主程序按其既有模型设置整理，文字可能发送给用户配置的模型服务。

日常使用：

1. 打开顺手排，点击侧栏“截图”，或在其他窗口按 **Ctrl+Alt+S**。拖动框选文字，松开开始本机识别；**Esc** 取消框选。
2. 核对识别结果。已有输入时默认“追加并整理”，也可明确选择替换；合并后超过 2000 字会提示缩短，原文不会截断。确认后进入原来的日程草稿表格，仍需确认保存。
3. 暂时不处理可点“稍后继续”，之后从“继续识别文字”恢复。截图旁的设置图标可开启可拖动悬浮入口，默认关闭；退出主程序后快捷键与浮窗一并停止。

无需配对设备，也无需另装 Java 或下载语言包。快捷键被占用时仍可使用截图按钮。已有未确认草稿或待核对保存结果时，先在主程序处理，再继续导入截图文字。

发行依赖的固定版本如下，精确官方地址和校验值保存在 `scripts/*lock.json`：

| 项目 | 版本 |
| --- | --- |
| Java 语言级别 | 17 |
| 随包运行时 | Eclipse Temurin 21.0.12.1+1，Windows x64 |
| Tess4J / Tesseract | 5.20.0 / 5.5.3 |
| Lept4J | 1.24.0 |
| JNA / JNA Platform | 5.19.1 |
| Gson / SLF4J | 2.13.2 / 2.0.18 |
| 中文、英文模型 | tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd` |
| Maven / JUnit Jupiter | 3.9.11 / 5.11.4，仅构建与测试 |

应用版本由根 `package.json` 传入 Maven、启动器及 `version.txt`，不单独维护版本号。Windows 原有 Qt 主体与 Node 本机服务保留，新增截图相关界面和处理逻辑由此 Java 模块提供。

Java 源码兼容 Java 17，构建/发行使用 Java 21 x64。正常构建只下载锁文件指定的官方 Maven artifact 与模型，并校验 SHA-256；Maven 本身验证固定 SHA-512。普通构建以 Maven offline 模式运行，不更新依赖锁。

```powershell
.\desktop-java\scripts\build.ps1 -JdkHome 'C:\path\to\jdk-21'
.\desktop-java\scripts\package.ps1 -JdkHome 'C:\path\to\jdk-21'
# 默认只做内存 OCR 与进程通信，不操作物理桌面。
.\desktop-java\scripts\check-package.ps1
# 可交互 Windows CI 桌面单独运行真实热键/框选检查：
.\desktop-java\scripts\check-package.ps1 -PhysicalCapture
```

模型随安装包分发，用户不需要下载语言包。完整 app-image 放在 `desktop-java/dist/ShunshoupaiCapture`；主程序安装时将其整体复制到 `capture-java` 子目录。发行运行时固定为 `scripts/runtime.lock.json` 中的 Eclipse Temurin JRE 并验证官方发布 SHA-256，不重新分发开发机 JDK。运行时包含 `bin/javaw.exe`，供独立 OCR worker 使用。缺模型/引擎时给出恢复提示，不回退到网络识别。

维护者需要升级依赖时，先审查 `pom.xml` 和固定官方版本，再运行 `scripts/update-dependencies.ps1`，审查新锁文件。模型更新必须同时固定官方 Git commit 与两份模型的 SHA-256。第三方许可和 VC++ v14 运行库要求见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

自动验证包括多屏负坐标/混合 DPI 几何、取消后重开、真实 DPAPI、快捷键冲突及释放、真实子进程内存 OCR、中文含空格模型路径、缺模型/超时/取消恢复、2000 字边界、已有草稿保护以及确认回包丢失后的同 id 重试。单元测试不产生物理键鼠输入。

```powershell
# 锁屏时可用：虚构内存图片 → 真实本机 OCR → Swing 内容渲染。
java -cp 'desktop-java/target/shunshoupai-capture.jar' com.shunshoupai.capture.Main --memory-smoke --model-dir 'desktop-java/.tools/tessdata' --output 'desktop-java/target/memory-smoke'

# 仅可交互 Windows 测试桌面：真实热键 → 框选 → 屏幕捕获 → OCR。
java -cp 'desktop-java/target/shunshoupai-capture.jar' com.shunshoupai.capture.Main --capture-smoke --model-dir 'desktop-java/.tools/tessdata' --output 'desktop-java/target/capture-smoke'
```

锁屏、无桌面或受限 runner 上物理 smoke 必须失败，不能把它当作截图通过证据。内存 smoke 不证明多屏硬件、物理快捷键和系统截图链路；这些由可交互 CI 与实际设备验收。

安装器 CI 先从实际安装目录运行 `--java-capture-smoke`，验证 Qt → 打包 Java → 本机 Node 的两行草稿交接，以及同 id 重试不会重复整理；随后运行 Java `--capture-smoke`，要求真实系统热键送达、鼠标拖选、Robot 读取屏幕和 OCR 全部成功。物理报告必须同时满足 `ok`、`real_local_ocr`、`physical_capture` 为 true，不能用内存测试替代。

未确认文字存于 `%LOCALAPPDATA%/Shunshoupai/capture-java`，按主程序 origin+owner 的 hash 分区并使用当前 Windows 用户 DPAPI 加密；图片不落盘。加密保存失败不会回退到明文。确认请求结果未知时保留原 id/payload，直到主程序确认持久化；退出后可继续核对，不会静默重复追加。
