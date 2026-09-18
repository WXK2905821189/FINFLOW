package com.finance.system.statement.voucherrule;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.statement.voucherrule.dto.KingdeeRuleGroupResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 金蝶凭证规则 — 规则中心端点（V34 WP-A 只读地基 + V37 W4 维护面）。
 *
 * <p>权限挂 {@code voucher:push}：规则是制证链路的推断依据，凭证草稿/制证确认页需要展示
 * 「命中的规则与分录模板」，因此与制证入口同权（ADMIN/FINANCE_STAFF/FINANCE_MANAGER，
 * V33 基线）。W4（2026-09-18）起规则维护面开放：CRUD/分组/Excel 导入（AI 映射 + 人工审阅）。</p>
 */
@RestController
@RequestMapping("/api")
public class KingdeeVoucherRuleController {

    private final KingdeeVoucherRuleService ruleService;
    private final KingdeeRuleImportService importService;

    public KingdeeVoucherRuleController(KingdeeVoucherRuleService ruleService,
                                        KingdeeRuleImportService importService) {
        this.ruleService = ruleService;
        this.importService = importService;
    }

    /**
     * 规则清单：GET /api/kingdee/voucher-rules?enabledOnly=&groupId=。
     * 响应统一走 {@link ApiResponse} 信封——前端 http 拦截器按 {@code code !== 0} 判定失败。
     */
    @GetMapping("/kingdee/voucher-rules")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<List<KingdeeVoucherRuleResponse>> list(
            @RequestParam(name = "enabledOnly", required = false) Boolean enabledOnly,
            @RequestParam(name = "groupId", required = false) Long groupId) {
        return ApiResponse.success(ruleService.listRules(enabledOnly, groupId));
    }

    @PostMapping("/kingdee/voucher-rules")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<KingdeeVoucherRuleResponse> createRule(
            @RequestBody KingdeeRuleGroupResponse.RuleUpsertRequest request) {
        return ApiResponse.success("规则已创建", ruleService.createRule(request));
    }

    @PutMapping("/kingdee/voucher-rules/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<KingdeeVoucherRuleResponse> updateRule(
            @PathVariable Long id, @RequestBody KingdeeRuleGroupResponse.RuleUpsertRequest request) {
        return ApiResponse.success("规则已更新", ruleService.updateRule(id, request));
    }

    @DeleteMapping("/kingdee/voucher-rules/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        ruleService.deleteRule(id);
        return ApiResponse.success("规则已删除", null);
    }

    // ---------------- 分组 ----------------

    @GetMapping("/kingdee/voucher-rule-groups")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<List<KingdeeRuleGroupResponse>> listGroups() {
        return ApiResponse.success(ruleService.listGroups());
    }

    @PostMapping("/kingdee/voucher-rule-groups")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<KingdeeRuleGroupResponse> createGroup(
            @RequestBody KingdeeRuleGroupResponse.UpsertRequest request) {
        return ApiResponse.success("分组已创建", ruleService.createGroup(request));
    }

    @PutMapping("/kingdee/voucher-rule-groups/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<KingdeeRuleGroupResponse> updateGroup(
            @PathVariable Long id, @RequestBody KingdeeRuleGroupResponse.UpsertRequest request) {
        return ApiResponse.success("分组已更新", ruleService.updateGroup(id, request));
    }

    @DeleteMapping("/kingdee/voucher-rule-groups/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<Void> deleteGroup(@PathVariable Long id) {
        ruleService.deleteGroup(id);
        return ApiResponse.success("分组已删除", null);
    }

    // ---------------- Excel 导入（无状态两步） ----------------

    /** 第一步：上传 xlsx → 解析 + AI 映射 → 预览（不入库；AI 不可用降级人工映射）。 */
    @PostMapping(value = "/kingdee/voucher-rules/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<KingdeeRuleGroupResponse.ImportPreviewResponse> importPreview(
            @RequestPart("file") MultipartFile file,
            @org.springframework.security.core.annotation.AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("解析完成，请核对预览",
                importService.preview(file, principal.getId()));
    }

    /** 第二步：人工勾选/修正后确认入库。 */
    @PostMapping("/kingdee/voucher-rules/import/confirm")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<List<KingdeeVoucherRuleResponse>> importConfirm(
            @RequestBody KingdeeRuleGroupResponse.ImportConfirmRequest request) {
        return ApiResponse.success("规则导入完成", importService.confirm(request));
    }

    /** 模板下载：GET /api/kingdee/voucher-rules/import-template（xlsx）。 */
    @GetMapping("/kingdee/voucher-rules/import-template")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] body = importService.template();
        String filename = java.net.URLEncoder.encode("规则导入模板.xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
