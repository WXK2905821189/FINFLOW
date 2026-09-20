# FINFLOW 财务工作台

FINFLOW 是一个前后端分离的企业财务管理平台。前端提供登录、资金仪表盘、转账、交易记录和用户管理工作台；后端提供 Spring Boot 3、JWT 认证、RBAC、MyBatis-Plus、Flyway、Knife4j 和可扩展的多银行网关。

## 技术栈

- 前端：React 18、TypeScript、Vite、Ant Design、Zustand
- 后端：Java 17、Spring Boot 3、Spring Security、MyBatis-Plus、Flyway、H2/MySQL、Knife4j

## 本地启动

准备 Java 17 或更高版本、Maven 3.9+、Node.js 18+ 和 pnpm（仓库用 `frontend/pnpm-lock.yaml` 锁定依赖，CI 亦用 pnpm）。

后端使用开发环境的内嵌 H2 数据库，不需要先安装 MySQL：

```powershell
cd backend
mvn spring-boot:run
```

服务默认运行在 `http://localhost:8080`。API 文档位于 `http://localhost:8080/doc.html`，OpenAPI JSON 位于 `http://localhost:8080/v3/api-docs`。

前端开发服务器：

```powershell
cd frontend
pnpm install
pnpm run dev
```

浏览器打开 `http://localhost:5173`。Vite 已将 `/api` 代理到后端的 `http://localhost:8080`。

## 初始管理员

首次启动后端时会自动创建初始管理员：

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `Admin@123` | `ADMIN` |

该凭据只用于本地初始启动。生产环境必须在首次登录后立即更换密码，并使用安全的 `JWT_SECRET`。

## 环境配置

默认 `dev` profile 使用 H2，配置位于 `backend/src/main/resources/application-dev.yml`。

生产环境使用 MySQL。设置下列环境变量，然后以 `prod` profile 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:DB_URL = 'jdbc:mysql://localhost:3306/finance_system?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:DB_USERNAME = 'finance'
$env:DB_PASSWORD = 'replace-with-a-strong-password'
$env:JWT_SECRET = 'replace-with-at-least-32-random-characters'
$env:JWT_EXPIRATION = '2h'
$env:CITIC_BASE_URL = 'https://your-citic-gateway.example'
$env:CITIC_APP_ID = 'your-citic-app-id'
$env:CITIC_MOCK_MODE = 'false'
mvn -f backend/pom.xml spring-boot:run
```

| 变量 | 用途 | 开发默认值 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 激活配置档 | `dev` |
| `DB_URL` | 生产 MySQL JDBC URL | H2 内存数据库 |
| `DB_USERNAME` / `DB_PASSWORD` | 生产数据库凭据 | `sa` / 空 |
| `JWT_SECRET` | JWT HMAC 密钥，至少 32 字符 | 仅开发用密钥 |
| `JWT_EXPIRATION` | JWT 生命周期 | `2h` |
| `CITIC_BASE_URL` | 中信银行网关地址 | 占位地址 |
| `CITIC_APP_ID` | 中信银行应用标识 | 空 |
| `CITIC_MOCK_MODE` | 是否使用本地中信模拟适配器 | `true` |

不要将生产数据库口令、真实银行密钥、证书或 JWT 密钥提交到仓库。

## 中信银行 SDK 本地安装

项目中的 `CiticBankSdkClient` 是银行厂商 SDK 的隔离边界。默认 `CITIC_MOCK_MODE=true` 时使用本地模拟实现，能够支持开发和接口联调。

接入真实 SDK 时：

1. 从中信银行获取经过授权的 SDK JAR、证书和环境参数，不要将私钥或生产证书提交到仓库。
2. 将供应商 JAR 安装到本机 Maven 仓库，例如：

```powershell
mvn install:install-file -Dfile='C:\path\to\citic-sdk.jar' -DgroupId=com.citic.bank -DartifactId=citic-sdk -Dversion=1.0.0 -Dpackaging=jar
```

3. 将对应依赖加入 `backend/pom.xml`，并以真实 SDK 调用替换 `backend/src/main/java/com/finance/system/bank/citic/ExternalCiticBankSdkAdapter.java` 中的占位实现；保持 `CiticBankSdkClient` 接口不变。
4. 配置 `CITIC_MOCK_MODE=false`、`CITIC_BASE_URL`、`CITIC_APP_ID` 以及供应商要求的密钥、证书路径和密码。真实凭据应由部署平台的密钥管理服务注入。

在没有替换占位适配器前，关闭模拟模式会返回明确的 `501`，避免任何看似成功的真实支付调用。

## 构建

```powershell
mvn -f backend/pom.xml clean package
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend run build
```

Flyway 会在后端启动时自动执行 `V1__init_rbac_and_bank_accounts.sql`，建立 RBAC 和银行账户基础表及演示数据。

## CI 与验证边界（务必知晓）

`gh run list` 显示的「CI 绿」**不等于全路径可用**。`.github/workflows/ci.yml` 有 5 个 job：`release-contract`、`h2-migration`、`mysql-migration`、`backend`、`frontend`。后端 job 实际执行的是：

```
mvn --batch-mode --no-transfer-progress -P '!citic-sdk' verify
```

| 边界 | 后果 |
| --- | --- |
| `-P '!citic-sdk'` + CI runner 的 `~/.m2` 里没有 vendor jar，而 `citic-sdk` / `kingdee-sdk` 两个 profile 是**按「jar 文件存在」激活**的（见 `backend/pom.xml`） | **`backend/src/{citic-sdk,kingdee-sdk}/java`（8 个文件 / 约 1334 行）在 CI 中根本不参与编译**，其测试类也不执行；CI 验证的是占位实现路径（`UnavailableCiticDlinkSdk` 的 `501` 边界） |
| 覆盖率**只上报、不设阈值**（无 `COVEREDRATIO` 校验），报告作为 `backend-jacoco-report` 构件上传 | 覆盖率退化有信号、但不拦提交；需看日志摘要或下载构件 |
| `release-contract` job 是约 104 条 `grep -q` 静态断言，其中 9 条 pin 在 `.java` 源码字面量上 | **重构命名 / 搬包 / 改字符串时它会变红**。先判断是「断言过期」还是「真回归」，不要一律当回归处理 |

### 因此

- **改动触及真实银行 / 金蝶 SDK 路径时，必须在本机跑全路径测试**（本机 `~/.m2` 装有 vendor jar，两个 profile 会激活）：
  ```powershell
  cd backend; mvn.cmd -o test
  ```
  只看 CI 绿是不够的 —— 那条路径 CI 根本没编译。要覆盖真实 SDK 路径，需本地跑，或按需为 CI 注入 vendor jar。
- **覆盖率**：CI 日志会打印「总体指令覆盖率」与「覆盖率最低 10 个包」。本项目当前约 **80%**，已知低覆盖区为 `feishu`、`bankdata.adapter.citic.dlink` 适配器、`statement.collector`。

### 本地验证与 CI 的差异（踩过）

- **前端 lint**：CI 跑 `pnpm run lint`（= `eslint .`）。本地若存在 `frontend/tmp/` 之类**被 gitignore、但不在 eslint ignores 内**的目录，`eslint .` 会报大量假错误（eslint 不读 `.gitignore`）→ 本地验证请用 `eslint src`。
- **JaCoCo 与中文路径**：仓库路径含中文时，JaCoCo agent 会让 fork 出的 JVM 崩溃（报 `The forked VM terminated without properly saying goodbye`，且 `Tests run: 0`，极易误判为「没匹配到测试」）。本机需把 agent 放 ASCII 路径并显式覆盖 `-DargLine`（备忘见 `docs/architecture-review-*.md`）；CI 在 Ubuntu 上无此问题。
