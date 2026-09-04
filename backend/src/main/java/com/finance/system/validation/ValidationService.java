package com.finance.system.validation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.audit.SystemAuditService;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.AccountingMapping;
import com.finance.system.domain.entity.ValidationRule;
import com.finance.system.domain.mapper.AccountingMappingMapper;
import com.finance.system.domain.mapper.ValidationRuleMapper;
import com.finance.system.validation.dto.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class ValidationService {
    private final ValidationRuleMapper ruleMapper; private final AccountingMappingMapper mappingMapper;
    private final CompanyScopeService scope; private final SystemAuditService audit;
    public ValidationService(ValidationRuleMapper ruleMapper, AccountingMappingMapper mappingMapper, CompanyScopeService scope, SystemAuditService audit) {
        this.ruleMapper=ruleMapper; this.mappingMapper=mappingMapper; this.scope=scope; this.audit=audit;
    }
    public PageResponse<ValidationRuleResponse> rules(Long userId, int page, int size, String status) {
        long company=scope.companyIdForUser(userId); Page<ValidationRule> p=ruleMapper.selectPage(new Page<>(Math.max(1,page), Math.min(100,Math.max(1,size))),
                new LambdaQueryWrapper<ValidationRule>().eq(ValidationRule::getCompanyId,company).eq(status!=null&&!status.isBlank(),ValidationRule::getStatus,status).orderByAsc(ValidationRule::getPriority).orderByDesc(ValidationRule::getId));
        return new PageResponse<>(p.getCurrent(),p.getSize(),p.getTotal(),p.getRecords().stream().map(this::rule).toList());
    }
    public PageResponse<AccountingMappingResponse> mappings(Long userId, int page, int size, String status) {
        long company=scope.companyIdForUser(userId); Page<AccountingMapping> p=mappingMapper.selectPage(new Page<>(Math.max(1,page), Math.min(100,Math.max(1,size))),
                new LambdaQueryWrapper<AccountingMapping>().eq(AccountingMapping::getCompanyId,company).eq(status!=null&&!status.isBlank(),AccountingMapping::getStatus,status).orderByDesc(AccountingMapping::getUpdatedAt).orderByDesc(AccountingMapping::getId));
        return new PageResponse<>(p.getCurrent(),p.getSize(),p.getTotal(),p.getRecords().stream().map(this::mapping).toList());
    }
    @Transactional public ValidationRuleResponse createRule(Long userId, ValidationRuleRequest request, String requestId) {
        long company=scope.companyIdForUser(userId); int version=nextRuleVersion(company,request.ruleCode()); ValidationRule rule=new ValidationRule(); rule.setCompanyId(company);
        rule.setRuleCode(request.ruleCode().trim()); rule.setName(request.name().trim()); rule.setRuleType(request.ruleType().trim().toUpperCase(Locale.ROOT)); rule.setExpression(request.expression().trim());
        rule.setVersionNo(version); rule.setPriority(request.priority()==null?100:request.priority()); rule.setStatus("DRAFT"); rule.setCreatedBy(userId); rule.setCreatedAt(LocalDateTime.now()); rule.setUpdatedAt(LocalDateTime.now());
        try { ruleMapper.insert(rule); } catch(DuplicateKeyException e){ throw new BusinessException(409,"Rule version already exists"); } audit.record(userId,"CREATE_RULE","VALIDATION_RULE",String.valueOf(rule.getId()),requestId,"SUCCESS",rule.getRuleCode()+" v"+version); return rule(rule);
    }
    @Transactional public ValidationRuleResponse activateRule(Long userId, Long id, String requestId) {
        long company=scope.companyIdForUser(userId); ValidationRule rule=ruleMapper.selectOne(new LambdaQueryWrapper<ValidationRule>().eq(ValidationRule::getId,id).eq(ValidationRule::getCompanyId,company)); if(rule==null) throw new BusinessException(404,"Validation rule not found");
        ruleMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ValidationRule>().set(ValidationRule::getStatus,"INACTIVE").eq(ValidationRule::getCompanyId,company).eq(ValidationRule::getRuleCode,rule.getRuleCode()).eq(ValidationRule::getStatus,"ACTIVE"));
        rule.setStatus("ACTIVE"); rule.setUpdatedAt(LocalDateTime.now()); ruleMapper.updateById(rule); audit.record(userId,"ACTIVATE_RULE","VALIDATION_RULE",String.valueOf(id),requestId,"SUCCESS",rule.getRuleCode()); return rule(rule);
    }
    @Transactional public AccountingMappingResponse createMapping(Long userId, AccountingMappingRequest request, String requestId) {
        long company=scope.companyIdForUser(userId); int version=mappingMapper.selectList(new LambdaQueryWrapper<AccountingMapping>().eq(AccountingMapping::getCompanyId,company).eq(AccountingMapping::getMappingCode,request.mappingCode())).stream().mapToInt(m->m.getVersionNo()==null?0:m.getVersionNo()).max().orElse(0)+1;
        String direction=request.direction().trim().toUpperCase(Locale.ROOT); if(!direction.equals("INCOME")&&!direction.equals("EXPENSE")&&!direction.equals("BOTH")) throw new BusinessException(400,"direction must be INCOME, EXPENSE or BOTH");
        AccountingMapping m=new AccountingMapping(); m.setCompanyId(company); m.setMappingCode(request.mappingCode().trim()); m.setName(request.name().trim()); m.setDirection(direction); m.setCounterpartyKeyword(blank(request.counterpartyKeyword())); m.setDebitSubject(request.debitSubject().trim()); m.setCreditSubject(request.creditSubject().trim()); m.setVoucherTemplate(request.voucherTemplate().trim()); m.setVersionNo(version); m.setStatus("DRAFT"); m.setCreatedBy(userId); m.setCreatedAt(LocalDateTime.now()); m.setUpdatedAt(LocalDateTime.now());
        try { mappingMapper.insert(m); } catch(DuplicateKeyException e){ throw new BusinessException(409,"Mapping version already exists"); } audit.record(userId,"CREATE_MAPPING","ACCOUNTING_MAPPING",String.valueOf(m.getId()),requestId,"SUCCESS",m.getMappingCode()+" v"+version); return mapping(m);
    }
    @Transactional public AccountingMappingResponse activateMapping(Long userId, Long id, String requestId) {
        long company=scope.companyIdForUser(userId); AccountingMapping m=mappingMapper.selectOne(new LambdaQueryWrapper<AccountingMapping>().eq(AccountingMapping::getId,id).eq(AccountingMapping::getCompanyId,company)); if(m==null) throw new BusinessException(404,"Accounting mapping not found");
        mappingMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AccountingMapping>().set(AccountingMapping::getStatus,"INACTIVE").eq(AccountingMapping::getCompanyId,company).eq(AccountingMapping::getMappingCode,m.getMappingCode()).eq(AccountingMapping::getStatus,"ACTIVE")); m.setStatus("ACTIVE"); m.setUpdatedAt(LocalDateTime.now()); mappingMapper.updateById(m); audit.record(userId,"ACTIVATE_MAPPING","ACCOUNTING_MAPPING",String.valueOf(id),requestId,"SUCCESS",m.getMappingCode()); return mapping(m);
    }
    private int nextRuleVersion(long c,String code){return ruleMapper.selectList(new LambdaQueryWrapper<ValidationRule>().eq(ValidationRule::getCompanyId,c).eq(ValidationRule::getRuleCode,code)).stream().mapToInt(r->r.getVersionNo()==null?0:r.getVersionNo()).max().orElse(0)+1;}
    private String blank(String v){return v==null||v.isBlank()?null:v.trim();}
    private ValidationRuleResponse rule(ValidationRule r){return new ValidationRuleResponse(r.getId(),r.getRuleCode(),r.getName(),r.getRuleType(),r.getExpression(),r.getVersionNo(),r.getStatus(),r.getPriority(),r.getCreatedBy(),r.getUpdatedAt());}
    private AccountingMappingResponse mapping(AccountingMapping m){return new AccountingMappingResponse(m.getId(),m.getMappingCode(),m.getName(),m.getDirection(),m.getCounterpartyKeyword(),m.getDebitSubject(),m.getCreditSubject(),m.getVoucherTemplate(),m.getVersionNo(),m.getStatus(),m.getUpdatedAt());}
}
