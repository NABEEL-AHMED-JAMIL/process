package process.emailer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders every job notification the way the mailer does.
 *
 * A template is not compiled, so nothing else catches a variable that never resolves -- Velocity
 * prints the expression literally and the mail goes out reading "Job $request.get(...)". That
 * shipped once already on the welcome message; these cover the other four.
 */
public class JobEmailTemplateTest {

    private VelocityManager velocityManager;

    @BeforeEach
    void setUp() {
        this.velocityManager = new VelocityManager();
        // @PostConstruct does not run outside the container.
        this.velocityManager.init();
    }

    private Map<String, Object> jobBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("job_id", 1985L);
        body.put("event_id", 5065L);
        body.put("time_slot", "2026-08-26 03:00");
        body.put("status", "Completed");
        body.put("job_name", "Cat bond loss -- 15-minute sweep");
        body.put("status_message", "Wrote 6 files to litware-financial.");
        return body;
    }

    private String render(TemplateType type, Map<String, Object> body) {
        return this.velocityManager.getResponseMessage(type, body);
    }

    private void assertNothingUnresolved(String message, String label) {
        // The exact failure that shipped before: an expression printed as its own source.
        assertFalse(message.contains("$request"), label + " left a variable unresolved");
        assertFalse(message.contains("#if") || message.contains("#end"),
            label + " left a directive unrendered");
    }

    @Test
    void everyJobTemplateRenders() {
        for (TemplateType type : new TemplateType[] {
            TemplateType.COMPLETE_JOB, TemplateType.FAIL_JOB, TemplateType.SKIP_JOB }) {
            String message = render(type, jobBody());
            assertNothingUnresolved(message, type.name());
            assertTrue(message.contains("Cat bond loss"), type + " lost the job name");
            assertTrue(message.contains("5065"), type + " lost the run id");
            assertTrue(message.contains("ETL Console"), type + " lost the wordmark");
        }
    }

    @Test
    void aJobWithNoNameFallsBackToItsId() {
        Map<String, Object> body = jobBody();
        body.remove("job_name");
        String message = render(TemplateType.FAIL_JOB, body);
        assertNothingUnresolved(message, "FAIL_JOB without a name");
        // Printing a gap where the name should be reads as a broken template.
        assertTrue(message.contains("Job 1985"), "should fall back to the id");
    }

    @Test
    void aRunWithNoMessageOmitsTheNoteEntirely() {
        Map<String, Object> body = jobBody();
        body.remove("status_message");
        String message = render(TemplateType.COMPLETE_JOB, body);
        assertNothingUnresolved(message, "COMPLETE_JOB without a message");
        assertFalse(message.contains("border-left:3px solid"),
            "an empty note block should not be drawn at all");
    }

    @Test
    void theOutcomesAreVisuallyDistinct() {
        // Three identical grey tables was the old design's real failure: a person could not tell
        // a failure from a success without reading the sentence.
        String ok = render(TemplateType.COMPLETE_JOB, jobBody());
        String bad = render(TemplateType.FAIL_JOB, jobBody());
        String skipped = render(TemplateType.SKIP_JOB, jobBody());
        assertTrue(ok.contains("Completed") && ok.contains("#16a34a"), "completed should read green");
        assertTrue(bad.contains("Failed") && bad.contains("#dc2626"), "failed should read red");
        assertTrue(skipped.contains("Skipped") && skipped.contains("#d97706"), "skipped should read amber");
    }

    @Test
    void theFileShareRendersWithAndWithoutAMessage() {
        Map<String, Object> body = new HashMap<>();
        body.put("sender_name", "Dana Whitfield");
        body.put("item_label", "a file");
        body.put("item_name", "port-disruption-2026.csv");
        body.put("item_type", "CSV");
        body.put("size_label", "1.2 MB");
        body.put("attachment_note", "It is attached to this message.");
        body.put("message", "Figures for the Tuesday review.");
        String withMessage = render(TemplateType.FILE_SHARE, body);
        assertNothingUnresolved(withMessage, "FILE_SHARE with a message");
        assertTrue(withMessage.contains("Figures for the Tuesday review."));

        body.put("message", "");
        String without = render(TemplateType.FILE_SHARE, body);
        assertNothingUnresolved(without, "FILE_SHARE without a message");
    }
}
