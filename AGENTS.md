<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## 提交与发布约定

用户已授权：每次完成项目更新后，同步提交、推送并发布对应新版，无需重复询问。

- 完成相关检查，按改动范围递增版本，保持桌面、安卓、安装包名称和发布文档一致；安卓 versionCode 必须递增。
- 将该轮源码、测试和文档提交并推送到远端，等待该提交的服务、Windows 和 Android 流水线通过。
- 从通过验收的同一源码版本获取 Windows 安装器与正式签名 Android APK，核对签名、文件哈希与版本后创建 GitHub Release，附发布说明和校验文件。
- 保留用户数据和既有正式签名；密钥、运行数据、测试数据库、构建缓存与二进制不提交 Git。
- 构建或验收失败时先修复，遇到必须由用户处理的外部阻碍则明确说明；只有实际发布成功后才能报告“已发布”。
