package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankSyncSchedule;
import com.finance.system.domain.mapper.BankSyncScheduleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户可配置的定时同步计划（V25 / 方案 D1=A1）。
 *
 * <p>调度语义从「每 10 分钟探测一轮」改为「命中计划时刻才拉一轮」：管理员把执行时刻设在
 * 银行流量低谷期（默认种子 02:10），到点触发一轮全账户 T-1 同步；同一窗口的重复触发由
 * 同步任务的 requestId 幂等兜底，不会重复调银行。</p>
 *
 * <p>时刻护栏：禁选整点/半点（分钟为 00 或 30）——招行账务查询与支付共享 20 并发，
 * 整点半点高峰响应明显变慢，官方建议错峰。</p>
 */
@Service
public class BankSyncScheduleService {

    private static final Logger log = LoggerFactory.getLogger(BankSyncScheduleService.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final BankSyncScheduleMapper mapper;
    private final BankDataScheduledSyncService scheduledSyncService;

    public BankSyncScheduleService(BankSyncScheduleMapper mapper, BankDataScheduledSyncService scheduledSyncService) {
        this.mapper = mapper;
        this.scheduledSyncService = scheduledSyncService;
    }

    public List<BankSyncSchedule> list() {
        return mapper.selectList(new LambdaQueryWrapper<BankSyncSchedule>()
                .orderByAsc(BankSyncSchedule::getExecuteHhmm));
    }

    public BankSyncSchedule create(String executeHhmm, Long operatorId) {
        String normalized = normalize(executeHhmm);
        if (mapper.selectCount(new LambdaQueryWrapper<BankSyncSchedule>()
                .eq(BankSyncSchedule::getExecuteHhmm, normalized)) > 0) {
            throw new BusinessException(409, "该执行时刻已存在");
        }
        BankSyncSchedule schedule = new BankSyncSchedule();
        schedule.setExecuteHhmm(normalized);
        schedule.setEnabled(true);
        schedule.setCreatedBy(operatorId);
        schedule.setCreatedAt(LocalDateTime.now());
        schedule.setUpdatedAt(LocalDateTime.now());
        mapper.insert(schedule);
        return schedule;
    }

    public void updateEnabled(Long id, boolean enabled, Long operatorId) {
        BankSyncSchedule schedule = mapper.selectById(id);
        if (schedule == null) {
            throw new BusinessException(404, "同步计划不存在");
        }
        schedule.setEnabled(enabled);
        schedule.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(schedule);
    }

    public void delete(Long id, Long operatorId) {
        if (mapper.selectById(id) == null) {
            throw new BusinessException(404, "同步计划不存在");
        }
        mapper.deleteById(id);
    }

    /** 心跳入口：当前分钟命中任一启用计划时触发一轮同步。同一分钟内只触发一次。 */
    public void fireIfDue() {
        String now = currentTime().format(HHMM);
        Set<String> due = list().stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .map(BankSyncSchedule::getExecuteHhmm)
                .collect(Collectors.toSet());
        if (due.contains(now)) {
            log.info("bank sync schedule fired at {}", now);
            scheduledSyncService.triggerScheduledSyncs();
        }
    }

    /** 可覆写的时钟：测试注入固定时刻，避免「计划建在当前分钟、触发前翻页」的竞态。 */
    protected LocalDateTime currentTime() {
        return LocalDateTime.now();
    }

    private String normalize(String executeHhmm) {
        if (executeHhmm == null || executeHhmm.isBlank()) {
            throw new BusinessException(400, "执行时刻不能为空");
        }
        String trimmed = executeHhmm.trim();
        String[] parts = trimmed.split(":");
        int hour;
        int minute;
        try {
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new NumberFormatException();
            }
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new BusinessException(400, "执行时刻格式应为 HH:mm，如 02:10");
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new BusinessException(400, "执行时刻超出 00:00-23:59 范围");
        }
        if (minute == 0 || minute == 30) {
            throw new BusinessException(400, "不能选择整点/半点（银行高峰期），请错峰设置，如 02:10");
        }
        return String.format("%02d:%02d", hour, minute);
    }
}
