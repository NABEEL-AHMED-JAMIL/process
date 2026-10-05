package process.forms;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.dto.ResponseDto;
import process.ScratchPostgres;
import process.security.TenantContext;
import org.barco.platform.tenancy.RowSecurity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-278 security tests: a form shared by link -- the workspace's switch, expired, used, revoked, tampered and unknown
 * links, sign-in links, the ticket (too fast, too old, another link's, forged, replayed), the honeypot, rate limits, and
 * forms that may not be shared. Links against a scratch PostgreSQL (V198); Redis counted in memory.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres). The links run on the application's own
 * connection (process_app, row security on), as in production: a visitor's work found no row there once (live check).
 */
class FormShareLinksPostgresTest {

    static final long A = 8881L;
    static final long B = 8882L;
    static final long FORM = 888101L;
    static final long LOOKUP_FORM = 888102L;

    private static ScratchPostgres db;
    private static JdbcTemplate sql;

    private FormShareLinks links;
    private FormStore store;
    private FormSubmissionService submissions;
    private StringRedisTemplate redis;
    private final Map<String, Long> counters = new HashMap<>();
    private Instant now = Instant.parse("2026-10-05T15:00:00Z");
    private PublicForms forms;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("form_share_links");
        sql = db.jdbc();
        for (long tenant : new long[] {A, B}) {
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant, "T" + tenant);
        }
        sql.update("INSERT INTO form_definition (form_id, tenant_id, name, status) VALUES (?, ?, 'Visitor check-in', 'Active'), "
            + "(?, ?, 'Looks up', 'Active')", FORM, A, LOOKUP_FORM, A);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sql.update("DELETE FROM form_share_link");
        sql.update("DELETE FROM form_share_policy");
        this.links = new FormShareLinks(db.appJdbc());
        this.policy(true);
        this.store = mock(FormStore.class);
        when(this.store.find(eq(A), eq(FORM))).thenReturn(Optional.of(form(FORM, "text")));
        when(this.store.find(eq(A), eq(LOOKUP_FORM))).thenReturn(Optional.of(form(LOOKUP_FORM, FormFields.LOOKUP)));
        this.submissions = mock(FormSubmissionService.class);
        // What Core answers a member: the submission, its job and run. A visitor hears none of it.
        when(this.submissions.receiveAs(anyLong(), any(), any(), any(), anyString(), any(), any()))
            .thenReturn(new ResponseDto("SUCCESS", "Submitted: run 77 started.", Collections.singletonMap("jobQueueId", 77L)));
        this.redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(this.redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(call -> this.counters.merge(call.getArgument(0), 1L, Long::sum));
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(call -> this.counters.putIfAbsent(call.getArgument(0), 1L) == null);
        this.forms = this.publicForms(new PublicForms.Limits());
        TenantContext.clear();
    }

    private void policy(boolean on) {
        RowSecurity.forTenant(A, () -> {
            this.links.setEnabled(A, on, 1L);
            return null;
        });
    }

    /** A's administrator at work, as FormSharing runs: under A's row security. */
    private static <T> T mine(Supplier<T> work) {
        return RowSecurity.forTenant(A, work::get);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private PublicForms publicForms(PublicForms.Limits limits) {
        return new PublicForms(this.links, this.store, this.submissions, this.redis, "test-service-token", limits, new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return FormShareLinksPostgresTest.this.now;
            }
        });
    }

    private static FormStore.Form form(long id, String type) {
        FormField field = new FormField("name", "Your name", type, true, null, null);
        return new FormStore.Form(id, A, "Visitor check-in", null, FormStore.ACTIVE, Collections.singletonList(field), null, 1, null,
            Instant.now(), Instant.now(), 0);
    }

    private String link(Integer max, boolean signIn) {
        return mine(() -> this.links.create(A, FORM, "Lobby tablet", 7, max, signIn, 1L).getKey());
    }

    private static Map<String, Object> answers() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("name", "Grace");
        return answers;
    }

    private HttpStatus refusal(Runnable call) {
        try {
            call.run();
        } catch (PublicForms.Refused refused) {
            return refused.status;
        }
        return null;
    }

    private String opened(String token) {
        return (String) this.forms.open(token, "203.0.113.5", null).get("ticket");
    }

    private void waitASecondOrFive() {
        this.now = this.now.plusSeconds(5);
    }

    @Test
    void onlyTheTokensHashIsKeptAndTheTokenOpensTheFormsFieldsAlone() {
        String token = this.link(null, false);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(sql.queryForObject("SELECT token_hash FROM form_share_link", String.class)).isEqualTo(FormShareLinks.hash(token)).isNotEqualTo(token);
        Map<String, Object> view = this.forms.open(token, "203.0.113.5", null);
        assertThat(view).containsKeys("name", "fields", "ticket", "expiresAt").doesNotContainKeys("tenantId", "formId", "jobId", "submissions");
    }

    @Test
    void unknownTamperedRevokedOrSwitchedOffLinksAreAllJustNotValid() {
        String token = this.link(null, false);
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat(this.refusal(() -> this.forms.open(tampered, "x", null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(this.refusal(() -> this.forms.open("not-a-token", "x", null))).isEqualTo(HttpStatus.NOT_FOUND);
        this.policy(false);
        assertThat(this.refusal(() -> this.forms.open(token, "x", null))).as("sharing off stops every link").isEqualTo(HttpStatus.NOT_FOUND);
        this.policy(true);
        long id = sql.queryForObject("SELECT link_id FROM form_share_link", Long.class);
        assertThat(mine(() -> this.links.revoke(A, id, 1L))).isTrue();
        assertThat(this.refusal(() -> this.forms.open(token, "x", null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(RowSecurity.forTenant(B, () -> this.links.revoke(B, id, 2L))).as("another workspace cannot revoke it").isFalse();
    }

    @Test
    void anExpiredLinkIsGone() {
        String token = this.link(null, false);
        sql.update("UPDATE form_share_link SET expires_at = now() - interval '1 minute'");
        this.now = Instant.now();
        assertThat(this.refusal(() -> this.forms.open(token, "x", null))).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void aOneUseLinkTakesOneSubmissionAndAReplayedTicketNone() {
        String token = this.link(1, false);
        this.now = Instant.now();
        String ticket = this.opened(token);
        this.waitASecondOrFive();
        ResponseDto sent = this.forms.submit(token, ticket, answers(), null, "203.0.113.5", null, null, null);
        assertThat(sent.getStatus()).isEqualTo("SUCCESS");
        assertThat(sent.getMessage()).isEqualTo("Thank you: your submission was received.");
        assertThat(sent.getData()).as("no submission, job or run id reaches a visitor").isNull();
        verify(this.submissions, times(1)).receiveAs(eq(A), any(), any(), eq(null), eq("Share link: Lobby tablet"), any(), any());
        assertThat(this.refusal(() -> this.forms.open(token, "x", null))).as("used up").isEqualTo(HttpStatus.GONE);
        assertThat(sql.queryForObject("SELECT used_count FROM form_share_link", Integer.class)).isEqualTo(1);

        String again = this.link(null, false);
        String ticket2 = this.opened(again);
        this.waitASecondOrFive();
        this.forms.submit(again, ticket2, answers(), null, "203.0.113.5", null, null, null);
        assertThat(this.refusal(() -> this.forms.submit(again, ticket2, answers(), null, "203.0.113.5", null, null, null)))
            .as("the same page cannot send twice").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aTicketMustBeThisLinksSignedFreshAndNotInstant() {
        String token = this.link(null, false);
        String other = this.link(null, false);
        String ticket = this.opened(token);
        assertThat(this.refusal(() -> this.forms.submit(token, ticket, answers(), null, "x", null, null, null)))
            .as("sent within three seconds of opening").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        this.waitASecondOrFive();
        assertThat(this.refusal(() -> this.forms.submit(other, ticket, answers(), null, "x", null, null, null)))
            .as("another link's ticket").isEqualTo(HttpStatus.BAD_REQUEST);
        String[] parts = ticket.split("\\.");
        String forged = parts[0] + "." + (Long.parseLong(parts[1]) - 60_000) + "." + parts[2] + "." + parts[3];
        assertThat(this.refusal(() -> this.forms.submit(token, forged, answers(), null, "x", null, null, null)))
            .as("a changed time breaks the signature").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.refusal(() -> this.forms.submit(token, "garbage", answers(), null, "x", null, null, null))).isEqualTo(HttpStatus.BAD_REQUEST);
        this.now = this.now.plus(Duration.ofHours(7));
        assertThat(this.refusal(() -> this.forms.submit(token, ticket, answers(), null, "x", null, null, null)))
            .as("a page left open for hours").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(this.submissions, never()).receiveAs(anyLong(), any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void aFilledHoneypotKeepsNothingButLooksTheSame() {
        String token = this.link(null, false);
        String ticket = this.opened(token);
        this.waitASecondOrFive();
        assertThat(this.forms.submit(token, ticket, answers(), "http://spam.example", "x", null, null, null).getStatus()).isEqualTo("SUCCESS");
        verify(this.submissions, never()).receiveAs(anyLong(), any(), any(), any(), anyString(), any(), any());
        assertThat(sql.queryForObject("SELECT used_count FROM form_share_link", Integer.class)).isZero();
    }

    @Test
    void anAddressIsLimitedPerMinuteAndALinkPerHour() {
        PublicForms.Limits limits = new PublicForms.Limits();
        limits.readsPerMinute = 3;
        this.forms = this.publicForms(limits);
        String token = this.link(null, false);
        for (int i = 0; i < 3; i++) {
            this.forms.open(token, "198.51.100.7", null);
        }
        assertThat(this.refusal(() -> this.forms.open(token, "198.51.100.7", null))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(this.forms.open(token, "198.51.100.8", null)).as("another address is counted on its own").containsKey("ticket");
        this.now = this.now.plusSeconds(61);
        assertThat(this.forms.open(token, "198.51.100.7", null)).as("the next minute").containsKey("ticket");
    }

    @Test
    void aSignInLinkTakesOnlyAMemberOfItsOwnWorkspace() {
        String token = this.link(null, true);
        assertThat(this.refusal(() -> this.forms.open(token, "x", null))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.refusal(() -> this.forms.open(token, "x", B))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(this.forms.open(token, "x", A)).containsKey("ticket");
    }

    @Test
    void aFormThatLooksUpAnotherFormsAnswersIsNotSharedAndSharingOffRefusesNewLinks() {
        FormSharing sharing = new FormSharing(this.links, this.store);
        TenantContext.set(A, "TENANT_ADMIN", 1L, "admin");
        FormSharing.CreateRequest ask = new FormSharing.CreateRequest();
        ask.formId = LOOKUP_FORM;
        assertThat(sharing.create(ask).getMessage()).contains("looks up another form's answers");
        ask.formId = FORM;
        ask.days = 91;
        assertThat(sharing.create(ask).getMessage()).isEqualTo("A link lasts 1 to 90 days.");
        ask.days = 7;
        ResponseDto made = sharing.create(ask);
        assertThat(made.getStatus()).isEqualTo("SUCCESS");
        assertThat(((Map<?, ?>) made.getData()).get("token")).isNotNull();
        assertThat(sharing.list(FORM).getData().toString()).doesNotContain(String.valueOf(((Map<?, ?>) made.getData()).get("token")));
        this.policy(false);
        assertThat(sharing.create(ask).getMessage()).isEqualTo(FormSharing.TURNED_OFF);
        TenantContext.set(A, "TENANT_USER", 2L, "member");
        assertThat(sharing.create(ask).getMessage()).isEqualTo(FormSharing.ADMIN_ONLY);
        assertThat(sharing.setPolicy(true).getMessage()).isEqualTo(FormSharing.ADMIN_ONLY);
        List<FormShareLinks.Link> listed = RowSecurity.forTenant(B, () -> this.links.list(B, FORM));
        assertThat(listed).as("another workspace sees no links of A").isEmpty();
        assertThat(Arrays.asList(FormSharing.TURNED_OFF, FormSharing.ADMIN_ONLY)).doesNotContainNull();
    }
}
