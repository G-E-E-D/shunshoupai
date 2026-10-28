<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## 产品约定

- 本轮开发与发布使用 `main`。新增悬浮窗、快捷键、框选和 OCR 客户端功能使用 Java；保留 main 原有服务架构，不合入 beta 的 Go 迁移。现有桌面仅用最小本机进程协议接入 Java 功能。
- v2 使用无账号、本地优先的设备配对模型。打开即可安排日程，不重新引入注册、邮箱密码或云端账号依赖。
- 手机离线编辑先持久保存；网络失败不清空本机内容。只有显式扫码授权的设备可参与同步，解除配对保留本机副本。
- 旧日程迁移保留事件 ID、版本、草稿与待确认操作编号；不同资料或设备来源不能未经选择自动混合。
- 本机授权必须使用原生进程传递的随机能力密钥，不能因为 Host、Origin、User-Agent 或回环地址就开放数据。
- 新设备地址需要新二维码授权，不能向它发送旧长期凭据；身份签名不等同于 HTTP 通道加密。
- 电脑和手机都是独立副本，任意两台原生设备可在局域网配对。配对会合并本机主日程，并可经其他已配对设备继续传播；不能把另一台电脑作为强制中心。
- 同步使用不可变修改编号及完整因果祖先，保留删除标记和并发修改。设备时间和跨设备版本号不能用于静默覆盖；解决冲突必须基于用户实际看到的版本。
- 配对只交换日程，不开放对端模型设置、智能整理或密钥。手机的整理、模型、草稿、提醒和 OCR 均独立运行。
- 截图 OCR 只处理用户主动框选的内容，识别在本机完成，截图不进入配对或模型请求。识别文字可编辑，整理后须确认草稿才保存；不能静默覆盖已有草稿或绕过系统屏幕捕获授权。

## 提交与发布约定

用户已授权：每次完成项目更新后，同步提交、推送并发布对应新版，无需重复询问。

- 完成相关检查，按改动范围递增版本，保持桌面、安卓、安装包名称和发布文档一致；安卓 versionCode 必须递增。
- 将该轮源码、测试和文档提交并推送到远端，等待该提交的服务、Windows 和 Android 流水线通过。
- 从通过验收的同一源码版本获取 Windows 安装器与正式签名 Android APK，核对签名、文件哈希与版本后创建 GitHub Release，附发布说明和校验文件。
- 保留用户数据和既有正式签名；密钥、运行数据、测试数据库、构建缓存与二进制不提交 Git。
- 构建或验收失败时先修复，遇到必须由用户处理的外部阻碍则明确说明；只有实际发布成功后才能报告“已发布”。
