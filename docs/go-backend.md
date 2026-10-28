# Go 后端（beta）

`beta` 分支使用 Go HTTP 服务和 SQLite，Next.js 仅负责构建静态界面。安装版运行 `shunshoupai-server.exe`，无需 Node.js 运行时。原 `main` 分支及其稳定版发布保持不变。

## 构建与启动

开发环境需要 Node.js 24 和 Go 1.26 以上；CI 固定使用 Go 1.27.3。Windows 原生客户端另需 Qt、CMake 和 C++ 工具链。

```powershell
npm ci
npm run build
$env:APP_DEVICE_BOOTSTRAP_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
npm start
# 同一受控环境下启动 Qt，继承相同随机引导密钥：
# .\desktop\build\Release\Shunshoupai.exe --backend http://127.0.0.1:3000
```

构建输出为 `out/`（静态界面）和 `backend/bin/`（Go 服务及版本清单）。也可直接运行 `backend/bin/shunshoupai-server --web out --port 3000`，Windows 文件名带 `.exe`。`npm run dev` 会重新构建后启动服务；修改后需重新执行。

Go 服务读取进程环境变量，不自动加载 `.env` 文件。`APP_DB_PATH`、`APP_DEVICE_BOOTSTRAP_KEY`、模型配置及加密密钥变量兼容原服务；`APP_LISTEN_HOST` 控制监听接口，`APP_WEB_DIR` 控制静态资源目录，`PORT` 默认为 3000。参数 `--db`、`--web`、`--host`、`--port` 可覆盖路径和监听设置。引导密钥只能通过受控原生进程环境传递。

## 数据兼容

Go 使用原 SQLite 表结构和同一个数据目录。旧事件编号、版本、请求编号、复制历史、配对凭据哈希和 P-256 身份原样保留。AES-256-GCM 密文继续使用原 `model-encryption.key`，不会在已有密钥丢失时自动替换它。多份旧资料需要用户选择，不能自动混合。

开始试用前关闭原服务，备份整个数据目录及原生客户端设置；不要同时启动两个服务操作同一数据文件。beta 与稳定版安装位置相同，试用安装会更新当前安装，但不改动 Git 的 `main` 分支。回退前应备份试用期间的数据，不能用旧备份覆盖而丢弃新修改。

## 代码与验证

`backend/cmd/shunshoupai` 为入口；`backend/internal/app` 包含 HTTP、授权、日程事务、不可变复制、设备回连、模型解析与加密。`src/lib/server` 保留为旧版数据兼容回归参考，不打入静态前端，也不运行 Node API。原 Next API 路由已由 Go 路由替换。

```sh
npm run lint
npm run typecheck
npm test
cd backend
go test ./...
go vet ./...
cd ..
npm run build
npm run test:e2e
```

Linux CI 额外运行 Go race detector。浏览器测试、Windows 安装器原生测试和 Android 模拟器测试连接实际 Go 后端。发布只使用同一提交通过三条流水线后的安装包，并标记为 GitHub 预发布。
