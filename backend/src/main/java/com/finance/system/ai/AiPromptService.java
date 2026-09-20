package com.finance.system.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.ai.dto.AiPromptView;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.AiPromptOverride;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.AiPromptOverrideMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 提示词配置（V38，W9 需求 4）：系统提示词的「覆盖读取 + 超管编辑 + 一键重置」。
 *
 * <p>读取口径（{@link #resolve}）：覆盖行存在且内容非空 → 用覆盖值；否则回落代码内默认。
 * 每次真实 AI 调用前都会走到 {@code resolve}（最多一次单行索引查询，相对 LLM 往返可忽略），
 * 保存即时生效、无需重启。</p>
 *
 * <p>编辑权限 {@code ai:config}（仅超管）在 Controller 强制；写操作记系统审计
 * （AI_PROMPT_SAVE / AI_PROMPT_RESET）。能力 key 必须在 {@link AiPromptCatalog} 登记。</p>
 */
@Service
public class AiPromptService {

    private static final Logger log = LoggerFactory.getLogger(AiPromptService.class);

    /** 覆盖提示词长度上限（与表格偏好同量级；正常提示词几 KB 内）。 */
    private static final int MAX_PROMPT_LENGTH = 20000;

    private final AiPromptOverrideMapper mapper;
    private final SysUserMapper userMapper;

    public AiPromptService(AiPromptOverrideMapper mapper, SysUserMapper userMapper) {
        this.mapper = mapper;
        this.userMapper = userMapper;
    }

    /** 当前生效的系统提示词：覆盖优先，无覆盖回落目录默认。 */
    public String resolve(String capability) {
        AiPromptCatalog.Entry entry = AiPromptCatalog.find(capability);
        if (entry == null) {
            throw new BusinessException(400, "未登记的 AI 能力：" + capability);
        }
        AiPromptOverride row = mapper.selectOne(new LambdaQueryWrapper<AiPromptOverride>()
                .eq(AiPromptOverride::getCapability, capability));
        if (row != null && row.getSystemPrompt() != null && !row.getSystemPrompt().isBlank()) {
            return row.getSystemPrompt();
        }
        return entry.defaultPrompt().get();
    }

    /** 全目录视图（AI 设置页 / 入口弹窗共用）。 */
    public List<AiPromptView> list() {
        Map<String, AiPromptOverride> overrides = mapper.selectList(null).stream()
                .collect(Collectors.toMap(AiPromptOverride::getCapability, Function.identity()));
        return AiPromptCatalog.ALL.stream().map(entry -> {
            AiPromptOverride row = overrides.get(entry.capability());
            boolean customized = row != null && row.getSystemPrompt() != null && !row.getSystemPrompt().isBlank();
            String editorName = customized ? usernameById(row.getUpdatedBy()) : null;
            return new AiPromptView(entry.capability(), entry.name(), entry.description(),
                    customized ? row.getSystemPrompt() : entry.defaultPrompt().get(),
                    customized,
                    entry.defaultPrompt().get(),
                    editorName,
                    customized ? row.getUpdatedAt() : null);
        }).toList();
    }

    /** 保存覆盖（upsert by capability）；prompt 非空且 ≤ 上限。 */
    public AiPromptView upsert(String capability, String prompt, Long operatorId) {
        AiPromptCatalog.Entry entry = requireEntry(capability);
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(400, "提示词不能为空（如需恢复默认请使用「重置」）");
        }
        String trimmed = prompt.strip();
        if (trimmed.length() > MAX_PROMPT_LENGTH) {
            throw new BusinessException(400, "提示词过长（上限 " + MAX_PROMPT_LENGTH + " 字符，当前 " + trimmed.length() + "）");
        }
        AiPromptOverride row = mapper.selectOne(new LambdaQueryWrapper<AiPromptOverride>()
                .eq(AiPromptOverride::getCapability, capability));
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new AiPromptOverride();
            row.setCapability(capability);
            row.setSystemPrompt(trimmed);
            row.setUpdatedBy(operatorId);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            mapper.insert(row);
        } else {
            row.setSystemPrompt(trimmed);
            row.setUpdatedBy(operatorId);
            row.setUpdatedAt(now);
            mapper.updateById(row);
        }
        log.info("AI 提示词已覆盖：capability={}, operator={}, length={}", capability, operatorId, trimmed.length());
        return view(entry, mapper.selectOne(new LambdaQueryWrapper<AiPromptOverride>()
                .eq(AiPromptOverride::getCapability, capability)));
    }

    /** 重置 = 删除覆盖行，回落系统默认；无覆盖行时 404。 */
    public void reset(String capability, Long operatorId) {
        requireEntry(capability);
        AiPromptOverride row = mapper.selectOne(new LambdaQueryWrapper<AiPromptOverride>()
                .eq(AiPromptOverride::getCapability, capability));
        if (row == null) {
            throw new BusinessException(404, "该能力没有自定义提示词，无需重置");
        }
        mapper.deleteById(row.getId());
        log.info("AI 提示词已重置为默认：capability={}, operator={}", capability, operatorId);
    }

    private AiPromptCatalog.Entry requireEntry(String capability) {
        AiPromptCatalog.Entry entry = AiPromptCatalog.find(capability);
        if (entry == null) {
            throw new BusinessException(400, "未登记的 AI 能力：" + capability);
        }
        return entry;
    }

    private AiPromptView view(AiPromptCatalog.Entry entry, AiPromptOverride row) {
        boolean customized = row != null && row.getSystemPrompt() != null && !row.getSystemPrompt().isBlank();
        return new AiPromptView(entry.capability(), entry.name(), entry.description(),
                customized ? row.getSystemPrompt() : entry.defaultPrompt().get(),
                customized,
                entry.defaultPrompt().get(),
                customized ? usernameById(row.getUpdatedBy()) : null,
                customized ? row.getUpdatedAt() : null);
    }

    private String usernameById(Long userId) {
        if (userId == null) return null;
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }
}
