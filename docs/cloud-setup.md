# 云端配置与部署

本地模式已经能让手机、电脑共用一份真实数据：两台设备访问同一个 Node.js 服务并登录同一账号即可。需要公网访问、使用无持久磁盘的平台，或把前端服务部署到多个实例时，选择 Supabase 模式。

本文提供可复现的云端接入步骤。仓库中的自动测试不会使用你的云端账号；未填入真实配置前，不代表已经完成公网部署或模型联调。

## 1. 创建 Supabase 项目

1. 创建自己的 Supabase 项目，记录 Project URL 与公开的 anon key / publishable key。
2. 在 SQL Editor 中执行 [初始化迁移](../supabase/migrations/20261027000000_initial.sql)。这是新项目的一次性迁移；不要在同一数据库反复执行。已有业务数据应使用新的增量迁移。
3. 在 Authentication → Providers 中启用 Email。默认开启邮箱确认时，注册后必须先点邮件中的确认链接，再回到应用登录。应用不会把“等待确认”显示为已经登录。
4. 在 Authentication → URL Configuration 中设置实际站点的 Site URL。仅添加自己使用的 HTTPS 域名和开发回调地址；不要使用宽泛的通配地址。
5. 从 `.env.example` 复制为 `.env.local`，配置：

```dotenv
APP_STORAGE=supabase
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=your-public-project-key
APP_ORIGIN=https://your-domain.example
COOKIE_SECURE=true
```

在本机 HTTP 开发时使用 `APP_ORIGIN=http://localhost:3000`、`COOKIE_SECURE=false`。生产 HTTPS 必须开启安全 Cookie。公开的 Supabase 项目密钥可以用于客户端；`service_role`、数据库密码、模型 API 密钥不应出现在 `NEXT_PUBLIC_` 变量、截图、聊天、日志或 Git 仓库里。

本地账号与 Supabase 账号属于不同认证系统，切换模式不会自动迁移账号或日程。请在新模式下注册自己的账号。

参考：[Supabase Next.js 接入](https://supabase.com/docs/guides/getting-started/quickstarts/nextjs)、[服务端会话与 Cookie](https://supabase.com/docs/guides/auth/server-side/creating-a-client?framework=nextjs)、[邮箱密码认证](https://supabase.com/docs/guides/auth/passwords)。

## 2. 表、权限和 RPC

初始化迁移创建：

| 对象 | 作用 |
| --- | --- |
| `profiles` | 每个账号的上海时区配置；注册触发器自动建立记录 |
| `events` | 正式日程、版本号和软删除标记 |
| `mutation_requests` | 用户与请求标识唯一，记录规范化内容哈希及原始保存结果 |
| `ai_daily_usage` | 按 UTC 日期计算的每账号解析次数 |
| `commit_events(p_request_id, p_events)` | 一次确认 1–5 项 `EventDraft`；事务内校验、冲突检查、防重和写入 |
| `mutate_event(p_id, p_base_version, p_patch, p_delete)` | 版本校验后修改、完成或软删除 |
| `consume_ai_quota(p_limit)` | 原子消耗每日配额，限制值 1–100 |

读取依赖当前用户的 RLS 策略。浏览器不能直接插入、修改或删除事件表；所有写入通过受控 RPC。数据库函数使用固定搜索路径、显式检查 `auth.uid()`，并统一获取用户级事务锁，防止“另一端刚新增了一项”绕过冲突校验。日程使用半开区间：09:00–10:00 与 10:00–11:00 可以相邻。只有未删除、未完成的日程占用时间；恢复一项已完成日程时也会重新检查冲突。

`events` 中保存实际时间点 `timestamptz`，另外保存 `Asia/Shanghai`。本版接受 2000–2100 年的日期，避开历史夏令时语义。客户端传来的本地日期时间在数据库内再次验证，并转换为上海时区的实际时间点。重试提交必须沿用原 `request_id` 和原内容；同一标识换内容会被拒绝。

当前应用通过前台约 8 秒轮询、聚焦、重连和手动刷新补拉数据，不依赖 Realtime 配置。若后续实现 Realtime 订阅，可按官方文档将 `events` 加入发布；订阅只能作为重新查询的提示，不能代替保存 API 的事务结果与 RLS。

参考：[RLS](https://supabase.com/docs/guides/database/postgres/row-level-security)、[数据库函数及安全](https://supabase.com/docs/guides/database/functions)、[Postgres 变更订阅](https://supabase.com/docs/guides/realtime/postgres-changes)。

## 3. 配置真实模型

配置服务端环境变量，然后重新启动 Node.js 服务：

```dotenv
AI_API_KEY=your-private-model-key
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-flash
AI_DAILY_LIMIT=50
```

接口使用兼容 OpenAI Chat Completions 的 JSON 输出模式。示例型号根据 2026-10-27 的 [DeepSeek 官方接入文档](https://api-docs.deepseek.com/)填写；模型名称仍应以实际账号可用配置为准。其他服务商须支持同等请求格式，并将 `AI_BASE_URL`、`AI_MODEL` 改为实际账号可用配置。不要假设任意模型都支持相同参数。

没有密钥时，界面明确显示“规则解析”，仅支持有限的中文固定时间表达。配置密钥后才会发出真实模型请求。模型超时、返回非法结构或拒绝请求时，应用保留输入并返回错误，不会以伪造结果冒充成功。只有用户确认草稿后才保存到数据库。服务端对每账号每日解析次数设限，UTC 零点重新计数；已发起的解析即使失败也计入次数，重试将再次消耗配额，达到上限后拒绝新的解析。

原始文本只会发送给你配置的模型服务，运行日志不应保存完整日程原文。使用前自行确认服务商的可用区域、数据处理方式和账号额度。可先使用虚构安排进行联调。

参考：[DeepSeek JSON 输出说明](https://api-docs.deepseek.com/guides/json_mode/)。

## 4. 部署 Node.js 服务

运行时需要 Node.js 24 或更新的 LTS 版本：

```sh
npm ci
npm run build
npm run start
```

通过托管平台配置环境变量，以 HTTPS 域名访问；反向代理应保留原请求来源，并把 `APP_ORIGIN` 设为浏览器实际访问的完整 origin。不要把 API 响应放进公共 CDN 缓存。认证与个人日程 API 已设置禁止缓存。

本地 SQLite 模式只适用于一个 Node.js 服务实例，并要求可写、持久磁盘。把 `APP_DB_PATH` 指向持久卷，备份数据库和对应 WAL 文件时应停机或使用 SQLite 一致性备份工具。不要把 SQLite 放在临时文件系统，也不要让多个独立服务实例各用一份文件宣称已经同步。无持久盘或多实例部署使用 Supabase 模式。

部署完成后，用电脑网络与手机蜂窝网络分别验证站点、注册/登录、日程读写和模型解析。平台显示“部署成功”不能代替真实访问验证。当前前台提醒要求页面活跃，不承诺锁屏或后台准点通知。

## 5. 云端验收

使用两个独立测试账号和虚构日程完成以下检查：

1. 账号 A 在手机创建日程，电脑登录 A 后能看到；电脑修改后手机恢复前台能看到新版本。
2. 同一提交重试后没有重复记录；改变内容却复用标识时收到冲突。
3. 两端读取同一版本并分别修改，第二次旧版本提交被拒绝，刷新后可以重新确认。
4. 账号 B 的查询列表为空，使用 A 的事件 ID 修改/删除也应得到未找到或无权限。
5. 在浏览器开发者工具尝试直接写 `events` 表，RLS/权限应拒绝；正式 API 保存仍正常。
6. 断网保存不能显示已同步；响应丢失后沿用原请求标识确认结果。
7. 模型配置错误或超时时，输入保留，没有事件落库。

迁移另附 [SQL 不变量测试](../supabase/tests/invariants.sql)。仅在可丢弃的开发 Supabase 数据库，以 SQL Editor 或 `psql` 的管理员连接执行。测试会创建两个虚构 `auth.users`，切换 JWT 身份验证数据隔离，最终回滚所有变更：

```sh
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/invariants.sql
```

不要把数据库连接串提交到仓库。测试检查幂等、批量回滚、跨账号隔离、禁止直接写入、版本冲突、相邻时间、已完成事件恢复、软删除重试、无效日期、每日配额和匿名权限。仓库开发过程中已在嵌入式 PostgreSQL 引擎上执行迁移和此脚本；CI 另配置独立 PostgreSQL 17 服务执行同一套检查。CI 的 [bootstrap.sql](../supabase/tests/bootstrap.sql)只是空白测试库的认证边界模拟，**不要在已有 Supabase 项目执行它**。这些测试不包含托管 Supabase 的认证邮件、PostgREST、实际网络或模型服务验证。
