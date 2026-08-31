package com.finance.system.feishu;

import com.finance.system.domain.entity.FeishuDestination;
import com.finance.system.domain.entity.NotificationEvent;
import org.springframework.stereotype.Component;

/** Deterministic local adapter. It never opens a network connection. */
@Component
public class MockFeishuAdapter implements FeishuAdapter {
    @Override
    public String send(NotificationEvent event, FeishuDestination destination) {
        return "MOCK-FEISHU-" + event.getEventId() + "-" + destination.getId();
    }
}
