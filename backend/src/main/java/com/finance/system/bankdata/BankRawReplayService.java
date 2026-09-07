package com.finance.system.bankdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.cmb.CmbRowMapper;
import com.finance.system.bankdata.adapter.citic.CiticRowMapper;
import com.finance.system.bankdata.aggregation.BankCanonicalizer;
import com.finance.system.bankdata.aggregation.BankDataStatus;
import com.finance.system.bankdata.dto.BankRawReplayResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Replay: re-parse a stored verbatim bank response with <em>today's</em> mapping rules
 * and diff the result against the view captured when the page was first collected.
 *
 * <p>This is the cleanup layer's traceability guarantee made concrete. The stored view
 * was produced by the mapping as it existed at collection time; if an adapter later
 * dropped a field, renamed a code or shifted a sign, the replay shows exactly which
 * JSON paths changed — without needing to involve the bank again.</p>
 *
 * <p>Two rules keep the diff honest:
 * <ul>
 *   <li>only rows with a verbatim response ({@code response_payload}) are replayable —
 *       replaying the old view-payload would compare the mapping against itself;</li>
 *   <li>volatile fields are excluded: balance as-of timestamps move with wall-clock and
 *       CITIC pagination heuristics depend on runtime page size, so neither marks a diff.</li>
 * </ul>
 */
@Service
public class BankRawReplayService {

    private static final int MAX_DIFFERENCES = 20;

    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataSyncTaskMapper taskMapper;
    private final CompanyScopeService companyScope;
    private final ObjectMapper objectMapper;

    public BankRawReplayService(BankDataRawMessageMapper rawMessageMapper,
                                BankDataSyncTaskMapper taskMapper,
                                CompanyScopeService companyScope,
                                ObjectMapper objectMapper) {
        this.rawMessageMapper = rawMessageMapper;
        this.taskMapper = taskMapper;
        this.companyScope = companyScope;
        this.objectMapper = objectMapper;
    }

    public BankRawReplayResponse replay(Long userId, Long id) {
        long companyId = companyScope.companyIdForUser(userId);
        BankDataRawMessage raw = rawMessageMapper.selectOne(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getId, id)
                .eq(BankDataRawMessage::getCompanyId, companyId));
        if (raw == null) {
            throw new BusinessException(404, "Raw bank message not found in the current company");
        }
        String response = raw.getResponsePayload();
        if (response == null || response.isBlank()) {
            return notReplayable(raw, "该报文没有银行原文（旧版留存或已按保留策略清理），无法重放");
        }
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getId, raw.getTaskId())
                .eq(BankDataSyncTask::getCompanyId, companyId));
        Long bankAccountId = task == null ? null : task.getBankAccountId();
        BankDataCollection replayed;
        try {
            replayed = switch (raw.getAdapterCode() == null ? "" : raw.getAdapterCode()) {
                case "CMB" -> replayCmb(raw, bankAccountId);
                case "CITIC" -> replayCitic(raw, bankAccountId);
                default -> null;
            };
        } catch (RuntimeException e) {
            return notReplayable(raw, "银行原文无法用当前解析规则重放：" + e.getMessage());
        }
        if (replayed == null) {
            return notReplayable(raw, "适配器 " + raw.getAdapterCode() + " 暂无重放映射");
        }
        // The stored view carries the canonical status (BankDataStatus), not the bank's raw
        // code — replay must normalize the same way or every diff would be a false positive.
        String normalizedStatus = BankDataStatus.fromVendor(
                replayed.status() == null || replayed.status().isBlank()
                        ? replayed.bankStatusCode() : replayed.status()).name();
        BankDataCollection canonical = new BankDataCollection(replayed.bankRequestNo(),
                BankCanonicalizer.canonicalEntries(replayed.entries()),
                BankCanonicalizer.canonicalBalances(replayed.balances()),
                replayed.hasMore(), replayed.nextCursor(), replayed.bankStatusCode(), normalizedStatus,
                replayed.pageTotals());
        String replayedPayload;
        try {
            replayedPayload = objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new BusinessException(500, "Replayed view cannot be serialized");
        }
        List<String> differences = new ArrayList<>();
        try {
            diff(objectMapper.readTree(raw.getPayload()), objectMapper.readTree(replayedPayload),
                    "", differences);
        } catch (Exception e) {
            differences.add("stored or replayed view is not valid JSON: " + e.getMessage());
        }
        int storedCount = entryCount(raw.getPayload());
        return new BankRawReplayResponse(raw.getId(), raw.getAdapterCode(), raw.getMappingVersion(),
                true, differences.isEmpty(), storedCount,
                canonical.entries() == null ? 0 : canonical.entries().size(),
                List.copyOf(differences), replayedPayload, LocalDateTime.now());
    }

    private BankRawReplayResponse notReplayable(BankDataRawMessage raw, String reason) {
        return new BankRawReplayResponse(raw.getId(), raw.getAdapterCode(), raw.getMappingVersion(),
                false, false, 0, 0, List.of(reason), null, LocalDateTime.now());
    }

    /**
     * CMB: the primary response is the statement page; a page-1 collect also carried an
     * NTQADINF balance snapshot, stored in the request evidence's auxiliary exchange —
     * replay it too so the reconstructed view has the same shape as the stored one.
     */
    private BankDataCollection replayCmb(BankDataRawMessage raw, Long bankAccountId) {
        BankDataCollection statement = CmbRowMapper.replayStatementPage(raw.getResponsePayload(),
                bankAccountId, raw.getBankRequestNo());
        AuxExchange aux = auxiliaryExchange(raw);
        if (aux == null || aux.response() == null || aux.response().isBlank()) {
            return statement;
        }
        List<BankDataBalanceEntry> balances = CmbRowMapper.replayBalance(aux.response(), bankAccountId,
                raw.getBankRequestNo());
        return new BankDataCollection(statement.bankRequestNo(), statement.entries(), balances,
                statement.hasMore(), statement.nextCursor(), statement.bankStatusCode(), statement.status(),
                statement.pageTotals());
    }

    private BankDataCollection replayCitic(BankDataRawMessage raw, Long bankAccountId) {
        BankDataCollection statement = CiticRowMapper.replayStatementPage(raw.getResponsePayload(),
                bankAccountId, raw.getBankRequestNo());
        AuxExchange aux = auxiliaryExchange(raw);
        if (aux == null || aux.response() == null || aux.response().isBlank()) {
            return statement;
        }
        List<BankDataBalanceEntry> balances = CiticRowMapper.replayBalance(aux.response(), bankAccountId,
                raw.getBankRequestNo());
        return new BankDataCollection(statement.bankRequestNo(), statement.entries(), balances,
                statement.hasMore(), statement.nextCursor(), statement.bankStatusCode(), statement.status(),
                statement.pageTotals());
    }

    private record AuxExchange(String funcode, String response) {
    }

    /** Reads the auxiliary exchange response out of the stored request-evidence JSON. */
    private AuxExchange auxiliaryExchange(BankDataRawMessage raw) {
        String evidence = raw.getRequestEvidence();
        if (evidence == null || evidence.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(evidence);
            JsonNode aux = node.get("auxiliary");
            if (aux == null || aux.isNull()) {
                return null;
            }
            JsonNode response = aux.get("response");
            String responseText = response == null || response.isNull() ? null : response.asText();
            JsonNode funcode = aux.get("funcode");
            return new AuxExchange(funcode == null || funcode.isNull() ? null : funcode.asText(), responseText);
        } catch (Exception e) {
            return null;
        }
    }

    private int entryCount(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode entries = node.get("entries");
            return entries == null || !entries.isArray() ? 0 : entries.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Path-style structural diff; empty-string paths are impossible (root is an object). */
    private void diff(JsonNode stored, JsonNode replayed, String path, List<String> out) {
        if (out.size() >= MAX_DIFFERENCES) {
            return;
        }
        if (isVolatile(path)) {
            return;
        }
        if (stored == null) stored = objectMapper.nullNode();
        if (replayed == null) replayed = objectMapper.nullNode();
        if (stored.equals(replayed)) {
            return;
        }
        if (stored.isObject() && replayed.isObject()) {
            diffObjects(stored, replayed, path, out);
        } else if (stored.isArray() && replayed.isArray()) {
            diffArrays(stored, replayed, path, out);
        } else {
            out.add(path + ": " + summarize(stored) + " -> " + summarize(replayed));
        }
    }

    private void diffObjects(JsonNode stored, JsonNode replayed, String path, List<String> out) {
        List<String> names = new ArrayList<>();
        stored.fieldNames().forEachRemaining(names::add);
        replayed.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) names.add(name);
        });
        for (String name : names) {
            if (out.size() >= MAX_DIFFERENCES) {
                return;
            }
            diff(stored.get(name), replayed.get(name),
                    path + "/" + name, out);
        }
    }

    private void diffArrays(JsonNode stored, JsonNode replayed, String path, List<String> out) {
        if (stored.size() != replayed.size()) {
            out.add(path + ": entry count " + stored.size() + " -> " + replayed.size());
        }
        int common = Math.min(stored.size(), replayed.size());
        for (int i = 0; i < common; i++) {
            if (out.size() >= MAX_DIFFERENCES) {
                return;
            }
            diff(stored.get(i), replayed.get(i), path + "[" + i + "]", out);
        }
    }

    /**
     * Volatile paths never mark a diff: balance as-of timestamps move with wall-clock,
     * and pagination cursors/flags can depend on runtime page-size configuration rather
     * than on the bank data itself.
     */
    private boolean isVolatile(String path) {
        return path.startsWith("/balances[") && (path.endsWith("/asOfTime") || path.endsWith("/bankRequestNo"))
                || path.equals("/nextCursor") || path.equals("/hasMore")
                || path.startsWith("/balances") && path.contains("/asOfTime");
    }

    private String summarize(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isValueNode()) return node.asText();
        return node.getNodeType().name().toLowerCase();
    }
}
