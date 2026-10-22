package process.pipeline.tasks;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.PipelineDefinition;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.MediaFiles;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.items.ItemReviews;
import process.pipeline.items.ItemRow;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read text from pages (MIG-421): the generic OCR step. Each row names a page image an earlier step of the run kept (Split
 * pages' page_image, by default); the platform's own OCR engine -- media-service's, the one Documents uses, local, nothing
 * downloaded -- reads it, and the row gains the text and how sure the engine is.
 *
 * <ul>
 *   <li><b>Only the pages that need it</b> (the default): a page whose text column already holds a text layer (at least
 *       minChars characters, Split pages' page_text) keeps that text, sureness 100, and is not read or billed. "Every page"
 *       reads them all.</li>
 *   <li><b>Sureness</b>: the words' mean confidence, 0-100. A page below the threshold is marked "Check by hand"
 *       (ocr_status check, the item's flag) and sent to Review: one Task inbox task names the run's pages to check, linking to
 *       the run's Items tab, where each page shows its image beside the text read.</li>
 *   <li><b>Billing</b>: one ocr.pages for each page read, billed by media to the run's workspace under the run's step,
 *       attempt and page (asked again, billed once).</li>
 *   <li><b>Items</b>: each page is a sub-item of its document, with its own status and retry ("Retry page").</li>
 *   <li>A page that cannot be read (not an image, too big, the engine refused it) is that page's failure: onError fails the
 *       step, skips the row, or keeps it with the text empty and ocr_status failed (the default, so one bad page does not
 *       stop the others). A busy reader is asked again after the wait it names.</li>
 * </ul>
 */
@Component
public class OcrPagesStepTask extends RegisteredTask implements StreamingStepTask {

    /** The languages this platform's OCR has installed (media-service's image: tesseract-ocr-eng). */
    static final List<String> LANGUAGES = Arrays.asList("eng");
    static final int MAX_PAGES = 2_000;
    static final int BUSY_TRIES = 6;
    static final Duration MAX_WAIT = Duration.ofMinutes(2);

    static final String TEXT = "ocr_text";
    static final String SURENESS = "ocr_sureness";
    static final String SOURCE = "ocr_source";
    static final String WORDS = "ocr_words";
    static final String STATUS = "ocr_status";
    static final String NOTE = "ocr_note";
    /** ZT-I25: how far the page was turned, clockwise in degrees (0, 90, 180, 270), before its words were read. */
    static final String ROTATION = "ocr_rotation";

    static final String SOURCE_OCR = "ocr";
    static final String SOURCE_TEXT_LAYER = "text-layer";
    static final String OK = "ok";
    static final String CHECK = "check";
    static final String FAILED = "failed";

    static final TaskSpec SPEC = TaskSpec.builder("ocr_pages", "Read text from pages", TaskKind.PROCESS)
        .description("Reads the text on page images an earlier step made (Split pages' page images: scans, photos) with the "
            + "platform's own OCR, and adds the text and how sure the reader is. A page it is not sure enough of is marked Check by "
            + "hand and sent to Review with its image beside the text. Billed per page read (ocr.pages).")
        .input(TaskSpec.rows("Rows naming a page image an earlier step of the run kept (Split pages: page_image, page_text)."))
        .output(TaskSpec.rows("The input's columns, then ocr_text, ocr_sureness (0-100), ocr_source (ocr or text-layer), ocr_words, "
            + "ocr_status (ok, check or failed), ocr_note and ocr_rotation (degrees the page was turned, clockwise, to be read upright)."))
        .config(JsonSchema.object()
            .required("fromStep", JsonSchema.string().minLength(1).maxLength(64).format("step").title("Pages from")
                .description("The earlier step whose page images are read: Split pages, usually."))
            .property("imageColumn", JsonSchema.string().minLength(1).maxLength(255).format("column").title("Image column")
                .defaultValue("page_image").description("The column naming each row's page image (a file that step kept with the run)."))
            .property("language", JsonSchema.string().enumOf(LANGUAGES.toArray(new String[0])).title("Language").defaultValue("eng")
                .description("eng: English, the language this platform's OCR has installed."))
            .property("pages", JsonSchema.string().enumOf("no_text_layer", "every_page").title("Which pages").defaultValue("no_text_layer")
                .description("no_text_layer: only pages with no text layer (scans, photos); the others keep their own text, not read "
                    + "or billed. every_page: read every page."))
            .property("textColumn", JsonSchema.string().minLength(1).maxLength(255).format("column").title("Text layer column")
                .defaultValue("page_text").description("The column holding a page's own text layer (Split pages' page_text)."))
            .property("minChars", JsonSchema.integer().minimum(1).maximum(10_000).title("A text layer has at least (characters)")
                .defaultValue(16).description("A page whose text column holds fewer characters has no text layer, and is read."))
            .property("threshold", JsonSchema.integer().minimum(50).maximum(99).title("Flag a page when sureness is below (%)")
                .defaultValue(80).description("A page read less surely is marked Check by hand and sent to Review with its image beside "
                    + "the text."))
            .property("reviewers", JsonSchema.object()
                .property("kind", JsonSchema.string().enumOf("role", "team").title("Reviewers are"))
                .property("value", JsonSchema.string().maxLength(64).title("Role or team")
                    .description("For a role: " + PipelineDefinition.Reviewers.ADMINS + " (the tenant administrators) or TENANT_USER; for "
                        + "a team: the team's id."))
                .title("Who checks the pages").description("Empty: the workspace's administrators."))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a page cannot be read").defaultValue("null")
                .description("fail the step, skip the row, or keep it with the text empty (ocr_status failed, ocr_note says why)."))
            .property("maxPages", JsonSchema.integer().minimum(1).maximum(MAX_PAGES).title("At most pages").defaultValue(200)
                .description("An input with more rows fails before any page is read.")))
        .backing(TaskSpec.MEDIA)
        .timeoutSeconds(7200)
        .aiToolName("read_text_from_pages")
        .items(ItemRole.EACH).build();

    private final MediaFiles media;
    private final Sleeper sleeper;
    private ItemReviews reviews;

    /** How the step waits for a busy reader (a test's is instant). */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    @Autowired
    public OcrPagesStepTask(MediaFiles media) {
        this(media, d -> Thread.sleep(d.toMillis()));
    }

    OcrPagesStepTask(MediaFiles media, Sleeper sleeper) {
        super(SPEC);
        this.media = media;
        this.sleeper = sleeper;
    }

    /** Where the pages to check by hand go (the Task inbox); without it they are only marked. */
    @Autowired(required = false)
    public void useReviews(ItemReviews reviews) {
        this.reviews = reviews;
    }

    @Override
    public Optional<String> unavailable() {
        return this.media.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        String language = Configs.text(config, "language", "eng");
        if (!LANGUAGES.contains(language)) {
            problems.add(new DefinitionProblem("language", "a language this platform's OCR has installed: " + String.join(", ", LANGUAGES)));
        }
        Object reviewers = config.get("reviewers");
        if (reviewers instanceof Map && !((Map<?, ?>) reviewers).isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> who = (Map<String, Object>) reviewers;
            String kind = Configs.text(who, "kind", null);
            String value = Configs.text(who, "value", null);
            if (kind == null) {
                problems.add(new DefinitionProblem("reviewers.kind", "role or team"));
            } else if (PipelineDefinition.Reviewers.ROLE.equals(kind) && !PipelineDefinition.Reviewers.ROLES.contains(value)) {
                problems.add(new DefinitionProblem("reviewers.value", "a role: one of " + PipelineDefinition.Reviewers.ROLES));
            } else if (PipelineDefinition.Reviewers.TEAM.equals(kind) && (value == null || !value.matches("\\d{1,18}"))) {
                problems.add(new DefinitionProblem("reviewers.value", "a team: the team's id"));
            }
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String fromStep = Configs.text(config, "fromStep", null);
        if (fromStep == null) {
            throw new IllegalStateException("Name the step whose page images are read (Pages from).");
        }
        String imageColumn = Configs.text(config, "imageColumn", "page_image");
        String textColumn = Configs.text(config, "textColumn", "page_text");
        String language = Configs.text(config, "language", "eng");
        boolean everyPage = "every_page".equals(Configs.text(config, "pages", "no_text_layer"));
        int minChars = Configs.integer(config, "minChars", 16);
        int threshold = Configs.integer(config, "threshold", 80);
        String onError = Configs.text(config, "onError", "null");
        int maxPages = Configs.integer(config, "maxPages", 200);

        // The page images the earlier step kept, by name (its latest attempt's: a retried page's own image).
        Map<String, StepContext.RunFile> images = new HashMap<>();
        for (StepContext.RunFile file : context.runFiles(fromStep)) {
            images.put(file.name, file);
        }
        Path scratch = context.scratch();
        int read = 0;
        int layered = 0;
        int failed = 0;
        List<String> toCheck = new ArrayList<>();
        try (RowSource input = context.openInput()) {
            if (input.size() > maxPages) {
                throw new IllegalStateException(String.format("The input has %,d pages; this step reads at most %d (maxPages).", input.size(),
                    maxPages));
            }
            if (!input.columns().contains(imageColumn)) {
                throw new IllegalStateException(String.format("The input has no column %s naming the page images (Image column).", imageColumn));
            }
            Set<String> columns = new LinkedHashSet<>(input.columns());
            columns.addAll(Arrays.asList(TEXT, SURENESS, SOURCE, WORDS, STATUS, NOTE, ROTATION));
            RowSink out = context.output();
            out.declare(new ArrayList<>(columns));
            int index = 0;
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                for (Map<String, Object> row : batch) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Read text from pages was stopped.");
                    }
                    int at = index++;
                    long began = System.nanoTime();
                    Map<String, Object> made = new LinkedHashMap<>(row);
                    String layer = text(row.get(textColumn));
                    String label = labelOf(row, at);
                    if (!everyPage && layer != null && layer.length() >= minChars) {
                        // A text layer: its own text, not read or billed.
                        made.put(TEXT, layer);
                        made.put(SURENESS, 100.0);
                        made.put(SOURCE, SOURCE_TEXT_LAYER);
                        made.put(WORDS, layer.split("\\s+").length);
                        made.put(STATUS, OK);
                        made.put(NOTE, "The page has its own text layer: kept, not read.");
                        made.put(ROTATION, 0);
                        layered++;
                        out.add(made);
                        context.itemTook(row, (System.nanoTime() - began) / 1_000_000L);
                        continue;
                    }
                    try {
                        String name = text(row.get(imageColumn));
                        if (name == null) {
                            throw new IllegalArgumentException("The row names no page image (" + imageColumn + ").");
                        }
                        StepContext.RunFile image = images.get(name);
                        if (image == null || !image.kept()) {
                            throw new IllegalArgumentException(String.format("The page image %s is not one step %s kept with the run.", name, fromStep));
                        }
                        MediaFiles.PageText page = this.read(context, row, image, name, at, language, scratch);
                        read++;
                        made.put(TEXT, page.text);
                        made.put(SURENESS, round(page.sureness));
                        made.put(SOURCE, SOURCE_OCR);
                        made.put(WORDS, page.wordCount);
                        made.put(ROTATION, page.rotation);
                        String turned = page.rotation == 0 ? "" : String.format(Locale.ROOT, " Turned %d° to be read upright.", page.rotation);
                        if (page.sureness < threshold) {
                            String note = String.format(Locale.ROOT, "Check by hand: %.0f %% sure (below %d %%).", page.sureness, threshold) + turned;
                            made.put(STATUS, CHECK);
                            made.put(NOTE, note);
                            context.itemFlagged(row, ItemRow.CHECK, note);
                            toCheck.add(String.format(Locale.ROOT, "%s (%.0f %% sure)", label, page.sureness));
                        } else {
                            made.put(STATUS, OK);
                            made.put(NOTE, String.format(Locale.ROOT, "Read: %d word%s, %.0f %% sure.", page.wordCount, page.wordCount == 1 ? "" : "s",
                                page.sureness) + turned);
                        }
                        out.add(made);
                    } catch (InterruptedException stopped) {
                        throw stopped;
                    } catch (Exception pageFailed) {
                        String why = pageFailed.getMessage() == null ? "The page could not be read." : pageFailed.getMessage();
                        context.itemFailed(row, why);
                        if ("fail".equals(onError)) {
                            throw new IllegalStateException(label + ": " + why, pageFailed);
                        }
                        failed++;
                        context.warn(label + ": " + why);
                        if (!"skip".equals(onError)) {
                            made.put(TEXT, null);
                            made.put(SURENESS, null);
                            made.put(SOURCE, null);
                            made.put(WORDS, null);
                            made.put(STATUS, FAILED);
                            made.put(NOTE, why);
                            made.put(ROTATION, null);
                            out.add(made);
                        }
                    } finally {
                        context.itemTook(row, (System.nanoTime() - began) / 1_000_000L);
                    }
                }
            }
            context.log(String.format("%d page(s) read by OCR (billed as ocr.pages), %d kept their own text layer%s%s.", read, layered,
                toCheck.isEmpty() ? "" : String.format(", %d to check by hand (below %d %%)", toCheck.size(), threshold),
                failed == 0 ? "" : String.format(", %d could not be read", failed)));
            if (!toCheck.isEmpty()) {
                this.sendToReview(context, config, toCheck, threshold);
            }
            return StepResult.streamed(out.size());
        }
    }

    /** One page image: spooled from the run's kept file, sent, and asked again while the reader is busy. */
    private MediaFiles.PageText read(StreamContext context, Map<String, Object> row, StepContext.RunFile image, String name, int index,
        String language, Path scratch)
        throws Exception {
        String extension = name.lastIndexOf('.') > 0 ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "png";
        Path spool = scratch.resolve("ocr-page-" + (index + 1) + "." + extension);
        try {
            try (InputStream in = image.open()) {
                Files.copy(in, spool, StandardCopyOption.REPLACE_EXISTING);
            }
            // Billed once per page per attempt: keyed by the page's item in an item run (its row number otherwise).
            String usageKey = String.format("pipeline#%d#%s#a%d#r%s", context.jobQueueId(), context.stepKey(), context.attempt(),
                context.rowTag(row, index));
            Duration wait = Duration.ZERO;
            for (int attempt = 1; ; attempt++) {
                try {
                    return this.media.ocr(context.tenantId(), usageKey, spool, name, language);
                } catch (IOException busy) {
                    boolean retryable = !(busy instanceof MediaFiles.Refused) || ((MediaFiles.Refused) busy).busy();
                    if (!retryable || attempt >= BUSY_TRIES) {
                        throw busy;
                    }
                    int named = busy instanceof MediaFiles.Refused ? ((MediaFiles.Refused) busy).retryAfterSeconds() : 15;
                    long next = Math.min(MAX_WAIT.getSeconds(), Math.max(Math.max(1, named), wait.getSeconds() * 2));
                    wait = Duration.ofSeconds(next);
                    context.log(String.format("The OCR reader is busy (%s); %s waits %d s and asks again (try %d of %d).", busy.getMessage(), name,
                        wait.getSeconds(), attempt + 1, BUSY_TRIES));
                    this.sleeper.sleep(wait);
                }
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /**
     * The pages to check by hand, as one Task inbox task for the reviewers the step names (else the administrators): the run,
     * the step, the pages and a link to the run's Items tab showing them. A task that cannot be made is said in the step's
     * log; the pages stay marked.
     */
    private void sendToReview(StreamContext context, Map<String, Object> config, List<String> pages, int threshold) {
        if (this.reviews == null || this.reviews.unavailable().isPresent()) {
            context.warn(String.format("%d page(s) to check by hand are marked on the run's Items tab; no Task inbox task was made here.", pages.size()));
            return;
        }
        String kind = PipelineDefinition.Reviewers.ROLE;
        String value = PipelineDefinition.Reviewers.ADMINS;
        Object who = config.get("reviewers");
        if (who instanceof Map && !((Map<?, ?>) who).isEmpty()) {
            Object k = ((Map<?, ?>) who).get("kind");
            Object v = ((Map<?, ?>) who).get("value");
            if (k != null && v != null) {
                kind = String.valueOf(k);
                value = String.valueOf(v);
            }
        }
        String pipeline = context.pipelineName() == null || context.pipelineName().trim().isEmpty() ? "pipeline" : context.pipelineName().trim();
        ItemReviews.ReviewRequest request = new ItemReviews.ReviewRequest();
        request.tenantId = context.tenantId();
        request.subjectId = String.format("pipeline-items:%d:%s:a%d", context.jobQueueId(), context.stepKey(), context.attempt());
        request.eventId = "ocr-check-" + context.jobQueueId() + "-" + context.stepKey() + "-a" + context.attempt();
        request.title = String.format("Check %d page%s by hand: %s, run #%d", pages.size(), pages.size() == 1 ? "" : "s", pipeline,
            context.jobQueueId());
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("jobQueueId", context.jobQueueId());
        subject.put("jobId", context.jobId());
        subject.put("pipelineName", pipeline);
        subject.put("stepKey", context.stepKey());
        subject.put("attempt", context.attempt());
        subject.put("why", String.format("The OCR step was less than %d %% sure of these pages: check each page's text beside its image.", threshold));
        subject.put("items", pages.size() > 100 ? new ArrayList<>(pages.subList(0, 100)) : pages);
        subject.put("itemCount", pages.size());
        subject.put("link", context.jobId() == null ? null
            : String.format("/pipelines/schedules/%d/runs/%d?tab=items&status=check", context.jobId(), context.jobQueueId()));
        request.subject = subject;
        request.reviewersKind = kind;
        request.reviewersValue = value;
        try {
            ItemReviews.Started started = this.reviews.send(request);
            context.log(String.format("%d page(s) to check by hand sent to Review: Task inbox request %d (%s %s).", pages.size(), started.instanceId,
                kind, value));
        } catch (RuntimeException unsent) {
            context.warn(String.format("%d page(s) to check by hand are marked on the run's Items tab, but no Task inbox task was made: %s",
                pages.size(), unsent.getMessage()));
        }
    }

    /** "referral-0412.pdf › Page 3": the document's name and the page, when the row says them; else the row's number. */
    static String labelOf(Map<String, Object> row, int index) {
        String file = text(row.get("file_name"));
        String page = text(row.get("page"));
        if (file != null && page != null) {
            return file + " › Page " + page;
        }
        return page != null ? "Page " + page : "Row " + (index + 1);
    }

    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    static double round(double sureness) {
        return Math.round(sureness * 10) / 10.0;
    }
}
