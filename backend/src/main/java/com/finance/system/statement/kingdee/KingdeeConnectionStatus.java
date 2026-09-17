package com.finance.system.statement.kingdee;

/**
 * Result of a read-only Kingdee connectivity probe (GET /api/statements/kingdee/ping).
 *
 * @param connected true only when the active gateway verified a live round-trip
 *                  against the Kingdee server (Real mode) or reports a live link.
 * @param mode      active gateway mode: MOCK / UNAVAILABLE / REAL.
 * @param message   human-readable evidence or the failure reason (Chinese, UI-facing).
 */
public record KingdeeConnectionStatus(boolean connected, String mode, String message) {
}
