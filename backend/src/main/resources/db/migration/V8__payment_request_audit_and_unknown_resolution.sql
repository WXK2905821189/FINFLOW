ALTER TABLE payment_transfer ADD COLUMN request_id VARCHAR(64);
UPDATE payment_transfer SET request_id = LEFT(idempotency_key, 64) WHERE request_id IS NULL;
ALTER TABLE payment_transfer MODIFY request_id VARCHAR(64) NOT NULL;
CREATE INDEX idx_payment_transfer_request ON payment_transfer(company_id, request_id, created_at);

ALTER TABLE payment_transfer ADD COLUMN resolved_by BIGINT;
ALTER TABLE payment_transfer ADD COLUMN resolved_at TIMESTAMP;
ALTER TABLE payment_transfer ADD COLUMN resolution_comment VARCHAR(500);
ALTER TABLE payment_transfer ADD CONSTRAINT fk_payment_resolver FOREIGN KEY (resolved_by) REFERENCES sys_user(id);

ALTER TABLE payment_transfer_audit_event ADD COLUMN request_id VARCHAR(64);
UPDATE payment_transfer_audit_event audit
SET request_id = (SELECT payment.request_id FROM payment_transfer payment WHERE payment.id = audit.payment_id)
WHERE request_id IS NULL;
ALTER TABLE payment_transfer_audit_event MODIFY request_id VARCHAR(64) NOT NULL;
CREATE INDEX idx_payment_audit_request ON payment_transfer_audit_event(company_id, request_id, created_at);
