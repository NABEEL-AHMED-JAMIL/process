package process.pipeline.items;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.identity.IdentityPort;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.PipelineDefinition;
import process.pipeline.RunOwnership;
import process.security.ManagedSessionOnly;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * "Send to review" on the Items tab (MIG-421): the selected items of a run as one Task inbox task for a team or a role
 * (ItemReviews: workflow-service's built-in pipeline-items-review). The task names the items -- their refs, names and
 * statuses, never a value of their data -- and links to the run's Items tab showing exactly them. The run's owner or an
 * administrator sends it (the Source Jobs page; viewers and reviewers do not); a team must be one of the workspace's own.
 */
@Service
public class RunItemReviews {

    /** The most items one task names; a link shows at most this many by ref. */
    static final int MAX_NAMED = 100;
    static final int MAX_NOTE = 500;

    /** Body: the selection (as the list's), who reviews, and a note for them. */
    public static class Request extends RunItems.ListRequest {
        public PipelineDefinition.Reviewers reviewers;
        public String note;

        public PipelineDefinition.Reviewers getReviewers() { return reviewers; }

        public void setReviewers(PipelineDefinition.Reviewers reviewers) { this.reviewers = reviewers; }

        public String getNote() { return note; }

        public void setNote(String note) { this.note = note; }
    }

    private final RunItems items;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private ItemReviews reviews;
    private IdentityPort identity;

    public RunItemReviews(RunItems items, JobQueueRepository runs, SourceJobRepository jobs) {
        this.items = items;
        this.runs = runs;
        this.jobs = jobs;
    }

    @Autowired(required = false)
    public void useReviews(ItemReviews reviews) {
        this.reviews = reviews;
    }

    @Autowired(required = false)
    public void useIdentity(IdentityPort identity) {
        this.identity = identity;
    }

    public RunItems.Answer send(Request request, Long userId) {
        if (ManagedSessionOnly.isPlatformOwnSignIn()) {
            return RunItems.Answer.refused(403, ManagedSessionOnly.REFUSAL);
        }
        // Sending is the run owner's or an administrator's, as retrying is; a reader who only reviews the run is told it is not there.
        Optional<RunOwnership.Owned> owned = request == null || request.jobQueueId == null ? Optional.empty()
            : RunOwnership.owned(this.runs, this.jobs, request.jobQueueId);
        if (!owned.isPresent()) {
            return RunItems.Answer.refused(404, RunItems.RUN_NOT_FOUND);
        }
        PipelineDefinition.Reviewers who = request.reviewers;
        String kind = who == null || who.getKind() == null ? null : who.getKind().trim();
        String value = who == null || who.getValue() == null ? null : who.getValue().trim();
        if (PipelineDefinition.Reviewers.TEAM.equals(kind)) {
            if (value == null || !value.matches("\\d{1,18}")) {
                return RunItems.Answer.refused(400, "Name the team by its id.");
            }
            Boolean ours = this.identity == null ? null : this.identity.isActiveTeamOf(owned.get().run.getTenantId(), Long.parseLong(value));
            if (ours == null) {
                return RunItems.Answer.refused(503, "The workspace's teams cannot be checked right now; try again in a minute.");
            }
            if (!ours) {
                return RunItems.Answer.refused(400, "That team is not in this workspace.");
            }
        } else if (PipelineDefinition.Reviewers.ROLE.equals(kind)) {
            if (!PipelineDefinition.Reviewers.ROLES.contains(value)) {
                return RunItems.Answer.refused(400, "A role is one of " + PipelineDefinition.Reviewers.ROLES + ".");
            }
        } else {
            return RunItems.Answer.refused(400, "Say who reviews the items: a team ({kind: team, value: its id}) or a role.");
        }
        RunItems.Selected selected = this.items.selected(request);
        if (selected == null) {
            return RunItems.Answer.refused(404, RunItems.RUN_NOT_FOUND);
        }
        if (selected.items.isEmpty()) {
            return RunItems.Answer.refused(409, "No item is selected.");
        }
        if (selected.items.size() > RunItems.MAX_SELECTION) {
            return RunItems.Answer.refused(413, String.format("At most %,d items go to review at once.", RunItems.MAX_SELECTION));
        }
        if (this.reviews == null || this.reviews.unavailable().isPresent()) {
            return RunItems.Answer.refused(503, this.reviews == null ? "Task inbox tasks cannot be made here." : this.reviews.unavailable().get());
        }
        long jobQueueId = owned.get().run.getJobQueueId();
        Long jobId = owned.get().run.getJobId();
        String jobName = owned.get().job.getJobName();
        List<Map<String, Object>> named = new ArrayList<>();
        for (ItemRow row : selected.items.subList(0, Math.min(MAX_NAMED, selected.items.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", row.itemRef);
            item.put("label", row.label);
            item.put("status", ItemRow.CHECK.equals(row.flag) ? "check" : row.childFailed > 0 ? ItemRow.FAILED : row.status);
            named.add(item);
        }
        String refs = selected.items.stream().limit(MAX_NAMED).map(r -> r.itemRef).collect(Collectors.joining(","));
        String note = request.note == null ? null : request.note.trim();
        if (note != null && note.length() > MAX_NOTE) {
            note = note.substring(0, MAX_NOTE);
        }
        int n = selected.items.size();
        ItemReviews.ReviewRequest ask = new ItemReviews.ReviewRequest();
        ask.tenantId = owned.get().run.getTenantId();
        String digest = sha256(jobQueueId + "|" + refs + "|" + kind + "|" + value + "|" + userId + "|" + Instant.now().getEpochSecond());
        ask.subjectId = "pipeline-items:" + jobQueueId + ":" + digest.substring(0, 16);
        ask.eventId = "items-review-" + jobQueueId + "-" + digest.substring(0, 32);
        ask.title = String.format("Review %d item%s of run #%d: %s", n, n == 1 ? "" : "s", jobQueueId, jobName == null ? "pipeline" : jobName);
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("jobQueueId", jobQueueId);
        subject.put("jobId", jobId);
        subject.put("jobName", jobName);
        subject.put("why", note == null || note.isEmpty() ? "Sent to review from the run's Items tab." : note);
        subject.put("items", named);
        subject.put("itemCount", n);
        subject.put("requestedBy", userId);
        subject.put("link", jobId == null ? null : String.format("/pipelines/schedules/%d/runs/%d?tab=items&items=%s", jobId, jobQueueId, refs));
        ask.subject = subject;
        ask.reviewersKind = kind;
        ask.reviewersValue = value;
        ItemReviews.Started started;
        try {
            started = this.reviews.send(ask);
        } catch (RuntimeException refused) {
            return RunItems.Answer.refused(502, "The items were not sent to review: " + refused.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobQueueId", jobQueueId);
        data.put("items", n);
        data.put("requestId", started.instanceId);
        data.put("state", started.state);
        data.put("reviewers", PipelineDefinition.Reviewers.of(kind, value));
        return RunItems.Answer.ok(String.format("Sent %d item%s to review: a Task inbox task for %s.", n, n == 1 ? "" : "s",
            PipelineDefinition.Reviewers.TEAM.equals(kind) ? "the team" : value), data);
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
