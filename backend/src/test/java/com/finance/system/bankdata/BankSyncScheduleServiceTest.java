package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankSyncSchedule;
import com.finance.system.domain.mapper.BankSyncScheduleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时同步计划（V25 / D1=A1）的 CRUD 护栏：禁整点/半点（CMB 高峰错峰）、格式校验、查重。
 * 心跳命中触发的端到端用例在 {@link BankDataScheduledScanIntegrationTest}（需 REAL 适配器装配）。
 */
@SpringBootTest
@ActiveProfiles("dev")
class BankSyncScheduleServiceTest {

    @Autowired
    private BankSyncScheduleService scheduleService;
    @Autowired
    private BankSyncScheduleMapper scheduleMapper;

    @Test
    void createRejectsOnTheHourAndHalfHourAndBadFormat() {
        // 02:10 是 V25 种子行（默认低谷期），这里用 02:15 避开种子验证创建路径。
        assertEquals("02:15", scheduleService.create("02:15", 1L).getExecuteHhmm());
        assertEquals("09:05", scheduleService.create("9:05", 1L).getExecuteHhmm(), "个位小时归一为 HH:mm");
        BusinessException onHour = assertThrows(BusinessException.class, () -> scheduleService.create("03:00", 1L));
        assertEquals(400, onHour.getCode());
        BusinessException onHalf = assertThrows(BusinessException.class, () -> scheduleService.create("03:30", 1L));
        assertEquals(400, onHalf.getCode());
        BusinessException badFormat = assertThrows(BusinessException.class, () -> scheduleService.create("2点10", 1L));
        assertEquals(400, badFormat.getCode());
        BusinessException duplicate = assertThrows(BusinessException.class, () -> scheduleService.create("02:15", 1L));
        assertEquals(409, duplicate.getCode());
    }

    @Test
    void updateEnabledAndDeleteWork() {
        BankSyncSchedule created = scheduleService.create("04:15", 1L);
        scheduleService.updateEnabled(created.getId(), false, 1L);
        assertEquals(false, scheduleMapper.selectById(created.getId()).getEnabled());
        scheduleService.delete(created.getId(), 1L);
        assertEquals(0, scheduleMapper.selectCount(new LambdaQueryWrapper<BankSyncSchedule>()
                .eq(BankSyncSchedule::getId, created.getId())));
        BusinessException missing = assertThrows(BusinessException.class, () -> scheduleService.delete(created.getId(), 1L));
        assertEquals(404, missing.getCode());
    }

    @Test
    void fireIfDueSilentWhenEverythingDisabled() {
        // 空集路径：全部停用时心跳不应抛错。
        scheduleService.list().forEach(s -> scheduleService.updateEnabled(s.getId(), false, 1L));
        scheduleService.fireIfDue();
        assertTrue(scheduleService.list().stream().noneMatch(s -> Boolean.TRUE.equals(s.getEnabled()))
                || scheduleService.list().isEmpty());
    }
}
