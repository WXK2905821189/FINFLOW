-- V23: true bank evidence columns for bank_data_raw_message (ODS layer)
--
-- Until now the raw-message payload stored the post-parse BankDataCollection view
-- (~300 B), not the bank's decrypted response (FIX-003 P2-4). That coupled the
-- evidence chain to parser correctness: any field the adapter dropped was dropped
-- from the evidence too, and no one could re-run today's mapping over yesterday's
-- wire data.
--
-- Two new nullable columns close the gap without touching the existing payload:
--
--   response_payload  the bank's decrypted response, verbatim (CMB JSON / CITIC XML)
--   request_evidence  request-side facts as JSON: endpoint, funcode/action, the
--                     plaintext (pre-encryption) request document, duration, HTTP
--                     status, and any auxiliary exchange (e.g. the page-1 CMB
--                     balance snapshot) with its own request/response
--
-- Both are nullable on purpose: rows captured before this migration keep only the
-- view payload, and adapters that cannot supply an element leave it null. Null
-- means "not captured", never "empty". Key material (sym keys, private keys) is
-- never written to either column.
--
-- Cross-database note: one ALTER per column (H2 2.2.224 rejects the comma form).

ALTER TABLE bank_data_raw_message ADD COLUMN response_payload MEDIUMTEXT NULL COMMENT '银行解密后原始响应报文 verbatim（CMB JSON / CITIC XML）';
ALTER TABLE bank_data_raw_message ADD COLUMN request_evidence MEDIUMTEXT NULL COMMENT '请求要素 JSON：端点/功能码/加密前明文请求/耗时/HTTP 状态/附属交换';
