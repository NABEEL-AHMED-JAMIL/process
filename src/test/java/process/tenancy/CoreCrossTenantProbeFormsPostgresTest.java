package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import process.forms.FormField;
import process.forms.FormInbox;
import process.forms.FormSaveRequest;
import process.forms.FormSharing;
import process.forms.FormStatusRequest;
import process.forms.FormSubmitRequest;
import process.model.pojo.JobQueue;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.A_BUCKET;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_JOB;
import static process.tenancy.CoreProbeFixture.Caller;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.tenantlessAdmin;

/**
 * MIG-166's cross-tenant probe for Wave 5 Forms (lite): workspace A's tenant user and administrator aim every form and
 * submission endpoint at B's form, B's submission and B's job. A probe passes when each is answered as a form or
 * submission that does not exist, nothing of B's comes back or changes, nothing is written to a bucket for B and no job
 * of B's is queued. A member's list and fill-in never name the job behind a form -- here a colleague's (JobOwnership) --
 * and A's own form still takes a submission, so a probe that refused everything would not pass. Against a real etl_job
 * under row-level security (CoreProbeFixture); opt-in like every ScratchPostgres test.
 */
class CoreCrossTenantProbeFormsPostgresTest {

    static final long B_FORM = 9701L;
    static final long A_FORM = 9702L;
    static final long B_SUBMISSION = 9801L;
    static final long A_RUN = 99701L;
    static final long B_LINK = 9901L;

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_forms");
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("INSERT INTO form_definition (form_id, tenant_id, name, description, status, fields, job_id) VALUES (?, ?, "
            + "'Bravo secret field form', 'bravo payload marker', 'Active', "
            + "'[{\"key\":\"q\",\"label\":\"Bravo secret field\",\"type\":\"text\",\"required\":false}]'::jsonb, ?)", B_FORM, B, B_JOB);
        sql.update("INSERT INTO form_submission (submission_id, form_id, tenant_id, form_version, answers, status, submitted_by_name) "
            + "VALUES (?, ?, ?, 1, '{\"q\":\"bravo payload marker\"}'::jsonb, 'Received', 'bella@bravo.example')", B_SUBMISSION, B_FORM, B);
        // MIG-278: B shares its form by link; A's sharing stays off.
        sql.update("INSERT INTO form_share_policy (tenant_id, enabled) VALUES (?, true)", B);
        sql.update("INSERT INTO form_share_link (link_id, form_id, tenant_id, token_hash, label, expires_at, status) VALUES (?, ?, ?, "
            + "repeat('b', 64), 'bravo payload marker', now() + interval '7 days', 'Active')", B_LINK, B_FORM, B);
        sql.update("INSERT INTO form_definition (form_id, tenant_id, name, status, fields, job_id) VALUES (?, ?, 'Acme intake', 'Active', "
            + "'[{\"key\":\"q\",\"label\":\"Question\",\"type\":\"text\",\"required\":true}]'::jsonb, ?)", A_FORM, A, COLLEAGUE_JOB);
        when(fx.formInbox.locate()).thenReturn(FormInbox.Location.at(A_BUCKET));
        JobQueue run = new JobQueue();
        run.setJobQueueId(A_RUN);
        when(fx.engine.addFormJobInQueue(any(), anyString(), anyString(), anyString(), anyLong())).thenReturn(run);
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() {
        fx.reset();
    }

    private static List<FormField> oneField() {
        return Collections.singletonList(new FormField("q", "Question", "text", false, null, null));
    }

    private static FormSharing.CreateRequest shareOf(long formId) {
        FormSharing.CreateRequest request = new FormSharing.CreateRequest();
        request.formId = formId;
        request.label = "probe";
        return request;
    }

    @Test
    void noFormEndpointReadsOrChangesAnotherWorkspacesFormsSubmissionsOrJobs() throws Exception {
        String before = fx.foreignRows();

        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            assertThat(fx.probe("GET form.json/fetch", caller, () -> fx.forms.fetch(B_FORM))).contains(REFUSED);
            assertThat(fx.probe("POST form.json/save", caller, () -> fx.forms.save(new FormSaveRequest(B_FORM, "Taken over", null,
                "Archived", oneField(), null)))).contains(REFUSED);
            assertThat(fx.probe("POST form.json/status", caller, () -> fx.forms.status(new FormStatusRequest(B_FORM, "Archived"))))
                .contains(REFUSED);
            assertThat(fx.probe("POST form.json/submit", caller, () -> fx.forms.submit(new FormSubmitRequest(B_FORM,
                Collections.<String, Object>singletonMap("q", "probe"))))).contains(REFUSED);
            assertThat(fx.probe("POST form.json/upload", caller, () -> fx.forms.upload(B_FORM, "q",
                new MockMultipartFile("file", "probe.png", "image/png", new byte[] {1, 2, 3})))).contains(REFUSED);
            assertThat(fx.probe("GET formSubmission.json/list", caller, () -> fx.formSubmissions.list(B_FORM, 50))).contains(REFUSED);
            assertThat(fx.probe("GET formSubmission.json/fetch", caller, () -> fx.formSubmissions.fetch(B_SUBMISSION))).contains(REFUSED);
            assertThat(fx.probe("GET formSubmission.json/export", caller, () -> fx.formSubmissions.export(B_FORM)))
                .contains(REFUSED).doesNotContain("Bravo");
            assertThat(fx.probe("GET form.json/list", caller, () -> fx.forms.list(true))).contains(SUCCEEDED).contains("Acme intake");
            assertThat(fx.probe("GET form.json/shareLinks", caller, () -> fx.forms.shareLinks(B_FORM))).contains(REFUSED);
            assertThat(fx.probe("POST form.json/shareLinks/create", caller, () -> fx.forms.createShareLink(shareOf(B_FORM))))
                .contains(REFUSED);
            assertThat(fx.probe("POST form.json/shareLinks/revoke", caller, () -> fx.forms.revokeShareLink(B_LINK))).contains(REFUSED);
            // A's own setting, never B's: B's is on, A's reads off.
            assertThat(fx.probe("GET form.json/sharePolicy", caller, () -> fx.forms.sharePolicy())).contains(SUCCEEDED)
                .contains("\"enabled\":false");
        }
        // Turning A's sharing on and off touches A's row alone; a member may not.
        assertThat(fx.probe("POST form.json/sharePolicy", USER_OF_A, () -> fx.forms.setSharePolicy(Collections.singletonMap("enabled",
            true)))).contains(REFUSED);
        assertThat(fx.probe("POST form.json/sharePolicy", ADMIN_OF_A, () -> fx.forms.setSharePolicy(Collections.singletonMap("enabled",
            true)))).contains(SUCCEEDED).contains("\"enabled\":true");
        assertThat(fx.probe("POST form.json/shareLinks/create(B's form, sharing on)", ADMIN_OF_A,
            () -> fx.forms.createShareLink(shareOf(B_FORM)))).contains(REFUSED);
        assertThat(fx.probe("POST form.json/shareLinks/revoke(sharing on)", ADMIN_OF_A, () -> fx.forms.revokeShareLink(B_LINK)))
            .contains(REFUSED);
        assertThat(fx.probe("POST form.json/sharePolicy(off)", ADMIN_OF_A, () -> fx.forms.setSharePolicy(Collections.singletonMap(
            "enabled", false)))).contains(SUCCEEDED);
        // B's job is not A's to link, and the job list is an administrator's and A's own.
        assertThat(fx.probe("POST form.json/save(linking B's job)", ADMIN_OF_A, () -> fx.forms.save(new FormSaveRequest(null,
            "Acme link probe", null, "Draft", oneField(), B_JOB)))).contains("The job this form starts was not found in this workspace.");
        assertThat(fx.probe("GET form.json/linkableJobs", ADMIN_OF_A, () -> fx.forms.linkableJobs())).contains(SUCCEEDED);
        assertThat(fx.probe("GET form.json/linkableJobs", USER_OF_A, () -> fx.forms.linkableJobs())).contains(REFUSED);
        // A caller with no workspace has no forms at all.
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("GET form.json/list(no workspace)", tenantlessAdmin(none), () -> fx.forms.list(true))).contains(REFUSED);
        }

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's, the platform's or the colleague's changed").isEqualTo(before);
        verify(fx.formBuckets, never()).upload(eq(B), anyString(), anyString(), any(), anyString());
        verify(fx.engine, never()).addFormJobInQueue(argThat(job -> job != null && job.getJobId() == B_JOB), anyString(), anyString(),
            anyString(), anyLong());
    }

    @Test
    void aMembersSubmissionToItsOwnFormStartsTheFormsJobWithoutNamingIt() throws Exception {
        String before = fx.foreignRows();

        String listed = fx.probe("GET form.json/fetch(mine)", USER_OF_A, () -> fx.forms.fetch(A_FORM));
        assertThat(listed).contains(SUCCEEDED).contains("\"startsJob\":true");
        String answer = fx.probe("POST form.json/submit(mine)", USER_OF_A, () -> fx.forms.submit(new FormSubmitRequest(A_FORM,
            Collections.<String, Object>singletonMap("q", "acme answer"))));

        assertThat(fx.leaks).as("the colleague's job is not named to the member").isEmpty();
        assertThat(answer).contains(SUCCEEDED);
        verify(fx.formBuckets).upload(eq(A), eq(A_BUCKET), argThat(key -> key.startsWith("intake/forms/form-" + A_FORM + "/")), any(),
            eq("application/json"));
        assertThat(fx.db.jdbc().queryForObject("SELECT count(*) FROM form_submission WHERE form_id = ? AND tenant_id = ?", Long.class,
            A_FORM, A)).isGreaterThanOrEqualTo(1L);
        assertThat(fx.foreignRows()).isEqualTo(before);
    }
}
