package com.finance.system.dict;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.dict.dto.DictItemResponse;
import com.finance.system.dict.dto.DictItemUpsertRequest;
import com.finance.system.dict.dto.DictTypeResponse;
import com.finance.system.dict.dto.DictTypeUpsertRequest;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典中心（V26，系统管理 → 字典中心）。
 *
 * <p>管理端点统一 {@code system:dict:manage}（V16 起的惯例：管理面单权限收敛）。
 * 消费方读取端点 {@code GET /api/system/dicts/{typeCode}/items} 只要求登录态——
 * 字典值本身不是敏感数据，各模块取数下拉/展示直接调用。</p>
 */
@RestController
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    // ---- 管理端点 ----

    @GetMapping("/api/system/dicts/types")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "List dictionary types with item counts")
    public ApiResponse<List<DictTypeResponse>> listTypes() {
        return ApiResponse.success(dictService.listTypes());
    }

    @PostMapping("/api/system/dicts/types")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "Create a dictionary type (typeCode immutable after creation)")
    public ApiResponse<DictTypeResponse> createType(@Valid @RequestBody DictTypeUpsertRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("字典类型已创建",
                dictService.createType(request, principal.getId()));
    }

    @PutMapping("/api/system/dicts/types/{id}")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "Update a dictionary type (name/description/status only)")
    public ApiResponse<DictTypeResponse> updateType(@PathVariable Long id,
                                                    @Valid @RequestBody DictTypeUpsertRequest request) {
        return ApiResponse.success("字典类型已更新", dictService.updateType(id, request));
    }

    @DeleteMapping("/api/system/dicts/types/{id}")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "Delete a dictionary type (refused while items exist unless force=true)")
    public ApiResponse<Void> deleteType(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean force) {
        dictService.deleteType(id, force);
        return ApiResponse.success("字典类型已删除", null);
    }

    @GetMapping("/api/system/dicts/types/{id}/items")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "List items of one dictionary type (management view, includes disabled)")
    public ApiResponse<List<DictItemResponse>> listItems(@PathVariable Long id) {
        return ApiResponse.success(dictService.listItems(id));
    }

    @PostMapping("/api/system/dicts/types/{id}/items")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "Add an item to a dictionary type")
    public ApiResponse<DictItemResponse> createItem(@PathVariable Long id,
                                                    @Valid @RequestBody DictItemUpsertRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("字典项已创建", dictService.createItem(id, request, principal.getId()));
    }

    @PutMapping("/api/system/dicts/items/{id}")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "Update a dictionary item")
    public ApiResponse<DictItemResponse> updateItem(@PathVariable Long id,
                                                    @Valid @RequestBody DictItemUpsertRequest request) {
        return ApiResponse.success("字典项已更新", dictService.updateItem(id, request));
    }

    @DeleteMapping("/api/system/dicts/items/{id}")
    @PreAuthorize("hasAuthority('system:dict:manage')")
    @Operation(summary = "Delete a dictionary item")
    public ApiResponse<Void> deleteItem(@PathVariable Long id) {
        dictService.deleteItem(id);
        return ApiResponse.success("字典项已删除", null);
    }

    // ---- 消费方端点 ----

    @GetMapping("/api/system/dicts/{typeCode}/items")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Read active items of a dictionary by type code (for consuming modules)")
    public ApiResponse<List<DictItemResponse>> activeItems(@PathVariable String typeCode) {
        return ApiResponse.success(dictService.activeItemsByTypeCode(typeCode));
    }
}
