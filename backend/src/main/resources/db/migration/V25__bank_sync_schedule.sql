-- V25: user-configurable scheduled sync plan (2026-09-08 product decision D1=A1)
--
-- Background: the scheduler used to probe every 10 minutes (fixedDelay grid), with the first
-- real pull at startup+7min — uncontrollable and noisy (~144 idempotent probes/day/account).
-- Product decided the admin picks the pull times (bank off-peak windows, e.g. 02:10) and the
-- scheduler fires one real sweep per configured time; idempotent request-ids keep repeats safe.
--
-- Seed row keeps an out-of-the-box off-peak default (02:10) so behavior after deploy is sane
-- even before the admin touches the UI. Times at :00/:30 are rejected at the API layer
-- (CMB concurrency guidance), so no DB constraint enforces that.

CREATE TABLE bank_sync_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    execute_hhmm VARCHAR(5) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_by BIGINT NULL,
    created_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT uk_bank_sync_schedule_hhmm UNIQUE (execute_hhmm)
);

INSERT INTO bank_sync_schedule (execute_hhmm, enabled, created_by, created_at, updated_at)
VALUES ('02:10', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
