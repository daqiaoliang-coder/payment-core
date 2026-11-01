package com.example.paymentcore.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 阶段 4 本地验证入口：地域权威、恢复门禁、审批、受益人账户与安全审计。 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    public record AuthoritySwitchRequest(@NotBlank String scopeId, @NotBlank String region) {
    }

    public record LateEffectRequest(@NotBlank String scopeId, @NotBlank String region, @NotNull Integer epoch,
                                    @NotBlank String externalRef, String detail, @NotBlank String actor) {
    }

    public record GuardRequest(@NotBlank String guardKey, String reason, @NotBlank String actor) {
    }

    public record ApprovalRequest(@NotBlank String operation, @NotBlank String targetId,
                                  @NotBlank String requestedBy, String reason) {
    }

    public record ApprovalDecisionRequest(@NotBlank String approvedBy, String reason) {
    }

    public record BeneficiaryChangeRequest(@NotBlank String merchantId, @NotBlank String bankAccountRef,
                                           @NotBlank String requestedBy, String approvalId) {
    }

    private final AuthorityService authority;
    private final RecoveryGuardService guard;
    private final ApprovalService approvals;
    private final BeneficiaryService beneficiaries;
    private final SecurityAuditService audit;

    public SecurityController(AuthorityService authority, RecoveryGuardService guard,
                              ApprovalService approvals, BeneficiaryService beneficiaries,
                              SecurityAuditService audit) {
        this.authority = authority;
        this.guard = guard;
        this.approvals = approvals;
        this.beneficiaries = beneficiaries;
        this.audit = audit;
    }

    @PostMapping("/authority/switch")
    public AuthorityService.ScopeState switchAuthority(@Valid @RequestBody AuthoritySwitchRequest request) {
        return authority.switchTo(request.scopeId(), request.region());
    }

    @GetMapping("/authority/{scopeId}")
    public AuthorityService.ScopeState authority(@PathVariable String scopeId) {
        return authority.current(scopeId);
    }

    @PostMapping("/late-effects")
    public AuthorityService.LateEffectView recordLateEffect(@Valid @RequestBody LateEffectRequest request) {
        return authority.recordLateEffect(request.scopeId(), request.region(), request.epoch(),
                request.externalRef(), request.detail(), request.actor());
    }

    @GetMapping("/late-effects")
    public List<AuthorityService.LateEffectView> lateEffects(@RequestParam String scopeId) {
        return authority.lateEffects(scopeId);
    }

    @PostMapping("/guards/pause")
    public RecoveryGuardService.GuardView pause(@Valid @RequestBody GuardRequest request) {
        return guard.pause(request.guardKey(), request.reason(), request.actor());
    }

    @PostMapping("/guards/resume")
    public RecoveryGuardService.GuardView resume(@Valid @RequestBody GuardRequest request) {
        return guard.resume(request.guardKey(), request.actor());
    }

    @GetMapping("/guards/{guardKey}")
    public RecoveryGuardService.GuardView guardState(@PathVariable String guardKey) {
        return guard.state(guardKey);
    }

    @PostMapping("/approvals")
    public ApprovalService.ApprovalView requestApproval(@Valid @RequestBody ApprovalRequest request) {
        return approvals.request(request.operation(), request.targetId(), request.requestedBy(),
                request.reason());
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public ApprovalService.ApprovalView approve(@PathVariable String approvalId,
                                                @Valid @RequestBody ApprovalDecisionRequest request) {
        return approvals.approve(approvalId, request.approvedBy(), request.reason());
    }

    @GetMapping("/approvals/{approvalId}")
    public ApprovalService.ApprovalView approval(@PathVariable String approvalId) {
        return approvals.get(approvalId);
    }

    @PostMapping("/beneficiaries")
    public BeneficiaryService.BeneficiaryView changeBeneficiary(
            @Valid @RequestBody BeneficiaryChangeRequest request) {
        return beneficiaries.change(request.merchantId(), request.bankAccountRef(), request.requestedBy(),
                request.approvalId());
    }

    @GetMapping("/beneficiaries/{merchantId}")
    public BeneficiaryService.BeneficiaryView beneficiary(@PathVariable String merchantId) {
        return beneficiaries.get(merchantId);
    }

    @GetMapping("/audits")
    public List<SecurityAuditService.AuditView> audits(@RequestParam(required = false) String targetId,
                                                       @RequestParam(required = false) String action) {
        if (targetId != null && !targetId.isBlank()) {
            return audit.findByTarget(targetId);
        }
        return audit.findByAction(action == null ? "" : action);
    }
}
