package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import process.emailer.EmailMessagesFactory;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.pojo.TenantRequest;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.repository.TenantRequestRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * TenantRequestServiceImpl had no test coverage at all before this file -- not even a happy-path
 * test for submit/approve/reject, the three actions the entire Workspace Requests screen exists
 * to drive. Covers: the request-form validation, the "same acknowledgement either way" privacy
 * rule on submit, approve()'s tenant/admin creation and its own guard rails (already-decided,
 * duplicate code, existing account), the response it gives when the welcome email fails to send,
 * and reject()'s guard against deciding an already-decided request twice.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class TenantRequestServiceImplTest {

    @Mock private TenantRequestRepository tenantRequestRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailMessagesFactory emailMessagesFactory;

    private TenantRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new TenantRequestServiceImpl(this.tenantRequestRepository,
            this.tenantRepository, this.appUserRepository, this.passwordEncoder, this.emailMessagesFactory);
        ReflectionTestUtils.setField(this.service, "consoleUrl", "http://localhost:4400");
    }

    private static TenantRequest submitted(String org, String name, String email) {
        TenantRequest request = new TenantRequest();
        request.setOrganisationName(org);
        request.setContactName(name);
        request.setContactEmail(email);
        return request;
    }

    // ---- submit ---------------------------------------------------------------------------

    @Test
    void submitRejectsABlankOrganisationName() {
        ResponseDto response = this.service.submit(submitted("", "Jane", "jane@example.com"));
        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("organisation");
    }

    @Test
    void submitRejectsABlankContactName() {
        ResponseDto response = this.service.submit(submitted("Acme", "", "jane@example.com"));
        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("your name");
    }

    @Test
    void submitRejectsAnEmailThatDoesNotLookLikeOne() {
        ResponseDto response = this.service.submit(submitted("Acme", "Jane", "not-an-email"));
        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("email");
    }

    @Test
    void submitSavesAPendingRequestOnAFreshAddress() {
        when(this.tenantRequestRepository.findOpenByEmail("jane@example.com")).thenReturn(Optional.empty());
        when(this.appUserRepository.findByUsernameAndStatusNot("jane@example.com", Status.Delete))
            .thenReturn(Optional.empty());

        ResponseDto response = this.service.submit(submitted("Acme Corp", "Jane Doe", "Jane@Example.com"));

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        ArgumentCaptor<TenantRequest> saved = ArgumentCaptor.forClass(TenantRequest.class);
        verify(this.tenantRequestRepository).save(saved.capture());
        // Lower-cased before it is ever compared or stored, so the same address in a different
        // case doesn't slip past the open-request/existing-account checks above.
        assertThat(saved.getValue().getContactEmail()).isEqualTo("jane@example.com");
        assertThat(saved.getValue().getStatus()).isEqualTo("Pending");
        assertThat(saved.getValue().getOrganisationName()).isEqualTo("Acme Corp");
    }

    @Test
    void submitSavesNothingWhenTheAddressAlreadyHasAnOpenRequest() {
        when(this.tenantRequestRepository.findOpenByEmail("jane@example.com"))
            .thenReturn(Optional.of(new TenantRequest()));

        ResponseDto response = this.service.submit(submitted("Acme", "Jane", "jane@example.com"));

        // Same acknowledgement as a brand-new request -- the form must not answer "do you
        // already have a pending request?" to an unauthenticated caller.
        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        verify(this.tenantRequestRepository, never()).save(any());
    }

    @Test
    void submitSavesNothingWhenAnAccountAlreadyExistsForTheAddress() {
        when(this.tenantRequestRepository.findOpenByEmail("jane@example.com")).thenReturn(Optional.empty());
        when(this.appUserRepository.findByUsernameAndStatusNot("jane@example.com", Status.Delete))
            .thenReturn(Optional.of(new AppUser()));

        ResponseDto response = this.service.submit(submitted("Acme", "Jane", "jane@example.com"));

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        verify(this.tenantRequestRepository, never()).save(any());
    }

    // ---- approve ----------------------------------------------------------------------------

    private TenantRequest pendingRequest() {
        TenantRequest request = new TenantRequest();
        request.setTenantRequestId(9L);
        request.setOrganisationName("Acme Corp");
        request.setContactName("Jane Doe");
        request.setContactEmail("jane@example.com");
        request.setStatus("Pending");
        return request;
    }

    @Test
    void approveCreatesATenantAndItsFirstAdminOnAPendingRequest() {
        TenantRequest request = this.pendingRequest();
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));
        when(this.tenantRepository.findByTenantCode("acme-corp")).thenReturn(Optional.empty());
        when(this.appUserRepository.findByUsernameAndStatusNot("jane@example.com", Status.Delete))
            .thenReturn(Optional.empty());
        when(this.passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(this.emailMessagesFactory.sendTenantWelcomeEmail(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("Sent");

        ResponseDto response = this.service.approve(9L, null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        ArgumentCaptor<Tenant> tenant = ArgumentCaptor.forClass(Tenant.class);
        verify(this.tenantRepository).save(tenant.capture());
        assertThat(tenant.getValue().getTenantCode()).isEqualTo("acme-corp");
        assertThat(tenant.getValue().getTenantName()).isEqualTo("Acme Corp");

        ArgumentCaptor<AppUser> admin = ArgumentCaptor.forClass(AppUser.class);
        verify(this.appUserRepository).save(admin.capture());
        assertThat(admin.getValue().getUsername()).isEqualTo("jane@example.com");
        assertThat(admin.getValue().getUserRole().name()).isEqualTo("TENANT_ADMIN");
        // The generated password is never handed back to the caller -- only its encoded form
        // reaches the saved AppUser, and only the raw form reaches the email.
        assertThat(admin.getValue().getPassword()).isEqualTo("hashed");
        assertThat(admin.getValue().isMustChangePassword()).isTrue();

        assertThat(request.getStatus()).isEqualTo("Approved");
        assertThat(request.getDecidedAt()).isNotNull();
    }

    @Test
    void approveRefusesARequestThatWasAlreadyDecided() {
        TenantRequest request = this.pendingRequest();
        request.setStatus("Approved");
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));

        ResponseDto response = this.service.approve(9L, null);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("already");
        verify(this.tenantRepository, never()).save(any());
    }

    @Test
    void approveRefusesADuplicateTenantCode() {
        TenantRequest request = this.pendingRequest();
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));
        when(this.tenantRepository.findByTenantCode("acme-corp")).thenReturn(Optional.of(new Tenant()));

        ResponseDto response = this.service.approve(9L, null);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("already in use");
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void approveRefusesWhenAnAccountWasCreatedForTheAddressSinceSubmission() {
        TenantRequest request = this.pendingRequest();
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));
        when(this.tenantRepository.findByTenantCode("acme-corp")).thenReturn(Optional.empty());
        when(this.appUserRepository.findByUsernameAndStatusNot("jane@example.com", Status.Delete))
            .thenReturn(Optional.of(new AppUser()));

        ResponseDto response = this.service.approve(9L, null);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("already an account");
        verify(this.tenantRepository, never()).save(any());
    }

    @Test
    void approveStillSucceedsWhenTheWelcomeEmailFailsButSaysSoExplicitly() {
        TenantRequest request = this.pendingRequest();
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));
        when(this.tenantRepository.findByTenantCode("acme-corp")).thenReturn(Optional.empty());
        when(this.appUserRepository.findByUsernameAndStatusNot("jane@example.com", Status.Delete))
            .thenReturn(Optional.empty());
        lenient().when(this.passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(this.emailMessagesFactory.sendTenantWelcomeEmail(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("Error: SMTP timed out");

        ResponseDto response = this.service.approve(9L, null);

        // The tenant and admin are still created -- only the notification failed -- so this must
        // still be SUCCESS, but the message has to say the credential never reached anyone
        // rather than implying the new administrator already knows how to sign in.
        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getMessage()).contains("could not be sent");
        verify(this.tenantRepository).save(any());
        verify(this.appUserRepository).save(any());
    }

    @Test
    void approveUsesAnExplicitTenantCodeOverTheOrganisationName() {
        TenantRequest request = this.pendingRequest();
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));
        when(this.tenantRepository.findByTenantCode("acme-eu")).thenReturn(Optional.empty());
        when(this.appUserRepository.findByUsernameAndStatusNot("jane@example.com", Status.Delete))
            .thenReturn(Optional.empty());
        lenient().when(this.passwordEncoder.encode(anyString())).thenReturn("hashed");
        lenient().when(this.emailMessagesFactory.sendTenantWelcomeEmail(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("Sent");

        this.service.approve(9L, "Acme EU!!");

        ArgumentCaptor<Tenant> tenant = ArgumentCaptor.forClass(Tenant.class);
        verify(this.tenantRepository).save(tenant.capture());
        assertThat(tenant.getValue().getTenantCode()).isEqualTo("acme-eu");
    }

    // ---- reject -----------------------------------------------------------------------------

    @Test
    void rejectRecordsTheDecisionAndNoteOnAPendingRequest() {
        TenantRequest request = this.pendingRequest();
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));

        ResponseDto response = this.service.reject(9L, "Not enough detail");

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(request.getStatus()).isEqualTo("Rejected");
        assertThat(request.getDecisionNote()).isEqualTo("Not enough detail");
        assertThat(request.getDecidedAt()).isNotNull();
        verify(this.tenantRequestRepository).save(request);
    }

    @Test
    void rejectRefusesARequestThatWasAlreadyDecided() {
        TenantRequest request = this.pendingRequest();
        request.setStatus("Rejected");
        when(this.tenantRequestRepository.findById(9L)).thenReturn(Optional.of(request));

        ResponseDto response = this.service.reject(9L, "Second look");

        assertThat(response.getStatus()).isEqualTo(ERROR);
        verify(this.tenantRequestRepository, never()).save(any());
    }

    @Test
    void rejectReturnsAnErrorWhenTheRequestDoesNotExist() {
        when(this.tenantRequestRepository.findById(404L)).thenReturn(Optional.empty());

        ResponseDto response = this.service.reject(404L, null);

        assertThat(response.getStatus()).isEqualTo(ERROR);
    }
}
