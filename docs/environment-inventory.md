# FINFLOW 项目环境索引

> 本文件只记录可复用的路径、版本、地址约定和环境状态，不记录密码、令牌、私钥、JWT 密钥、银行 SDK 或任何生产凭据。更新时间：2026-08-27。

## 项目位置

| 项目项 | 地址 |
| --- | --- |
| 项目根目录 | `C:\Users\王小棵\Documents\ChatGPT\财务系统` |
| 后端模块 | `C:\Users\王小棵\Documents\ChatGPT\财务系统\backend` |
| 前端模块 | `C:\Users\王小棵\Documents\ChatGPT\财务系统\frontend` |
| CI 配置 | `C:\Users\王小棵\Documents\ChatGPT\财务系统\.github\workflows\ci.yml` |
| 部署指南 | `C:\Users\王小棵\Documents\ChatGPT\财务系统\docs\deployment-guide.md` |
| v0.2 运行手册 | `C:\Users\王小棵\Documents\ChatGPT\财务系统\docs\v0.2\bank-data-runbook.md` |
| GitHub 仓库 | `https://github.com/WXK2905821189/FINFLOW` |

## 本机已安装环境

| 工具 | 版本/状态 | 可执行文件或目录 |
| --- | --- | --- |
| Java | Temurin `17.0.20.1`，可用 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe` |
| Maven | Apache Maven `3.9.16`，可用 | `C:\Users\王小棵\tools\apache-maven-3.9.16\bin\mvn.cmd` |
| Git | `2.55.0.windows.5`，可用 | `C:\Program Files\Git\cmd\git.exe` |

Java 的系统级 `JAVA_HOME` 已指向 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`。当前 Codex 进程不会自动刷新新环境变量；新终端应重新打开后再运行 `java --version` 和 `mvn --version`。

## 当前缺失环境

以下工具在本机检查时未发现可执行文件或服务：

- Node.js 20：前端构建所需。
- pnpm 9：前端依赖安装与构建所需。
- Docker：容器化运行/集成验证所需，当前项目没有 Docker Compose 配置。
- MySQL 客户端和本地 MySQL 服务：生产使用 RDS MySQL；开发默认使用 H2，因此不是本地后端启动的必要条件。
- Redis、PostgreSQL 客户端/服务：当前项目配置和依赖未要求。

安装 Node.js 20 后，再安装 pnpm 9，并在新终端执行：

```powershell
node --version
corepack enable
corepack prepare pnpm@9 --activate
pnpm --version
```

## 构建与运行地址

| 用途 | 地址/命令 |
| --- | --- |
| 后端开发服务 | `http://localhost:8080` |
| 后端 API 文档 | `http://localhost:8080/v3/api-docs` |
| 后端 Swagger UI | `http://localhost:8080/swagger-ui.html` |
| 前端 Vite 开发服务 | `http://localhost:5173` |
| 前端开发 API 代理 | `/api` -> `http://localhost:8080` |
| 后端默认 profile | `dev` |
| 后端开发数据库 | H2 内存库 `finance`，用户 `sa`，无密码 |
| 后端生产数据库 | MySQL，使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 注入 |

后端构建：

```powershell
cd "C:\Users\王小棵\Documents\ChatGPT\财务系统\backend"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
& "C:\Users\王小棵\tools\apache-maven-3.9.16\bin\mvn.cmd" --batch-mode --no-transfer-progress verify
```

前端构建：

```powershell
cd "C:\Users\王小棵\Documents\ChatGPT\财务系统\frontend"
pnpm install --frozen-lockfile
pnpm run build
```

## 项目版本与迁移

- 后端 Maven 项目版本：`0.0.1-SNAPSHOT`。
- 前端包版本：`0.1.0`。
- 当前发布标签：`v0.2.0-rc.2`。
- 当前分支：`codex/auto-accounting-release`。
- 数据库迁移目录：`backend/src/main/resources/db/migration`。
- 当前迁移文件范围：`V1` 至 `V5`，其中 `V4` 为银行数据接入基础，`V5` 为余额与适配器端口扩展。
- 生产迁移由 Flyway 按版本顺序执行；不要手工修改 `flyway_schema_history`，不要改动已发布迁移内容。

## 环境边界

- `dev` 使用 H2 内存库；`prod` 使用通过环境变量注入的 MySQL 连接。
- 自动入账一期使用文件或模拟来源，`KINGDEE_MOCK_MODE=true`；真实银行 SDK/API、真实金蝶接口和生产密钥未启用。
- 敏感配置只能通过部署平台、KMS 或密钥管理服务注入，不得写入 Git、镜像、日志、命令历史或本索引。
- CI 要求后端 Java 17 + Maven、前端 Node 20 + pnpm，并包含迁移与银行数据运行边界检查。

## 已完成的本机检查

- Java 17 可执行文件直接运行成功。
- Maven 3.9.16 可启动并识别 Java 17。
- 后端测试执行 `6` 项，失败 `0`，错误 `0`；完整 `verify` 在 Spring Boot 重打包阶段因目标 JAR 无法重命名而失败，疑似已有 Java 进程或文件锁占用。
- Maven 首次依赖下载需要访问 Maven Central；受限网络环境下会报告 `Permission denied`。
- 前端构建尚未执行，因为本机未发现 Node.js 与 pnpm。
- 当前工作区已有未跟踪 `.vite/`；本索引不会删除或覆盖它。
