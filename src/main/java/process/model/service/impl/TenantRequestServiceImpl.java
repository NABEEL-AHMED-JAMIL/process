package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.emailer.EmailMessagesFactory;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.pojo.TenantRequest;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.repository.TenantRequestRepository;
import process.security.TenantContext;
import process.util.ProcessUtil;
import process.util.TemporaryPassword;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static process.util.ProcessUtil.ERROR_MESSAGE;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Requests for a workspace, and what happens when one is granted.
 *
 * Submitting is open to anyone, so nothing here trusts the contents of a request: it creates a
 * record and no more. The tenant, its first administrator and that account's password come into
 * existence only when a platform administrator approves, which is the point at which a person
 * has vouched for the claim.
 *
 * @author Nabeel Ahmed
 */
@Service
public class TenantRequestServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(TenantRequestServiceImpl.class);
    private static final String ERROR = ERROR_MESSAGE;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");
    /** Long enough that guessing is pointless in the short window before it is spent. */
    private static final int GENERATED_PASSWORD_LENGTH = 16;
    /**
     * No I, l, 1, O or 0. The password is read off a screen and typed by hand, and a character
     * someone mistypes is indistinguishable from a wrong password.
     */
    private static final String PASSWORD_ALPHABET =
        "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final TenantRequestRepository tenantRequestRepository;
    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailMessagesFactory emailMessagesFactory;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.console.url:http://localhost:4400}")
    private String consoleUrl;

    public TenantRequestServiceImpl(TenantRequestRepository tenantRequestRepository,
        TenantRepository tenantRepository, AppUserRepository appUserRepository,
        PasswordEncoder passwordEncoder, EmailMessagesFactory emailMessagesFactory) {
        this.tenantRequestRepository = tenantRequestRepository;
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailMessagesFactory = emailMessagesFactory;
    }

    /**
     * Records a request. Open to anyone, so it deliberately says the same thing whether or not
     * the address is already known -- otherwise the form answers "does this person have an
     * account here?" for anybody who asks.
     */
    public ResponseDto submit(TenantRequest submitted) {
        if (submitted == null) {
            return new ResponseDto(ERROR, "Nothing to submit.");
        }
        if (isBlank(submitted.getOrganisationName())) {
            return new ResponseDto(ERROR, "Tell us the name of your organisation.");
        }
        if (isBlank(submitted.getContactName())) {
            return new ResponseDto(ERROR, "Tell us your name.");
        }
        String email = safe(submitted.getContactEmail()).toLowerCase(Locale.ROOT);
        if (email.isEmpty() || !EMAIL.matcher(email).matches()) {
            return new ResponseDto(ERROR, "That does not look like an email address.");
        }

        String acknowledgement = "Thank you. Your request has been recorded, and you will hear "
            + "back at the address you gave.";

        // An address that already has an open request, or already has an account, gets the same
        // acknowledgement as a new one. Nothing is created in either case.
        if (this.tenantRequestRepository.findOpenByEmail(email).isPresent()
            || this.appUserRepository.findByUsernameAndStatusNot(email, Status.Delete).isPresent()) {
            return new ResponseDto(SUCCESS, acknowledgement);
        }

        TenantRequest request = new TenantRequest();
        request.setOrganisationName(safe(submitted.getOrganisationName()));
        request.setContactName(safe(submitted.getContactName()));
        request.setContactEmail(email);
        request.setPurpose(blankToNull(submitted.getPurpose()));
        request.setStatus("Pending");
        request.setDateCreated(new Timestamp(System.currentTimeMillis()));
        this.tenantRequestRepository.save(request);
        return new ResponseDto(SUCCESS, acknowledgement);
    }

    public ResponseDto listRequests() {
        List<TenantRequest> requests = this.tenantRequestRepository.findAllByOrderByTenantRequestIdDesc();
        return new ResponseDto(SUCCESS, String.format("%d request(s).", requests.size()), requests);
    }

    /**
     * Grants a request: creates the tenant, its first administrator, and a password that account
     * owes a replacement for. The password is generated here, sent once, and never returned to
     * the caller -- an administrator approving a request has no reason to see it, and a response
     * body is logged in more places than a mail body.
     */
    @Transactional
    public ResponseDto approve(Long tenantRequestId, String tenantCode) {
        if (ProcessUtil.isNull(tenantRequestId)) {
            return new ResponseDto(ERROR, "Request id missing.");
        }
        Optional<TenantRequest> found = this.tenantRequestRepository.findById(tenantRequestId);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, String.format("Request not found with %d.", tenantRequestId));
        }
        TenantRequest request = found.get();
        if (!"Pending".equals(request.getStatus())) {
            return new ResponseDto(ERROR, String.format("This request was already %s.",
                request.getStatus().toLowerCase(Locale.ROOT)));
        }

        String code = normaliseCode(isBlank(tenantCode) ? request.getOrganisationName() : tenantCode);
        if (code.isEmpty()) {
            return new ResponseDto(ERROR, "Give the tenant a code.");
        }
        if (this.tenantRepository.findByTenantCode(code).isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant code \"%s\" is already in use.", code));
        }
        // Checked again at approval rather than trusting the check made at submission: an account
        // may have been created for this address in between.
        if (this.appUserRepository.findByUsernameAndStatusNot(
                request.getContactEmail(), Status.Delete).isPresent()) {
            return new ResponseDto(ERROR, String.format(
                "There is already an account for %s.", request.getContactEmail()));
        }

        Tenant tenant = new Tenant();
        tenant.setUuid(UUID.randomUUID().toString());
        tenant.setTenantName(request.getOrganisationName());
        tenant.setTenantCode(code);
        tenant.setStatus(TenantStatus.Active);
        tenant.setDateCreated(new Timestamp(System.currentTimeMillis()));
        this.tenantRepository.save(tenant);

        String temporaryPassword = TemporaryPassword.generate();
        AppUser admin = new AppUser();
        admin.setUuid(UUID.randomUUID().toString());
        admin.setTenantId(tenant.getTenantId());
        admin.setUsername(request.getContactEmail());
        admin.setFullName(request.getContactName());
        admin.setUserRole(UserRole.TENANT_ADMIN);
        admin.setStatus(Status.Active);
        admin.setPassword(this.passwordEncoder.encode(temporaryPassword));
        admin.setMustChangePassword(true);
        admin.setDateCreated(new Timestamp(System.currentTimeMillis()));
        this.appUserRepository.save(admin);

        request.setStatus("Approved");
        request.setDecidedAt(new Timestamp(System.currentTimeMillis()));
        request.setDecidedBy(TenantContext.getAppUserId());
        request.setCreatedTenantId(tenant.getTenantId());
        request.setCreatedUserId(admin.getAppUserId());
        this.tenantRequestRepository.save(request);

        String mailResult = this.emailMessagesFactory.sendTenantWelcomeEmail(
            request.getContactEmail(), request.getContactName(), tenant.getTenantName(),
            admin.getUsername(), temporaryPassword, this.consoleUrl + "/login");

        if (mailResult != null && mailResult.startsWith("Error")) {
            // The tenant and the account exist either way, so say plainly that the credential did
            // not reach anyone rather than reporting success. It has to be reset by hand now:
            // this is the only moment the password was readable, and it is gone.
            logger.error("Tenant {} was created but its welcome email could not be sent.",
                tenant.getTenantId());
            return new ResponseDto(SUCCESS, String.format(
                "Tenant \"%s\" created, but the welcome email could not be sent. "
                + "Reset the password for %s and pass it on another way.",
                tenant.getTenantName(), admin.getUsername()));
        }
        return new ResponseDto(SUCCESS, String.format(
            "Tenant \"%s\" created. %s has been emailed how to sign in.",
            tenant.getTenantName(), admin.getUsername()));
    }

    public ResponseDto reject(Long tenantRequestId, String note) {
        if (ProcessUtil.isNull(tenantRequestId)) {
            return new ResponseDto(ERROR, "Request id missing.");
        }
        Optional<TenantRequest> found = this.tenantRequestRepository.findById(tenantRequestId);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, String.format("Request not found with %d.", tenantRequestId));
        }
        TenantRequest request = found.get();
        if (!"Pending".equals(request.getStatus())) {
            return new ResponseDto(ERROR, String.format("This request was already %s.",
                request.getStatus().toLowerCase(Locale.ROOT)));
        }
        request.setStatus("Rejected");
        request.setDecidedAt(new Timestamp(System.currentTimeMillis()));
        request.setDecidedBy(TenantContext.getAppUserId());
        request.setDecisionNote(blankToNull(note));
        this.tenantRequestRepository.save(request);
        return new ResponseDto(SUCCESS, "Request rejected.");
    }

    /** A tenant code from a company name: lower case, words joined by hyphens. */
    private static String normaliseCode(String value) {
        return safe(value).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String blankToNull(String value) { return isBlank(value) ? null : value.trim(); }
}
