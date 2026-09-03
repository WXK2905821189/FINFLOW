# FINFLOW 招行(CMB)真实接入 — 部署工程师运维清单

> 版本: v1.0 ｜ 2026-09-03 ｜ 交付方: 全栈工程师对话 ｜ 执行方: 部署工程师对话
> 前置: 招行测试网关联调已通（探针 SUC0000 双接口），本清单解决"**Web 系统页面看不到流水**"问题

---

## 0. 背景（为什么页面看不到流水）

- 探针打通 ≠ 系统打通：探针是独立 Java 程序，**绕开了 Spring 应用**
- 财务系统 `finflow-app` 容器从未被配置调招行：
  - 旧版 `docker-compose.yml` 的 backend 环境变量**没有透传任何 CMB 变量**（`BANKDATA_CMB_REAL_ENABLED` / `CMB_URL` / `CMB_UID` / 三个密钥 / `CMB_BRANCH_CODE`）
  - docker compose **只透传文件里列出的变量** → 即使 `.env` 写了也进不了容器
  - 结果：容器里只装配了 `CmbMockBankDataAdapter`（CMB_MOCK 模拟造数），真实适配器 `RealCmbBankDataAdapter`（代码已存在，靠 `bankdata.adapter.cmb.real-enabled=true` 激活）从未生效
- 页面流水读 RDS 投影表（`bank_data_statement`/`bank_data_balance`），数据靠"同步任务"落库；同步调度默认关，需手动触发

**本清单目标**：让 `finflow-app` 容器真正启用 CMB real 适配器（4 步：更新 compose → 填 .env → 重启 → 验证）。

---

## 1. 步骤一：更新 docker-compose.yml（必做，已由开发侧改好）

开发侧已修改本地 `deploy/docker-compose.yml`，backend `environment` 段新增 7 行：

```yaml
      BANKDATA_CMB_REAL_ENABLED: ${BANKDATA_CMB_REAL_ENABLED:-false}
      CMB_URL: ${CMB_URL:-}
      CMB_UID: ${CMB_UID:-}
      CMB_PRIVATE_KEY: ${CMB_PRIVATE_KEY:-}
      CMB_PUBLIC_KEY: ${CMB_PUBLIC_KEY:-}
      CMB_SYM_KEY: ${CMB_SYM_KEY:-}
      CMB_BRANCH_CODE: ${CMB_BRANCH_CODE:-}
```

**两种获取方式，任选其一：**

- **方式 A（推荐）**：从开发侧拿最新 `deploy/docker-compose.yml` 覆盖 ECS 上的 `/opt/finflow/docker-compose.yml`（scp 上传后比对确认含上面 7 行）
- **方式 B（最小改动）**：直接在 ECS 上手动追加——`nano /opt/finflow/docker-compose.yml`，定位到 backend 的 `environment:` 段中 `BANKDATA_REAL_ADAPTERS_ENABLED:` 这一行，在其后插入上面 7 行（保持 6 空格缩进对齐），保存

改完自检：`cd /opt/finflow && grep -n "CMB_" docker-compose.yml` 应输出 7 行含 `CMB_` 的变量。

> ⚠️ 若本机 `deploy/docker-compose.yml` 有其他未发布的改动，请以开发侧交付的版本为准整体替换，不要只抄这 7 行。

---

## 2. 步骤二：.env 追加 CMB 配置（含测试密钥，勿外传）

`nano /opt/finflow/.env`，文件末尾追加以下 7 行（**全部来自招行测试回执，仅测试环境有效**）：

```bash
# ---- CMB 招行真实接入（2026-09-03 新增，测试网关）----
BANKDATA_REAL_ADAPTERS_ENABLED=true
BANKDATA_CMB_REAL_ENABLED=true
CMB_URL=http://cdctest.cmburl.cn/cdcserver/api/v2
CMB_UID=U006855378
CMB_PRIVATE_KEY=NBtl7WnuUtA2v5FaebEkU0/Jj1IodLGT6lQqwkzmd2E=
CMB_PUBLIC_KEY=BNsIe9U0x8IeSe4h/dxUzVEz9pie0hDSfMRINRXc7s1UIXfkExnYECF4QqJ2SnHxLv3z/99gsfDQrQ6dzN5lZj0=
CMB_SYM_KEY=VuAzSWQhsoNqzn0K
CMB_BRANCH_CODE=12
```

说明：
- `BANKDATA_REAL_ADAPTERS_ENABLED=true` 是聚合调用层总开关；`BANKDATA_CMB_REAL_ENABLED=true` 激活 CMB real 适配器 bean；两个都要 true
- `CMB_BRANCH_CODE` 传 12 即可（实测银行按账户归属返回 bbknbr=75 深圳，以账户归属为准，不影响调用）
- ⚠️ **安全**：此文件含国密测试密钥，禁止提交任何 git 仓库、禁止转发

---

## 3. 步骤三：重启 app 容器

```bash
cd /opt/finflow
docker compose config --quiet && echo "compose OK"   # 语法校验，报错则回查前两步
docker compose up -d --force-recreate app
docker compose ps
```

预期：`finflow-app` 容器 `running (healthy)`，`finflow-web` 正常。首次重启会重新 build 镜像（若镜像里 jar 旧，可顺手 `docker compose build app && docker compose up -d --force-recreate app`——但**仅当需要更新 jar 版本**时做，本次改动只涉及 compose 配置，重建可选）。

---

## 4. 步骤四：验证 real 适配器已激活（3 项检查）

```bash
# ① 容器环境变量已注入（关键检查）
docker inspect finflow-app --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E "BANKDATA_(REAL|CMB)|CMB_"

# ② 应用启动正常
docker logs --tail 50 finflow-app | grep -iE "error|exception|started" 

# ③ 版本确认（可选）
docker exec finflow-app sh -c 'java -version 2>&1 | head -1'
```

**通过标准**：
- ① 能看到 `BANKDATA_REAL_ADAPTERS_ENABLED=true`、`BANKDATA_CMB_REAL_ENABLED=true`、`CMB_URL=http://cdctest...`、`CMB_UID=U006855378` 及三密钥已注入
- ② 无启动异常，Spring 正常 Started
- 若 ① 中 CMB 变量为空 → compose 文件没生效或 .env 写错，回查步骤一/二

---

## 5. 完成后回执（贴回开发侧/全栈侧对话）

把以下内容贴回对话即可，后续"建档 + 触发同步"由业务侧/开发侧完成：

```text
1. docker inspect finflow-app env 中 BANKDATA_/CMB_ 行输出（可打码密钥中间段）
2. docker compose ps 状态
3. docker logs 最近 20 行无异常确认
4. RDS 可选项（若方便）：SELECT id, account_no, account_name FROM finflow.bank_account WHERE account_no='128965327910000';
   → 有行 = 系统内已有账户档案（大概率），无行 = 需要业务侧在页面补建档
```

---

## 6. 后续分工（不需要部署工程师执行，知悉即可）

1. **建档确认**：业务侧/开发侧在财务系统"银企直联/银行账户"确认存在账号 `128965327910000` 的档案（或补建）
2. **触发同步**：登录系统 → 银企直联模块 → 发起同步任务（STATEMENT_PULL，窗口 2026-09-01 ~ 2026-09-03）→ 校验 RDS `bank_data_statement` 出现 9/2 共 4 笔真实流水（合计 -0.27，收方何桂香）→ 页面即可见
3. **观察点**：首次真实调用若返回 DCAT003（IP 白名单）——出口 IP 应为本 ECS 公网 IP 101.200.72.87（已在白名单），若异常需核对 NAT 出口；若返回密钥类错误，核对 .env 三密钥与回执一致性

## 7. 回滚预案（如需回到 mock）

```bash
cd /opt/finflow
# .env 中把 BANKDATA_CMB_REAL_ENABLED 改回 false（BANKDATA_REAL_ADAPTERS_ENABLED 也可改 false）
docker compose up -d --force-recreate app
```
