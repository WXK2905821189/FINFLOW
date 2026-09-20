# FINFLOW AI 二期部署验收报告（2026-09-14）

**范围**：V28 AI 在线设置（AI 设置页）+ A1 智能入账建议，代码版本 `3ce5db7`（CI run 34795317896 五 job 全绿）
**目标**：ECS 101.200.72.87 /opt/finflow（Docker：finflow-app + finflow-web）

## 一、部署验证结果（全部通过）

| 验收项 | 结果 | 证据 |
|---|---|---|
| jar 完整性 | ✅ | 本地/上传/落位三方 MD5 一致 `984b511e71087b5084c5e1fdff7819cb` |
| 前端资产 | ✅ | dist MD5 `dc5b7d75...`；解压后 `AiSettingsPage-7a_sPO5r.js` chunk 在场 |
| 容器健康 | ✅ | `finflow-app Up (healthy)`，t+20s 转健康，Tomcat 9.7s 启动完成 |
| 数据库迁移 | ✅ | Flyway **v25 → v28** 三连迁移成功（V26 字典中心 / V27 AI 网关 / V28 AI 配置表） |
| 状态资产 | ✅ | citic-cert 宿主目录与容器内均确认在位（cert.properties） |
| 行为探测 | ✅ | `/` 200；`/api/ai/status` 经 nginx 401（端点存在+认证链生效）；`/api/statements` 401 |
| 端口安全 | ✅ | 8080 未对宿主暴露（仅 nginx 80 代理），符合设计 |

> 说明：ECS 库此前停在 v25，本次一并补上 V26/V27/V28 三条迁移，全部成功无失败。

## 二、本次上线内容

1. **AI 设置页**（系统管理 → AI 设置，权限 `ai:config` 仅 role1/2）
   - 页面直接配置 baseUrl / API Key / 模型 / 超时 / 重试 / 日限频 / 能力开关，**保存即生效**（V28：DB 配置逐字段覆盖 env，无需改环境变量、无需重启）
   - API Key AES-256-GCM 加密落库，只写不读，回显仅尾 4 位（`****-xxxx`）；「测试连接」用表单当前值（未保存也可测）
2. **A1 智能入账建议**（流水复核弹窗 → 「AI 入账建议」按钮，权限 `ai:use`）
   - 脱敏上下文出域（不含对手方账号/原始报文/公司主体）；AI 只建议不执行，采纳需人工确认
   - 全链路审计（ai_call_log 只存哈希+摘要，永不落明文）

## 三、启用 AI 功能（三步，页面上完成）

1. 系统管理 → **AI 设置**：填入 baseUrl（如 `https://api.deepseek.com`）、API Key、模型名 → **保存并生效**
2. 打开能力开关（`accounting-suggestion` 入账建议 / `self-test` 自检）
3. 点 **测试连接** 自检通过即可使用

> 默认全关（fail-closed）；compose 已加 AI_* env 透传行但默认 false，DB 配置优先。

## 四、回滚方案（如需）

- 后端：`/opt/finflow/app.jar.bak-20260914-ai2-pre` → 覆盖 app.jar 后 `docker compose up -d --build app`
- 前端：`/opt/finflow/web-dist.bak-20260914-ai2-pre.tar.gz` → 原地解压回 web-dist/
- 完整部署日志：`/opt/finflow/deploy-ai2.log`
- Flyway V26-V28 已应用不需回退（纯新增表/权限，向前兼容旧 jar）
