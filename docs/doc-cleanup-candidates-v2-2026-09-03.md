# docs 文档清理候选清单 v2（2026-09-03 19:0x）

> 状态：**✅ 已执行（2026-09-03 19:1x，用户拍板"按推荐集执行"）** ｜ 编写：WB · 2026-09-03
> 执行结果：删 8 份（6 闭环会话产物 + 2 零引用计划文档）+ 归档 1 份执行回执 → `docs/archive/2026-09-03-cmb-session/`；`cmb-clouddc/FINFLOW-*` 留存 3 份活资产（对接要点/联调教程/行动手册）。恢复：`git restore docs/`（git 跟踪内）。

---

## 一、推荐归档/删除：CMB 接入会话批次产物（6+1 份，均已闭环）

> 背景：9/3 下午 CMB 接入经历"运维清单 → 执行回执 → 缺口工单① → 交付单① → 缺口工单②(SUC0000) → 交付单② → 状态语义改造交付单"完整链路，**全部已闭环且线上复验通过**（最终 jar `22bf01ba`/`98cf29f9` 已上线，18:00-18:10 真实 STATEMENT 4 笔落库）。这些"工单/交付单/运维清单"是一次性会话产物，闭环后即纯历史。

| # | 文件 | 大小 | 闭环证据 | 建议 |
|---|---|---|---|---|
| 1 | `cmb-clouddc/FINFLOW-CMB接通-部署工程师运维清单-20260903.md` | 6.4K | 执行回执（16:30）确认清单 4 步已执行完毕 | 归档（或删） |
| 2 | `cmb-clouddc/FINFLOW-CMB缺口工单-交全栈侧-20260903.md` | 6.5K | 交付单①确认修复；回执 16:55 确认建档/触发/真实调用全通 | 删 |
| 3 | `cmb-clouddc/FINFLOW-CMB缺口修复-交付单-20260903.md` | 5.5K | jar `fb60e7ae` 已上线；后被缺口②取代 | 删 |
| 4 | `cmb-clouddc/FINFLOW-CMB缺口2-SUC0000映射-交全栈侧-20260903.md` | 4.8K | 交付单②（17:07）确认 115/115，jar `98cf29f9` 就绪 | 删 |
| 5 | `cmb-clouddc/FINFLOW-CMB缺口2-SUC0000修复-交付单-20260903.md` | 3.8K | 已上线，回执 18:00 复验 4 笔真实流水 | 删 |
| 6 | `cmb-clouddc/FINFLOW-真实直联状态语义改造-交付单-20260903.md` | 4.6K | 17:48 上线，18:00-18:10 复验 STATEMENT 4 笔成功 | 删 |
| 7 | `cmb-clouddc/FINFLOW-CMB接通-部署工程师执行回执-20260903.md` | 19.4K | 完整排障时间线（16:30→16:55→18:00 三阶段） | **保留**（历史取证价值最高，含三次状态更新与错误码记录；如想彻底清，也可归档至子目录） |

**保留（活资产，勿动）**：`FINFLOW-招行免前置对接开发要点.md`（接口速查/自检清单）、`FINFLOW-招行联调傻瓜教程.md`、`FINFLOW-招行免前置AI行动手册.md`、`cmb-clouddc/markdown/`（接口文档）、`raw/`（原始镜像，见第三类）。

## 二、推荐归档：根目录计划/历史类（零引用确认）

| # | 文件 | 大小 | 判断依据 | 建议 |
|---|---|---|---|---|
| 8 | `frontend-engineering-plan.md` | 14.7K | v0.2 时代工程计划；grep 全 docs 零外部引用（仅被已执行的 v1 清理清单自身提及） | 删 |
| 9 | `backend-engineering-plan.md` | 26.7K | 同上，零外部引用 | 删 |
| 10 | `doc-cleanup-candidates-2026-09-03.md` | 4.4K | v1 清理清单本体，已执行完毕 | 删（v2 清单已含其结论；如需审计"删了什么"可留） |

## 三、待决策：厂商文档镜像（大体积、有冗余，非删除而是**搬迁/精简**）

> 记忆红线：`docs/` 下禁止提交厂商二进制（.exe/.dll/.class/.zip）；当前 .gitignore 已排除 `*.exe/*.dll/*.class/*.zip` + `cmb-clouddc/samples/` + `linklab/`（git 内无二进制，安全）。

| 目录 | 体积 | 构成 | 冗余情况 | 建议选项 |
|---|---|---|---|---|
| `cmb-clouddc/raw/` | 13M（59 文件 git 跟踪） | 招行文档中心原始抓取 json | 与 `markdown/`（123 文件，转译后可用态）内容重复；原始态含大量未格式化 html/json 噪声 | A. 整体搬出 docs（如 D: 厂商镜像目录）B. 保留 markdown/，删 raw/ 中已转译的源 C. 不动 |
| `cmb-clouddc/samples/` | 4.3M | 官方示例代码+国密工具（含 SMKeyTool.exe 等，已被 gitignore 排除） | 无冗余，但含可执行文件（本地留存合规即可） | A. 搬出 docs B. 不动（已 gitignore） |
| `cmb-clouddc/linklab/` | 53K | 联调工具输出 .class | 一次性联调产物 | 删（已 gitignore，本地也无用） |
| `kingdee-openapi/raw/` + `articles/*.json` | 4.6M | 金蝶文章原始抓取 json + 转译 md（`articles-md/` 84K 为可用态） | `articles/*.json`（16 份）+ `raw/` 与 `articles-md/*.md`（13 份）内容重复；另有抓取脚本 `fetch-articles.js/kingdee-login.js/probe-kingdee-api.js` + `storage-state.json/manifest.json` | A. 保留 articles-md/（md 可用态）+ README，删 raw json 与抓取中间态 B. 整目录搬出 C. 不动 |

## 四、建议保留（勿动）——与 v1 一致

`product-requirements.md` / `quality-security-checklist.md` / `ui-specification.md` / `permission-catalog.md` / `pending-fixes.md` / `deployment-aliyun-2026-09-02-noconsole.md` / `deployment-guide.md`（被 environment-inventory 引用）/ `maintenance-sop.md` / `environment-inventory.md` / `auto-accounting-test-plan.md` / `operations-module-test-plan.md` / `function-points.md` / `tech-architecture.md` / `v0.3-module-architecture.md` / `finflow-overview.md` / `bank-connect-schedule-2026-09-03.md` / `citic-bank-interface-dev-guide.md` / `kingdee-openapi-knowledge-base.md` / `v0.2/`（5 份 runbook/backlog 仍有运维参考价值）/ `architecture-review-2026-09-03.md`（今日评审，pending-fixes 关联）/ `FINFLOW-银行连接模块-真实状态路线图-20260903.md`（活跃方案）

## 五、执行建议（拍板后）

```bash
# 默认推荐：只删/归档第一、二类（9 份），第三类按 D 决策执行
cd docs
# 归档（保留取证价值）
mkdir -p archive/2026-09-03-cmb-session
mv cmb-clouddc/FINFLOW-CMB接通-部署工程师执行回执-20260903.md archive/2026-09-03-cmb-session/
# 删除已闭环会话产物 + 零引用计划文档
rm cmb-clouddc/FINFLOW-CMB接通-部署工程师运维清单-20260903.md \
   cmb-clouddc/FINFLOW-CMB缺口工单-交全栈侧-20260903.md \
   cmb-clouddc/FINFLOW-CMB缺口修复-交付单-20260903.md \
   cmb-clouddc/FINFLOW-CMB缺口2-SUC0000映射-交全栈侧-20260903.md \
   cmb-clouddc/FINFLOW-CMB缺口2-SUC0000修复-交付单-20260903.md \
   cmb-clouddc/FINFLOW-真实直联状态语义改造-交付单-20260903.md \
   frontend-engineering-plan.md backend-engineering-plan.md \
   doc-cleanup-candidates-2026-09-03.md
# 恢复：git restore docs/
```

若确认执行，回复"执行"即可；也可指定只处理其中若干份，或对第三类厂商镜像给出搬迁/精简选项。
