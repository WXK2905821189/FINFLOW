# FINFLOW 生产环境维护 SOP（0 基础可执行版）

> 适用对象：**没有部署经验**的后续维护人员。照着做就能完成日常巡检、版本更新、故障回滚。
> 本文是「操作手册」——每条命令都给了预期输出，**看到预期才算成功**，没看到就停下看 §6。
> 原理细节/建站全过程/深坑实录：见 `docs/deployment-aliyun-2026-09-02-noconsole.md`（权威长文档，本文是其操作化子集）。
> 本文维护人：部署工程师（FINFLOW 部署对话）。每次修订在文末修订表登记。

---

## §0 五分钟背景（先读，不读会踩坑）

### 0.1 系统长什么样

| 角色 | 是什么 | 位置 |
|---|---|---|
| 前端 | React 静态页面 | ECS 服务器 `/opt/finflow/web-dist/`，由 nginx 容器提供 |
| 后端 | Spring Boot 应用 | ECS 服务器 `/opt/finflow/app.jar`，打进 app 容器运行 |
| 数据库 | MySQL 8.0 | 阿里云 RDS（内网地址，ECS 之外**连不上**） |
| 编排 | Docker Compose | `/opt/finflow/docker-compose.yml`，两个容器：`finflow-app` + `finflow-web` |

服务器：阿里云 ECS `101.200.72.87`（Debian 12）。**所有操作都在 ECS 上执行**，你本机只负责传文件。

### 0.2 两种「发版更新」的区别（先分清，别搞混）

| | 前端更新（最常见） | 后端更新 |
|---|---|---|
| 换什么 | `web-dist/` 整目录 | `app.jar` |
| 交付物 | `web-dist-日期.tar.gz` + MD5 清单 | `app.jar` + MD5 + 构建时间 |
| 动哪个容器 | 只重建 `finflow-web`（秒级） | 重建 `finflow-app`（1~3 分钟） |
| 风险 | 低（纯静态文件） | 中（会重启后端 + 可能跑 DB 迁移） |

> ⚠️ 判断这次是前端还是后端：**全栈交付时一定会说**。只说"页面更新"= 前端；带了 `.jar` = 后端（或前后端都有，那就 A+B 都做）。

### 0.3 三条铁律（违反 = 事故）

1. **先备份再替换**：动 `web-dist` 前先 `mv` 出 `.bak`；动 `app.jar` 前先 `cp` 出 `.bak`。旧版是唯一的回滚弹药，**永不删除**。
2. **收到交付先核对 MD5**：不核对就上线 = 白屏事故重演（见 §11.5 实录）。全栈侧交付一定会附 MD5 清单，`md5sum -c` 全 OK 才准替换。
3. **绝不碰数据库**：不 DROP、不手工改表、不删 `flyway_schema_history`。迁移由应用启动时自动执行（Flyway），你只管看日志确认成功。

---

## §1 开工前：你要有的东西

| 需要什么 | 从哪拿 | 说明 |
|---|---|---|
| 本机 SSH 客户端 | Windows 自带 | 开始菜单搜 "Git Bash" 或 "PowerShell"，本文命令两者通用 |
| ECS 登录密码 | 向项目负责人要 | 账号 `root@101.200.72.87`，**密码不写在本文档里**（防泄密） |
| 公司网络出口 | — | SSH 连不上时先检查是否在公司网络（手册 §1-2） |
| 本次交付物 | 全栈工程师对话 | tar.gz/jar + MD5 清单，文件名带日期 |

**连服务器（本机执行，输密码时屏幕无回显是正常的，输完回车）：**

```bash
ssh root@101.200.72.87
```

**预期：** 出现 `root@iZxxxx:~#` 提示符 = 登录成功。看到 `Permission denied` = 密码错或不在白名单。
**首次连接**问 `Are you sure you want to continue connecting` 输 `yes` 回车。

> 小技巧：登录后建议先敲一次 `cd /opt/finflow` 并回车，后面所有命令都在这个目录执行。本文所有「服务器执行」命令默认你已经在这个目录。

---

## §2 日常巡检（每次 5 分钟，建议每周一次）

**目的：** 确认系统活着、能登录、磁盘不爆。三查：

```bash
# ① 容器健康（服务器执行）
cd /opt/finflow && docker compose ps
```

**预期：** 两个容器都在，`STATUS` 列 `finflow-app` 显示 `(healthy)`、`finflow-web` 显示 `Up`。
**异常：** `Exited`/`Restarting` → 走 §6.2。

```bash
# ② 磁盘（服务器执行）
df -h / | tail -1
```

**预期：** `Use%` < 80%。超了 → 手册 §11.2「磁盘涨满」条目清理。

```bash
# ③ 页面可达（本机或任意浏览器）
# 浏览器打开 http://101.200.72.87/ → 应出现登录页，无白屏
```

**③ 异常：** 白屏/打不开 → §6.1。

---

## §3 标准更新流程 A：前端（web-dist 热替换，5 分钟）

**适用：** 页面视觉/交互/前端逻辑改动。**后端不用动，不影响正在跑的服务里的数据。**

### 3.1 本机：核对交付物

把交付的 `web-dist-日期.tar.gz` 和 `web-dist-日期-MD5.txt` 放进一个文件夹，在**该文件夹**内执行：

```bash
tar -xzf web-dist-*.tar.gz
md5sum -c web-dist-*-MD5.txt
```

**预期：** 每行一个 `OK`，最后全 `OK` 无 `FAILED`。
**不 OK：** **停止，找全栈侧重新交付**，别上传坏包。

### 3.2 本机：上传到服务器

```bash
scp web-dist-*.tar.gz web-dist-*-MD5.txt root@101.200.72.87:/opt/finflow/
```

**预期：** 输密码后文件进度条走完、回到提示符 = 成功。

### 3.3 服务器：备份 → 替换 → 核对 → 重建（一条龙）

```bash
cd /opt/finflow
mv web-dist web-dist.bak-$(date +%Y%m%d-%H%M)     # ① 备份当前线上版（回滚弹药）
tar -xzf web-dist-*.tar.gz                         # ② 解压出新 web-dist/
md5sum -c web-dist-*-MD5.txt                       # ③ 服务器上再核对一次
docker compose up -d --force-recreate nginx        # ④ 重建 nginx 让新文件生效（app 不受影响）
```

**预期：**
- ③ 全部 `OK`（21 行左右）；
- ④ 输出 `Container finflow-web Recreated` → `Started`，无报错。

**为什么要 `--force-recreate`：** 老目录被改名后，nginx 容器还指着旧挂载，必须重建一次让挂载指向新目录。

### 3.4 验收（缺一不可）

```bash
# ① 服务器内看首页引用的文件名（应是最新的 hash，如 index-xxxx.js）
curl -s http://127.0.0.1/ | grep -oE 'assets/index-[A-Za-z0-9_-]+\.js'
# ② 本机浏览器打开 http://101.200.72.87/ 刷新（建议 Ctrl+F5 强刷）
```

**验收通过 =** ① 有输出且不是旧文件名；② 登录页正常渲染、无白屏、按 F12 看 Console 无红色报错。
**验收不通过 =** 立即回滚，见 §6.3。

---

## §4 标准更新流程 B：后端（app.jar，动 app 容器）

**适用：** 接口/业务逻辑/安全改动（如收紧注册）。**会重启后端服务**，期间页面短暂打不开（约 1~3 分钟）属正常，选业务低峰做。

### 4.1 前置确认（服务器执行）

```bash
cd /opt/finflow && ls -la app.jar* 
```

**预期：** 能看到 `app.jar`（当前运行版）。顺手看下这次交付说明里有没有写「含 DB 迁移」——有的话 §4.4 盯日志时重点看 `Migrating`。

### 4.2 本机：核对 + 上传

```bash
# 核对（交付的 MD5 值 vs 实际文件，在 jar 所在目录执行）
md5sum app.jar
# 上传
scp app.jar root@101.200.72.87:/opt/finflow/app.jar.new
```

**预期：** 本机 `md5sum` 输出与交付方给的 MD5 **逐字符一致**才继续。

### 4.3 服务器：备份 → 换入 → 重建

```bash
cd /opt/finflow
cp app.jar app.jar.bak-$(date +%Y%m%d-%H%M)       # ① 备份旧版（回滚弹药）
mv app.jar.new app.jar                             # ② 新 jar 就位
docker compose build app                           # ③ 打进镜像（🔴 1~3 分钟，别 Ctrl+C）
docker compose up -d app                           # ④ 重启 app 容器
```

**预期：** ③ 结尾 `Successfully built`；④ 输出 `Container finflow-app Started`。

### 4.4 验收（等 1~2 分钟让应用启动完）

```bash
docker compose ps                                  # ① app 变成 (healthy) 才算好
docker compose logs --tail=80 app                  # ② 看启动日志
```

**预期：**
- ① `finflow-app` 状态 `Up X minutes (healthy)`；
- ② 日志里有 `Started ... in ... seconds`；若本次含 DB 迁移，还应看到 `Successfully applied 迁移` / `Schema is up to date`（后者=无新迁移，正常）。
- 看到红色 `ERROR`/`Exception` → §6.2。

```bash
# ③ 公网接口探活（本机执行，无代理直连）
curl --noproxy '*' -m 10 -o /dev/null -w "%{http_code}\n" http://101.200.72.87/api/health
# 预期 401 = 链路通（401 是安全拦截的正常表现，不是错）
```

浏览器打开首页 → 登录页正常 → **尝试登录一次**（若交付内容涉及登录/权限改动，此项必做）。

---

## §5 版本更新的公共纪律（A、B 都适用）

1. **一次只做一件事**：前端就只走 §3，后端就只走 §4；前后端同时交付就按 §3 → §4 顺序做，中间各自验收。
2. **保留交付物原件**：服务器上 `/opt/finflow/` 的 `web-dist-*.tar.gz`、`web-dist.bak-*`、`app.jar.bak-*` 全部**不要删**——它们是回滚弹药和历史留证。磁盘吃紧时也只删 30 天以前的（先问部署工程师）。
3. **登记发版记录**：更新完成后，在服务器执行以下命令留痕（输出结果截图或记到交接文档）：
   ```bash
   date '+%F %T' && docker compose ps && ls -la /opt/finflow/web-dist.bak-* /opt/finflow/app.jar.bak-* 2>/dev/null | tail -5
   ```

---

## §6 故障应急（先看现象对号入座，别乱动）

### 6.1 页面白屏 / 打不开

```bash
# ① 容器还活着吗（服务器执行）
cd /opt/finflow && docker compose ps
# ② 首页 HTML 通吗（服务器内）
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1/
```

| 现象 | 判定 | 处理 |
|---|---|---|
| 容器全 healthy + curl 200，但浏览器白屏 | 前端产物问题（多半是刚更新的 web-dist） | **回滚 web-dist（§6.3）**，并把 console 报错截图登记给全栈侧 |
| `finflow-web` Exited/Restarting | nginx 挂了 | `docker compose logs --tail=50 nginx` 看报错；配置问题找部署工程师 |
| `finflow-app` 非 healthy | 后端问题 | 走 §6.2 |
| 浏览器连不上（超时/拒绝） | 网络/安全组 | 检查是否公司网络；仍不行找管理员（§9） |

### 6.2 后端异常（接口 502 / app 反复重启）

```bash
docker compose logs --tail=100 app
```

看最后 100 行日志里的**第一条**红色报错/堆栈，把截图给部署工程师或全栈侧判断。
**不要**自己清库/删容器数据。`docker compose down && up` 是最后手段，做了会触发短暂全停，先问再动。

### 6.3 回滚标准动作（把「刚才的更新」退回去）

**前端回滚（最常见，30 秒，全程不删任何东西）：**
```bash
cd /opt/finflow
ls -d web-dist.bak-*                              # 看有哪些历史备份，挑要回滚到的那份
mv web-dist web-dist.bad-$(date +%Y%m%d-%H%M)     # ① 当前版改名挪走（保留留证，不删除）
mv web-dist.bak-xxxxxx web-dist                   # ② 恢复备份（xxxxxx = 上面 ls 挑出的实际名）
docker compose up -d --force-recreate nginx       # ③ 重建 nginx 生效
```
回滚后刷新浏览器验证登录页恢复。`web-dist.bad-*` 是刚换上去的版本，确认无用后再让部署工程师决定去留。

**后端回滚（用备份的旧 jar 重来一遍 §4.3 的 ③④，全程不删任何东西）：**
```bash
cd /opt/finflow
ls -la app.jar.bak-*                     # 挑要回滚的旧 jar
cp app.jar.bak-xxxxxx app.jar            # ① 用备份覆盖回旧版（xxxxxx = 实际文件名）
docker compose build app && docker compose up -d app   # ② 重建并重启
```

### 6.4 服务整体重启（页面全挂时的兜底）

```bash
cd /opt/finflow && docker compose restart
```
约 1~2 分钟恢复。若仍起不来，抓 `docker compose logs --tail=100 app` 截图上报，**不要反复重启**。

---

## §7 数据备份（数据库）

- **主兜底**：RDS 自动备份（需公司管理员确认开启，见 §9）。
- **手动额外备份**（可选，服务器执行，约 1~3 分钟，静默无输出=正常）：

```bash
mysqldump -h <RDS内网地址> -u finflow_app -p finflow --single-transaction --set-gtid-purged=OFF > /opt/finflow/backup-finflow-$(date +%F).sql
ls -lh /opt/finflow/backup-finflow-*.sql
```

**预期：** 第一条输密码后无输出（正常）；第二条能看到文件且有大小（几百 KB ~ 几 MB）= 成功。

---

## §8 禁区清单（看到这些操作=停手找人）

| 绝对不做 | 为什么 |
|---|---|
| 动 `flyway_schema_history` 表 / 清库 / DROP 表 | 应用启动自动管迁移，手工动 = 校验失败后端起不来 |
| 改 `/opt/finflow/.env` 里的数据库地址/密码 | 连不上库 = 全站挂；要改先备份并问部署工程师 |
| 删除 `web-dist.bak-*` / `app.jar.bak-*` | 唯一回滚弹药 |
| 手工 `docker exec` 进库执行 SQL | 绕过应用层 = 数据不一致 |
| 编辑 nginx 配置文件后不验证直接重载 | location 片段挂错位置会 `[emerg]` 起不来（手册 §11 有实录） |

**凡在禁区边缘的操作，先截图当前状态、把想法发给部署工程师，确认了再动。**

---

## §9 需要公司管理员的事（你搞不定的，只有管理员能做）

| 事项 | 说明 |
|---|---|
| 公司网络 IP 加白名单（SSH 连不上时） | 手册 §1-2 |
| RDS 自动备份开启确认（保留 ≥7 天） | 手册 §1-5 |
| HTTPS 正式证书 + 域名备案 | 手册 §9；过渡期可用自签评估（需部署工程师操作） |

---

## §10 常用命令速查卡

| 想干嘛 | 在哪执行 | 命令 |
|---|---|---|
| 登服务器 | 本机 | `ssh root@101.200.72.87` |
| 看容器状态 | 服务器 | `cd /opt/finflow && docker compose ps` |
| 看后端最近日志 | 服务器 | `docker compose logs --tail=100 app` |
| 跟后端实时日志 | 服务器 | `docker compose logs -f app`（Ctrl+C 退出） |
| 看 nginx 最近日志 | 服务器 | `docker compose logs --tail=50 nginx` |
| 重启后端 | 服务器 | `docker compose restart app` |
| 前端换版 | 服务器 | §3.3 四条龙 |
| 后端换版 | 服务器 | §4.3 |
| 前端回滚 | 服务器 | §6.3 |
| 磁盘 | 服务器 | `df -h / \| tail -1` |
| 传文件到服务器 | 本机 | `scp 文件名 root@101.200.72.87:/opt/finflow/` |
| 公网探活 | 本机 | `curl --noproxy '*' -m 10 -o /dev/null -w "%{http_code}\n" http://101.200.72.87/api/health`（预期 401） |

---

## 附录 A：发版验收门禁清单（每次更新后逐项打勾，全过才可宣称完成）

- [ ] 交付物 MD5 核对通过（本机一次 + 服务器一次）
- [ ] 旧版已备份（存在 `web-dist.bak-*` 或 `app.jar.bak-*`）
- [ ] `docker compose ps`：目标容器 `Up` 且 `finflow-app` 为 `(healthy)`
- [ ] 服务器内 `curl -s http://127.0.0.1/` 首页 200
- [ ] 公网 `curl --noproxy '*'` 首页 200、`/api/health` 返回 401（不是 502/504）
- [ ] 浏览器打开 http://101.200.72.87/ 登录页正常、**无白屏**
- [ ] 后端更新额外项：启动日志无 `ERROR`；含迁移则 `Successfully applied`
- [ ] 本次涉及的功能人工点一遍（登录/查询等）

## 附录 B：交付物接收标准（给全栈侧看）

一次合格的交付必须包含，缺一样拒绝接收：
1. 产物：前端 `web-dist-日期.tar.gz` / 后端 `app.jar`；
2. **文件级 MD5 清单**（或 jar 的 MD5 值）；
3. **构建时间**（`vite build` / `mvn package` 完成时刻）；
4. 来源标识：git commit hash；
5. 注明：是否含 DB 迁移（决定 §4.4 要不要盯 `Migrating`）。

---

## 修订记录

| 日期 | 版本 | 修订人 | 内容 |
|---|---|---|---|
| 2026-09-02 | v1.0 | 部署工程师（WB） | 初版。基于 FIX-001 白屏修复线上替换全流程实测沉淀；前端热替换命令与三层验收法取自手册 §11.5 实录 |
