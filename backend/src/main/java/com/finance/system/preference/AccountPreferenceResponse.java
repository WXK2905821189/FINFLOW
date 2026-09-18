package com.finance.system.preference;

/**
 * 账号级界面偏好的读写载荷（V35）。
 *
 * <p>{@code payload} 为前端不透明 JSON 快照；{@code updatedAt} 为 null 表示该 scope 还没有保存过，
 * 前端据此回落到内置默认视图（而不是把「空对象」当成用户选择）。</p>
 */
public record AccountPreferenceResponse(String scopeKey, String payload, java.time.LocalDateTime updatedAt) {

    /** 尚未保存过该 scope 偏好时的应答（200 + data，而不是 404：未保存是正常状态，不是错误）。 */
    public static AccountPreferenceResponse absent(String scopeKey) {
        return new AccountPreferenceResponse(scopeKey, null, null);
    }
}
