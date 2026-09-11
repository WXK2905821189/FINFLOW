package com.finance.system.dict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.dict.dto.DictItemResponse;
import com.finance.system.dict.dto.DictItemUpsertRequest;
import com.finance.system.dict.dto.DictTypeResponse;
import com.finance.system.dict.dto.DictTypeUpsertRequest;
import com.finance.system.domain.entity.SysDictItem;
import com.finance.system.domain.entity.SysDictType;
import com.finance.system.domain.mapper.SysDictItemMapper;
import com.finance.system.domain.mapper.SysDictTypeMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 字典中心（V26）：两层字典（类型 → 项），项带 extraJson 扩展属性。
 *
 * <p>定位是"不改代码就能改的字段值清单"：typeCode 创建后不可改（消费方按 code
 * 取数），项码可改但受唯一约束；extraJson 写入时校验为 JSON object、原样存储，
 * 新增属性零迁移。删除类型时若还有项则拒绝（force=true 才连带删除）——物理删除
 * 的业务键护栏。</p>
 */
@Service
public class DictService {

    private static final Pattern TYPE_CODE = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern ITEM_CODE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysDictTypeMapper typeMapper;
    private final SysDictItemMapper itemMapper;
    private final ObjectMapper objectMapper;

    public DictService(SysDictTypeMapper typeMapper, SysDictItemMapper itemMapper, ObjectMapper objectMapper) {
        this.typeMapper = typeMapper;
        this.itemMapper = itemMapper;
        this.objectMapper = objectMapper;
    }

    // ---- 类型 ----

    public List<DictTypeResponse> listTypes() {
        List<SysDictType> types = typeMapper.selectList(
                new LambdaQueryWrapper<SysDictType>().orderByAsc(SysDictType::getId));
        // selectMaps on H2 returns UPPERCASE column keys → aggregate case-insensitively.
        Map<Long, Long> counts = itemMapper.selectMaps(new QueryWrapper<SysDictItem>()
                        .select("type_id", "COUNT(*) AS cnt")
                        .groupBy("type_id"))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> longValue(firstKey(row, "type_id")),
                        row -> longValue(firstKey(row, "cnt"))));
        return types.stream()
                .map(type -> toTypeResponse(type, counts.getOrDefault(type.getId(), 0L)))
                .toList();
    }

    @Transactional
    public DictTypeResponse createType(DictTypeUpsertRequest request, Long userId) {
        String typeCode = normalizeTypeCode(request.typeCode());
        if (typeMapper.selectCount(new LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getTypeCode, typeCode)) > 0) {
            throw new BusinessException(400, "字典标识已存在：" + typeCode);
        }
        SysDictType type = new SysDictType();
        type.setTypeCode(typeCode);
        applyType(type, request);
        type.setCreatedBy(userId);
        type.setCreatedAt(LocalDateTime.now());
        type.setUpdatedAt(LocalDateTime.now());
        typeMapper.insert(type);
        return toTypeResponse(type, 0L);
    }

    @Transactional
    public DictTypeResponse updateType(Long id, DictTypeUpsertRequest request) {
        SysDictType type = requireType(id);
        applyType(type, request);
        type.setUpdatedAt(LocalDateTime.now());
        typeMapper.updateById(type);
        long count = itemMapper.selectCount(new LambdaQueryWrapper<SysDictItem>().eq(SysDictItem::getTypeId, id));
        return toTypeResponse(type, count);
    }

    @Transactional
    public void deleteType(Long id, boolean force) {
        requireType(id);
        long count = itemMapper.selectCount(new LambdaQueryWrapper<SysDictItem>().eq(SysDictItem::getTypeId, id));
        if (count > 0 && !force) {
            throw new BusinessException(400, "该字典下还有 " + count + " 个字典项，确认连带删除请勾选强制删除");
        }
        if (count > 0) {
            itemMapper.delete(new LambdaQueryWrapper<SysDictItem>().eq(SysDictItem::getTypeId, id));
        }
        typeMapper.deleteById(id);
    }

    // ---- 项 ----

    public List<DictItemResponse> listItems(Long typeId) {
        requireType(typeId);
        return itemMapper.selectList(new LambdaQueryWrapper<SysDictItem>()
                        .eq(SysDictItem::getTypeId, typeId)
                        .orderByAsc(SysDictItem::getSortNo)
                        .orderByAsc(SysDictItem::getId))
                .stream().map(DictService::toItemResponse).toList();
    }

    /** 消费方读取：按 typeCode 取启用中的项（排序生效），任何登录用户可调。 */
    public List<DictItemResponse> activeItemsByTypeCode(String typeCode) {
        SysDictType type = typeMapper.selectOne(new LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getTypeCode, typeCode));
        if (type == null || !ACTIVE.equals(type.getStatus())) {
            return List.of();
        }
        return itemMapper.selectList(new LambdaQueryWrapper<SysDictItem>()
                        .eq(SysDictItem::getTypeId, type.getId())
                        .eq(SysDictItem::getStatus, ACTIVE)
                        .orderByAsc(SysDictItem::getSortNo)
                        .orderByAsc(SysDictItem::getId))
                .stream().map(DictService::toItemResponse).toList();
    }

    @Transactional
    public DictItemResponse createItem(Long typeId, DictItemUpsertRequest request, Long userId) {
        requireType(typeId);
        String itemCode = normalizeItemCode(request.itemCode());
        if (itemMapper.selectCount(new LambdaQueryWrapper<SysDictItem>()
                .eq(SysDictItem::getTypeId, typeId)
                .eq(SysDictItem::getItemCode, itemCode)) > 0) {
            throw new BusinessException(400, "该字典下项标识已存在：" + itemCode);
        }
        SysDictItem item = new SysDictItem();
        item.setTypeId(typeId);
        applyItem(item, request);
        item.setCreatedBy(userId);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.insert(item);
        return toItemResponse(item);
    }

    @Transactional
    public DictItemResponse updateItem(Long id, DictItemUpsertRequest request) {
        SysDictItem item = itemMapper.selectById(id);
        if (item == null) {
            throw new BusinessException(404, "字典项不存在");
        }
        String itemCode = normalizeItemCode(request.itemCode());
        if (itemMapper.selectCount(new LambdaQueryWrapper<SysDictItem>()
                .eq(SysDictItem::getTypeId, item.getTypeId())
                .eq(SysDictItem::getItemCode, itemCode)
                .ne(SysDictItem::getId, id)) > 0) {
            throw new BusinessException(400, "该字典下项标识已存在：" + itemCode);
        }
        applyItem(item, request);
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);
        return toItemResponse(item);
    }

    @Transactional
    public void deleteItem(Long id) {
        if (itemMapper.deleteById(id) == 0) {
            throw new BusinessException(404, "字典项不存在");
        }
    }

    // ---- 内部 ----

    private void applyType(SysDictType type, DictTypeUpsertRequest request) {
        String name = trim(request.name());
        if (name == null) {
            throw new BusinessException(400, "字典名称必填");
        }
        String status = normalizeStatus(request.status());
        type.setName(name);
        type.setDescription(trim(request.description()));
        type.setStatus(status);
    }

    private void applyItem(SysDictItem item, DictItemUpsertRequest request) {
        item.setItemCode(normalizeItemCode(request.itemCode()));
        String label = trim(request.label());
        if (label == null) {
            throw new BusinessException(400, "显示名称必填");
        }
        item.setLabel(label);
        item.setExtraJson(normalizeExtraJson(request.extraJson()));
        Integer sortNo = request.sortNo();
        item.setSortNo(sortNo == null ? 0 : sortNo);
        item.setStatus(normalizeStatus(request.status()));
        item.setRemark(trim(request.remark()));
    }

    /** extraJson 必须是 JSON object（或空）；格式化后存储，保证库内形态统一。 */
    private String normalizeExtraJson(String raw) {
        String trimmed = trim(raw);
        if (trimmed == null) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(trimmed);
        } catch (Exception e) {
            throw new BusinessException(400, "扩展属性不是合法 JSON");
        }
        if (!node.isObject()) {
            throw new BusinessException(400, "扩展属性必须是 JSON 对象（键值对）");
        }
        return node.toString();
    }

    private String normalizeTypeCode(String raw) {
        String value = trim(raw) == null ? null : trim(raw).toLowerCase(Locale.ROOT);
        if (value == null || !TYPE_CODE.matcher(value).matches()) {
            throw new BusinessException(400, "字典标识需为小写字母开头的字母/数字/下划线（2~64 位），如 company_entity");
        }
        return value;
    }

    private String normalizeItemCode(String raw) {
        String value = trim(raw);
        if (value == null || !ITEM_CODE.matcher(value).matches()) {
            throw new BusinessException(400, "项标识需为字母/数字/中划线/下划线（1~64 位）");
        }
        return value;
    }

    private String normalizeStatus(String raw) {
        String value = trim(raw);
        if (value == null) {
            return ACTIVE;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case ACTIVE -> ACTIVE;
            case DISABLED -> DISABLED;
            default -> throw new BusinessException(400, "状态仅支持 ACTIVE/DISABLED");
        };
    }

    private SysDictType requireType(Long id) {
        SysDictType type = typeMapper.selectById(id);
        if (type == null) {
            throw new BusinessException(404, "字典类型不存在");
        }
        return type;
    }

    private static DictTypeResponse toTypeResponse(SysDictType type, long itemCount) {
        return new DictTypeResponse(type.getId(), type.getTypeCode(), type.getName(),
                type.getDescription(), type.getStatus(), itemCount,
                type.getCreatedAt() == null ? null : type.getCreatedAt().format(DATETIME));
    }

    private static DictItemResponse toItemResponse(SysDictItem item) {
        return new DictItemResponse(item.getId(), item.getTypeId(), item.getItemCode(),
                item.getLabel(), item.getExtraJson(), item.getSortNo(), item.getStatus(), item.getRemark());
    }

    private static Long firstKey(Map<String, Object> row, String lowerKey) {
        return row.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(lowerKey))
                .findFirst()
                .map(key -> longValue(row.get(key)))
                .orElse(0L);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
