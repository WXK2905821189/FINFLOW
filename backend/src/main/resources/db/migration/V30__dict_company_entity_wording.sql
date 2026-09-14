-- V30: correct the over-promised description of the company_entity dictionary type.
--
-- Background (2026-09-14 user feedback): the dictionary center's seeded "company_entity"
-- (公司主体) description claimed it feeds "module dropdowns and value lookups", but no
-- module consumes sys_dict_item -- the bank data query company dropdown reads the
-- `company` table (maintained on 银行数据 → 账户与主体归档), gated by
-- bankdata:cross-company:view (V24, roles 1/3). Users who maintained companies in the
-- dictionary expected them to appear in bank data filters and saw only the seeded
-- placeholder company instead. Reword the description so the dictionary no longer
-- promises integration it does not have; the authoritative source stays the company
-- table. Data fix for real companies happens on the archive page (business data, not a
-- migration concern).

UPDATE sys_dict_type
SET description = '参考清单：集团内公司主体及其属性（税号、开户行等）。注意：业务模块的公司下拉（如银行数据查询）不读取本字典，主体增删以「银行数据 → 账户与主体归档」为准。'
WHERE type_code = 'company_entity';
