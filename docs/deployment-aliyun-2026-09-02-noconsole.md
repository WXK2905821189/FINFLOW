# FINFLOW 上云部署手册（无控制台权限版 · 命令输出对照版）

> 适用场景：**ECS/RDS 属公司账号，本人无阿里云控制台访问权限**，仅持有：
> ① ECS 服务器登录密码（SSH） ② 数据库账号与密码
> 编写：WB · 2026-09-02（本版每条命令附**预期输出/界面示例**，解决"输入后没反应"的困惑）

---

## 阅读约定（先看这个，能省一半困惑）

每条命令用图标标注它的"行为类型"：

| 图标 | 含义 | 你该做什么 |
|---|---|---|
| 🟢 | **静默命令**：成功时屏幕上**什么都不出现**，直接回到新的命令行 | 别等输出，看到光标回到 `root@xxx:~#` 就是成功 |
| 🟡 | **交互式命令**：会进入特殊界面（输密码/编辑器/提示符） | 按说明操作并**正确退出**（下方每个都有退出方法） |
| 🔴 | **长耗时命令**：会持续滚动输出 1~10 分钟 | 耐心等，**不要按 Ctrl+C**，等它自己停下来 |

> **通用判断法**：命令跑完的标志是屏幕上重新出现 `root@xxxx:~#`（服务器上）或 `PS C:\...>`（你本机 PowerShell）。只要回到这个提示符，不管刚才有没有输出，都是成功了。

---

## 0. 当前已就绪条件（无需再操作）

| # | 事项 | 状态 | 说明 |
|---|---|---|---|
| 1 | ECS 与 RDS 同地域（华北2 北京）、同 VPC | ✅ 已完成 | 内网互通，不走公网，免费 |
| 2 | ECS 安全组 | ✅ 已完成 | HTTP/HTTPS 对外开放；SSH 仅限公司 IP |
| 3 | RDS 白名单 | ✅ 已完成 | 已放行 ECS，内网直连可用 |
| 4 | 数据库 root（高权限）账号 | ✅ 可用 | 建库/建号/授权均可用 SQL 完成 |

**本手册全部操作 = 你本机（上传/生成密钥）+ ECS（SSH 密码登录）+ 数据库（SQL 命令）**，全程不需要打开阿里云控制台。

---

## 1. 需要公司管理员协助的事项（只有这几件才需要找人）

| # | 事项 | 何时需要 | 给管理员的建议 |
|---|---|---|---|
| 1 | 白名单变更 | 换 ECS / 新增访问来源时 | 将新 IP 加入 RDS 白名单 |
| 2 | 安全组变更 | 换公司出口 IP / 开放新端口时 | SSH 入方向更新为最新公司 IP |
| 3 | 域名解析 + 证书 | 将来要正式 HTTPS（见 §9） | 域名 A 记录指向 ECS 公网 IP + 申请证书 |
| 4 | RDS 参数调整 | 出现时区/字符集问题时（见 §11 FAQ） | 控制台参数组改 `time_zone=+08:00`、`character_set_server=utf8mb4` |
| 5 | 备份策略确认 | 首启后（见 §10） | 确认自动备份已开启、保留天数 ≥ 7 |
| 6 | RDS 版本确认 | 现在 | 请确认 RDS 为 **MySQL 8.0**（与本地/CI 一致） |

---

## 2. 开工前信息清单

| # | 信息 | 从哪拿 | 本项目的值（示例） |
|---|---|---|---|
| 1 | ECS 公网 IP | 你手上 | `101.200.72.87` |
| 2 | ECS 登录密码 | 你手上 | （SSH 用） |
| 3 | ECS 操作系统 | 登录后 `cat /etc/os-release` | Debian 12 / Ubuntu 22.04 |
| 4 | RDS 内网地址 | 你手上（或问管理员） | `rm-2zezxf5vds35q5x92.mysql.rds.aliyuncs.com` |
| 5 | 数据库账号 + 密码 | 你手上 | root（高权限） |
| 6 | 部署包 | 本机已生成 | `tmp/finflow-deploy-20260902.tar.gz`（42MB） |

> ⚠️ RDS 内网地址以你手上拿到的**实际连接串**为准。本手册示例值来自此前截图推断，若管理员给了正式地址请替换（下文所有 `<RDS内网地址>` 都换成你的真实地址）。

---

## 3. 本机：上传部署包到 ECS

### 3.1 执行上传（🔴 长耗时，约 4~5 分钟）

在**你本机**的 Git Bash / PowerShell 执行：

```bash
scp "C:\Users\王小棵\Documents\ChatGPT\财务系统\tmp\finflow-deploy-20260902.tar.gz" \
    root@101.200.72.87:/tmp/
```

**预期界面（PowerShell / Git Bash）：**

```
PS C:\Users\王小棵> scp "C:\...\finflow-deploy-20260902.tar.gz" root@101.200.72.87:/tmp/
root@101.200.72.87's password:          ← 🟡 输入 ECS 登录密码，屏幕不显示任何字符，正常！
finflow-deploy-20260902.tar.gz   100%   42MB  1.1MB/s   00:38
```

**看到 `100%` 和进度条走完 = 上传成功**，此时自动回到 `PS C:\...>` 提示符。

> - 输入密码时**光标不动、没有星号**是正常的，输完直接回车。
> - 若卡在 `password:` 后十几秒报 `Permission denied` = 密码错，重输。
> - 若提示 `Connection timed out` = 不在公司出口 IP（安全组"SSH 仅限公司 IP"的预期行为），回公司网络再试。
> - 首次连接如果问你 `Are you sure you want to continue connecting (yes/no)?`，输入 `yes` 回车。

---

## 4. 登录 ECS 并安装 Docker

### 4.1 登录 ECS（🟡 交互式）

```bash
ssh root@101.200.72.87
```

**预期界面：**

```
root@101.200.72.87's password:        ← 输入密码（无回显，正常），回车
Welcome to Ubuntu 22.04.4 LTS ...     ← 登录横幅，内容因系统而异
Last login: ...
root@iZbp1xxxxxxx:~#                  ← 🎯 出现这个 root@ 提示符 = 登录成功
```

**至此你已经在服务器里了**，后面所有命令都在这台 ECS 上执行（提示符是 `root@...:#` 就对了）。

### 4.2 确认操作系统（🟢 静默，但会打印系统信息）

```bash
cat /etc/os-release
```

**预期输出：**

```
PRETTY_NAME="Ubuntu 22.04.4 LTS"      ← 或者 Debian GNU/Linux 12
NAME="Ubuntu"
VERSION_ID="22.04"
...
```

**按 `PRETTY_NAME` 决定用 4.3 还是 4.4 的分支**：Ubuntu/Debian 用 4.3；Alibaba Cloud Linux/CentOS 用 4.4。

### 4.3 安装 Docker（Ubuntu / Debian 系，🔴 长耗时 1~3 分钟）

```bash
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
```

**预期输出（长段滚动）：**

```
# Executing docker install script ...
+ sh -c 'apt-get update -qq >/dev/null'
...
+ sh -c 'apt-get install -y docker-ce docker-ce-cli containerd.io ...'
...
Client: Docker Engine - Community
 Server: Docker Engine - Community
...
If you would like to use Docker as a non-root user, ...
```

**看到 `Client:` / `Server:` 两行 = 安装成功**。全程别 Ctrl+C，等它自己结束回到 `root@...:#`。

### 4.4 安装 Docker（Alibaba Cloud Linux / CentOS 系，🔴 长耗时）

```bash
dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
dnf install -y docker-ce docker-compose-plugin
```

**预期输出：** 安装时大量 `Installing : docker-ce ...`、`Complete!` 结尾。

### 4.5 启动 Docker 并验证（🟢 / 快速验证）

```bash
systemctl enable --now docker
```

**预期：** 可能打印一行 `Created symlink ...`，也可能什么都没输出——**都正常**，看到提示符回来即可。

```bash
docker compose version
```

**预期输出：**

```
Docker Compose version v2.29.1         ← 看到 v2.x = OK
```

> 若报 `command not found`：Ubuntu/Debian 系需再跑 `apt-get install -y docker-compose-plugin`，然后重试本命令。

### 4.6 配置镜像加速（⚠️ 注意：别配已失效的旧阿里云地址）

> **踩坑警示**：网上流传的 `https://mirror.csi.aliyun.com`（阿里云旧公共加速器）**已于 2024 年下线**，配了反而会让所有镜像拉取失败（报错 `dial tcp: lookup mirror.csi.aliyun.com: no such host`）。本手册**默认不配加速器**，让 Docker 直连官方源；只有直连失败时才按下面"备选"配置社区加速源。

**默认方案：直连（🟢 静默）**

```bash
mkdir -p /etc/docker && tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": []
}
EOF
```

**预期界面：** 输入完 `EOF` 回车后，屏幕会**原样回显**你输入的 JSON，然后回到提示符——回显是正常现象：

```
{
  "registry-mirrors": []
}
root@xxx:~#
```

```bash
systemctl restart docker
```

**预期：🟢 完全无输出，直接回到提示符 = 成功。**

重启后验证加速器已清空（应显示空列表）：

```bash
docker info 2>/dev/null | grep -A1 "Registry Mirrors"
```

**预期输出：**

```
Registry Mirrors:
 []
```

> 若此步输出里还残留 `mirror.csi.aliyun.com`，说明没保存成功，重跑上面的 `tee` 命令再 `systemctl restart docker`。

**备选方案（仅当直连拉镜像超时/失败时才用）：配置社区加速源**

社区维护的镜像加速站（非官方、可用性随时变化），逐个测通后再配：

```bash
curl -sI -m 8 https://docker.m.daocloud.io/v2/ -o /dev/null -w "daocloud: %{http_code}\n"
curl -sI -m 8 https://docker.1ms.run/v2/ -o /dev/null -w "1ms: %{http_code}\n"
curl -sI -m 8 https://hub.rat.dev/v2/ -o /dev/null -w "rat: %{http_code}\n"
```

**判定：** 返回 `200` / `401` / `403` 都算**可用**；返回 `000` 或 `curl: (28)` 超时 = 不可用，换下一个。把第一个可用的地址填进下面命令（示例以 daocloud 为准，把地址换成你测通的那个）：

```bash
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://docker.m.daocloud.io"]
}
EOF
sudo systemctl restart docker
```

**官方兜底：** 社区源都不稳时，找公司管理员要**阿里云 ACR 专属加速地址**（免费、官方、最稳）——登录阿里云控制台 → 容器镜像服务 ACR → 镜像加速器，把形如 `https://xxxxxx.mirror.aliyuncs.com` 的地址发给你，填进上面的 `registry-mirrors` 即可。

### 4.7 验证与 RDS 内网连通（关键步骤，别跳过）

```bash
timeout 3 bash -c 'echo > /dev/tcp/rm-2zezxf5vds35q5x92.mysql.rds.aliyuncs.com/3306' && echo "RDS OK" || echo "RDS UNREACHABLE"
```

> 把命令里的 `rm-2zezxf5vds35q5x92.mysql.rds.aliyuncs.com` 换成你手上的真实内网地址。

**预期输出（二选一）：**

```
RDS OK            ← ✅ 网络通，继续
```

或

```
RDS UNREACHABLE   ← ❌ 白名单/网络未就绪，找管理员（§1 第 1 项）
```

---

## 5. 数据库初始化（纯 SQL 完成）

### 5.1 安装 mysql 客户端（🔴 长耗时约 30 秒~1 分钟）

```bash
apt-get update && apt-get install -y default-mysql-client
```

**预期输出（滚动）：**

```
Reading package lists... Done
...
Setting up mysql-client-8.0 (8.0.35-...) ...
```

**看到 `Setting up` 结尾 = 成功。**

> 备选（不想装客户端就用 Docker 临时容器，🟡 交互式）：
> ```bash
> docker run --rm -it mysql:8.0 mysql -h rm-2zezxf5vds35q5x92.mysql.rds.aliyuncs.com -u root -p
> ```
> 首次会拉镜像（几百 MB，🔴 约 2~5 分钟），之后提示 `Enter password:`。

### 5.2 连接 RDS（🟡 交互式，重点看这段）

```bash
mysql -h rm-2zezxf5vds35q5x92.mysql.rds.aliyuncs.com -P 3306 -u root -p
```

**预期界面：**

```
Enter password:               ← 输入数据库 root 密码（无回显，正常），回车
Welcome to the MySQL monitor.  Commands end with ; or \g.
Your MySQL connection id is 123456
Server version: 8.0.30 MySQL Community Server - GPL

Copyright (c) 2000, 2023, Oracle and/or its affiliates.

Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.

mysql>                        ← 🎯 出现 mysql> 提示符 = 已连上数据库
```

**注意：**
- 看到 `mysql>` 后，下面 5.3/5.4 的 SQL 都**在这里输入**，每条 SQL **以分号 `;` 结尾**再回车。
- **退出数据库**用 `exit;`（或 `\q`），会回到 `root@...:#`。
- 连不上时的报错对照见 §11 FAQ 前两行。

### 5.3 建库 + 建业务账号 + 授权（🟡 在 mysql> 下逐条执行）

> 以下每条输入后回车，**每条都应有对应输出**（见右侧注释）。

```sql
CREATE DATABASE finflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 预期：Query OK, 1 row affected (0.02 sec)

CREATE USER 'finflow_app'@'%' IDENTIFIED BY '你的强密码';
-- 预期：Query OK, 0 rows affected (0.01 sec)     ← 注意是 0 rows，正常！

GRANT ALL PRIVILEGES ON finflow.* TO 'finflow_app'@'%';
-- 预期：Query OK, 0 rows affected (0.01 sec)

FLUSH PRIVILEGES;
-- 预期：Query OK, 0 rows affected (0.00 sec)

SHOW DATABASES LIKE 'finflow';
-- 预期：
-- +--------------------+
-- | Database (finflow) |
-- +--------------------+
-- | finflow            |
-- +--------------------+
-- 1 row in set (0.00 sec)
```

**四条 `Query OK` 齐了 + 最后能查出 finflow = 建库成功。** `0 rows affected` 是正常的，不要以为失败了。

> 表结构**不需要**手工建——Flyway 首启自动落全部迁移（§7）。

### 5.4 顺手核对字符集与时区（🟡 在 mysql> 下）

```sql
SHOW VARIABLES LIKE 'character_set_server';
-- 预期：
-- +----------------------+--------+
-- | Variable_name        | Value  |
-- +----------------------+--------+
-- | character_set_server | utf8mb4|
-- +----------------------+--------+

SHOW VARIABLES LIKE 'time_zone';
-- 预期：
-- +---------------+--------+
-- | Variable_name | Value  |
-- +---------------+--------+
-- | time_zone     | SYSTEM |
-- +---------------+--------+
```

期望：字符集 `utf8mb4`；时区 `SYSTEM` 或 `+08:00` 都行。若时区不是 +8 且后续日志出现 8 小时偏差，走 §1 第 4 项找管理员（应用连接串已带 `serverTimezone=Asia/Shanghai`，通常可兜底）。

**退出数据库：**

```sql
exit;
-- 预期：Bye
-- 然后回到 root@xxx:~# 提示符
```

---

## 6. 解压部署包 + 填 .env

### 6.1 解压到 /opt/finflow（🟢 静默）

```bash
mkdir -p /opt/finflow
tar -xzf /tmp/finflow-deploy-20260902.tar.gz -C /opt/finflow --strip-components=1
cd /opt/finflow
ls -a
```

**预期：** 前三条命令**无输出**（正常）；最后 `ls -a` 打印文件列表（`-a` 才能看到以 `.` 开头的隐藏文件，如 `.env.example`；`.env` 要等 §6.2 生成后才会出现）：

```
.  ..  Dockerfile.backend  app.jar  docker-compose.yml
nginx-app-locations.conf  nginx-https.conf  nginx.conf  web-dist  .env.example
```

**看到 `app.jar` 和 `web-dist` = 解压成功。**（`.` 和 `..` 两行是目录自身，正常现象）

### 6.2 生成 .env 并编辑（🟡 交互式，重点看这段）

```bash
cp .env.example .env
```

**预期：🟢 无输出 = 正常。**

```bash
nano .env
```

> Debian/Ubuntu 一般自带 `nano`（比 vim 简单）。若报 `command not found`，改用 `vim .env`（见下方"vim 用法"）。

**nano 预期界面（全屏编辑器，不是卡死！）：**

```
  GNU nano 6.2                      /opt/finflow/.env
# 复制为 .env 并填入真实值；.env 已被 .gitignore 覆盖（.env / .env.*），严禁提交
# ============ 数据库（RDS MySQL）============
# rm-xxx 为 RDS 内网地址（与 ECS 同 VPC 时用内网域名，不走公网流量计费）
DB_URL=jdbc:mysql://rm-2zezxf5vds35q5x92.mysql.rds.aliyuncs.com:3306/finflow?...  ← 光标在这
DB_USERNAME=finflow_app
DB_PASSWORD=CHANGE_ME_STRONG_PASSWORD
JWT_SECRET=CHANGE_ME_RANDOM_64_CHAR_SECRET
...
                              [ 已读 19 行 ]    ← 底部是快捷键提示栏
^G 帮助  ^O 保存  ^X 退出  ...
```

**nano 操作口诀：**
1. 用**方向键**把光标移到要改的行，直接打字修改（不需要按任何模式键）。
2. 改完：`Ctrl + O` 保存 → 底部问 `File Name to write: .env`，直接**回车**确认。
3. `Ctrl + X` 退出编辑器 → 回到 `root@...:#`。

**要改的三处**（其余不动）：

| 行 | 改成 |
|---|---|
| `DB_PASSWORD=...` | `DB_PASSWORD=你在 §5.3 设的强密码` |
| `JWT_SECRET=...` | `JWT_SECRET=用 §6.3 生成的随机串` |
| `DB_URL` 里的 `rm-xxx...` | 核对是否为你的真实 RDS 内网地址（不是则替换） |

**vim 用法（如果 nano 用不了，选这个）：**

```
vim .env
```

预期界面：也是**全屏编辑器**。按键：按 `i` 进入编辑模式（底部出现 `-- INSERT --`）→ 方向键移动、打字修改 → 按 `Esc` 退出编辑 → 输入 `:wq` 回车（保存并退出）→ 回到提示符。改乱了想放弃：`Esc` 后输入 `:q!` 回车（不保存退出）。

### 6.3 生成 JWT 密钥（🟢 有输出，在**本机**或 ECS 上执行都行）

```bash
openssl rand -base64 48
```

**预期输出（一行乱码样的字符串）：**

```
k9F2mQpZ8xLv3NtR5sW7uYb1cDe4fGh6jKl0MnO2pQrS4tUvW6xYz
```

**把这一整行复制，填进 .env 的 `JWT_SECRET=`**。这是随机的，每次生成都不一样，正常。

### 6.4 收紧 .env 权限（🟢 静默）

```bash
chmod 600 .env
```

**预期：🟢 无输出 = 正常。**

> ⚠️ 绝不能沿用默认值 `CHANGE_ME_STRONG_PASSWORD` / `CHANGE_ME_RANDOM_64_CHAR_SECRET`。其余变量（mock 开关等）保持默认即可。

---

## 7. 首次启动

### 7.1 启动服务（🔴 长耗时，首次 3~10 分钟，**别 Ctrl+C**）

> **先确认在正确目录**：SSH 断线重连后目录会回到初始位置，不在 `/opt/finflow` 里跑 `docker compose` 会报 `no configuration file provided: not found`。执行前先跑这条确认（**必须用 `ls -a`，普通 `ls` 看不到 `.env` 隐藏文件**）：

```bash
cd /opt/finflow && ls -a
```

**预期输出（文件列表，有 `docker-compose.yml` 和 `.env` 才行）：**

```
.  ..  Dockerfile.backend  app.jar  docker-compose.yml
nginx-app-locations.conf  nginx-https.conf  nginx.conf  web-dist  .env  .env.example
```

- 列表里**有 `.env`**（§6.2 已复制生成）和 `docker-compose.yml` = 就绪，继续往下。
- 报 `ls: cannot access '/opt/finflow': No such file or directory` = 还没解压，先回 §6.1。
- 有 `.env.example` 但**没有 `.env`** = §6.2 没做或 cp 没成功，先回去 `cp .env.example .env` 再填完（不然后端连不上库）。

```bash
docker compose up -d --build
```

**预期输出（长段滚动，耐心等）：**

```
[+] Building 8.2s (12/12) FINISHED                    ← 后端镜像构建完成
 => => naming to docker.io/library/finflow-app       ← 或 finflow-app:latest
[+] Running 3/3
 ✔ Container finflow-app  Started                     ← 🎯 两个容器都 Started = 成功
 ✔ Container finflow-web   Started
```

> - 首次会**拉取 nginx 等基础镜像**（几十~几百 MB），可能先出现一长串 `Pulling` + 进度条，等 3~10 分钟正常。
> - 最后两行 `Started` 出现、回到提示符 = 启动完成。
> - 若出现红字 `Error response from daemon` 或 `failed to ...`，把报错贴给我。

### 7.2 查看容器状态（🟢 有输出）

```bash
docker compose ps
```

**预期输出：**

```
NAME           IMAGE                  COMMAND                  SERVICE   STATUS          PORTS
finflow-app    finflow-app:latest     "java -jar app.jar ..."  app       running (healthy)  8080/tcp
finflow-web    nginx:1.27-alpine      "nginx -g 'daemon off'"  nginx     running         0.0.0.0:80->80/tcp, 443/tcp
```

**两行 STATUS 都是 `running` = 正常**。app 显示 `(healthy)` 表示后端就绪。

### 7.3 观察启动日志（🟡 交互式，看 Flyway 建表）

```bash
docker compose logs -f app
```

**预期界面（实时滚动的日志流，不是卡死）：**

```
finflow-app  | 2026-09-02T12:35:01.123Z  INFO 1 --- [           main] o.f.core.internal.command.DbMigrate    : Migrating schema "finflow" to version "1 - init rbac and bank accounts"
finflow-app  | ... Migrating schema ... to version "2 - statement import and voucher push"
finflow-app  | ... (中间还有 3~13 各版本)
finflow-app  | ... Migrating schema ... to version "14 - retire dead transfer permissions"
finflow-app  | ... Successfully applied 14 migrations to schema "finflow"
finflow-app  | ... Started FinanceSystemApplication in 8.42 seconds
```

**看到 `Successfully applied 14 migrations` + `Started ... in ... seconds` = 数据库建表和启动全部成功！**

**退出日志**（不影响服务，服务在后台继续跑）：按 `Ctrl + C`，回到提示符。

> MySQL 面实际迁移 14 个 = 主目录 13 个（V1~V6、V8~V14）+ MySQL vendor V7。若之前启动失败重试过，Flyway 会从断点续跑，正常。

---

## 8. 验收清单（逐项打勾）

| # | 验收项 | 操作 | 预期 |
|---|---|---|---|
| 1 | 前端可访问 | 浏览器打开 `http://101.200.72.87/` | 登录页出现，无白屏（等 10 秒再刷新一次） |
| 2 | API 通 | 登录页提交任意表单 | 返回明确业务错误而非 502/504 |
| 3 | 建表完整 | `docker compose logs app \| grep -i "applied 14"` | 打印 `Successfully applied 14 migrations`（🟢 有输出） |
| 4 | 表落库 | `mysql -h <RDS内网> -u finflow_app -p finflow -e "SHOW TABLES;"`（🟡 输密码后） | 约 31 张表（含 sys_user、bank_account 等） |
| 5 | 注册首个管理员 | `POST /api/auth/register`（唯一放行的注册口） | 返回成功，可登录 |
| 6 | 登录鉴权 | 管理员登录后访问各菜单 | 各页面数据正常加载 |
| 7 | 租户隔离抽查 | B 企业 token 访问 A 企业资源 | 404，不泄露存在性 |
| 8 | 8080 未暴露 | 本机 `curl http://101.200.72.87:8080` | 连接被拒（容器未映射 8080） |
| 9 | 容器自愈 | `docker restart finflow-app`（🟢 输出容器名 `finflow-app`） | 服务自动恢复 |
| 10 | 重启不重复迁移 | `docker compose down && docker compose up -d`（🔴 1~2 分钟） | 日志显示 `validated 14 migrations` 而非再次 Migrating |

> ⚠️ 上线前 P0：`/api/auth/register` 目前是 permitAll，公网可注册。验收完成后第一件事就是收紧（改邀请制/关闭注册，或 Nginx 限 IP），需要我改代码随时说。

---

## 9. HTTPS（无控制台权限下的两条路）

> ⚠️ 服务器在中国大陆机房 → **域名必须 ICP 备案**，备案与域名解析在域名/云控制台，需要管理员配合（§1 第 3 项）。

### 路线 A：正式 HTTPS（域名 + 证书）——需管理员

1. 管理员：域名 A 记录指向 `101.200.72.87`；走 ICP 备案（1~3 周）；申请免费 DV 证书。
2. 管理员把 `fullchain.pem` + `privkey.pem` 放到服务器（或给你后你上传到 `/opt/finflow/certs/`）。
3. 切换配置（🟢/🔴）：

```bash
mkdir -p /opt/finflow/certs        # 🟢 无输出
chmod 600 /opt/finflow/certs/privkey.pem   # 🟢 无输出
# 编辑 docker-compose.yml：注释掉 nginx.conf 挂载行，放开 nginx-https.conf 行（文件内有现成注释标记）
# 编辑方法同 §6.2 的 nano/vim
docker compose up -d --force-recreate nginx   # 🔴 输出 Container finflow-web Started
docker compose exec nginx nginx -t            # 预期输出末尾：nginx: configuration file /etc/nginx/nginx.conf test is successful
curl -I https://<域名>/                       # 预期第一行：HTTP/1.1 200 OK
```

**`test is successful` + `HTTP/1.1 200 OK` = HTTPS 生效。**

### 路线 B：自签证书（无域名过渡，浏览器有"不安全"告警）——全自助

```bash
mkdir -p /opt/finflow/certs && cd /opt/finflow/certs
```

**预期：🟢 无输出（mkdir 静默）。**

```bash
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout privkey.pem -out fullchain.pem \
  -subj "/CN=finflow" \
  -addext "subjectAltName=IP:101.200.72.87"
```

**预期输出：**

```
Generating a RSA private key
writing new private key to 'privkey.pem'
-----
```

**看到两行 = 证书生成成功。** 然后按路线 A 第 3 步切换 nginx 配置并验证。

浏览器首次访问点"高级 → 继续前往"。自签仅加密不验证身份，适合内测过渡，**正式对外前务必换路线 A**。

> HTTPS 切换后：80 自动 301 跳 443；JWT_SECRET/DB 配置不用动；前端全部走相对路径 `/api`，无混合内容问题。

---

## 10. 日常运维

### 10.1 常用命令（附预期）

| 操作 | 命令 | 预期 |
|---|---|---|
| 看实时日志（🟡） | `docker compose logs -f app` | 滚动日志，Ctrl+C 退出（不影响服务） |
| 看最近 100 行（🟢） | `docker compose logs --tail=100 app` | 打印 100 行后自动结束，回到提示符 |
| 重启后端（🟢） | `docker compose restart app` | 输出 `Container finflow-app ... Restarting/Restarted` |
| 全部重启（🔴 1~2 分钟） | `docker compose down && docker compose up -d` | `Removing` 后重新 `Started` |
| 进容器排查（🟡） | `docker compose exec app sh` | 出现 `/#` 提示符（容器内 shell），退出输 `exit` |
| 资源占用（🟡） | `docker stats` | 实时表格，Ctrl+C 退出 |

### 10.2 备份

- **RDS 自动备份**：找管理员确认已开启（§1 第 5 项），这是主兜底。
- **自建额外快照**（可选，ECS 上执行，🟢 静默约 1~3 分钟）：

```bash
mysqldump -h <RDS内网地址> -u finflow_app -p finflow \
  --single-transaction --set-gtid-purged=OFF > /opt/finflow/backup-finflow-$(date +%F).sql
```

**预期：** 输入密码后**屏幕无输出**（重定向到文件了，正常）。确认是否成功：

```bash
ls -lh /opt/finflow/backup-finflow-*.sql
# 预期：-rw-r--r-- 1 root root 2.3M Sep  2 12:40 backup-finflow-2026-09-02.sql   ← 有大小 = 备份成功
```

### 10.3 发版更新

```bash
cd /opt/finflow
docker compose build app && docker compose up -d    # 🔴 构建 1~3 分钟
docker compose logs --tail=50 app                   # 看有无 Migrating/Started/ERROR
```

### 10.4 回滚

后端回滚 = 用旧 jar 重跑 `docker compose build app && docker compose up -d`（建议保留 `app.jar.v<日期>` 副本）。数据库结构变更全部向前兼容设计，回滚只需回滚 jar；若发版含破坏性迁移，回滚方案需在发版前单独设计。

---

## 11. 常见问题（FAQ）

### 11.1 "命令输入后没反应"自查表（先看这个）

| 现象 | 真相 | 怎么办 |
|---|---|---|
| 输入密码后什么都没显示 | 🟡 **正常**，密码输入就是无回显 | 输完直接回车 |
| 屏幕变成全屏文本/编辑器 | 🟡 进了 nano/vim | 按 §6.2 的退出方法（nano: Ctrl+X；vim: Esc 后 `:q!`） |
| 出现 `mysql>` 提示符 | 🟡 已进入数据库 | 正常操作，退出用 `exit;` |
| 日志流一直滚动不停 | 🟡 在 `docker compose logs -f` 里 | 这是"实时跟踪"模式，Ctrl+C 退出，服务不受影响 |
| 命令后直接回到提示符、无任何输出 | 🟢 静默命令**成功了** | 继续下一步 |
| 一条命令跑了 5 分钟还在滚 | 🔴 长耗时命令（传包/装 Docker/拉镜像） | 耐心等，**别 Ctrl+C** |

### 11.2 报错对照表

| 症状 | 原因 | 处理 |
|---|---|---|
| 启动报 `Communications link failure` | RDS 白名单未含 ECS / 不在同 VPC / 内网地址错 | 重跑 §4.7 探测命令；地址以你手上的为准；不行找管理员（§1-1） |
| 启动报 `Access denied for user` | .env 账号/密码错，或账号未授权 finflow 库 | 核对 §5.3 授权与 .env 两处；重跑 `GRANT` |
| `mysql: command not found` | 客户端没装上 | 重跑 §5.1，或用 Docker 备选方式 |
| `ERROR 2003 Can't connect to MySQL server` | RDS 地址错 / 网络不通 | 检查地址拼写；跑 §4.7 探测 |
| `ERROR 1045 Access denied`（连接时） | root 密码错 | 核对密码；确认是 root 高权限账号 |
| `ERROR 1044 Access denied`（执行 SQL 时） | 当前账号无建库权限 | 用 root（高权限）账号执行 §5.3 |
| Flyway 报 checksum mismatch | 曾手工改过已执行迁移 SQL | 恢复原 SQL 或 `flyway repair`（先备份） |
| 页面能开但接口 502 | app 未启动完或挂了 | `docker compose logs --tail=100 app` 看栈；`docker compose ps` 看健康 |
| 中文/emoji 乱码 | 库字符集非 utf8mb4 | 确认建库语句带 `CHARACTER SET utf8mb4`；已建错则重建库 |
| 时间差 8 小时 | RDS 时区非 +8 | 先确认连接串 `serverTimezone=Asia/Shanghai` 在；仍差则找管理员（§1-4） |
| 上传文件 413 | nginx body 限制 | `nginx-app-locations.conf` 已设 20m，可按需调大 |
| 磁盘涨满 | docker 日志无限堆积 | `docker system prune` + 配日志轮转 |
| SSH 连接超时 | 不在公司出口 IP | 回公司网络，或让管理员加你的 IP（§1-2） |
| 拉镜像报 `lookup mirror.csi.aliyun.com: no such host` | **配了已失效的阿里云旧加速器**（`mirror.csi.aliyun.com` 2024 年已下线） | 见下方 §11.3，三步修复 |
| 拉镜像一直卡住/超时（无报错或 `dial tcp: i/o timeout`） | 直连 Docker Hub 被限 | 见下方 §11.3 的"备选方案"配社区源 |
| app 反复启动失败，Flyway 报 `Invalid default value for 'xxx'`（xxx 是 TIMESTAMP 列），清 flyway_schema_history 后重放仍失败 | **阿里云 RDS 参数 `explicit_defaults_for_timestamp=OFF`**（沿袭 5.7 语义）→ 无显式 DEFAULT 的 TIMESTAMP 列被隐式赋零值默认 → sql_mode 的 `NO_ZERO_DATE`+`STRICT_TRANS_TABLES` 拒绝零日期 | 见下方 §11.4（迁移 SQL 的 TIMESTAMP 列必须带显式 DEFAULT；本部署包已修复，若报错说明用了旧 jar，重传 §7.2 的新包） |

### 11.3 镜像拉取失败专项（`no such host` / 超时）

**现象：** `docker compose up` 时报 `failed to resolve reference "docker.io/..."`、`dial tcp: lookup mirror.csi.aliyun.com: no such host`，或镜像拉取长时间卡住。

**原因：** Docker daemon 里配的镜像加速器地址失效（阿里云旧公共地址 `mirror.csi.aliyun.com` 已于 2024 年下线，DNS 都解析不到）；或直连 Docker Hub 网络受限。

**修复三步：**

**① 清掉失效加速器，改直连：**

```bash
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": []
}
EOF
sudo systemctl restart docker
```

**预期：** 🟢 tee 回显 JSON 后无输出；restart 无输出回提示符 = 成功。

**② 验证 + 拉小镜像测试直连：**

```bash
docker info 2>/dev/null | grep -A1 "Registry Mirrors"
docker pull nginx:1.27-alpine
```

**预期：** 第一条应显示空列表 `[]`。第二条若出现 `Status: Downloaded newer image for nginx:1.27-alpine` = **直连可用**，直接回去重跑 §7.1。

**③ 直连不行 → 配社区源（见 §4.6 备选方案）：**

```bash
curl -sI -m 8 https://docker.m.daocloud.io/v2/ -o /dev/null -w "daocloud: %{http_code}\n"
curl -sI -m 8 https://docker.1ms.run/v2/ -o /dev/null -w "1ms: %{http_code}\n"
curl -sI -m 8 https://hub.rat.dev/v2/ -o /dev/null -w "rat: %{http_code}\n"
```

返回 `200/401/403` = 可用，把它填进 daemon.json 的 `registry-mirrors`，再 `systemctl restart docker` + 重跑 `docker pull nginx:1.27-alpine`。

**④ 别忘了 backend 的大镜像：** nginx 通了不代表后端能起来——backend 基于 `eclipse-temurin:17-jre`（约 200MB+），单独先拉一次确认：

```bash
docker pull eclipse-temurin:17-jre
```

**都通了**再重跑 `cd /opt/finflow && docker compose up -d --build`。

**官方兜底：** 社区源都不稳 → 找公司管理员要阿里云 ACR 专属加速地址（免费、官方、最稳）：阿里云控制台 → 容器镜像服务 ACR → 镜像加速器，形如 `https://xxxxxx.mirror.aliyuncs.com`，填入 `registry-mirrors` 即可。

### 11.4 排障实录：Flyway 迁移反复失败（`Invalid default value for TIMESTAMP`）——阿里云 RDS 参数坑

**完整现象链（2026-09-02 实测）：**
1. V2 迁移在 RDS 上失败，清 `flyway_schema_history` 失败记录后重放**仍然失败**——因为根因不是烂账残留；
2. `docker compose logs app --tail 60` 只见 `Validate failed: Detected failed migration to version 2` 堆栈——**这是启动校验拦截**（history 有 `success=0` 就拒绝重放，真实错误在更早日志或被删容器里）；
3. 抓全量日志（容器别 down）才看到真凶：`Caused by: java.sql.SQLSyntaxErrorException: Invalid default value for 'completed_at'`。

**根因（RDS 参数组合，非 SQL 语法错误）：**
```sql
SELECT @@explicit_defaults_for_timestamp, @@sql_mode;
-- 结果: 0 | ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,...
```
- 阿里云 RDS MySQL 8.0 参数模板把 `explicit_defaults_for_timestamp` 设为 **0（OFF）**（沿袭 5.7 语义，与社区版 8.0 默认 ON 不同）；
- OFF 模式下，**无显式 DEFAULT 的 TIMESTAMP 列**会被自动处理并隐式赋零值默认 `0000-00-00 00:00:00`；
- sql_mode 含 `NO_ZERO_DATE` + `STRICT_TRANS_TABLES` → 零日期直接拒绝 → DDL 建表失败；
- 本地/CI 用社区版 8.0（默认 ON）跑全绿，**上线必炸**——CI 需模拟该参数才防得住（本仓库 CI 已改 mysql:8.0 + `--explicit_defaults_for_timestamp=OFF`）。

**写法规范（迁移 SQL 新增 TIMESTAMP 列必须二选一）：**
```sql
-- 可空列：必须同时显式 NULL 属性 + DEFAULT NULL（只写 DEFAULT NULL 会被 OFF 模式强制成 NOT NULL 照样报 1067）
completed_at TIMESTAMP NULL DEFAULT NULL,
-- 必填列：显式 DEFAULT CURRENT_TIMESTAMP
transaction_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
```
修复后本部署包 jar 已含全部修正（7 个迁移 18 处）；若线上仍报此错，先确认用的是不是新 jar（`docker compose build app` 重建过）。

### 11.5 排障实录：登录页白屏（`reading 'version'`）——vite manualChunks 手工分桶运行时断裂（2026-09-02）

**完整现象链：**
1. 用户报 `http://101.200.72.87/` 白屏，控制台 `rc-components-DV2vU3nI.js:1 Uncaught TypeError: Cannot read properties of undefined (reading 'version')`；
2. 公网 curl 首页 HTML 200 且资源全相对路径（无 CDN）→ 排除 CDN；
3. 线上 chunk 与本地部署包 **MD5 逐字节一致**（`876bf7a3...`）→ 排除部署覆盖/半覆盖事故，产物自身缺陷；
4. **本地同源复现**：本机起 http.server 托管 `frontend/dist` + Edge headless 打开 → **稳定复现白屏 + 同款报错** → 与网络/服务器无关；
5. 解包定位：`rc-components` chunk 顶层执行 `Number(o.version.split(".")[0])`，`o`（React）为 undefined——React 由 `framework` chunk `export{k as r}` 跨 chunk 提供，运行时绑定断裂；
6. git 溯源：commit **7280e81**（09-01「frontend code splitting」）首次引入 `vite.config.ts` 的 `manualChunks` 手工分桶（react→framework、`/rc-`→rc-components），只验证了 `vite build ok`，**未做浏览器运行时验收**——构建绿 ≠ 运行绿。

**修复（全栈侧完成，commit c268f38）：** 移除 vite manualChunks 手工分桶，回归 Vite 默认分包策略。产物 21 文件（index.html + assets/20）。

**部署动作 = web-dist 热替换（纯前端修复，app.jar 未变，无需重启 app）：**
```bash
# ① 上传（本机 scp，web-dist 顶层目录结构在包内）
scp web-dist-20260902.tar.gz web-dist-20260902-MD5.txt root@101.200.72.87:/opt/finflow/
# ② 服务器：备份旧版 → 解压 → 核对（bind mount 目录被 rename 后必须重建 nginx）
cd /opt/finflow
mv web-dist web-dist.bak-20260902-1728          # 旧坏版留作回滚弹药，勿删
tar -xzf web-dist-20260902.tar.gz
md5sum -c web-dist-20260902-MD5.txt             # 预期 21/21 OK
docker compose up -d --force-recreate nginx     # bind mount 重新解析；app 不动
```
**验收（三层，缺一不可）：**
1. 服务器本地 `curl -s http://127.0.0.1/` 首页 HTML 引用**新 chunk hash**（`index-C04XUgQt.js`）；
2. 公网 `curl --noproxy '*'`：首页 200 + 新 chunk 可下载 + `/api/health` 仍 401（Security 不回归）；
3. **浏览器级**（本轮教训的闭环，构建绿不算数）：Edge headless `--dump-dom` 公网打开 → `<div id="root">` 内登录页渲染非空 + console **零** Uncaught/TypeError。

**方法论沉淀（写进后续发布流程）：**
- 前端产物**每次上线前必须浏览器级冒烟**（本地 http.server + Edge headless，检查 root 非空 + console 零错误），不能只信 `vite build ok` 与 curl 200；
- 「首页 200」只证明 HTML 下发成功，**不代表页面能渲染**——curl 与浏览器是两回事；
- manualChunks 手工分桶是高危配置，改动后必须过运行时验收；纯前端修复走 web-dist 热替换（备份→解压→md5sum→`--force-recreate nginx`），别重启 app 造成不必要抖动。

---

## 附：部署包内容与来源

| 文件 | 来源 | 用途 |
|---|---|---|
| `app.jar` | 本地构建（mvn package，测试 36/36 全绿） | 后端运行 |
| `web-dist/` | 本地构建（vite build） | 前端静态资源 |
| `docker-compose.yml` | 仓库 `deploy/` | 双容器编排（app + nginx） |
| `Dockerfile.backend` | 仓库 `deploy/` | 后端镜像（temurin 17 JRE + 健康检查） |
| `nginx.conf` / `nginx-https.conf` | 仓库 `deploy/` | HTTP / HTTPS server 块 |
| `nginx-app-locations.conf` | 仓库 `deploy/` | 共享业务路由（SPA 回退、/api 反代） |
| `.env.example` | 仓库 `deploy/`，**已预填 RDS 内网地址** | 环境变量模板（复制为 .env 后填真实值） |

> 部署包已包含最新代码（含 V14 迁移与方案 A+C 清理）。首启后 Flyway 自动落 V1~V14，**无需手工导 SQL**。
