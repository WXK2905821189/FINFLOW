package com.finance.system.closing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.audit.SystemAuditService;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.ClosingPeriod;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.ClosingPeriodMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.closing.dto.ClosingPeriodResponse;
import com.finance.system.rbac.RbacService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ClosingService {
    private static final DateTimeFormatter PERIOD=DateTimeFormatter.ofPattern("yyyy-MM");
    /**
     * 流水域的校验/推送状态字面量必须与 {@code StatementService} 保持一致：
     * 校验通过是 {@code PASSED}（不是 {@code VALID}，那是 bank_data_* 投影层的字面量），
     * 推送落库有两种拼写（历史路径 {@code PUSHED}、规则引擎路径 {@code GL_PUSHED}）。
     * 取错字面量会让每个账期的异常数等于全部流水数，账期恒为 BLOCKED、结账永远 409。
     */
    private static final String VALIDATION_PASSED="PASSED";
    private final ClosingPeriodMapper periodMapper; private final StatementRecordMapper statementMapper; private final CompanyScopeService scope; private final SystemAuditService audit; private final RbacService rbacService;
    public ClosingService(ClosingPeriodMapper periodMapper, StatementRecordMapper statementMapper, CompanyScopeService scope, SystemAuditService audit, RbacService rbacService){this.periodMapper=periodMapper;this.statementMapper=statementMapper;this.scope=scope;this.audit=audit;this.rbacService=rbacService;}
    public PageResponse<ClosingPeriodResponse> list(Long userId,int page,int size,String status){long c=scope.companyIdForUser(userId); List<ClosingPeriod> all=periodMapper.selectList(new LambdaQueryWrapper<ClosingPeriod>().eq(ClosingPeriod::getCompanyId,c).eq(status!=null&&!status.isBlank(),ClosingPeriod::getStatus,status).orderByDesc(ClosingPeriod::getPeriod)); int from=Math.min((Math.max(1,page)-1)*Math.min(100,Math.max(1,size)),all.size()); int to=Math.min(from+Math.min(100,Math.max(1,size)),all.size()); return new PageResponse<>(page,size,all.size(),all.subList(from,to).stream().map(this::response).toList());}
    @Transactional public ClosingPeriodResponse check(Long userId,String period,String requestId){long c=scope.companyIdForUser(userId); YearMonth ym=parse(period); ClosingPeriod p=find(c,period); if(p==null){p=new ClosingPeriod();p.setCompanyId(c);p.setPeriod(period);p.setCreatedAt(LocalDateTime.now());} if("CLOSED".equalsIgnoreCase(p.getStatus())) return response(p); refresh(p,ym); p.setRequestId(request(requestId)); p.setUpdatedAt(LocalDateTime.now()); if(p.getId()==null)periodMapper.insert(p);else periodMapper.updateById(p); audit.record(userId,"CHECK_CLOSING","CLOSING_PERIOD",period,p.getRequestId(),"SUCCESS",p.getStatus()+" blockers="+p.getPendingCount()+"/"+p.getExceptionCount()+"/"+p.getUnpostedCount()); return response(p);}
    @Transactional public ClosingPeriodResponse close(Long userId,String period,String requestId){ClosingPeriodResponse checked=check(userId,period,requestId); if(!"READY".equals(checked.status())) throw new BusinessException(409,"账期存在未处理流水、异常或未制证记录，不能结账"); long c=scope.companyIdForUser(userId); ClosingPeriod p=find(c,period); p.setStatus("CLOSED");p.setConfirmedBy(userId);p.setConfirmedAt(LocalDateTime.now());p.setUpdatedAt(LocalDateTime.now());p.setRequestId(request(requestId));periodMapper.updateById(p);audit.record(userId,"CLOSE_PERIOD","CLOSING_PERIOD",period,p.getRequestId(),"SUCCESS","账期已结账");return response(p);}

    /**
     * W7（2026-09-18）：解锁已结账账期（CLOSED→READY），仅超管可操作，可逆并留审计。
     * 结账后如需补录/修正流水（原实现 CLOSE 后不可逆，业务只能等下一个账期），超管解锁后
     * 账期回到 READY；解锁不重算指标（check 会按当前流水重算），下次 check 仍可能回到 BLOCKED/CLOSED 前状态。
     */
    @Transactional public ClosingPeriodResponse unlock(Long userId,String period,String requestId){
        boolean isAdmin=rbacService.rolesForUser(userId).stream()
                .anyMatch(r->"ADMIN".equals(r.getCode()));
        if(!isAdmin) throw new BusinessException(403,"仅超级管理员可解锁已结账账期");
        long c=scope.companyIdForUser(userId); ClosingPeriod p=find(c,period);
        if(p==null) throw new BusinessException(404,"账期不存在，请先执行账期检查生成记录");
        if(!"CLOSED".equalsIgnoreCase(p.getStatus())) throw new BusinessException(409,"仅已结账（CLOSED）账期可解锁，当前状态："+p.getStatus());
        p.setStatus("READY");p.setUpdatedAt(LocalDateTime.now());p.setRequestId(request(requestId));periodMapper.updateById(p);
        audit.record(userId,"UNLOCK_PERIOD","CLOSING_PERIOD",period,p.getRequestId(),"SUCCESS","账期已解锁（CLOSED→READY）");
        return response(p);
    }

    /**
     * W7 账期锁：CLOSED 账期拦截业务写（导入/转入/AI 制证/推送）。
     * 按<b>流水所属公司</b>的账期判断（跨公司用户写他司流水时用他司账期），无记录视为开放。
     * 注意状态字面量：CLOSED 是 closing 域自有状态（READY/BLOCKED/CLOSED），与其他域不同名不同义。
     */
    public void ensurePeriodOpen(long companyId,String period){
        ClosingPeriod p=find(companyId,period);
        if(p!=null&&"CLOSED".equalsIgnoreCase(p.getStatus())){
            throw new BusinessException(409,"账期 "+period+" 已结账（CLOSED），请先在结账管理中解锁账期后再操作");
        }
    }
    public void ensurePeriodOpen(long companyId,LocalDateTime transactionTime){
        if(transactionTime==null) return;
        ensurePeriodOpen(companyId,YearMonth.from(transactionTime).format(PERIOD));
    }
    /** 批量版：一次 in 查询覆盖批次内全部涉及月份，命中任一 CLOSED 即 409（一次报清，避免逐条查询）。 */
    public void ensurePeriodsOpen(long companyId,Collection<LocalDateTime> transactionTimes){
        List<String> periods=transactionTimes.stream().filter(Objects::nonNull)
                .map(t->YearMonth.from(t).format(PERIOD)).distinct().sorted().toList();
        if(periods.isEmpty()) return;
        List<ClosingPeriod> closed=periodMapper.selectList(new LambdaQueryWrapper<ClosingPeriod>()
                .eq(ClosingPeriod::getCompanyId,companyId)
                .eq(ClosingPeriod::getStatus,"CLOSED")
                .in(ClosingPeriod::getPeriod,periods));
        if(!closed.isEmpty()){
            String hit=closed.stream().map(ClosingPeriod::getPeriod).distinct().sorted().collect(Collectors.joining("、"));
            throw new BusinessException(409,"账期 "+hit+" 已结账（CLOSED），请先在结账管理中解锁账期后再操作");
        }
    }
    private void refresh(ClosingPeriod p,YearMonth ym){LocalDateTime start=ym.atDay(1).atStartOfDay(),end=ym.plusMonths(1).atDay(1).atStartOfDay(); List<StatementRecord> records=statementMapper.selectList(new LambdaQueryWrapper<StatementRecord>().eq(StatementRecord::getCompanyId,p.getCompanyId()).ge(StatementRecord::getTransactionTime,start).lt(StatementRecord::getTransactionTime,end)); int pending=0,exception=0,unposted=0; for(StatementRecord s:records){boolean validated=VALIDATION_PASSED.equalsIgnoreCase(s.getValidationStatus());boolean pushed="PUSHED".equalsIgnoreCase(s.getPushStatus())||"GL_PUSHED".equalsIgnoreCase(s.getPushStatus());if("PENDING".equalsIgnoreCase(s.getReviewStatus()))pending++;if(!validated)exception++;if(validated&&"APPROVED".equalsIgnoreCase(s.getReviewStatus())&&!pushed)unposted++;}p.setTotalCount(records.size());p.setPendingCount(pending);p.setExceptionCount(exception);p.setUnpostedCount(unposted);p.setStatus(pending==0&&exception==0&&unposted==0?"READY":"BLOCKED");}
    private ClosingPeriod find(long c,String period){return periodMapper.selectOne(new LambdaQueryWrapper<ClosingPeriod>().eq(ClosingPeriod::getCompanyId,c).eq(ClosingPeriod::getPeriod,period));}
    private YearMonth parse(String value){try{return YearMonth.parse(value,PERIOD);}catch(DateTimeParseException e){throw new BusinessException(400,"period must use yyyy-MM format");}}
    private String request(String r){return r==null||r.isBlank()?java.util.UUID.randomUUID().toString():r.length()>64?r.substring(0,64):r;}
    private ClosingPeriodResponse response(ClosingPeriod p){return new ClosingPeriodResponse(p.getId(),p.getPeriod(),p.getStatus(),n(p.getTotalCount()),n(p.getPendingCount()),n(p.getExceptionCount()),n(p.getUnpostedCount()),p.getConfirmedBy(),p.getConfirmedAt(),p.getRequestId(),p.getNote(),p.getUpdatedAt());}
    private int n(Integer v){return v==null?0:v;}
}
