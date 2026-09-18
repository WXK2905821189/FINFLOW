package com.finance.system.preference;

import jakarta.validation.constraints.NotBlank;

/** 保存偏好的请求体：只有一个不透明 JSON 字符串。 */
public record AccountPreferenceRequest(@NotBlank(message = "payload 不能为空") String payload) {
}
