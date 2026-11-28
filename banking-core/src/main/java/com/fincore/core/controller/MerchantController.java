package com.fincore.core.controller;

import com.fincore.core.dto.ChangeMemberRoleRequest;
import com.fincore.core.dto.ChangePlanRequest;
import com.fincore.core.dto.CreateTeamInviteRequest;
import com.fincore.core.dto.JoinTeamRequest;
import com.fincore.core.dto.MerchantQrResponse;
import com.fincore.core.dto.MerchantRoleResponse;
import com.fincore.core.dto.OutletPaymentResponse;
import com.fincore.core.dto.TeamInvitePreviewResponse;
import com.fincore.core.dto.TeamInviteResponse;
import com.fincore.core.dto.TeamMemberResponse;
import com.fincore.core.dto.TeamResponse;
import com.fincore.core.dto.CreateInviteRequest;
import com.fincore.core.dto.InvitePreviewResponse;
import com.fincore.core.dto.InviteResponse;
import com.fincore.core.dto.JoinFranchiseRequest;
import com.fincore.core.dto.MerchantLookupResponse;
import com.fincore.core.dto.MerchantPayRequest;
import com.fincore.core.dto.MerchantPaymentResponse;
import com.fincore.core.dto.MerchantPlanResponse;
import com.fincore.core.dto.MerchantReceiptResponse;
import com.fincore.core.dto.MerchantResponse;
import com.fincore.core.dto.NetworkResponse;
import com.fincore.core.dto.OutletResponse;
import com.fincore.core.dto.RegisterMerchantRequest;
import com.fincore.core.dto.SettlementResponse;
import com.fincore.core.entity.MerchantPlan;
import com.fincore.core.entity.MerchantRole;
import com.fincore.core.service.MerchantService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import com.fincore.common.exception.FincoreException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
@PreAuthorize("hasRole('CUSTOMER')")
public class MerchantController {

    private final MerchantService service;

    public MerchantController(MerchantService service) {
        this.service = service;
    }

    // ---- as a paying customer ----------------------------------------------------------------

    /**
     * Public (no sign-in). A link that carries an amount must carry its signature too, otherwise it
     * has been edited and is refused; a valid one comes back with the amount locked.
     */
    @GetMapping("/lookup/{merchantCode}")
    public MerchantLookupResponse lookup(
            @PathVariable String merchantCode,
            @RequestParam(required = false) BigDecimal amt,
            @RequestParam(required = false) String sig) {
        var merchant = service.lookup(merchantCode);
        if (amt == null) {
            return MerchantLookupResponse.from(merchant);
        }
        if (sig == null || sig.isBlank()) {
            throw FincoreException.badRequest(
                    "INVALID_PAYMENT_LINK", "This payment link is not valid");
        }
        service.assertLinkAmount(merchantCode, amt, sig);
        return MerchantLookupResponse.from(merchant, amt);
    }

    @PostMapping("/{merchantCode}/pay")
    public MerchantReceiptResponse pay(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String merchantCode,
            @Valid @RequestBody MerchantPayRequest r) {
        return MerchantReceiptResponse.from(
                service.pay(
                        user.userId(),
                        merchantCode,
                        r.accountNumber(),
                        r.amount(),
                        r.note(),
                        r.backupAccountNumbers(),
                        r.sig()));
    }

    // ---- becoming a merchant -----------------------------------------------------------------

    @GetMapping("/plans")
    public List<MerchantPlanResponse> plans() {
        return Arrays.stream(MerchantPlan.values()).map(MerchantPlanResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse register(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RegisterMerchantRequest r) {
        service.register(user.userId(), r.businessName(), r.category());
        return MerchantResponse.from(service.overview(user.userId()));
    }

    @GetMapping("/invites/{inviteCode}")
    public InvitePreviewResponse previewInvite(@PathVariable String inviteCode) {
        MerchantService.InvitePreview p = service.previewInvite(inviteCode);
        return new InvitePreviewResponse(
                p.parent().getBusinessName(),
                p.parent().getCategory(),
                p.invite().getRoyaltyPercent(),
                p.invite().getNote(),
                p.invite().getExpiresAt());
    }

    @PostMapping("/join")
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse join(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody JoinFranchiseRequest r) {
        service.join(user.userId(), r.inviteCode(), r.businessName());
        return MerchantResponse.from(service.overview(user.userId()));
    }

    @GetMapping("/roles")
    public List<MerchantRoleResponse> roles() {
        return Arrays.stream(MerchantRole.values()).map(MerchantRoleResponse::from).toList();
    }

    // ---- joining a merchant's team -----------------------------------------------------------

    @GetMapping("/team/invites/{inviteCode}")
    public TeamInvitePreviewResponse previewTeamInvite(@PathVariable String inviteCode) {
        MerchantService.TeamInvitePreview p = service.previewTeamInvite(inviteCode);
        return new TeamInvitePreviewResponse(
                p.merchant().getBusinessName(),
                p.invite().getRole(),
                p.invite().getRole().displayName(),
                p.invite().getRole().permissions().stream().map(Enum::name).sorted().toList(),
                p.invite().getExpiresAt());
    }

    @PostMapping("/team/join")
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse joinTeam(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody JoinTeamRequest r) {
        service.joinTeam(user.userId(), r.inviteCode());
        return MerchantResponse.from(service.overview(user.userId()));
    }

    // ---- my merchant account -----------------------------------------------------------------

    @GetMapping("/me")
    public MerchantResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return MerchantResponse.from(service.overview(user.userId()));
    }

    @PutMapping("/me/plan")
    public MerchantResponse changePlan(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChangePlanRequest r) {
        service.changePlan(user.userId(), r.plan());
        return MerchantResponse.from(service.overview(user.userId()));
    }

    /** Payment QR: standing (no amount) or fixed-amount for one bill. */
    @GetMapping("/me/qr")
    public MerchantQrResponse qr(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) String note) {
        MerchantService.Qr q = service.qr(user.userId(), amount, note);
        return new MerchantQrResponse(
                q.merchant().getReference(),
                q.merchant().getBusinessName(),
                q.amount(),
                q.note(),
                q.payload(),
                q.svg());
    }

    @GetMapping("/me/payments")
    public List<MerchantPaymentResponse> payments(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.payments(user.userId()).stream()
                .map(MerchantPaymentResponse::from)
                .toList();
    }

    @PostMapping("/me/payments/{reference}/refund")
    public MerchantPaymentResponse refund(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return MerchantPaymentResponse.from(service.refund(user.userId(), reference));
    }

    @GetMapping("/me/settlements")
    public List<SettlementResponse> settlements(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.settlements(user.userId()).stream().map(SettlementResponse::from).toList();
    }

    @PostMapping("/me/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse settleNow(@AuthenticationPrincipal AuthenticatedUser user) {
        return SettlementResponse.from(service.settleNow(user.userId()));
    }

    // ---- franchise network (main merchants) --------------------------------------------------

    @GetMapping("/me/network")
    public NetworkResponse network(@AuthenticationPrincipal AuthenticatedUser user) {
        MerchantService.Network n = service.network(user.userId());
        return new NetworkResponse(
                n.totalRoyalty(),
                n.maxOutlets(),
                n.outlets().stream().map(OutletResponse::from).toList(),
                n.invites().stream().map(InviteResponse::from).toList());
    }

    @PostMapping("/me/network/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse createInvite(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateInviteRequest r) {
        return InviteResponse.from(
                service.createInvite(user.userId(), r.royaltyPercent(), r.note()));
    }

    @DeleteMapping("/me/network/invites/{inviteCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelInvite(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String inviteCode) {
        service.cancelInvite(user.userId(), inviteCode);
    }

    @PostMapping("/me/network/outlets/{merchantCode}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String merchantCode) {
        service.setOutletSuspended(user.userId(), merchantCode, true);
    }

    @PostMapping("/me/network/outlets/{merchantCode}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String merchantCode) {
        service.setOutletSuspended(user.userId(), merchantCode, false);
    }

    @GetMapping("/me/network/outlets/{merchantCode}/payments")
    public List<OutletPaymentResponse> outletPayments(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String merchantCode) {
        return service.outletPayments(user.userId(), merchantCode).stream()
                .map(OutletPaymentResponse::from)
                .toList();
    }

    // ---- team (owner only) -------------------------------------------------------------------

    @GetMapping("/me/team")
    public TeamResponse team(@AuthenticationPrincipal AuthenticatedUser user) {
        MerchantService.TeamView t = service.team(user.userId());
        return new TeamResponse(
                t.maxMembers(),
                t.members().stream().map(v -> TeamMemberResponse.from(v.member())).toList(),
                t.invites().stream().map(TeamInviteResponse::from).toList());
    }

    @PostMapping("/me/team/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamInviteResponse createTeamInvite(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateTeamInviteRequest r) {
        return TeamInviteResponse.from(
                service.createTeamInvite(user.userId(), r.role(), r.label()));
    }

    @DeleteMapping("/me/team/invites/{inviteCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelTeamInvite(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String inviteCode) {
        service.cancelTeamInvite(user.userId(), inviteCode);
    }

    @PutMapping("/me/team/members/{memberCode}")
    public TeamMemberResponse changeRole(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String memberCode,
            @Valid @RequestBody ChangeMemberRoleRequest r) {
        return TeamMemberResponse.from(
                service.changeMemberRole(user.userId(), memberCode, r.role()));
    }

    @DeleteMapping("/me/team/members/{memberCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String memberCode) {
        service.removeMember(user.userId(), memberCode);
    }

    @PostMapping("/me/team/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthenticatedUser user) {
        service.leaveTeam(user.userId());
    }
}
