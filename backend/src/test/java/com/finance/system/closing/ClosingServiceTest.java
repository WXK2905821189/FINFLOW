package com.finance.system.closing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.finance.system.audit.SystemAuditService;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.ClosingPeriod;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.mapper.ClosingPeriodMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.rbac.RbacService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 结账口径的回归测试（纯单测，不起 Spring 上下文）。
 *
 * <p>锁死一条曾真实发生的缺陷：{@code ClosingService.refresh()} 用 {@code "VALID"} 判定流水校验状态，
 * 而流水域的真实字面量是 {@code "PASSED"}（{@code "VALID"} 是 bank_data_* 投影层的写法）。
 * 取错字面量会把账期内每条流水都算成「异常」，账期恒为 BLOCKED，导致「确认结账」永远 409——
 * 该缺陷此前无任何测试覆盖（closing 包原本没有测试）。</p>
 *
 * <p>同时锁定推送状态的双拼写：历史路径写 {@code PUSHED}，规则引擎路径写 {@code GL_PUSHED}，
 * 两者都必须视为「已制证」，否则已成功推送的流水会被反复计入未制证而阻塞结账。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClosingServiceTest {

    private static final long USER_ID = 7L;
    private static final long COMPANY_ID = 1L;

    @Mock private ClosingPeriodMapper periodMapper;
    @Mock private StatementRecordMapper statementMapper;
    @Mock private CompanyScopeService scope;
    @Mock private SystemAuditService audit;
    @Mock private RbacService rbacService;

    private ClosingService service;

    @BeforeEach
    void setUp() {
        service = new ClosingService(periodMapper, statementMapper, scope, audit, rbacService);
        when(scope.companyIdForUser(USER_ID)).thenReturn(COMPANY_ID);
        when(periodMapper.updateById(any(ClosingPeriod.class))).thenReturn(1);
        when(periodMapper.insert(any(ClosingPeriod.class))).thenReturn(1);
        when(periodMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rbacService.rolesForUser(USER_ID)).thenReturn(List.of(role("FINANCE_STAFF")));
        when(rbacService.rolesForUser(ADMIN_ID)).thenReturn(List.of(role("ADMIN")));
    }

    private static final long ADMIN_ID = 9L;

    private static SysRole role(String code) {
        SysRole r = new SysRole();
        r.setCode(code);
        return r;
    }

    /** 让 find() 命中一条已存在的账期，避免走 insert 分支。 */
    private void existingPeriod(String status) {
        ClosingPeriod p = new ClosingPeriod();
        p.setId(8001L);
        p.setCompanyId(COMPANY_ID);
        p.setPeriod("2026-09");
        p.setStatus(status);
        p.setCreatedAt(LocalDateTime.now());
        when(periodMapper.selectOne(any(Wrapper.class))).thenReturn(p);
    }

    private static StatementRecord row(String validation, String review, String push) {
        StatementRecord r = new StatementRecord();
        r.setCompanyId(COMPANY_ID);
        r.setTransactionTime(LocalDateTime.of(2026, 9, 10, 10, 0));
        r.setValidationStatus(validation);
        r.setReviewStatus(review);
        r.setPushStatus(push);
        return r;
    }

    private void statements(StatementRecord... rows) {
        when(statementMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("全部 PASSED + APPROVED + GL_PUSHED → 账期 READY、异常 0、未制证 0（回归：曾因用 VALID 比对而恒为 BLOCKED）")
    void readyWhenAllStatementsValidatedAndPushedViaRuleEngine() {
        existingPeriod("BLOCKED");
        statements(
                row("PASSED", "APPROVED", "GL_PUSHED"),
                row("PASSED", "APPROVED", "PUSHED"));

        var result = service.check(USER_ID, "2026-09", "req-1");

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.exceptionCount()).isZero();
        assertThat(result.unpostedCount()).isZero();
        assertThat(result.totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("FAILED 只计 1 条异常，不再把 PASSED 行也计入异常")
    void onlyFailedRowCountsAsException() {
        existingPeriod("BLOCKED");
        statements(
                row("PASSED", "APPROVED", "GL_PUSHED"),
                row("FAILED", "APPROVED", "GL_FAILED"));

        var result = service.check(USER_ID, "2026-09", "req-2");

        assertThat(result.exceptionCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("已复核通过但未推送（pushStatus 为空）→ 计入未制证并阻塞结账")
    void approvedButNotPushedBlocksClosing() {
        existingPeriod("BLOCKED");
        statements(row("PASSED", "APPROVED", null));

        var result = service.check(USER_ID, "2026-09", "req-3");

        assertThat(result.unpostedCount()).isEqualTo(1);
        assertThat(result.exceptionCount()).isZero();
        assertThat(result.status()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("待复核流水计入 pending 并阻塞结账")
    void pendingReviewBlocksClosing() {
        existingPeriod("BLOCKED");
        statements(row("PASSED", "PENDING", null));

        var result = service.check(USER_ID, "2026-09", "req-4");

        assertThat(result.pendingCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("BLOCKED 账期调用结账 → 409 且不改状态")
    void closeRejectsBlockedPeriod() {
        existingPeriod("BLOCKED");
        statements(row("FAILED", "APPROVED", "GL_FAILED"));

        assertThatThrownBy(() -> service.close(USER_ID, "2026-09", "req-5"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能结账");
    }

    @Test
    @DisplayName("READY 账期调用结账 → 状态置 CLOSED 并记确认人")
    void closeMarksReadyPeriodClosed() {
        existingPeriod("READY");
        statements(row("PASSED", "APPROVED", "GL_PUSHED"));

        var result = service.close(USER_ID, "2026-09", "req-6");

        assertThat(result.status()).isEqualTo("CLOSED");
        assertThat(result.confirmedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("空账期 → READY（0 条流水不算异常）")
    void emptyPeriodIsReady() {
        existingPeriod("BLOCKED");
        statements();

        var result = service.check(USER_ID, "2026-09", "req-7");

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.totalCount()).isZero();
    }

    // ===== W7（2026-09-18）：解锁与账期锁拦截 =====

    @Test
    @DisplayName("W7 unlock：非 ADMIN（FINANCE_STAFF）→ 403，账期状态不被改动")
    void unlockRejectsNonAdmin() {
        existingPeriod("CLOSED");

        assertThatThrownBy(() -> service.unlock(USER_ID, "2026-09", "req-u1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅超级管理员");
    }

    @Test
    @DisplayName("W7 unlock：ADMIN 解锁 CLOSED 账期 → READY 并写审计")
    void unlockByAdminMovesClosedToReady() {
        existingPeriod("CLOSED");

        var result = service.unlock(ADMIN_ID, "2026-09", "req-u2");

        assertThat(result.status()).isEqualTo("READY");
    }

    @Test
    @DisplayName("W7 unlock：账期不存在 → 404（提示先做账期检查）")
    void unlockRejectsUnknownPeriod() {
        when(periodMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.unlock(ADMIN_ID, "2026-08", "req-u3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账期不存在");
    }

    @Test
    @DisplayName("W7 unlock：非 CLOSED（READY/BLOCKED）→ 409")
    void unlockRejectsNonClosedStatus() {
        existingPeriod("READY");

        assertThatThrownBy(() -> service.unlock(ADMIN_ID, "2026-09", "req-u4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅已结账");
    }

    @Test
    @DisplayName("W7 账期锁：CLOSED 账期 → ensurePeriodOpen 抛 409 提示解锁")
    void ensurePeriodOpenRejectsClosed() {
        existingPeriod("CLOSED");

        assertThatThrownBy(() -> service.ensurePeriodOpen(COMPANY_ID, "2026-09"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已结账");
    }

    @Test
    @DisplayName("W7 账期锁：无账期记录或 READY → 放行（不拦截）")
    void ensurePeriodOpenAllowsOpenPeriods() {
        when(periodMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatCode(() -> service.ensurePeriodOpen(COMPANY_ID, "2026-09")).doesNotThrowAnyException();

        existingPeriod("READY");
        assertThatCode(() -> service.ensurePeriodOpen(COMPANY_ID, LocalDateTime.of(2026, 9, 15, 8, 0)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("W7 账期锁（批量）：任一涉及月份 CLOSED → 409 且一次报清全部命中月份")
    void ensurePeriodsOpenRejectsAnyClosedMonth() {
        ClosingPeriod closed = new ClosingPeriod();
        closed.setCompanyId(COMPANY_ID);
        closed.setPeriod("2026-09");
        closed.setStatus("CLOSED");
        when(periodMapper.selectList(any(Wrapper.class))).thenReturn(List.of(closed));

        assertThatThrownBy(() -> service.ensurePeriodsOpen(COMPANY_ID, java.util.Arrays.asList(
                LocalDateTime.of(2026, 9, 10, 10, 0),
                LocalDateTime.of(2026, 8, 1, 10, 0),
                null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2026-09");
    }
}
