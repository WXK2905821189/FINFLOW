package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.bank.dto.BankTransferRequest;
import com.finance.system.bank.dto.BankTransferResponse;
import com.finance.system.bank.dto.PaymentResolutionRequest;
import com.finance.system.bank.dto.PaymentTransferAuditResponse;
import com.finance.system.bankdata.scope.CompanyScopeService;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.PaymentTransfer;
import com.finance.system.domain.entity.PaymentTransferAuditEvent;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.PaymentTransferAuditEventMapper;
import com.finance.system.domain.mapper.PaymentTransferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Stateful transfer workflow. Only the adapter owns any future balance mutation. */
@Service
public class PaymentTransferService {

    private static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    private static final String APPROVED = "APPROVED";
    private static final String EXECUTING = "EXECUTING";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String FAILED = "FAILED";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String UNKNOWN_MESSAGE = "External outcome is unknown; reconciliation is required before any retry";
    private static final Set<String> PAYMENT_STATUSES = Set.of(
            PENDING_APPROVAL, APPROVED, EXECUTING, SUBMITTED, FAILED, UNKNOWN);

    private final CompanyScopeService companyScope;
    private final PaymentTransferMapper paymentMapper;
    private final PaymentTransferAuditEventMapper auditMapper;
    private final BankAccountMapper accountMapper;
    private final BankServiceFactory bankServiceFactory;

    public PaymentTransferService(CompanyScopeService companyScope, PaymentTransferMapper paymentMapper,
                                  PaymentTransferAuditEventMapper auditMapper, BankAccountMapper accountMapper,
                                  BankServiceFactory bankServiceFactory) {
        this.companyScope = companyScope;
        this.paymentMapper = paymentMapper;
        this.auditMapper = auditMapper;
        this.accountMapper = accountMapper;
        this.bankServiceFactory = bankServiceFactory;
    }

    @Transactional
    public BankTransferResponse create(Long userId, BankTransferRequest request, String idempotencyKey,
                                       String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        String key = requireIdempotencyKey(idempotencyKey);
        String operationRequestId = requestId(requestId);
        PaymentTransfer existing = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentTransfer>()
                .eq(PaymentTransfer::getCompanyId, companyId)
                .eq(PaymentTransfer::getIdempotencyKey, key));
        if (existing != null) {
            if (!sameRequest(existing, request)) {
                throw new BusinessException(409, "Idempotency key was already used for a different transfer");
            }
            return response(existing);
        }
        BankAccount payer = account(companyId, request.payerAccountId());
        if (!"ACTIVE".equalsIgnoreCase(payer.getStatus())) throw new BusinessException(409, "Payer account is not active");
        if (!payer.getBankCode().equalsIgnoreCase(request.bankCode())) {
            throw new BusinessException(400, "Payer account does not belong to the selected bank");
        }
        PaymentTransfer payment = new PaymentTransfer();
        payment.setCompanyId(companyId);
        payment.setPaymentNo("PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT));
        payment.setIdempotencyKey(key);
        payment.setRequestId(operationRequestId);
        payment.setPayerAccountId(payer.getId());
        payment.setBankCode(payer.getBankCode());
        payment.setPayeeName(request.payeeName().trim());
        payment.setPayeeAccount(request.payeeAccount().trim());
        payment.setPayeeAccountMasked(mask(request.payeeAccount()));
        payment.setPayeeBank(request.payeeBank().trim());
        payment.setAmount(request.amount().setScale(2));
        payment.setCurrency(payer.getCurrency());
        payment.setRemark(request.remark().trim());
        payment.setStatus(PENDING_APPROVAL);
        payment.setCreatedBy(userId);
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException exception) {
            PaymentTransfer concurrent = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentTransfer>()
                    .eq(PaymentTransfer::getCompanyId, companyId)
                    .eq(PaymentTransfer::getIdempotencyKey, key));
            if (concurrent != null && sameRequest(concurrent, request)) return response(concurrent);
            throw new BusinessException(409, "Idempotency key was already used for a different transfer");
        }
        audit(payment, operationRequestId, "CREATE", null, PENDING_APPROVAL, userId,
                "Transfer application created; no bank call or balance change");
        return response(payment);
    }

    @Transactional
    public BankTransferResponse approve(Long userId, Long paymentId, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        String operationRequestId = requestId(requestId);
        PaymentTransfer payment = require(paymentId, companyId);
        if (payment.getCreatedBy().equals(userId)) throw new BusinessException(403, "Creators cannot approve their own transfers");
        int updated = paymentMapper.update(null, new LambdaUpdateWrapper<PaymentTransfer>()
                .set(PaymentTransfer::getStatus, APPROVED)
                .set(PaymentTransfer::getApprovedBy, userId)
                .set(PaymentTransfer::getApprovedAt, LocalDateTime.now())
                .eq(PaymentTransfer::getId, paymentId).eq(PaymentTransfer::getCompanyId, companyId)
                .eq(PaymentTransfer::getStatus, PENDING_APPROVAL));
        if (updated != 1) throw new BusinessException(409, "Transfer is not awaiting approval");
        PaymentTransfer approved = require(paymentId, companyId);
        audit(approved, operationRequestId, "APPROVE", PENDING_APPROVAL, APPROVED, userId, null);
        return response(approved);
    }

    @Transactional
    public BankTransferResponse execute(Long userId, Long paymentId, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        String operationRequestId = requestId(requestId);
        PaymentTransfer payment = require(paymentId, companyId);
        if (!APPROVED.equals(payment.getStatus())) throw new BusinessException(409, "Only approved transfers can be executed");
        BankAccount payer = account(companyId, payment.getPayerAccountId());
        if (payer.getAvailableBalance().compareTo(payment.getAmount()) < 0) {
            throw new BusinessException(409, "Available balance is insufficient");
        }
        int claimed = paymentMapper.update(null, new LambdaUpdateWrapper<PaymentTransfer>()
                .set(PaymentTransfer::getStatus, EXECUTING)
                .set(PaymentTransfer::getExecutedBy, userId)
                .set(PaymentTransfer::getExecutedAt, LocalDateTime.now())
                .eq(PaymentTransfer::getId, paymentId).eq(PaymentTransfer::getCompanyId, companyId)
                .eq(PaymentTransfer::getStatus, APPROVED));
        if (claimed != 1) throw new BusinessException(409, "Transfer execution is already in progress or has completed");
        try {
            BankTransferResponse bankResult = bankServiceFactory.get(payment.getBankCode()).submitTransfer(payer,
                    new BankTransferCommand(payment.getPaymentNo(), payment.getPayeeName(), payment.getPayeeAccount(),
                            payment.getPayeeBank(), payment.getAmount(), payment.getRemark()));
            String next = accepted(bankResult.status()) ? SUBMITTED : FAILED;
            paymentMapper.update(null, new LambdaUpdateWrapper<PaymentTransfer>()
                    .set(PaymentTransfer::getStatus, next)
                    .set(PaymentTransfer::getExternalReference, bankResult.bankReference())
                    .set(PaymentTransfer::getExternalStatus, bankResult.status())
                    .set(PaymentTransfer::getErrorMessage, next.equals(FAILED) ? safe(bankResult.message()) : null)
                    .eq(PaymentTransfer::getId, paymentId).eq(PaymentTransfer::getCompanyId, companyId)
                    .eq(PaymentTransfer::getStatus, EXECUTING));
            PaymentTransfer result = require(paymentId, companyId);
            audit(result, operationRequestId, "EXECUTE", EXECUTING, next, userId, bankResult.message());
            return response(result);
        } catch (RuntimeException exception) {
            paymentMapper.update(null, new LambdaUpdateWrapper<PaymentTransfer>()
                    .set(PaymentTransfer::getStatus, UNKNOWN)
                    .set(PaymentTransfer::getExternalStatus, UNKNOWN)
                    .set(PaymentTransfer::getErrorMessage, UNKNOWN_MESSAGE)
                    .eq(PaymentTransfer::getId, paymentId).eq(PaymentTransfer::getCompanyId, companyId)
                    .eq(PaymentTransfer::getStatus, EXECUTING));
            PaymentTransfer result = require(paymentId, companyId);
            audit(result, operationRequestId, "EXECUTE", EXECUTING, UNKNOWN, userId, UNKNOWN_MESSAGE);
            return response(result);
        }
    }

    public List<BankTransferResponse> list(Long userId, String status) {
        long companyId = companyScope.companyIdForUser(userId);
        String normalizedStatus = normalizeStatus(status);
        return paymentMapper.selectList(new LambdaQueryWrapper<PaymentTransfer>()
                        .eq(PaymentTransfer::getCompanyId, companyId)
                        .eq(normalizedStatus != null, PaymentTransfer::getStatus, normalizedStatus)
                        .orderByDesc(PaymentTransfer::getCreatedAt)
                        .orderByDesc(PaymentTransfer::getId))
                .stream().map(this::response).toList();
    }

    public List<PaymentTransferAuditResponse> auditTrail(Long userId, Long paymentId) {
        long companyId = companyScope.companyIdForUser(userId);
        require(paymentId, companyId);
        return auditMapper.selectList(new LambdaQueryWrapper<PaymentTransferAuditEvent>()
                        .eq(PaymentTransferAuditEvent::getCompanyId, companyId)
                        .eq(PaymentTransferAuditEvent::getPaymentId, paymentId)
                        .orderByAsc(PaymentTransferAuditEvent::getCreatedAt)
                        .orderByAsc(PaymentTransferAuditEvent::getId))
                .stream().map(this::auditResponse).toList();
    }

    @Transactional
    public BankTransferResponse resolveUnknown(Long userId, Long paymentId, PaymentResolutionRequest request,
                                               String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        String operationRequestId = requestId(requestId);
        PaymentTransfer payment = require(paymentId, companyId);
        if (!UNKNOWN.equals(payment.getStatus())) {
            throw new BusinessException(409, "Only UNKNOWN transfers can be resolved manually");
        }
        if (payment.getCreatedBy().equals(userId) || userId.equals(payment.getExecutedBy())) {
            throw new BusinessException(403, "Creators and executors cannot resolve unknown transfers");
        }

        String action = request.action().trim().toUpperCase(Locale.ROOT);
        String nextStatus;
        if ("CONFIRM_SUBMITTED".equals(action)) {
            if (request.externalReference() == null || request.externalReference().isBlank()) {
                throw new BusinessException(400, "External reference is required for a submitted resolution");
            }
            nextStatus = SUBMITTED;
        } else if ("CONFIRM_FAILED".equals(action)) {
            nextStatus = FAILED;
        } else {
            throw new BusinessException(400, "Resolution action must be CONFIRM_SUBMITTED or CONFIRM_FAILED");
        }

        LocalDateTime resolvedAt = LocalDateTime.now();
        String comment = safe(request.comment().trim());
        int updated = paymentMapper.update(null, new LambdaUpdateWrapper<PaymentTransfer>()
                .set(PaymentTransfer::getStatus, nextStatus)
                .set(PaymentTransfer::getExternalStatus, "MANUALLY_CONFIRMED")
                .set("CONFIRM_SUBMITTED".equals(action), PaymentTransfer::getExternalReference,
                        request.externalReference() == null ? null : request.externalReference().trim())
                .set(PaymentTransfer::getErrorMessage, FAILED.equals(nextStatus) ? comment : null)
                .set(PaymentTransfer::getResolvedBy, userId)
                .set(PaymentTransfer::getResolvedAt, resolvedAt)
                .set(PaymentTransfer::getResolutionComment, comment)
                .eq(PaymentTransfer::getId, paymentId)
                .eq(PaymentTransfer::getCompanyId, companyId)
                .eq(PaymentTransfer::getStatus, UNKNOWN));
        if (updated != 1) throw new BusinessException(409, "Transfer resolution status has changed");
        PaymentTransfer resolved = require(paymentId, companyId);
        audit(resolved, operationRequestId, "RESOLVE_UNKNOWN", UNKNOWN, nextStatus, userId,
                action + ": " + comment);
        return response(resolved);
    }

    public BankTransferResponse get(Long userId, Long paymentId) {
        return response(require(paymentId, companyScope.companyIdForUser(userId)));
    }

    private PaymentTransfer require(Long id, long companyId) {
        PaymentTransfer payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentTransfer>()
                .eq(PaymentTransfer::getId, id).eq(PaymentTransfer::getCompanyId, companyId));
        if (payment == null) throw new BusinessException(404, "Transfer not found");
        return payment;
    }

    private BankAccount account(long companyId, Long accountId) {
        BankAccount account = accountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, accountId).eq(BankAccount::getCompanyId, companyId));
        if (account == null) throw new BusinessException(404, "Payer account not found");
        return account;
    }

    private void audit(PaymentTransfer payment, String requestId, String action, String previous, String current,
                       Long operator, String detail) {
        PaymentTransferAuditEvent event = new PaymentTransferAuditEvent();
        event.setCompanyId(payment.getCompanyId()); event.setPaymentId(payment.getId()); event.setRequestId(requestId);
        event.setAction(action);
        event.setPreviousStatus(previous); event.setCurrentStatus(current); event.setOperatorId(operator); event.setDetail(safe(detail));
        auditMapper.insert(event);
    }

    private BankTransferResponse response(PaymentTransfer payment) {
        String message = payment.getErrorMessage() == null ? "Transfer application " + payment.getStatus() : payment.getErrorMessage();
        return new BankTransferResponse(payment.getId(), payment.getPaymentNo(), payment.getBankCode(),
                payment.getExternalReference(), payment.getStatus(), message);
    }

    private PaymentTransferAuditResponse auditResponse(PaymentTransferAuditEvent event) {
        return new PaymentTransferAuditResponse(event.getId(), event.getRequestId(), event.getAction(),
                event.getPreviousStatus(), event.getCurrentStatus(), event.getOperatorId(), event.getDetail(),
                event.getCreatedAt());
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(400, "Idempotency-Key header is required");
        String key = value.trim();
        if (key.length() > 96) throw new BusinessException(400, "Idempotency-Key is too long");
        return key;
    }

    private String requestId(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String requestId = value.trim();
        if (requestId.length() > 64 || !requestId.matches("[A-Za-z0-9._:-]+")) {
            throw new BusinessException(400, "X-Request-Id format is invalid");
        }
        return requestId;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!PAYMENT_STATUSES.contains(normalized)) throw new BusinessException(400, "Transfer status is invalid");
        return normalized;
    }

    private boolean sameRequest(PaymentTransfer payment, BankTransferRequest request) {
        return payment.getPayerAccountId().equals(request.payerAccountId())
                && payment.getBankCode().equalsIgnoreCase(request.bankCode())
                && payment.getAmount().compareTo(request.amount()) == 0
                && payment.getPayeeName().equals(request.payeeName().trim())
                && payment.getPayeeAccount().equals(request.payeeAccount().trim())
                && payment.getPayeeBank().equals(request.payeeBank().trim())
                && payment.getRemark().equals(request.remark().trim());
    }

    private boolean accepted(String status) { return "ACCEPTED".equalsIgnoreCase(status) || "SUBMITTED".equalsIgnoreCase(status); }
    private String mask(String value) { String account = value.trim(); return account.length() <= 4 ? "****" : "****" + account.substring(account.length() - 4); }
    private String safe(String value) {
        if (value == null) return null;
        String sanitized = value
                .replaceAll("(?i)(password|secret|token|authorization|private[_ -]?key)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]")
                .replaceAll("(?<!\\d)\\d{8,}(?!\\d)", "****");
        return sanitized.substring(0, Math.min(500, sanitized.length()));
    }
}
