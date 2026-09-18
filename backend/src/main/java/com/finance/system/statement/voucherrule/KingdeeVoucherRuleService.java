package com.finance.system.statement.voucherrule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.KingdeeRuleGroup;
import com.finance.system.domain.entity.KingdeeVoucherRule;
import com.finance.system.domain.mapper.KingdeeRuleGroupMapper;
import com.finance.system.domain.mapper.KingdeeVoucherRuleMapper;
import com.finance.system.statement.voucherrule.dto.KingdeeRuleGroupResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 金蝶凭证规则引擎 — 规则供数 + 规则中心维护面（V34 WP-A / V37 W4 扩展）。
 *
 * <p>职责：规则行查询、JSON 列结构化，以及 W4（2026-09-18）新增的规则 CRUD 与分组管理
 * （终结「规则维护走版本迁移」的只读时代）。匹配算法（预过滤/条件求值/优先级仲裁/金额门）
 * 属 {@link KingdeeVoucherMatchingService}，本服务只提供数据访问与校验：</p>
 * <ul>
 *   <li>{@link #listEnabled()}：匹配引擎的取数入口，enabled=1 按 priority 升序、
 *   rule_no 升序稳定排序；</li>
 *   <li>{@link #listRules(Boolean, Long)}：规则中心查询（可按启用状态/分组过滤）；</li>
 *   <li>{@link #createRule} / {@link #updateRule} / {@link #deleteRule}：规则维护
 *   （voucher:push 权限，AI 归档审计走 ai_call_log + 应用日志）；</li>
 *   <li>分组 CRUD：删除非空分组拒绝（409），防止规则漂移到「未分组」。</li>
 * </ul>
 */
@Service
public class KingdeeVoucherRuleService {

    private static final Logger log = LoggerFactory.getLogger(KingdeeVoucherRuleService.class);

    private final KingdeeVoucherRuleMapper ruleMapper;
    private final KingdeeRuleGroupMapper groupMapper;
    private final ObjectMapper objectMapper;

    public KingdeeVoucherRuleService(KingdeeVoucherRuleMapper ruleMapper,
                                     KingdeeRuleGroupMapper groupMapper,
                                     ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.groupMapper = groupMapper;
        this.objectMapper = objectMapper;
    }

    /** 匹配引擎取数入口：启用中的规则，priority 升序 → rule_no 升序稳定排序。 */
    public List<KingdeeVoucherRule> listEnabled() {
        return ruleMapper.selectList(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getEnabled, true)
                .orderByAsc(KingdeeVoucherRule::getPriority)
                .orderByAsc(KingdeeVoucherRule::getRuleNo));
    }

    /**
     * 规则中心查询（HTTP）：enabledOnly/groupId 均可空；排序 priority → rule_no。
     */
    public List<KingdeeVoucherRuleResponse> listRules(Boolean enabledOnly, Long groupId) {
        LambdaQueryWrapper<KingdeeVoucherRule> wrapper = new LambdaQueryWrapper<KingdeeVoucherRule>()
                .orderByAsc(KingdeeVoucherRule::getPriority)
                .orderByAsc(KingdeeVoucherRule::getRuleNo);
        if (enabledOnly != null) {
            wrapper.eq(KingdeeVoucherRule::getEnabled, enabledOnly);
        }
        if (groupId != null) {
            wrapper.eq(KingdeeVoucherRule::getGroupId, groupId);
        }
        Map<Long, String> groupNames = loadGroupNames();
        return ruleMapper.selectList(wrapper).stream()
                .map(rule -> toResponse(rule, groupNames)).toList();
    }

    /** 兼容旧签名（EnabledOnly 过滤）。 */
    public List<KingdeeVoucherRuleResponse> listRules(Boolean enabledOnly) {
        return listRules(enabledOnly, null);
    }

    /** 单条规则结构化（WP-B 按 ruleNo 复查时可用）。 */
    public KingdeeVoucherRuleResponse getByRuleNo(int ruleNo) {
        KingdeeVoucherRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getRuleNo, ruleNo));
        if (rule == null) {
            throw new BusinessException(404, "凭证规则不存在: ruleNo=" + ruleNo);
        }
        return toResponse(rule, loadGroupNames());
    }

    // ---------------- 分组管理（W4） ----------------

    public List<KingdeeRuleGroupResponse> listGroups() {
        Map<Long, Long> counts = ruleMapper.selectList(new LambdaQueryWrapper<KingdeeVoucherRule>()
                        .isNotNull(KingdeeVoucherRule::getGroupId))
                .stream().filter(r -> r.getGroupId() != null)
                .collect(Collectors.groupingBy(KingdeeVoucherRule::getGroupId, Collectors.counting()));
        return groupMapper.selectList(new LambdaQueryWrapper<KingdeeRuleGroup>()
                        .orderByAsc(KingdeeRuleGroup::getSortNo)
                        .orderByAsc(KingdeeRuleGroup::getId))
                .stream().map(g -> new KingdeeRuleGroupResponse(g.getId(), g.getName(), g.getDescription(),
                        g.getSortNo(), counts.getOrDefault(g.getId(), 0L))).toList();
    }

    @Transactional
    public KingdeeRuleGroupResponse createGroup(KingdeeRuleGroupResponse.UpsertRequest request) {
        String name = requireName(request);
        if (groupMapper.selectCount(new LambdaQueryWrapper<KingdeeRuleGroup>()
                .eq(KingdeeRuleGroup::getName, name)) > 0) {
            throw new BusinessException(409, "分组名称已存在：" + name);
        }
        KingdeeRuleGroup group = new KingdeeRuleGroup();
        group.setName(name);
        group.setDescription(trimToNull(request.description()));
        group.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        group.setCreatedAt(LocalDateTime.now());
        group.setUpdatedAt(LocalDateTime.now());
        groupMapper.insert(group);
        log.info("W4 规则分组创建 id={} name={} sortNo={}", group.getId(), name, group.getSortNo());
        return new KingdeeRuleGroupResponse(group.getId(), group.getName(), group.getDescription(),
                group.getSortNo(), 0);
    }

    @Transactional
    public KingdeeRuleGroupResponse updateGroup(Long id, KingdeeRuleGroupResponse.UpsertRequest request) {
        KingdeeRuleGroup group = requireGroup(id);
        String name = requireName(request);
        if (groupMapper.selectCount(new LambdaQueryWrapper<KingdeeRuleGroup>()
                .eq(KingdeeRuleGroup::getName, name).ne(KingdeeRuleGroup::getId, id)) > 0) {
            throw new BusinessException(409, "分组名称已存在：" + name);
        }
        group.setName(name);
        group.setDescription(trimToNull(request.description()));
        group.setSortNo(request.sortNo() == null ? group.getSortNo() : request.sortNo());
        group.setUpdatedAt(LocalDateTime.now());
        groupMapper.updateById(group);
        log.info("W4 规则分组更新 id={} name={}", id, name);
        return new KingdeeRuleGroupResponse(group.getId(), group.getName(), group.getDescription(),
                group.getSortNo(), ruleCountOf(id));
    }

    @Transactional
    public void deleteGroup(Long id) {
        requireGroup(id);
        long rules = ruleCountOf(id);
        if (rules > 0) {
            throw new BusinessException(409, "分组下仍有 " + rules + " 条规则，请先移动或删除规则");
        }
        groupMapper.deleteById(id);
        log.info("W4 规则分组删除 id={}", id);
    }

    // ---------------- 规则 CRUD（W4） ----------------

    @Transactional
    public KingdeeVoucherRuleResponse createRule(KingdeeRuleGroupResponse.RuleUpsertRequest request) {
        KingdeeVoucherRule rule = toEntity(request);
        if (request.ruleNo() == null) {
            Integer max = ruleMapper.selectList(new LambdaQueryWrapper<KingdeeVoucherRule>()
                            .orderByDesc(KingdeeVoucherRule::getRuleNo).last("LIMIT 1"))
                    .stream().findFirst().map(KingdeeVoucherRule::getRuleNo).orElse(0);
            rule.setRuleNo(max + 1);
        } else if (ruleMapper.selectCount(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getRuleNo, request.ruleNo())) > 0) {
            throw new BusinessException(409, "规则号已存在：ruleNo=" + request.ruleNo());
        }
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.insert(rule);
        log.info("W4 规则创建 id={} ruleNo={} businessType={} groupId={}",
                rule.getId(), rule.getRuleNo(), rule.getBusinessType(), rule.getGroupId());
        return toResponse(rule, loadGroupNames());
    }

    @Transactional
    public KingdeeVoucherRuleResponse updateRule(Long id, KingdeeRuleGroupResponse.RuleUpsertRequest request) {
        KingdeeVoucherRule existing = ruleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "凭证规则不存在: id=" + id);
        }
        KingdeeVoucherRule rule = toEntity(request);
        rule.setId(id);
        if (request.ruleNo() == null) {
            rule.setRuleNo(existing.getRuleNo());
        } else if (ruleMapper.selectCount(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getRuleNo, request.ruleNo()).ne(KingdeeVoucherRule::getId, id)) > 0) {
            throw new BusinessException(409, "规则号已存在：ruleNo=" + request.ruleNo());
        }
        if (request.enabled() == null) {
            rule.setEnabled(existing.getEnabled());
        }
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);
        // MyBatis-Plus updateById 默认跳过 null 字段——「移出分组」必须显式置 NULL
        if (request.groupId() == null) {
            ruleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KingdeeVoucherRule>()
                    .set(KingdeeVoucherRule::getGroupId, null)
                    .eq(KingdeeVoucherRule::getId, id));
        }
        log.info("W4 规则更新 id={} ruleNo={} groupId={}", id, rule.getRuleNo(), rule.getGroupId());
        return toResponse(ruleMapper.selectById(id), loadGroupNames());
    }

    @Transactional
    public void deleteRule(Long id) {
        KingdeeVoucherRule existing = ruleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "凭证规则不存在: id=" + id);
        }
        ruleMapper.deleteById(id);
        log.info("W4 规则删除 id={} ruleNo={}", id, existing.getRuleNo());
    }

    /** 批量入库（Excel 导入第二步 confirm）：逐行走 createRule 校验，rule_no 冲突整体 409。 */
    @Transactional
    public List<KingdeeVoucherRuleResponse> confirmImport(List<KingdeeRuleGroupResponse.RuleUpsertRequest> rows,
                                                          Long defaultGroupId) {
        List<KingdeeRuleGroupResponse.RuleUpsertRequest> effective = rows.stream()
                .map(r -> r.groupId() == null && defaultGroupId != null
                        ? new KingdeeRuleGroupResponse.RuleUpsertRequest(r.ruleNo(), r.businessType(), r.category(),
                        r.priority(), r.scopeOrgs(), r.scopeBankChannels(), r.direction(), r.amountMin(),
                        r.amountMax(), r.match(), r.debitLines(), r.creditLines(), r.extraVoucher(),
                        r.enabled(), r.remark(), defaultGroupId)
                        : r)
                .toList();
        List<KingdeeVoucherRuleResponse> created = effective.stream().map(this::createRule).toList();
        log.info("W4 规则导入入库 {} 条（defaultGroupId={}）", created.size(), defaultGroupId);
        return created;
    }

    // ---------------- 内部 ----------------

    private KingdeeVoucherRule toEntity(KingdeeRuleGroupResponse.RuleUpsertRequest request) {
        if (request == null) {
            throw new BusinessException(400, "规则内容不能为空");
        }
        String businessType = trimToNull(request.businessType());
        String category = trimToNull(request.category());
        String direction = trimToNull(request.direction());
        if (businessType == null || category == null || direction == null) {
            throw new BusinessException(400, "业务类型/大类/方向（INCOME/EXPENSE/BOTH）均为必填");
        }
        if (!"INCOME".equalsIgnoreCase(direction) && !"EXPENSE".equalsIgnoreCase(direction)
                && !"BOTH".equalsIgnoreCase(direction)) {
            throw new BusinessException(400, "方向仅支持 INCOME / EXPENSE / BOTH");
        }
        if (request.match() == null || request.match().conditions() == null
                || request.match().conditions().isEmpty()) {
            throw new BusinessException(400, "匹配条件（match.conditions）至少一条");
        }
        if (request.debitLines() == null || request.debitLines().isEmpty()
                || request.creditLines() == null || request.creditLines().isEmpty()) {
            throw new BusinessException(400, "借/贷分录模板均不能为空");
        }
        if (request.priority() == null || request.priority() < 1) {
            throw new BusinessException(400, "优先级必须为正整数");
        }
        if (request.groupId() != null) {
            requireGroup(request.groupId());
        }
        KingdeeVoucherRule rule = new KingdeeVoucherRule();
        rule.setRuleNo(request.ruleNo());
        rule.setBusinessType(businessType);
        rule.setCategory(category);
        rule.setPriority(request.priority());
        rule.setScopeOrgs(joinCsv(request.scopeOrgs(), "ALL"));
        rule.setScopeBankChannels(joinCsv(request.scopeBankChannels(), ""));
        rule.setDirection(direction.toUpperCase());
        rule.setAmountMin(request.amountMin());
        rule.setAmountMax(request.amountMax());
        rule.setMatchJson(writeJsonStrict(new KingdeeVoucherRuleResponse.Match(
                request.match().logic() == null ? "ALL" : request.match().logic(),
                request.match().conditions()), "match"));
        rule.setDebitLinesJson(writeJsonStrict(request.debitLines(), "debitLines"));
        rule.setCreditLinesJson(writeJsonStrict(request.creditLines(), "creditLines"));
        rule.setExtraVoucherJson(request.extraVoucher() == null ? null
                : writeJsonStrict(request.extraVoucher(), "extraVoucher"));
        rule.setEnabled(!Boolean.FALSE.equals(request.enabled()));
        rule.setRemark(trimToNull(request.remark()));
        rule.setGroupId(request.groupId());
        return rule;
    }

    /** 规范化 JSON 写入：写库前往返一次，非法结构/不可序列化直接 400（fail-closed）。 */
    private String writeJsonStrict(Object value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(400, "规则 " + field + " 结构非法：" + e.getMessage());
        }
    }

    private KingdeeRuleGroup requireGroup(Long id) {
        KingdeeRuleGroup group = groupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException(404, "规则分组不存在: id=" + id);
        }
        return group;
    }

    private long ruleCountOf(Long groupId) {
        return ruleMapper.selectCount(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getGroupId, groupId));
    }

    private Map<Long, String> loadGroupNames() {
        return groupMapper.selectList(null).stream()
                .collect(Collectors.toMap(KingdeeRuleGroup::getId, KingdeeRuleGroup::getName, (a, b) -> a));
    }

    private KingdeeVoucherRuleResponse toResponse(KingdeeVoucherRule rule, Map<Long, String> groupNames) {
        try {
            List<String> orgs = splitCsv(rule.getScopeOrgs());
            List<String> channels = splitCsv(rule.getScopeBankChannels());
            KingdeeVoucherRuleResponse.Match match =
                    objectMapper.readValue(rule.getMatchJson(), KingdeeVoucherRuleResponse.Match.class);
            List<KingdeeVoucherRuleResponse.LineTemplate> debitLines = objectMapper.readValue(
                    rule.getDebitLinesJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            KingdeeVoucherRuleResponse.LineTemplate.class));
            List<KingdeeVoucherRuleResponse.LineTemplate> creditLines = objectMapper.readValue(
                    rule.getCreditLinesJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            KingdeeVoucherRuleResponse.LineTemplate.class));
            KingdeeVoucherRuleResponse.ExtraVoucher extraVoucher = rule.getExtraVoucherJson() == null
                    ? null
                    : objectMapper.readValue(rule.getExtraVoucherJson(),
                            KingdeeVoucherRuleResponse.ExtraVoucher.class);
            return new KingdeeVoucherRuleResponse(
                    rule.getId(), rule.getRuleNo(), rule.getBusinessType(), rule.getCategory(),
                    rule.getPriority(), orgs, channels, rule.getDirection(),
                    rule.getAmountMin(), rule.getAmountMax(), match, debitLines, creditLines,
                    extraVoucher, Boolean.TRUE.equals(rule.getEnabled()), rule.getRemark(),
                    rule.getGroupId(), rule.getGroupId() == null ? null : groupNames.get(rule.getGroupId()));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500,
                    "凭证规则 JSON 损坏 (ruleNo=" + rule.getRuleNo() + "): " + e.getMessage());
        }
    }

    private static String requireName(KingdeeRuleGroupResponse.UpsertRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "分组名称必填");
        }
        return request.name().trim();
    }

    private static String joinCsv(List<String> values, String fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        String joined = values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.joining(","));
        return joined.isEmpty() ? fallback : joined;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
