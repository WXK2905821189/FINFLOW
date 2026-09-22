package com.finance.system.statement.voucherrule;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.MappingResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.MappingUpsertRequest;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.SlotResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.SlotUpsertRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 金蝶核算维度配置端点（V42，2026-09-21）。
 *
 * <p>用户拍板「新建维度映射表，且可以在系统里直接修改，而不是只能通过代码修改」——
 * 因此槽位（弹性域键）与值映射（来源值→档案编码）都开维护面，不再是常量/环境变量。</p>
 *
 * <p>权限同规则中心 {@code voucher:push}（本页是制证链路的配置面，与规则维护面同权）。</p>
 */
@RestController
@RequestMapping("/api")
public class KingdeeDimensionMappingController {

    private final KingdeeDimensionMappingService service;

    public KingdeeDimensionMappingController(KingdeeDimensionMappingService service) {
        this.service = service;
    }

    // ---------------- 槽位配置（维度类型 → 弹性域槽位） ----------------

    @GetMapping("/kingdee/dimension-slots")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<List<SlotResponse>> listSlots(
            @RequestParam(name = "enabledOnly", required = false) Boolean enabledOnly) {
        return ApiResponse.success(service.listSlots(enabledOnly));
    }

    @PostMapping("/kingdee/dimension-slots")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<SlotResponse> createSlot(@RequestBody SlotUpsertRequest request) {
        return ApiResponse.success("槽位配置已创建", service.createSlot(request));
    }

    @PutMapping("/kingdee/dimension-slots/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<SlotResponse> updateSlot(@PathVariable Long id,
                                                @RequestBody SlotUpsertRequest request) {
        return ApiResponse.success("槽位配置已更新", service.updateSlot(id, request));
    }

    @DeleteMapping("/kingdee/dimension-slots/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<Void> deleteSlot(@PathVariable Long id) {
        service.deleteSlot(id);
        return ApiResponse.success("槽位配置已删除", null);
    }

    // ---------------- 值映射（来源值 → 金蝶档案编码） ----------------

    @GetMapping("/kingdee/dimension-mappings")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<List<MappingResponse>> listMappings(
            @RequestParam(name = "dimensionType", required = false) String dimensionType,
            @RequestParam(name = "enabledOnly", required = false) Boolean enabledOnly) {
        return ApiResponse.success(service.listMappings(dimensionType, enabledOnly));
    }

    @PostMapping("/kingdee/dimension-mappings")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<MappingResponse> createMapping(@RequestBody MappingUpsertRequest request) {
        return ApiResponse.success("维度映射已创建", service.createMapping(request));
    }

    @PutMapping("/kingdee/dimension-mappings/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<MappingResponse> updateMapping(@PathVariable Long id,
                                                      @RequestBody MappingUpsertRequest request) {
        return ApiResponse.success("维度映射已更新", service.updateMapping(id, request));
    }

    @DeleteMapping("/kingdee/dimension-mappings/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<Void> deleteMapping(@PathVariable Long id) {
        service.deleteMapping(id);
        return ApiResponse.success("维度映射已删除", null);
    }

    /** 批量导入（前端粘贴/Excel 数百条场景）；同键已存在按更新处理。 */
    @PostMapping("/kingdee/dimension-mappings/batch")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<Integer> batchUpsert(@RequestBody List<MappingUpsertRequest> requests) {
        int affected = service.batchUpsert(requests);
        return ApiResponse.success("已导入 " + affected + " 条维度映射", affected);
    }

    /**
     * 只读同步金蝶基础资料档案目录（2026-09-22 用户授权 ExecuteBillQuery 拉供应商/客户/员工档案）。
     * 返回「档案编码 + 名称 + 文档状态」供前端与既有映射比对补差；不写库。
     */
    @GetMapping("/kingdee/dimension-mappings/base-data-catalog")
    @PreAuthorize("hasAuthority('voucher:push')")
    public ApiResponse<List<com.finance.system.statement.kingdee.KingdeeVoucherGateway.KingdeeBaseDataRef>> syncBaseDataCatalog(
            @RequestParam(name = "dimensionType") String dimensionType) {
        return ApiResponse.success(service.syncBaseDataCatalog(dimensionType));
    }
}
