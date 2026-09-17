-- V33（2026-09-17）：权限基线再规划 + 凭证草稿结构化建议列
--
-- 背景（用户 2026-09-17 报障）：
--   1. 财务经理看不到「凭证草稿与制证」、流水查询无 AI 制证按钮——根因是 voucher:push(13)
--      在 V2 基线只授予 ADMIN+FINANCE_STAFF。制证链路收敛后（2026-09-16 三页下线），
--      经理是复核与推送的实际负责人，必须持权。
--   2. 超管无法可视化调整内置角色——由代码层开放（RbacService 仅锁 ADMIN），本迁移负责
--      把三个业务角色的默认基线修到合理值，避免新部署再踩同样的问题。
--
-- 角色基线调整（FINANCE_MANAGER = role_id 3）：
--   + voucher:push(13)       制证与推送（凭证草稿与制证页 + AI 制证入口）
--   + bankdata:raw:view(39)  原始报文（财务证据链，经理审阅需要全量证据）
--   + system:dict:manage(41) 字典中心（公司主体/科目字典维护是财务职责）
--   FINANCE_STAFF / VIEWER 基线不动；AI 网关配置(ai:config)维持仅 ADMIN（V32 口径）。
--
-- 凭证草稿详情（用户需求：点击 AI 制证草稿看到金蝶式单据，各科目 AI 预填+置信度，人工复核+改）：
--   statement_record 增加 ai_suggestion_json，保存 AI 结构化建议（分录数组+逐行置信度），
--   人工修正回写同列（edited 标记），推送前人工摘要修正同步 statement.summary（金蝶
--   FREMARK/FCOMMENT 取自该字段，KingdeeBillPayloadBuilder 已实证）。

ALTER TABLE statement_record ADD COLUMN ai_suggestion_json VARCHAR(4000) NULL;

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (3, 13),
    (3, 39),
    (3, 41);
