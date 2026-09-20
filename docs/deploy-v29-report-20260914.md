# FINFLOW V29 部署验收报告（2026-09-14）

**版本**：`6a76bc6`（V29 飞书连接向导 + AI 设置增强 + 导航图标补齐）
**CI**：run 34812179144 五 job 全绿 · **部署目标**：ECS 101.200.72.87

## 一、本次交付内容

| # | 需求 | 落地 |
|---|------|------|
| 1 | 无图标模块补图标 | 余额查询/流水查询/对账核对/运行日志/原始报文/账期结账/三方对账 7 处导航图标补齐 |
| 2 | 飞书配置连接向导 | 四步引导（创建应用→开机器人→拿凭证→填入验证）+「AI 代办」可复制提示词 + 真实凭证验证（V29 `feishu_app_config` 表，Secret AES-256-GCM 只写不读，真实调飞书 `tenant_access_token/internal`） |
| 3 | AI 设置模型拉取 | baseUrl+密钥就绪后可自动/手动拉取 OpenAI 兼容模型列表（`POST /api/ai/config/models`），模型改为可搜索可自由输入 |
| 3b | 取消日限频 | 日限频全链移除（guard 429 检查、配置项、表单、状态页），不再限次 |

## 二、部署验收

| 验收项 | 结果 |
|---|---|
| jar 完整性 | ✅ 本地构建 MD5 `fb143d55` = 上传 = ECS 落位，85 lib 含中信（dlink/isec/cfca）+ 金蝶 SDK |
| 前端资产 | ✅ 62 assets 原地刷新，index 引用新 chunk（CI dist 构件） |
| 容器健康 | ✅ healthy at t+18s |
| 数据库迁移 | ✅ Flyway **now at version v29**（feishu_app_config 落库） |
| 行为探测 | ✅ `/api/ai/status`=401、`/api/feishu/app-config`=401（新端点上线且受保护）、statements=401、root=200 |
| 状态资产 | ✅ citic-cert 在位 |

## 三、回滚弹药

- `/opt/finflow/app.jar.bak-20260914-v29-pre`
- `/opt/finflow/web-dist.bak-20260914-v29-pre.tar.gz`
- 部署日志：`/opt/finflow/deploy-v29.log`

## 四、飞书连接使用指引（上线后的用户视角）

1. **飞书协同页 → 连接真实飞书卡片**：按四步提示在 open.feishu.cn 创建企业自建应用并开启机器人能力；
2. 不想手动操作 → 复制卡片里的 **AI 代办提示词** 交给 AI 助手逐步代办；
3. 拿到 App ID / App Secret 后填入表单 → **保存并验证**（服务端真实调飞书接口，通过后显示租户名）；
4. Secret 加密存储、明文永不回显（仅尾 4 位提示）；留空 = 保持不变。

## 五、后续注意

- 本机前端 node_modules 已不可修复，前端验证/产物一律走 CI 构件（已写入部署铁律）；
- CI frontend job 现上传 `finflow-web-dist` 构件（保留 7 天），ECS 部署直接取用；
- AI 能力仍需在 AI 设置页配置 baseUrl/API Key/模型后开启能力开关才可用。
