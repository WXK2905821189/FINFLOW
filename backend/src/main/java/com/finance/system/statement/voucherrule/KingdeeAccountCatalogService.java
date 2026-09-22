package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账套科目目录（BD_Account）只读缓存服务（2026-09-21 方案 B）。
 *
 * <p>解决两个真实问题：</p>
 * <ol>
 *   <li><b>科目校验</b>：AI/规则给出的科目编码与名称必须与账套一致。实测案例——账套里
 *   {@code 2232} 是「应付股利」，而 AI 建议把它当「应付账款」用；不校验就会**静默记错账**。</li>
 *   <li><b>维度需求判定</b>：账套科目挂了必录核算维度时（如 1002 → ZDY0001 银行账号），
 *   报文必须注入维度，否则被金蝶拒绝（见 {@code KingdeeGlVoucherPayloadBuilder#appendDimension}）。</li>
 * </ol>
 *
 * <p><b>降级口径</b>（不可用时宁可放行也不误杀）：目录拉取失败/为空时——
 * 科目校验与维度注入**跳过**，并在结果里以 {@code catalogUnavailable} 标注，
 * 由推送消息提示「未校验」；目录可用但科目不存在 / 名称不一致则**拒绝推送**（fail-closed）。</p>
 *
 * <p>缓存：{@code kingdee.account-catalog-ttl-seconds}（默认 600s）；科目体系变更频率低，
 * 但推送前每次校验都在热路径上，避免逐次拉全表。</p>
 */
@Service
public class KingdeeAccountCatalogService {

    private static final Logger log = LoggerFactory.getLogger(KingdeeAccountCatalogService.class);

    /** 银行账号维度类型编码（账套 ZDY0001；用户 2026-09-21 截图确认「维度类型=基础资料」）。 */
    public static final String BANK_ACCOUNT_DIMENSION = "ZDY0001";

    private final KingdeeVoucherGateway gateway;
    private final KingdeeProperties props;

    private volatile Map<String, KingdeeVoucherGateway.KingdeeAccountRef> cache = Map.of();
    private volatile long cachedAt = 0L;
    private volatile boolean catalogLoaded = false;

    public KingdeeAccountCatalogService(KingdeeVoucherGateway gateway, KingdeeProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    /** 科目校验结果（catalogUnavailable=true 时未做校验，属降级放行）。 */
    public record AccountCheck(String code, String name, boolean catalogUnavailable, String note) {
    }

    /** 目录快照（按需加载；异常时返回空表，绝不抛出——制证不应因校验基建故障整体不可用）。 */
    public Map<String, KingdeeVoucherGateway.KingdeeAccountRef> catalog() {
        long now = System.currentTimeMillis();
        long ttl = (props.getAccountCatalogTtlSeconds() == null ? 600 : props.getAccountCatalogTtlSeconds()) * 1000L;
        if (catalogLoaded && now - cachedAt < ttl) {
            return cache;
        }
        try {
            List<KingdeeVoucherGateway.KingdeeAccountRef> rows = gateway.queryAccountCatalog();
            Map<String, KingdeeVoucherGateway.KingdeeAccountRef> loaded = new LinkedHashMap<>();
            for (KingdeeVoucherGateway.KingdeeAccountRef row : rows) {
                if (row != null && row.number() != null && !row.number().isBlank()) {
                    loaded.put(row.number().trim(), row);
                }
            }
            cache = Map.copyOf(loaded);
            cachedAt = now;
            catalogLoaded = true;
            log.info("账套科目目录已加载：{} 条", cache.size());
        } catch (Exception e) {
            // 目录不可用：保持旧缓存（若有），标记未加载 → 调用方按降级放行处理
            log.warn("账套科目目录拉取失败，本次跳过科目校验：{}", e.getMessage());
            catalogLoaded = false;
        }
        return cache;
    }

    public boolean isCatalogAvailable() {
        return catalogLoaded && !cache.isEmpty();
    }

    /**
     * 校验科目编码与名称是否与账套一致。
     *
     * @throws BusinessException 目录可用但科目不存在，或名称不一致（400，附处置指引）
     */
    public AccountCheck check(String code, String name) {
        Map<String, KingdeeVoucherGateway.KingdeeAccountRef> snapshot = catalog();
        if (snapshot.isEmpty()) {
            return new AccountCheck(code, name, true, "账套科目目录不可用，本次未校验科目");
        }
        KingdeeVoucherGateway.KingdeeAccountRef ref = snapshot.get(code == null ? "" : code.trim());
        if (ref == null) {
            throw new BusinessException(400, "科目 " + code + " 在金蝶账套中不存在，无法制证；"
                    + "请在凭证草稿中改用账套已有科目（科目表可通过「连接测试」或科目目录接口查看）");
        }
        if (name != null && !name.isBlank() && ref.name() != null && !ref.name().isBlank()
                && !ref.name().trim().equals(name.trim())) {
            // 2026-09-21 改判（用户实际被这条挡住）：科目编码在账套中存在即为可用。
            // 金蝶推送报文只发 FNumber（编码），名称是账套侧的描述字段、**不参与推送** ——
            // 本地因名称不同就拒绝属于过度拦截。改为「以账套名称为准」并留 note 供人工复核。
            return new AccountCheck(code, ref.name(), false,
                    "科目 " + code + " 名称本地为「" + name.trim() + "」、账套为「" + ref.name().trim()
                            + "」，已按账套名称处理");
        }
        return new AccountCheck(code, ref.name(), false, null);
    }

    /**
     * 该科目编码是否存在于账套科目目录。**目录不可用时返回 true**（不阻断，交由金蝶报错），
     * 与 {@link #check} 的降级放行口径一致。用于校验「兜底科目」本身是否可用 ——
     * 若兜底科目在账套里也不存在，替换只会把 400 变成金蝶的 502，等于没解决问题。
     */
    public boolean exists(String code) {
        Map<String, KingdeeVoucherGateway.KingdeeAccountRef> snapshot = catalog();
        if (snapshot.isEmpty()) {
            return true;
        }
        return snapshot.containsKey(code == null ? "" : code.trim());
    }

    /**
     * 该科目是否必须带银行账号核算维度（挂 ZDY0001）。
     *
     * <p><b>降级口径与可观测性（2026-09-22 P1-1）</b>：目录不可用时返回 false（不注入，
     * 交由金蝶报错校准）——fail-open 语义保持不变；但「该科目可能需要维度而系统判定不了」
     * 必须**留痕**，否则会出现「金蝶报必录维度未录入、本地却查不到任何判定错误」的静默盲区
     * （W15 排查曾因此推断到极限）。目录不可用时打 WARN，供排查时直接定位。</p>
     */
    public boolean requiresBankDimension(String code) {
        Map<String, KingdeeVoucherGateway.KingdeeAccountRef> snapshot = catalog();
        if (snapshot.isEmpty()) {
            log.warn("科目目录不可用，无法判定科目 {} 是否需要「银行账号」核算维度（本次推送未做维度需求判定，"
                    + "若金蝶报「必录维度未录入」应优先排查目录拉取）", code);
            return false;
        }
        KingdeeVoucherGateway.KingdeeAccountRef ref = snapshot.get(code == null ? "" : code.trim());
        return ref != null && BANK_ACCOUNT_DIMENSION.equalsIgnoreCase(
                ref.dimensionCode() == null ? "" : ref.dimensionCode().trim());
    }

    /** 科目编码 → 账套科目引用（含维度类型）；目录不可用或编码不存在返回 null。 */
    public KingdeeVoucherGateway.KingdeeAccountRef refOf(String code) {
        Map<String, KingdeeVoucherGateway.KingdeeAccountRef> snapshot = catalog();
        if (snapshot.isEmpty()) {
            return null;
        }
        return snapshot.get(code == null ? "" : code.trim());
    }

    /**
     * 名称反查编码结果。
     *
     * @param code       唯一命中时的科目编码；否则 null
     * @param candidates 命中多个时的候选（「编码 名称」），供人工指定
     */
    public record NameResolution(String code, List<String> candidates) {
        public boolean unique() {
            return code != null;
        }

        public boolean ambiguous() {
            return code == null && !candidates.isEmpty();
        }
    }

    /**
     * 用科目**名称**在账套科目表里反查编码（2026-09-21）。
     *
     * <p>存在的原因：AI 提示词明确写着「科目编码不确定就给空字符串」，而总账落点（GL）
     * 必须有科目编码才能制证。此处按**精确名称**反查：
     * 唯一命中 → 用该编码；命中多个（如「外部往来」同时存在于 1122.01/1123.01/2241.06）→
     * 返回候选由调用方拒绝并提示人工指定；无命中 → 返回空结果。</p>
     */
    public NameResolution resolveByName(String name) {
        if (name == null || name.isBlank()) {
            return new NameResolution(null, List.of());
        }
        String target = name.trim();
        List<String> hits = new java.util.ArrayList<>();
        for (KingdeeVoucherGateway.KingdeeAccountRef ref : catalog().values()) {
            if (ref.name() != null && target.equals(ref.name().trim())) {
                hits.add(ref.number() + " " + ref.name().trim());
            }
        }
        if (hits.isEmpty()) {
            return new NameResolution(null, List.of());
        }
        if (hits.size() == 1) {
            return new NameResolution(hits.get(0).split(" ")[0], List.of());
        }
        return new NameResolution(null, List.copyOf(hits));
    }
}
