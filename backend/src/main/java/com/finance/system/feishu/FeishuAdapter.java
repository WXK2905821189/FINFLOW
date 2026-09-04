package com.finance.system.feishu;

import com.finance.system.domain.entity.FeishuDestination;
import com.finance.system.domain.entity.NotificationEvent;

public interface FeishuAdapter {
    String send(NotificationEvent event, FeishuDestination destination);
}
