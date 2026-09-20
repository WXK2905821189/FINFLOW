# FINFLOW W8 部署报告（2026-09-20）

## 部署内容

**提交 `1b11f8e`（W8：用户反馈五项修复）**，CI run `35482338452` 五 job 全绿。

| # | 反馈 | 修复 |
|---|------|------|
| 1 | 银行字段无法筛选 / 复制选区不进粘贴板 / 余额旁要复制标签 / 不要本页小计 | 列键错位修复（decorate）；copyText 降级（http 可用）；余额/联机余额 copyChip；删状态栏小计 + 服务端全量合计 |
| 2 | 全部主体无法多选 | 顶栏多选 chips + `companyIds` 多值参数 + 主体树多选双向联动 |
| 3 | 超管要能编辑用户名/邮箱 | 前端 disabled 放开（后端本就支持，user:manage 均可改） |
| 4 | 驳回后不能重新制证 / 推送失败怎么办 | reopen 端点（REJECTED→PENDING，审计）；状态签补 GL_FAILED；失败行「重试推送」+ 批量推送 |
| 5 | Excel 导入模板做好点 | 说明横幅+样式+冻结+方向下拉校验+「填写说明」sheet；preview 表头识别兼容 |

## 物料与执行

| 项 | 值 |
|---|---|
| jar | 隔离构建（`git archive` → `/c/Users/Public/finflow-w8`），MD5 `22a309b9d5b57efadf6f45a7353ef51e`，102MB，95 lib（84+POI 系 11） |
| dist | CI 构件 `finflow-web-dist`（run 35482338452），65 assets，入口 `index-QsnRc-RV.js`（与本地 vite build 哈希一致），包 MD5 `e1874caedfd385d80fbae7c41fe299bd` |
| 执行 | 备份（`app.jar.bak-20260920-w8-pre` + `web-dist.bak-20260920-w8-pre.tar.gz`）→ 换 jar → 清旧 chunk 62 个 → 原地解压（65=干净态）→ `up -d --build app` → t+20s healthy |
| compose/.env | 零改动（W8 无新增环境变量） |

## 验收（三层门禁全绿）

1. **门禁①**：ECS 本机 `curl 127.0.0.1` 首页引用 `index-QsnRc-RV.js` ✅；迁移日志 `Successfully validated 36 migrations` ✅；`Started FinanceSystemApplication in 9.8s` ✅
2. **门禁②**：公网 `root=200` / `health=401`（未带 token 预期）/ `assets/index-QsnRc-RV.js=200` ✅
3. **门禁③**：Edge headless 渲染公网首页 206KB DOM 非空，登录页可交互元素渲染 ✅

容器内取证（python3 zipfile，容器/宿主均无 unzip——新坑已记录）：W8 关键 class（BankDataQueryService/StatementController/KingdeeRuleImportService）+ V37 迁移 SQL 均在包内；jar MD5 与本地构建三方一致即内容等价。

## 回滚

```bash
cd /opt/finflow
cp -f app.jar.bak-20260920-w8-pre app.jar
rm -rf web-dist && mkdir web-dist && tar -xzf web-dist.bak-20260920-w8-pre.tar.gz -C web-dist
docker compose up -d --build app
```

## 遗留提示

- **导出语义**：多选主体时导出按钮置灰（CSV 镜像银行单文件无公司列，混导破坏对账锚点）；单主体照常。
- **AI 制证兼容**：SKIPPED_REJECTED 的行在 reopen 后可直接重新制证。
- **验证建议**：用真实账号实测——①余额页银行列值筛选；②表格复制选区（http 下应真正进粘贴板）；③顶栏勾选 2+ 主体看余额/流水数据变化；④凭证中心驳回行「重新打开」；⑤用户管理改用户名；⑥规则中心下载新模板。
