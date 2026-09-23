package com.finance.system.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.KingdeeVoucherRule;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.KingdeeVoucherRuleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W17 #1a 公司主体软删 + 引用检查（2026-09-23）。
 *
 * <p>契约：DELETE /api/bank-account-archive/companies/{id} 把 company.status 置为
 * INACTIVE（软删，不物理删除）；存在「活跃」引用时 409 并在 message 里列明占用项
 * （活跃账户 = 账户自身 status 非 INACTIVE、软删账户由 @TableLogic 排除不算占用；
 * ACTIVE 用户；银行流水 / 余额；启用中规则 scope 精确引用公司编码）。INACTIVE 主体
 * 禁作归属目标（assign 409）与新建账户挂靠目标（409）。软删后档案视图不再显示。</p>
 *
 * <p>与 {@link CompanyArchiveIntegrationTest} 同款约定：全部经 API 造数，不依赖 V1 种子
 * （共享 H2 被其他测试类改动，种子假设不可靠）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CompanyDeleteIntegrationTest {

    private static final String UNIQUE_SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CompanyMapper companyMapper;

    @Autowired
    private KingdeeVoucherRuleMapper ruleMapper;

    @Autowired
    private com.finance.system.domain.mapper.BankAccountMapper bankAccountMapper;

    /** 删除成功：status 落 INACTIVE（软删），重复删除 404（幂等边界），档案视图不再显示。 */
    @Test
    void deleteCompanySoftDeletesAndHidesFromView() throws Exception {
        String token = login();
        long companyId = createCompany(token, "软删主体-" + UNIQUE_SUFFIX);

        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        Company after = companyMapper.selectById(companyId);
        assertNotNull(after, "soft delete must keep the row");
        assertEquals("INACTIVE", after.getStatus(), "company.status must flip ACTIVE -> INACTIVE");

        // 已停用主体再删 → 404（不存在可删对象）
        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());

        // 档案视图只返回 ACTIVE 主体
        String view = mockMvc.perform(get("/api/bank-account-archive")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(!view.contains("软删主体-" + UNIQUE_SUFFIX),
                "INACTIVE company must vanish from the archive view");
    }

    /** 有活跃账户（status 非 INACTIVE）引用 → 409，message 列明占用项；移除账户后可删。 */
    @Test
    void deleteCompanyWithActiveAccountIsRejected409() throws Exception {
        String token = login();
        long companyId = createCompany(token, "占用主体-" + UNIQUE_SUFFIX);
        long accountId = createAccount(token, "占用账户-" + UNIQUE_SUFFIX);
        mockMvc.perform(put("/api/bank-account-archive/accounts/" + accountId + "/company")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":" + companyId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("活跃银行账户")));

        // 账户已 assign 到目标公司，admin（companyId=1）走 API 删会被公司数据隔离挡住（404）；
        // 测试目的只是清掉「活跃账户」引用 → 直接 mapper 软删（@TableLogic 自动排除）等价于档案移除
        bankAccountMapper.deleteById(accountId);
        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertEquals("INACTIVE", companyMapper.selectById(companyId).getStatus());
    }

    /** 启用中规则 scope 精确引用公司编码 → 409；停用规则后不再算占用。 */
    @Test
    void deleteCompanyWithScopedRuleIsRejected409() throws Exception {
        String token = login();
        long companyId = createCompany(token, "规则占用主体-" + UNIQUE_SUFFIX);
        String code = companyMapper.selectById(companyId).getCode();
        // 启用中规则 scope 引用该编码（另带一个前缀相近编码，验证精确分词不误伤判断的写入路径）
        KingdeeVoucherRule rule = new KingdeeVoucherRule();
        rule.setRuleNo(900000 + (Math.abs(UNIQUE_SUFFIX.hashCode()) % 90000));
        rule.setBusinessType("INCOME");
        rule.setCategory("测试");
        rule.setPriority(999);
        rule.setScopeOrgs(code + ",300");
        rule.setScopeBankChannels("");
        rule.setDirection("INCOME");
        rule.setMatchJson("{\"logic\":\"ALL\",\"conditions\":[]}");
        rule.setDebitLinesJson("[]");
        rule.setCreditLinesJson("[]");
        rule.setEnabled(true);
        ruleMapper.insert(rule);

        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("凭证规则")));

        // 停用规则 → 不再算「活跃」引用 → 可删
        rule.setEnabled(false);
        ruleMapper.updateById(rule);
        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    /** INACTIVE 主体禁作归类目标（assign 409）。 */
    @Test
    void assignIntoInactiveCompanyIsRejected409() throws Exception {
        String token = login();
        long companyId = createCompany(token, "停用归属主体-" + UNIQUE_SUFFIX);
        long accountId = createAccount(token, "停用归属账户-" + UNIQUE_SUFFIX);
        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/bank-account-archive/accounts/" + accountId + "/company")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":" + companyId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已停用")));
    }

    /** INACTIVE 主体禁挂新账户（create with companyId 409，跨公司路径）。 */
    @Test
    void createAccountUnderInactiveCompanyIsRejected409() throws Exception {
        String token = login();
        long companyId = createCompany(token, "禁挂主体-" + UNIQUE_SUFFIX);
        mockMvc.perform(delete("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // admin 带 cross-company 权限：显式指定已停用主体 → 必须被 409 拦下
        String body = "{\"bankCode\":\"CITIC\",\"accountName\":\"禁挂账户-" + UNIQUE_SUFFIX + "\","
                + "\"accountNumber\":\"6222" + UNIQUE_SUFFIX.replaceAll("\\D", "3") + "0009\","
                + "\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\","
                + "\"companyId\":" + companyId + "}";
        mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已停用")));
    }

    private long createCompany(String token, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/bank-account-archive/companies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data").get("id").asLong();
    }

    private long createAccount(String token, String accountName) throws Exception {
        String body = "{\"bankCode\":\"CITIC\",\"accountName\":\"" + accountName + "\","
                + "\"accountNumber\":\"6222" + UNIQUE_SUFFIX.replaceAll("\\D", "5") + "0007\","
                + "\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}";
        MvcResult result = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data").get("id").asLong();
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
