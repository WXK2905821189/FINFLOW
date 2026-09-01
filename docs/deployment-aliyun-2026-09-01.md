# FINFLOW 上云部署手册（阿里云 ECS + RDS MySQL + Docker Compose）

> 适用版本：`fdba1b4`（架构审阅四行动已落地，prod profile 全环境变量驱动）
> 编写：WB · 2026-09-01
> 访问形态：IP 直连 HTTP（域名/HTTPS 章节在 §9，后续启用）

---

## 0. 架构总览

```
用户浏览器
   │ http://<ECS公网IP>/
   ▼
[ECS 安全组：放行 80]
   ▼
┌─────────────── ECS（Docker Compose）───────────────┐
│  finflow-web (nginx:1.27)      80:80               │
│    ├─ 静态资源：frontend dist (web-dist/)           │
│    └─ /api/ 反代 ──────────► finflow-app :8080      │
│                              (仅容器内网，不对外)     │
│                              Spring Boot 3 prod     │
│                              Flyway 自动迁移         │
└──────────────────────────┬─────────────────────────┘
                           │ 内网地址（同 VPC 免流费）
                           ▼
              [RDS MySQL] rm-xxx.mysql.rds.aliyuncs.com:3306
              数据库 finflow（utf8mb4）
```

**核心链路**：nginx 托管前端静态文件并反代 `/api` → Spring Boot（prod profile）→ RDS MySQL。8080 端口不暴露公网，数据库走 RDS 内网地址。

**首次启动时 Flyway 自动建表**（V1–V13，其中 V7 为 MySQL vendor 迁移），无需手工执行任何 SQL。

---

## 1. 开工前信息核对清单

| # | 需要的信息 | 在哪拿 | 示例 |
|---|---|---|---|
| 1 | ECS 公网 IP | ECS 控制台实例详情 | `47.98.x.x` |
| 2 | ECS 登录方式 | 密钥对或密码 | `ssh root@47.98.x.x` |
| 3 | ECS 操作系统 | 实例详情（影响 §3 命令） | Ubuntu 22.04 / Alibaba Cloud Linux 3 |
| 4 | RDS 内网地址 | RDS 控制台 → 数据库连接 | `rm-xxx.mysql.rds.aliyuncs.com` |
| 5 | RDS 版本 | 实例详情 | **需 MySQL 8.0**（与 CI/本地一致） |
| 6 | ECS 与 RDS 是否同 VPC | RDS 控制台网络类型 | 同 VPC → 走内网地址 |
| 7 | 高级权限账号 | RDS 控制台账号管理 | 用于建库建号（super 权限） |

> ⚠️ 若 RDS 与 ECS 不在同一 VPC，内网地址不通，需要在 RDS 控制台把 ECS 加入同一 VPC 或临时切换公网地址（公网访问有流量费且有安全风险，强烈建议同 VPC）。

---

## 2. RDS MySQL 初始化（控制台操作，约 15 分钟）

### 2.1 白名单

RDS 控制台 → 数据安全性 → 白名单设置：

- 将 **ECS 内网 IP**（或 ECS 所在 VPC 的网段）加入 default 白名单。
- 不要直接加 `0.0.0.0/0`。

### 2.2 创建业务数据库

RDS 控制台 → 数据库管理 → 创建数据库：

| 项 | 值 |
|---|---|
| 数据库名 | `finflow` |
| 字符集 | `utf8mb4`（必须，emoji/长文本安全） |
| 授权账号 | 下一步创建的 `finflow_app` |

### 2.3 创建业务账号（不要用 root 高权限账号跑应用）

RDS 控制台 → 账号管理 → 创建账号：

| 项 | 值 |
|---|---|
| 账号类型 | **普通账号**（够用，缩小爆炸半径） |
| 账号名 | `finflow_app` |
| 授权数据库 | `finflow`：读写（DDL 读写） |

> Flyway 需要建表/改表权限，普通账号默认具备对授权库的 DDL 权限，可直接使用。

### 2.4 参数核对（RDS 控制台 → 参数设置）

| 参数 | 期望值 | 原因 |
|---|---|---|
| `character_set_server` | `utf8mb4` | 与建库一致 |
| `time_zone` | `Asia/Shanghai`（或 `+8:00`） | 与应用 Jackson 时区对齐 |
| `lower_case_table_names` | 保持默认（阿里云 RDS 通常为 1） | 建库后不可改，保持即可 |
| `max_allowed_packet` | ≥ `64M` | 导入/批量写入余量 |

### 2.5 备份策略

RDS 控制台 → 备份恢复：确认自动备份已开启（建议数据备份保留 ≥ 7 天，日志备份开启）。这是免费的兜底，务必确认。

---

## 3. ECS 初始化（约 10 分钟）

### 3.1 安全组配置（ECS 控制台 → 安全组）

| 方向 | 端口 | 授权对象 | 用途 |
|---|---|---|---|
| 入方向 | 22 | **你的常用 IP/32**（不要 0.0.0.0/0） | SSH |
| 入方向 | 80 | 0.0.0.0/0 | 网站访问 |
| 入方向 | 8080 | **不配置** | 后端仅容器内网可达 |
| 出方向 | 全部 | 默认放行 | 拉镜像/连 RDS |

### 3.2 安装 Docker

**Ubuntu 22.04/24.04：**

```bash
# 用阿里云镜像源装（国内速度快）
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
systemctl enable --now docker
docker compose version   # 需要 v2.x，自带 compose 子命令
```

**Alibaba Cloud Linux 3 / CentOS 系：**

```bash
dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
dnf install -y docker-ce docker-compose-plugin
systemctl enable --now docker
docker compose version
```

### 3.3 配置 Docker 镜像加速（拉镜像快）

```bash
mkdir -p /etc/docker && tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://mirror.csi.aliyun.com"]
}
EOF
systemctl restart docker
```

### 3.4 验证 RDS 连通性（关键步骤，别跳过）

```bash
# 同 VPC 时应能通（telnet 或 curl 探测端口）
timeout 3 bash -c 'echo > /dev/tcp/rm-xxxxxxxxxxxx.mysql.rds.aliyuncs.com/3306' \
  && echo "RDS OK" || echo "RDS UNREACHABLE"
```

不通的话回查：白名单是否含 ECS 内网 IP、是否同 VPC。

---

## 4. 生产密钥与配置准备

### 4.1 生成 JWT 密钥（本地任意机器执行）

```bash
openssl rand -base64 48
```

输出填入 `.env` 的 `JWT_SECRET`。**绝不能沿用 dev 默认值** `dev-only-secret-change-me-before-production-2026`。

### 4.2 在 ECS 上准备部署目录

```bash
mkdir -p /opt/finflow && cd /opt/finflow
# 最终目录结构：
# /opt/finflow
# ├── docker-compose.yml     （来自仓库 deploy/）
# ├── Dockerfile.backend     （来自仓库 deploy/）
# ├── nginx.conf             （来自仓库 deploy/）
# ├── .env                   （从 .env.example 复制后填写真实值）
# ├── app.jar                （本地构建后上传）
# ├── web-dist/              （前端构建产物）
# └── logs/                  （应用日志，自动生成）
```

### 4.3 填写 .env

```bash
cp .env.example .env
vim .env   # 填入 DB_URL / DB_PASSWORD / JWT_SECRET 三项真实值
chmod 600 .env   # 收紧权限
```

`DB_URL` 模板（替换 RDS 内网地址）：

```
jdbc:mysql://rm-xxxxxxxxxxxx.mysql.rds.aliyuncs.com:3306/finflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
```

业务开关首期**全部保持安全默认值**（mock、payment 关闭），与"统一聚合层首期不做真实银行通道"的既定边界一致。

---

## 5. 构建与上传（本地 Windows / Git Bash 执行）

### 5.1 构建后端 jar（离线 Maven，用既有 .m2-settings.xml）

```bash
cd /c/Users/王小棵/Documents/ChatGPT/财务系统
C:/Users/王小棵/tools/apache-maven-3.9.16/bin/mvn.cmd \
  -s backend/.m2-settings.xml -f backend/pom.xml -o package -DskipTests
cp backend/target/finance-system-0.0.1-SNAPSHOT.jar deploy/app.jar
```

### 5.2 构建前端 dist

```bash
cd frontend
npx -y pnpm@11.19.0 install --registry=https://registry.npmmirror.com
node node_modules/typescript/bin/tsc -b --force
node node_modules/vite/bin/vite.js build
cd ..
```

### 5.3 打包上传

```bash
cd frontend && tar czf ../deploy/web-dist.tgz dist && cd ..
scp deploy/app.jar deploy/web-dist.tgz deploy/docker-compose.yml \
    deploy/Dockerfile.backend deploy/nginx.conf deploy/.env.example \
    root@47.98.x.x:/opt/finflow/
```

### 5.4 服务器侧解压

```bash
ssh root@47.98.x.x
cd /opt/finflow
tar xzf web-dist.tgz && mv dist web-dist && rm web-dist.tgz
cp .env.example .env && vim .env && chmod 600 .env
```

> 以后每次发版重复 §5.1 → §5.4 → §8.1 即可；也可以把 §5 打成脚本，需要的话我下一轮补 `deploy/release.sh`。

---

## 6. 首次启动

```bash
cd /opt/finflow
docker compose up -d --build
docker compose ps          # 两个容器都应 running（app 需先通过健康检查）
```

启动日志观察（重点看 Flyway）：

```bash
docker compose logs -f app
```

预期出现：

```
Migrating schema ... to version "1 - init rbac and bank accounts"
...
Successfully applied 13 migrations
```

> 若已有一半迁移历史（如迁移失败重试），Flyway 会从断点续跑，这是正常的。

---

## 7. 验收清单（首次上线逐项打勾）

| # | 验收项 | 操作 | 预期 |
|---|---|---|---|
| 1 | 前端可访问 | 浏览器打开 `http://47.98.x.x/` | 出现登录页，无白屏 |
| 2 | API 通 | 登录页提交任意表单 | 返回明确业务错误而非 502/504 |
| 3 | 数据库落库 | `docker compose exec app sh -c` 后用日志确认 / RDS 控制台看表 | `finflow` 库出现全部业务表（31 张左右） |
| 4 | 迁移完整 | `docker compose logs app \| grep -i "applied 13"` | 有 `Successfully applied 13 migrations` |
| 5 | 注册首个管理员 | 调 `POST /api/auth/register`（唯一放行的注册口） | 创建成功并可登录 |
| 6 | 登录与鉴权 | 用管理员登录后访问各菜单 | 各页面数据正常加载 |
| 7 | 租户隔离抽查 | 用 B 企业 token 访问 A 企业资源 | 404，不泄露存在性 |
| 8 | 8080 未暴露 | 外网 `curl http://47.98.x.x:8080` | 连接被拒（安全组拦截） |
| 9 | 容器自愈 | `docker restart finflow-app` 后访问 | 服务自动恢复 |
| 10 | 重启不重复迁移 | `docker compose down && docker compose up -d` | 日志显示 validated 而非再次 Migrating |

> 注册口安全提示：`/api/auth/register` 目前是 permitAll。上云后建议限制注册来源（Nginx 层限 IP 或尽快接管理员邀请制），这是上云后第一优先级的加固项。

---

## 8. 日常运维

### 8.1 发版更新流程

```bash
# 本地：重复 §5.1–5.3 构建 + scp 覆盖 app.jar / web-dist
# 服务器：
cd /opt/finflow
tar xzf web-dist.tgz && mv dist web-dist_new   # 先解压到新目录
docker compose build app
docker compose up -d                            # app 重建；nginx 静态目录是挂载，换名字前先原子切换：
rm -rf web-dist_old && mv web-dist web-dist_old && mv web-dist_new web-dist
docker compose restart nginx
docker compose logs -f app | grep -iE "migrat|started|error"
```

### 8.2 常用命令

| 操作 | 命令 |
|---|---|
| 看实时日志 | `docker compose logs -f app` |
| 重启后端 | `docker compose restart app` |
| 全部重启 | `docker compose down && docker compose up -d` |
| 进容器排查 | `docker compose exec app sh` |
| 查看资源占用 | `docker stats`（按 Ctrl+C 退出） |

### 8.3 备份

- **数据库**：RDS 自动备份为主（§2.5），重大发版前可在 RDS 控制台手动做一次备份。
- **配置**：`.env` 与 `nginx.conf` 建议在本地留一份加密副本（`.env` 含密钥，不进 git）。

### 8.4 回滚

后端回滚 = 用旧 jar 重跑 `docker compose build app && docker compose up -d`（建议保留 `app.jar.v<日期>` 副本）。
数据库回滚**不依赖 Flyway down**：结构变更全部向前兼容设计下，回滚只需回滚 jar；若当次发版含破坏性迁移，回滚方案必须在发版前单独设计。

---

## 9. 后续加固路线（按优先级）

| 优先级 | 事项 | 说明 |
|---|---|---|
| P0 | 收紧 `/api/auth/register` | Nginx 层先限 IP，后续改管理员邀请制 |
| P1 | 域名 + HTTPS | 买域名 → 备案 → DNS 解析到 ECS → `certbot --nginx` 一键签证书，nginx.conf 增加 443 server 块并 80 强跳 |
| P1 | SSH 加固 | 改密钥登录禁密码、fail2ban |
| P2 | 日志轮转 | compose 里给 app 加 logging driver max-size 限制 |
| P2 | 云监控 | ECS 云监控告警（CPU/磁盘）+ RDS 连接数告警 |
| P3 | CI/CD | GitHub Actions 推送后自动构建镜像到 ACR + 服务器拉取发布，替代手工 §5 |

---

## 10. 常见问题（FAQ）

| 症状 | 原因 | 处理 |
|---|---|---|
| 启动报 `Communications link failure` | RDS 白名单没加 ECS / 不在同 VPC | §2.1 回查；用 §3.4 命令探测 |
| 启动报 `Access denied for user` | `.env` 账号密码错误 / 账号未授权 finflow 库 | RDS 控制台核对授权 |
| Flyway 报 checksum mismatch | 曾手工改过已执行的迁移 SQL | 恢复原 SQL 或 `flyway repair`（先备份） |
| 页面能开但接口 502 | app 未启动完或挂了 | `docker compose logs app` 看栈；`docker compose ps` 看健康状态 |
| 中文/emoji 乱码 | 库字符集不是 utf8mb4 | §2.2 建库时选对；已建错则需重建库 |
| 时间差 8 小时 | 容器/连接串时区缺失 | 确认 TZ=Asia/Shanghai 与 URL 的 serverTimezone 均在 |
| 上传文件 413 | nginx body 限制 | nginx.conf 已设 20m，可按需调大 |
| 磁盘涨满 | docker 日志无限堆积 | §9 P2 配日志轮转 + `docker system prune` |

---

## 附：本手册配套文件（已提交到仓库 deploy/ 目录）

| 文件 | 用途 |
|---|---|
| `deploy/docker-compose.yml` | 双容器编排（app + nginx），环境变量驱动 |
| `deploy/Dockerfile.backend` | 后端运行镜像（temurin 17 JRE + 健康检查） |
| `deploy/nginx.conf` | 前端托管 + /api 反代 + Swagger 屏蔽 |
| `deploy/.env.example` | 环境变量模板（真实值填在服务器 .env，不进 git） |
