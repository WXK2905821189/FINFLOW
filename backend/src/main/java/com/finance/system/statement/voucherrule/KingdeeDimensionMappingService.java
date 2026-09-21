package com.finance.system.statement.voucherrule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.KingdeeDimensionMapping;
import com.finance.system.domain.entity.KingdeeDimensionSlot;
import com.finance.system.domain.mapper.KingdeeDimensionMappingMapper;
import com.finance.system.domain.mapper.KingdeeDimensionSlotMapper;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.MappingResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.MappingUpsertRequest;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.ResolvedDimension;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.SlotResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeDimensionDtos.SlotUpsertRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 金蝶核算维度配置服务（V42，2026-09-21）：槽位解析 + 来源值→档案编码翻译 + 界面维护能力。
 *
 * <p><b>为什么是表不是常量</b>：用户 2026-09-21 拍板——槽位（报错驱动试出）与档案映射都要
 * 能在系统界面直接改，不能每次改代码发版。</p>
 *
 * <p><b>解析口径（fail-visible，不静默）</b>：</p>
 * <ul>
 *   <li>槽位未配置 / 值为空 → 该维度不注入，并在 {@link ResolvedDimension#reason()} 写明原因，
 *   由推送结果透出给用户补配置；</li>
 *   <li>值映射按「组织精确优先、其次通用（org_code='')」消歧，同层多条按 id 升序取首条
 *   （唯一键保证同 (类型,来源值,组织) 不会重复）；</li>
 *   <li>{@code sourceKind} 决定比较方式：KEYWORD=包含（业务线），其余=忽略大小写精确。</li>
 * </ul>
 */
@Service
public class KingdeeDimensionMappingService {

    /** 维度类型常量（与 kingdee_dimension_slot.dimension_type、规则模板 dimension 字段同字面量）。 */
    public static final String BANK_ACCOUNT = "BANK_ACCOUNT";
    public static final String SUPPLIER = "SUPPLIER";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String BUSINESS_LINE = "BUSINESS_LINE";

    private static final String KIND_KEYWORD = "KEYWORD";
    private static final String KIND_DEFAULT = "NAME";

    private final KingdeeDimensionSlotMapper slotMapper;
    private final KingdeeDimensionMappingMapper mappingMapper;

    public KingdeeDimensionMappingService(KingdeeDimensionSlotMapper slotMapper,
                                          KingdeeDimensionMappingMapper mappingMapper) {
        this.slotMapper = slotMapper;
        this.mappingMapper = mappingMapper;
    }

    // ---------------- 槽位配置 ----------------

    public List<SlotResponse> listSlots(Boolean enabledOnly) {
        LambdaQueryWrapper<KingdeeDimensionSlot> query = new LambdaQueryWrapper<>();
        if (Boolean.TRUE.equals(enabledOnly)) {
            query.eq(KingdeeDimensionSlot::getEnabled, true);
        }
        query.orderByAsc(KingdeeDimensionSlot::getId);
        return slotMapper.selectList(query).stream().map(KingdeeDimensionMappingService::toSlot).toList();
    }

    @Transactional
    public SlotResponse createSlot(SlotUpsertRequest request) {
        String type = requireText(request.dimensionType(), "维度类型");
        if (slotMapper.selectCount(new LambdaQueryWrapper<KingdeeDimensionSlot>()
                .eq(KingdeeDimensionSlot::getDimensionType, type)) > 0) {
            throw new BusinessException(400, "维度类型 " + type + " 已存在槽位配置，请直接修改");
        }
        KingdeeDimensionSlot entity = new KingdeeDimensionSlot();
        entity.setDimensionType(type);
        applySlot(entity, request);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(entity);
        return toSlot(entity);
    }

    @Transactional
    public SlotResponse updateSlot(Long id, SlotUpsertRequest request) {
        KingdeeDimensionSlot entity = slotMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(404, "槽位配置不存在");
        }
        applySlot(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        slotMapper.updateById(entity);
        return toSlot(entity);
    }

    @Transactional
    public void deleteSlot(Long id) {
        if (slotMapper.selectById(id) == null) {
            throw new BusinessException(404, "槽位配置不存在");
        }
        slotMapper.deleteById(id);
    }

    private static void applySlot(KingdeeDimensionSlot entity, SlotUpsertRequest request) {
        entity.setDimensionName(trimToNull(request.dimensionName()));
        entity.setDimensionCode(trimToNull(request.dimensionCode()));
        entity.setSlot(trimToNull(request.slot()));
        entity.setDimensionKind(trimToNull(request.dimensionKind()));
        entity.setEnabled(!Boolean.FALSE.equals(request.enabled()));
        entity.setRemark(trimToNull(request.remark()));
    }

    // ---------------- 值映射 ----------------

    public List<MappingResponse> listMappings(String dimensionType, Boolean enabledOnly) {
        LambdaQueryWrapper<KingdeeDimensionMapping> query = new LambdaQueryWrapper<>();
        if (dimensionType != null && !dimensionType.isBlank()) {
            query.eq(KingdeeDimensionMapping::getDimensionType, dimensionType.trim());
        }
        if (Boolean.TRUE.equals(enabledOnly)) {
            query.eq(KingdeeDimensionMapping::getEnabled, true);
        }
        query.orderByAsc(KingdeeDimensionMapping::getDimensionType)
                .orderByAsc(KingdeeDimensionMapping::getSourceKey);
        return mappingMapper.selectList(query).stream()
                .map(KingdeeDimensionMappingService::toMapping).toList();
    }

    @Transactional
    public MappingResponse createMapping(MappingUpsertRequest request) {
        KingdeeDimensionMapping entity = new KingdeeDimensionMapping();
        applyMapping(entity, request, true);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mappingMapper.insert(entity);
        return toMapping(entity);
    }

    @Transactional
    public MappingResponse updateMapping(Long id, MappingUpsertRequest request) {
        KingdeeDimensionMapping entity = mappingMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(404, "维度映射不存在");
        }
        applyMapping(entity, request, false);
        entity.setUpdatedAt(LocalDateTime.now());
        mappingMapper.updateById(entity);
        return toMapping(entity);
    }

    @Transactional
    public void deleteMapping(Long id) {
        if (mappingMapper.selectById(id) == null) {
            throw new BusinessException(404, "维度映射不存在");
        }
        mappingMapper.deleteById(id);
    }

    /**
     * 批量新增（前端「批量粘贴」入口，供应商/员工动辄数百条，逐条点不现实）。
     * 同 (类型,来源值,组织) 已存在的行按「更新」处理，便于反复导入修订表。
     */
    @Transactional
    public int batchUpsert(List<MappingUpsertRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(400, "批量导入内容为空");
        }
        int affected = 0;
        for (MappingUpsertRequest request : requests) {
            KingdeeDimensionMapping existing = findByKey(request.dimensionType(), request.sourceKey(),
                    normalizeOrg(request.orgCode()));
            if (existing == null) {
                createMapping(request);
            } else {
                updateMapping(existing.getId(), request);
            }
            affected++;
        }
        return affected;
    }

    private KingdeeDimensionMapping findByKey(String dimensionType, String sourceKey, String orgCode) {
        return mappingMapper.selectOne(new LambdaQueryWrapper<KingdeeDimensionMapping>()
                .eq(KingdeeDimensionMapping::getDimensionType, requireText(dimensionType, "维度类型"))
                .eq(KingdeeDimensionMapping::getSourceKey, requireText(sourceKey, "来源值"))
                .eq(KingdeeDimensionMapping::getOrgCode, orgCode)
                .last("LIMIT 1"));
    }

    private static void applyMapping(KingdeeDimensionMapping entity, MappingUpsertRequest request,
                                     boolean creating) {
        if (creating) {
            entity.setDimensionType(requireText(request.dimensionType(), "维度类型"));
            entity.setSourceKey(requireText(request.sourceKey(), "来源值"));
            entity.setOrgCode(normalizeOrg(request.orgCode()));
        } else {
            // 维度类型/来源值/组织是唯一键组成部分：以路径 id 为准，不允许悄悄改身份
            if (request.dimensionType() != null && !request.dimensionType().isBlank()
                    && !request.dimensionType().trim().equals(entity.getDimensionType())) {
                throw new BusinessException(400, "维度类型不可修改，请删除后重建");
            }
            if (request.sourceKey() != null && !request.sourceKey().isBlank()
                    && !request.sourceKey().trim().equals(entity.getSourceKey())) {
                throw new BusinessException(400, "来源值不可修改，请删除后重建");
            }
        }
        entity.setSourceKind(normalizeKind(request.sourceKind()));
        entity.setKingdeeValue(requireText(request.kingdeeValue(), "金蝶档案编码"));
        entity.setKingdeeName(trimToNull(request.kingdeeName()));
        entity.setEnabled(!Boolean.FALSE.equals(request.enabled()));
        entity.setRemark(trimToNull(request.remark()));
    }

    // ---------------- 引擎侧解析 ----------------

    /** 维度类型 → 槽位键；未配置 / 已停用 / 槽位为空均返回 null（调用方据此跳过并提示）。 */
    public String slotOf(String dimensionType) {
        if (dimensionType == null || dimensionType.isBlank()) {
            return null;
        }
        KingdeeDimensionSlot entity = slotMapper.selectOne(new LambdaQueryWrapper<KingdeeDimensionSlot>()
                .eq(KingdeeDimensionSlot::getDimensionType, dimensionType.trim())
                .last("LIMIT 1"));
        if (entity == null || Boolean.FALSE.equals(entity.getEnabled())) {
            return null;
        }
        return trimToNull(entity.getSlot());
    }

    /**
     * 完整解析一个维度：槽位 + 值 + 未注入原因。
     *
     * @param dimensionType 维度类型（SUPPLIER/EMPLOYEE/BANK_ACCOUNT/BUSINESS_LINE…）
     * @param sourceKey     FINFLOW 侧来源值（对手方名称 / 员工姓名 / 账户号 / 业务线关键词）
     * @param orgCode       金蝶组织编码，用于消歧（可空）
     */
    public ResolvedDimension resolve(String dimensionType, String sourceKey, String orgCode) {
        String slot = slotOf(dimensionType);
        String value = resolveValue(dimensionType, sourceKey, orgCode);
        String reason = null;
        if (slot == null) {
            reason = "维度 " + dimensionType + " 未配置弹性域槽位（在「维度映射 › 槽位配置」补）";
        } else if (value == null) {
            reason = "维度 " + dimensionType + " 未找到来源值「" + safe(sourceKey) + "」的档案映射（在「维度映射 › 值映射」补）";
        }
        return new ResolvedDimension(dimensionType, slot, value, reason);
    }

    /**
     * 来源值 → 金蝶档案编码。组织精确优先、其次通用；匹配不到返回 null。
     */
    public String resolveValue(String dimensionType, String sourceKey, String orgCode) {
        if (dimensionType == null || dimensionType.isBlank() || sourceKey == null || sourceKey.isBlank()) {
            return null;
        }
        List<KingdeeDimensionMapping> rows = mappingMapper.selectList(
                new LambdaQueryWrapper<KingdeeDimensionMapping>()
                        .eq(KingdeeDimensionMapping::getDimensionType, dimensionType.trim())
                        .eq(KingdeeDimensionMapping::getEnabled, true)
                        .orderByAsc(KingdeeDimensionMapping::getId));
        if (rows.isEmpty()) {
            return null;
        }
        String org = trimToNull(orgCode);
        List<KingdeeDimensionMapping> hits = new ArrayList<>();
        for (KingdeeDimensionMapping row : rows) {
            if (matches(row, sourceKey.trim())) {
                hits.add(row);
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        // 组织精确命中优先，其次通用行（org_code=''）
        return hits.stream()
                .sorted(Comparator.comparingInt(row -> orgMatchRank(row.getOrgCode(), org)))
                .map(KingdeeDimensionMapping::getKingdeeValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(null);
    }

    private static int orgMatchRank(String rowOrg, String org) {
        String normalized = rowOrg == null ? "" : rowOrg.trim();
        if (org != null && org.equals(normalized)) {
            return 0; // 组织精确
        }
        return "".equals(normalized) ? 1 : 2; // 通用次之，其他组织最后（仍可兜底命中）
    }

    private static boolean matches(KingdeeDimensionMapping row, String sourceKey) {
        String key = row.getSourceKey();
        if (key == null || key.isBlank()) {
            return false;
        }
        if (KIND_KEYWORD.equalsIgnoreCase(row.getSourceKind())) {
            return sourceKey.contains(key.trim());
        }
        return key.trim().equalsIgnoreCase(sourceKey);
    }

    // ---------------- 映射 ----------------

    private static SlotResponse toSlot(KingdeeDimensionSlot entity) {
        return new SlotResponse(entity.getId(), entity.getDimensionType(), entity.getDimensionName(),
                entity.getDimensionCode(), entity.getSlot(), entity.getDimensionKind(),
                entity.getEnabled(), entity.getRemark());
    }

    private static MappingResponse toMapping(KingdeeDimensionMapping entity) {
        return new MappingResponse(entity.getId(), entity.getDimensionType(), entity.getSourceKey(),
                entity.getSourceKind(), entity.getKingdeeValue(), entity.getKingdeeName(),
                entity.getOrgCode(), entity.getEnabled(), entity.getRemark());
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return KIND_DEFAULT;
        }
        return kind.trim().toUpperCase();
    }

    /** org_code 在表里是 NOT NULL DEFAULT ''（唯一索引需要，MySQL 不比较 NULL）。 */
    private static String normalizeOrg(String orgCode) {
        return orgCode == null ? "" : orgCode.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, field + "不能为空");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
