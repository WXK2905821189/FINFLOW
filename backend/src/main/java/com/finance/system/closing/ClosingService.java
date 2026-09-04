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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class ClosingService {
    private static final DateTimeFormatter PERIOD=DateTimeFormatter.ofPattern("yyyy-MM");
    private final ClosingPeriodMapper periodMapper; private final StatementRecordMapper statementMapper; private final CompanyScopeService scope; private final SystemAuditService audit;
    public ClosingService(ClosingPeriodMapper periodMapper, StatementRecordMapper statementMapper, CompanyScopeService scope, SystemAuditService audit){this.periodMapper=periodMapper;this.statementMapper=statementMapper;this.scope=scope;this.audit=audit;}
    public PageResponse<ClosingPeriodResponse> list(Long userId,int page,int size,String status){long c=scope.companyIdForUser(userId); List<ClosingPeriod> all=periodMapper.selectList(new LambdaQueryWrapper<ClosingPeriod>().eq(ClosingPeriod::getCompanyId,c).eq(status!=null&&!status.isBlank(),ClosingPeriod::getStatus,status).orderByDesc(ClosingPeriod::getPeriod)); int from=Math.min((Math.max(1,page)-1)*Math.min(100,Math.max(1,size)),all.size()); int to=Math.min(from+Math.min(100,Math.max(1,size)),all.size()); return new PageResponse<>(page,size,all.size(),all.subList(from,to).stream().map(this::response).toList());}
    @Transactional public ClosingPeriodResponse check(Long userId,String period,String requestId){long c=scope.companyIdForUser(userId); YearMonth ym=parse(period); ClosingPeriod p=find(c,period); if(p==null){p=new ClosingPeriod();p.setCompanyId(c);p.setPeriod(period);p.setCreatedAt(LocalDateTime.now());} if("CLOSED".equalsIgnoreCase(p.getStatus())) return response(p); refresh(p,ym); p.setRequestId(request(requestId)); p.setUpdatedAt(LocalDateTime.now()); if(p.getId()==null)periodMapper.insert(p);else periodMapper.updateById(p); audit.record(userId,"CHECK_CLOSING","CLOSING_PERIOD",period,p.getRequestId(),"SUCCESS",p.getStatus()+" blockers="+p.getPendingCount()+"/"+p.getExceptionCount()+"/"+p.getUnpostedCount()); return response(p);}
    @Transactional public ClosingPeriodResponse close(Long userId,String period,String requestId){ClosingPeriodResponse checked=check(userId,period,requestId); if(!"READY".equals(checked.status())) throw new BusinessException(409,"账期存在未处理流水、异常或未制证记录，不能结账"); long c=scope.companyIdForUser(userId); ClosingPeriod p=find(c,period); p.setStatus("CLOSED");p.setConfirmedBy(userId);p.setConfirmedAt(LocalDateTime.now());p.setUpdatedAt(LocalDateTime.now());p.setRequestId(request(requestId));periodMapper.updateById(p);audit.record(userId,"CLOSE_PERIOD","CLOSING_PERIOD",period,p.getRequestId(),"SUCCESS","账期已结账");return response(p);}
    private void refresh(ClosingPeriod p,YearMonth ym){LocalDateTime start=ym.atDay(1).atStartOfDay(),end=ym.plusMonths(1).atDay(1).atStartOfDay(); List<StatementRecord> records=statementMapper.selectList(new LambdaQueryWrapper<StatementRecord>().eq(StatementRecord::getCompanyId,p.getCompanyId()).ge(StatementRecord::getTransactionTime,start).lt(StatementRecord::getTransactionTime,end)); int pending=0,exception=0,unposted=0; for(StatementRecord s:records){if("PENDING".equalsIgnoreCase(s.getReviewStatus()))pending++;if(!"VALID".equalsIgnoreCase(s.getValidationStatus()))exception++;if("VALID".equalsIgnoreCase(s.getValidationStatus())&&"APPROVED".equalsIgnoreCase(s.getReviewStatus())&&!"PUSHED".equalsIgnoreCase(s.getPushStatus()))unposted++;}p.setTotalCount(records.size());p.setPendingCount(pending);p.setExceptionCount(exception);p.setUnpostedCount(unposted);p.setStatus(pending==0&&exception==0&&unposted==0?"READY":"BLOCKED");}
    private ClosingPeriod find(long c,String period){return periodMapper.selectOne(new LambdaQueryWrapper<ClosingPeriod>().eq(ClosingPeriod::getCompanyId,c).eq(ClosingPeriod::getPeriod,period));}
    private YearMonth parse(String value){try{return YearMonth.parse(value,PERIOD);}catch(DateTimeParseException e){throw new BusinessException(400,"period must use yyyy-MM format");}}
    private String request(String r){return r==null||r.isBlank()?java.util.UUID.randomUUID().toString():r.length()>64?r.substring(0,64):r;}
    private ClosingPeriodResponse response(ClosingPeriod p){return new ClosingPeriodResponse(p.getId(),p.getPeriod(),p.getStatus(),n(p.getTotalCount()),n(p.getPendingCount()),n(p.getExceptionCount()),n(p.getUnpostedCount()),p.getConfirmedBy(),p.getConfirmedAt(),p.getRequestId(),p.getNote(),p.getUpdatedAt());}
    private int n(Integer v){return v==null?0:v;}
}
