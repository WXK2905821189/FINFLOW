# FINFLOW 待修清单（部署侧登记 · 交全栈工程师对话处理）

> 维护：部署工程师对话。发现代码缺陷不改代码，登记于此（文件+位置+现象+证据），由全栈工程师对话修复后出产物。
> 部署侧收到新产物时核对 MD5 + 构建时间，替换前备份旧产物。

---

## FIX-001（P0 · 线上白屏，阻塞浏览器验收）2026-09-02 16:28 登记

### 现象
- 公网 `http://101.200.72.87/` 白屏，`<div id="root">` 为空。
- 浏览器控制台：`rc-components-DV2vU3nI.js:1 Uncaught TypeError: Cannot read properties of undefined (reading 'version')`
- 影响：全部前端功能不可用（非局部页面问题，module 预加载阶段即中断）。

### 现象链 / 证据（部署侧已完成的排查）
1. **线上产物 = 本地部署包原样**：线上 index.html 引用的 chunk hash（index-CRV9K5yY / rc-components-DV2vU3nI / framework-CizkEVCI / antd-BdPy1Fz_ 等）与 `tmp/finflow-deploy-20260902/web-dist/assets` 完全一致；`rc-components-DV2vU3nI.js` 线上/本地 MD5 均 `876bf7a3e6172c3cfc7d58db7226b504`。→ 排除部署覆盖/半覆盖事故。
2. **本地原样复现**：Edge headless 打开本地静态服务的 `frontend/dist`（同源产物），console 报完全相同的错误：`Uncaught TypeError: Cannot read properties of undefined (reading 'version'), source: .../assets/rc-components-DV2vU3nI.js (1)`，root 为空。→ 产物自身运行时缺陷，与服务器/网络/用户浏览器缓存无关。
3. **报错点定位**：`rc-components-DV2vU3nI.js` 第 1 行（minified）`var Mc=Number(o.version.split(".")[0])` —— `o`（React 实例，chunk 内用作 `o.useRef`/`o.Component`）为 `undefined`，模块**顶层求值阶段**即读取 React 版本号，抛 TypeError 中断整个模块图。
4. **React 来源链**：rc-components chunk 头部 `import{... r as o ...}from"./framework-CizkEVCI.js"`（React 由 framework chunk 导出，framework 尾部 `export{... k as r ...}`，`k` 即 framework 内联的 react 对象）；另一路 `R as le` 也指向 React。跨 chunk 的 React 导出在运行时为 undefined。
5. **引入提交**：`7280e81`（2026-09-01 17:28，feat: managed adapter call executor and **frontend code splitting**）首次加入 `vite.config.ts` 的 `manualChunks`：react/react-dom/react-router → `framework`；`/rc-` → `rc-components`；antd → `antd` 等。提交说明仅验证 `vite build ok`，**未做浏览器运行时验收**（构建绿 ≠ 运行绿）。此前 f45ca7e（v0.1.0）为默认打包无 manualChunks。

### 根因线索（供全栈工程师对话参考，非结论）
- 嫌疑集中在 manualChunks 手工分桶导致的 **ESM chunk 间 React 互操作/绑定或求值顺序问题**：rc-* 库（rc-util 等）以 React default import 编译，打包后从 framework chunk 跨 chunk 取 React，运行时为 undefined。
- `version.split` 源码同类模式见于 `antd/es/config-provider/UnstableContext.js`（`Number(React.version...)` 类检查）。

### 建议修复方向（任选，需实测验证）
- A. 移除 `/rc-` 的独立分桶（rc-* 并入 antd 桶或 vendor 桶），重构建验证；
- B. 将 react/react-dom 显式拆为单一独立 chunk 并核查其 export 在 framework 内的连接方式；
- C. 核查 pnpm 依赖树 react 是否多副本（`pnpm why react`），排除 dual-package 干扰；
- D. 修完**必须浏览器实测首屏**：页面渲染非空、console 无 TypeError（部署侧会以 Edge headless + 公网双重验收兜底）。

### 验收标准
- 重新构建产物替换后：`curl http://101.200.72.87/` 引用的 chunk hash 更新；浏览器打开登录页正常渲染；console 无 `reading 'version'` 报错；`/api/health` 仍 401（链路不回归）。

### 交付要求（对全栈工程师对话）
- 新产物（web-dist 或前端 dist 目录打包）+ **目录级 MD5 清单** + 构建时间；部署侧负责上传替换 `/opt/finflow/web-dist`（替换前备份当前 web-dist）并重建 nginx 容器。

### ✅ 已修复（全栈工程师 2026-09-02 17:00 回填）

- **修复方案（对应建议方向 A+，取最彻底路径）**：commit `c268f38` 整段移除 `vite.config.ts` 的 `manualChunks` 手工分桶，页面级拆分保留 React.lazy 动态 import，共享依赖交 Rollup 自动分配（与 f45ca7e v0.1.0 默认打包一致——当时浏览器运行正常）。**不留任何手工桶**，根除跨 chunk React 互操作问题，而非仅在 rc-* 桶间挪移。
- **验证证据（全部实测）**：
  1. `vite build` 27.96s 成功，产物 19 个 chunk，**无 rc-components/framework/vendor 手工桶残留**（grep=0）；
  2. Edge headless + `--virtual-time-budget=8000` 打开新产物：root 渲染完整登录页（FINFLOW 品牌 + 登录表单 + "账号由管理员开通"），console 无 `reading 'version'`；
  3. 部署包内 web-dist 本体单独起服务实测同样渲染正常（DOM 205,471B 非空）；
  4. 构建绿 ≠ 运行绿已封堵：本次修复以浏览器实测首屏为验收，不再只信 vite build 成功。
- **新产物交付**：`tmp/finflow-deploy-20260902.tar.gz`（43,373,578B，16:57 重打）+ **目录级 MD5 清单** `tmp/finflow-deploy-20260902/web-dist-MD5.txt`（21 文件）。jar 未变（36fc54d6…，16:43:33）——本次为纯前端修复，**部署侧仅需替换 `/opt/finflow/web-dist` 静态资源 + 重建 nginx 容器，无需重启 app 容器**。
- **部署侧验收建议**：按上方"验收标准"公网复核；替换前备份当前 web-dist。

---
<!-- 后续缺陷继续追加：FIX-002 ... -->

## FIX-002（P2 · 技术债登记，非阻塞）2026-09-03 登记（来源：独立架构审阅 docs/architecture-review-2026-09-03.md）

> 非线上缺陷，为审阅提出的长期质量项，交全栈工程师对话排期处理。当日已同步完成的遗留项：
> P0 收尾（新增 `CmbRealPathTenantIsolationIntegrationTest`，以 FakeCmbServer 全链路覆盖真实直联路径的租户隔离）
> 与 P1-1（citic-sdk profile 由 activeByDefault 改为按 SDK jar 文件存在自动激活）不在此列——已完成并入库。

### P2-1 · CI release-contract grep 断言易碎
- **位置**：`.github/workflows/ci.yml` release-contract job，bankdata 目录 `git grep -E '(HttpClient|RestTemplate|WebClient|java\.net\.http|https?://)'` 守卫 + 机密扫描。
- **现象**：守卫靠"源码/注释无 scheme URL"等文本断言表达，35 条断言对重构极其敏感——改一行 javadoc 即 CI 红（2026-09-03 已实际踩中：CmbAdapterProperties javadoc 带 URL 导致 36618b5 才修绿）。
- **方向**：把"禁止硬编码银行网关 URL"转化为真实单测（扫描 class 常量/配置绑定而非源码文本），或建立显式豁免清单，降低误伤率。

### P2-2 · 覆盖率盲区
- **位置**：jacoco 报告——`operations` 包约 5.6%、`kingdee` 包约 8.8%。
- **现象**：两包为连接配置/金蝶凭证推送链路，主流程测试几乎为空；当前 CI 不设覆盖率门槛，盲区会随功能叠加持续扩大。
- **方向**：优先给 `ConnectionOperationsService`（overview/configuration/dataCapability 投影，已在 2026-09-03 语义改造中承担真实状态判定）补契约测试；kingdee 按推送状态机补路径测试。

### P2-3 · 前端主 chunk 偏大
- **位置**：`vite build` 输出主 chunk 约 795.70 kB（>500 kB 警告线）。
- **现象**：FIX-001 移除 manualChunks 后运行时稳定性恢复，但代价是主包体积集中；React.lazy 路由级拆分已缓解，首屏仍一次性加载 antd 主体。
- **方向**：评估 antd 按需引入 / babel-plugin-import、路由级预取策略；改动后必须浏览器实测首屏（沿用 FIX-001 验收标准），避免回归 chunk 互操作问题。

---

### ✅ FIX-002 清偿记录（全栈工程师 2026-09-04 回填）

**P2-1 · CI grep 断言易碎 → 已加固**：bankdata URL 守卫的豁免从隐式 `-vE` 正则提升为显式 `url_allowlist` 变量（XML 解析器加固 URI 白名单），报错信息从一句 echo 改为指路式（说明网关地址应登记在 docs/ 而非源码/注释）。bankdata 源码 URL 字面量已只剩 XML 安全 URI 3 处。

**P2-2 · 覆盖率盲区 → 已补 `ConnectionOperationsServiceTest`（Mockito 纯单测 8 用例）**：覆盖装配推导（REAL 装配列表→connectedBanks 文案动态化）、资源门（资源不存在→BusinessException 404）、logs 投影（状态/请求号筛选、分页、空连接）、**并抓出真实生产缺陷**：`logs()` 中 `.eq(condition, normalize(status))` 因 Java 先求值参数导致 `normalize(null)` NPE——UI 不传状态筛选即 500。已修复（`normalize` 移入条件分支前判空）。kingdee 包测试仍未覆盖（后续批次）。

**P2-3 · 主 chunk 798→578 kB（gzip 203→192 kB）**：Shell / Login / Forbidden 三者 React.lazy 化（Login、Forbidden 从 `auth/pages.tsx` 拆为独立文件，避免同文件守卫组件拖累拆分）。无 manualChunks（FIX-001 红线），纯页面级 lazy。Edge headless 实测首屏登录页完整渲染（FINFLOW 品牌/表单/文案全在 DOM，无 Uncaught）。剩余 578 kB 为 react/antd 基座，继续压缩只能上 manualChunks——已被 FIX-001 教训否决，警告线调至 600 kB 并在 vite.config.ts 注释说明理由。

---

## FIX-003（P2 · 报文证据链与接口补全，非阻塞）2026-09-04 登记（来源：招行云直联文档对照审查）

> 对照 openbiz.cmbchina.com 三份接口文档（NTQADINF 余额 / trsQryByBreakPoint 流水 / NTQABINF 历史余额）与线上 raw 数据发现。当日已顺手完成的部分不在登记范围（NTQADINF 漏解析的 stscod/opndat/inttyp/dpstxt 四个 Y 必返字段已随 V21 补齐）。

### P2-4 · raw-message 报文体存的不是银行原始响应
- **位置**：`BankRawMessage.payload` —— 当前存的是 FINFLOW 入库后的 `BankDataCollection.toJson()`（约 300B），不是招行解密后的原始响应 body（几 KB 级）。
- **现象**：用户在「原始报文」/行级报文抽屉看到的不是"银行真的回答了什么"，而是"我们解析后保留了哪些字段"。适配器丢字段（如 V21 前的 stscod）raw 里也丢——报文证据链与解析正确性耦合，违背该模块"报文体才能证明银行真的回答了"的自身定位。
- **方向**：DB 加列 `raw_response_body`（或复用 payload 双视图：原始 + 入库后），适配器解密后先落原始 body 再投影；需评估体积增长与保留策略联动、敏感字段脱敏范围（现在 sanitize 只在日志层）。涉及迁移 + 双写 + 隐私评估，故排 P2。

### P2-5 · NTQABINF 历史余额接口未实现
- **位置**：招行云直联 7 号接口 NTQABINF（6 响应字段：accnbr/accnam/onlblv/avlblv/dat/hour 之类按文档），FINFLOW 未实现。
- **现象**：余额页只有"当下快照"（NTQADINF），查不到历史某日的余额；对账场景如需 T-1/T-N 余额只能靠流水倒推。
- **方向**：非 v0.2 必需（测试用户申请表未开通）。若 M2/M3 阶段对账需要历史余额锚点，再按文档实现（接口简单：单账户 + 日期 → 一行余额）。


