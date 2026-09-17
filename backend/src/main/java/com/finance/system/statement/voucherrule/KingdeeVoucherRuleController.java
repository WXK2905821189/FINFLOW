package com.finance.system.statement.voucherrule;

import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 金蝶凭证规则 — 只读查询端点（V34 WP-A 地基）。
 *
 * <p>权限挂 {@code voucher:push}：规则是制证链路的推断依据，凭证草稿/制证确认页需要展示
 * 「命中的规则与分录模板」，因此与制证入口同权（ADMIN/FINANCE_STAFF/FINANCE_MANAGER，
 * V33 基线）。规则维护（增删改/启停）一期不做 UI——种子经迁移追加，财务补充规则走新迁移。</p>
 */
@RestController
@RequestMapping("/api")
public class KingdeeVoucherRuleController {

    private final KingdeeVoucherRuleService ruleService;

    public KingdeeVoucherRuleController(KingdeeVoucherRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * 规则清单（可按启用状态过滤）：GET /api/kingdee/voucher-rules?enabledOnly=true。
     * enabledOnly 缺省返回全部（含停用），前端按 priority 升序展示。
     */
    @GetMapping("/kingdee/voucher-rules")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ResponseEntity<List<KingdeeVoucherRuleResponse>> list(
            @RequestParam(name = "enabledOnly", required = false) Boolean enabledOnly) {
        return ResponseEntity.ok(ruleService.listRules(enabledOnly));
    }
}
