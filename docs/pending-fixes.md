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
