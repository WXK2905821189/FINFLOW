package com.finance.system.statement.voucherrule;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEngineRequests;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 金蝶凭证规则引擎端点（V34 WP-B）：预览解析 + 确认推送。
 *
 * <p>权限挂 {@code voucher:push}（与 AI 制证/凭证草稿链路同权——规则制证是制证体系的
 * 规则驱动路径，操作角色一致）。推送落 GL_VOUCHER 草稿，凭证号回写
 * statement_record.voucher_no；不自动提交/审核。</p>
 */
@RestController
@RequestMapping("/api")
public class KingdeeVoucherEngineController {

    private final KingdeeVoucherEngineService engineService;

    public KingdeeVoucherEngineController(KingdeeVoucherEngineService engineService) {
        this.engineService = engineService;
    }

    /** 批量预览：流水 → 规则解析结果（AUTO_FILL/CANDIDATES/UNMATCHED/NOT_ELIGIBLE）。 */
    @PostMapping("/kingdee/voucher-rule/preview")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ResponseEntity<ApiResponse<List<KingdeeVoucherRulePreview>>> preview(
            @jakarta.validation.Valid @RequestBody KingdeeVoucherEngineRequests.PreviewRequest request) {
        List<Long> ids = request.statementIds() == null ? List.of() : request.statementIds();
        return ResponseEntity.ok(ApiResponse.success("规则解析完成",
                engineService.preview(ids)));
    }

    /** 确认推送：按选定规则生成 GL_VOUCHER 草稿并保存（MANUAL 行金额由确认页提供）。 */
    @PostMapping("/kingdee/voucher-rule/push")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ResponseEntity<ApiResponse<KingdeeVoucherEngineService.KingdeeVoucherPushResult>> push(
            @jakarta.validation.Valid @RequestBody KingdeeVoucherEngineRequests.PushRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        KingdeeVoucherEngineService.KingdeeVoucherPushResult result = engineService.push(
                request.statementId(), request.ruleNo(), request.manualAmounts(), principal.getId());
        return ResponseEntity.ok(ApiResponse.success("凭证草稿推送完成", result));
    }
}
