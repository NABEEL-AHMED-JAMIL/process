package process.pipeline.tasks;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Split pages (MIG-420, the Run Items view's "a step that makes many files per item"): each row's PDF -- in the run's
 * workspace's own bucket, named as transcribe_audio names its (FileRows) -- split into its pages. Each page is a row of its
 * own (the document's columns, then page, page_count, page_text, page_chars, page_image, page_status and page_error), and
 * each page's image is kept with the run as a PNG output -- so every page is a sub-item of its document, with its own
 * status, data, file and retry. Pure Core: Apache PDFBox reads the PDF; nothing is sent anywhere.
 *
 * <ul>
 *   <li>A page with no text on it (fewer than minChars characters: a scanned page that needs OCR, a blank page) is that page's
 *       failure, which onError handles: {@code null} (the default) keeps the page's row with its text empty and the reason in
 *       page_error; {@code skip} drops the page; {@code fail} fails the step. One bad page never fails the document.</li>
 *   <li>A file that is not a PDF, or cannot be read, is the document's failure, handled the same way.</li>
 *   <li>At most maxFiles documents (default 20), maxPages pages each (default 200) and 2,000 pages in all; more fail the step
 *       before any page is written. The PDF is spooled to the try's scratch directory and read from there, never held in
 *       memory whole; a page image is rendered at dpi (default 100, 72 to 200) and written straight to the run's files.</li>
 *   <li>Billed as a pipeline read (storage-service): the bytes of each PDF read.</li>
 * </ul>
 */
@Component
public class SplitPagesStepTask extends RegisteredTask implements StreamingStepTask {

    static final int MAX_FILES = 100;
    static final int MAX_PAGES = 2_000;
    static final long MAX_PDF_BYTES = 200L * 1024 * 1024;

    static final String PAGE = "page";
    static final String PAGE_COUNT = "page_count";
    static final String PAGE_TEXT = "page_text";
    static final String PAGE_CHARS = "page_chars";
    static final String PAGE_IMAGE = "page_image";
    static final String STATUS = "page_status";
    static final String ERROR = "page_error";
    /** The longest page text a row keeps; the image is the page. */
    static final int MAX_TEXT = 20_000;

    static final TaskSpec SPEC = TaskSpec.builder("split_pages", "Split pages", TaskKind.PROCESS)
        .description("Splits each row's PDF (in a workspace bucket) into its pages: one row per page with its text, and each "
            + "page's image kept with the run. Every page becomes a sub-item of its document, with its own status and retry.")
        .input(TaskSpec.rows("Rows naming a PDF each (a key column, Read S3's list), or none: the files the run was started for."))
        .output(TaskSpec.rows("One row per page: the document's columns with file_name and file_stem, then page, page_count, "
            + "page_text, page_chars, page_image, page_status and page_error."))
        .config(FileRows.addressing(JsonSchema.object(), "PDFs")
            .property("dpi", JsonSchema.integer().minimum(72).maximum(200).title("Image resolution (dpi)").defaultValue(100))
            .property("minChars", JsonSchema.integer().minimum(0).maximum(10_000).title("A page needs at least characters")
                .defaultValue(1).description("A page with less text than this failed (a scanned page needs OCR). 0: never."))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a page or file fails")
                .defaultValue("null").description("fail the step, skip the page, or keep it with its text empty and page_error "
                    + "saying why."))
            .property("maxFiles", JsonSchema.integer().minimum(1).maximum(MAX_FILES).title("At most documents").defaultValue(20))
            .property("maxPages", JsonSchema.integer().minimum(1).maximum(MAX_PAGES).title("At most pages a document").defaultValue(200)))
        .backing(TaskSpec.STORAGE)
        .timeoutSeconds(3600)
        .aiToolName("split_pages")
        .items(ItemRole.FAN_OUT).itemSubKey(PAGE).build();

    private final BucketStore buckets;

    public SplitPagesStepTask(BucketStore buckets) {
        super(SPEC);
        this.buckets = buckets;
    }

    @Override
    public Optional<String> unavailable() {
        return this.buckets.unavailable();
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String onError = Configs.text(config, "onError", "null");
        int maxFiles = Configs.integer(config, "maxFiles", 20);
        int maxPages = Configs.integer(config, "maxPages", 200);
        int minChars = Configs.integer(config, "minChars", 1);
        int dpi = Math.max(72, Math.min(200, Configs.integer(config, "dpi", 100)));
        long maxBytes = Math.min(MAX_PDF_BYTES, context.maxFileBytes());

        List<FileRows.Target> targets = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>();
        try (RowSource input = context.openInput()) {
            String keyColumn = FileRows.keyColumn(config, input.columns());
            if (keyColumn == null) {
                targets.addAll(FileRows.runFiles(context, config));
                columns.addAll(Arrays.asList(FileRows.BUCKET, FileRows.KEY));
            } else {
                columns.addAll(input.columns());
                if (input.size() > maxFiles) {
                    throw new IllegalStateException(String.format("The input names %,d files; this step splits at most %d (maxFiles).",
                        input.size(), maxFiles));
                }
                for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                    for (Map<String, Object> row : batch) {
                        targets.add(FileRows.of(context, config, keyColumn, row));
                    }
                }
            }
        }
        FileRows.warnSeveral(context, Configs.text(config, "keyColumn", FileRows.KEY), targets);
        if (targets.size() > maxFiles) {
            throw new IllegalStateException(String.format("The run names %,d files; this step splits at most %d (maxFiles).", targets.size(),
                maxFiles));
        }
        columns.addAll(Arrays.asList(FileRows.FILE_NAME, FileRows.FILE_STEM, PAGE, PAGE_COUNT, PAGE_TEXT, PAGE_CHARS, PAGE_IMAGE, STATUS, ERROR));
        RowSink out = context.output();
        out.declare(new ArrayList<>(columns));

        Path scratch = context.scratch();
        Set<String> taken = new HashSet<>();
        int documents = 0;
        int pagesOut = 0;
        int pagesFailed = 0;
        int filesFailed = 0;
        int total = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Split pages was stopped.");
            }
            FileRows.Target target = targets.get(i);
            long began = System.nanoTime();
            Path spool = scratch.resolve("document-" + (i + 1) + ".pdf");
            try {
                if (target.key.isEmpty()) {
                    throw new IllegalArgumentException(target.missing());
                }
                if (!"pdf".equals(target.extension())) {
                    throw new IllegalArgumentException(String.format("'%s' is not a PDF.", target.fileName()));
                }
                try (InputStream in = this.buckets.open(context.tenantId(), target.bucket, target.key, maxBytes)) {
                    Files.copy(in, spool, StandardCopyOption.REPLACE_EXISTING);
                }
                try (PDDocument pdf = PDDocument.load(spool.toFile(), MemoryUsageSetting.setupTempFileOnly())) {
                    int count = pdf.getNumberOfPages();
                    if (count > maxPages) {
                        throw new IllegalArgumentException(String.format("'%s' has %,d pages; this step splits at most %d a document (maxPages).",
                            target.fileName(), count, maxPages));
                    }
                    total += count;
                    if (total > MAX_PAGES) {
                        throw new IllegalStateException(String.format("The documents have more than %,d pages, the most one step splits.", MAX_PAGES));
                    }
                    PDFRenderer renderer = new PDFRenderer(pdf);
                    PDFTextStripper stripper = new PDFTextStripper();
                    String stem = FileRows.segment(target.row.get(FileRows.FILE_STEM));
                    for (int p = 1; p <= count; p++) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Split pages was stopped.");
                        }
                        long pageBegan = System.nanoTime();
                        Map<String, Object> page = new LinkedHashMap<>(target.row);
                        page.put(PAGE, p);
                        page.put(PAGE_COUNT, count);
                        stripper.setStartPage(p);
                        stripper.setEndPage(p);
                        String text = stripper.getText(pdf).trim();
                        String image = FileRows.unique(FileRows.plainName(String.format("%s-page-%02d.png", stem, p)), taken);
                        BufferedImage rendered = renderer.renderImageWithDPI(p - 1, dpi, ImageType.RGB);
                        StreamContext.KeptFile kept = context.keepFile(image, new ArrayList<>(), 1, o -> ImageIO.write(rendered, "png", o));
                        context.recordOutput(RunOutput.file(image, "png", 1, kept.bytes).withSha256(kept.sha256), page);
                        page.put(PAGE_TEXT, text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
                        page.put(PAGE_CHARS, text.length());
                        page.put(PAGE_IMAGE, image);
                        if (text.length() < minChars) {
                            String why = String.format("Page %d has no text (%d character%s): a scanned page needs OCR.", p, text.length(),
                                text.length() == 1 ? "" : "s");
                            context.itemFailed(page, why);
                            context.itemTook(page, (System.nanoTime() - pageBegan) / 1_000_000L);
                            pagesFailed++;
                            if ("fail".equals(onError)) {
                                throw new IllegalStateException(target.fileName() + ": " + why);
                            }
                            if ("skip".equals(onError)) {
                                continue;
                            }
                            page.put(STATUS, "failed");
                            page.put(ERROR, why);
                        } else {
                            page.put(STATUS, "ok");
                            page.put(ERROR, null);
                            context.itemTook(page, (System.nanoTime() - pageBegan) / 1_000_000L);
                        }
                        out.add(page);
                        pagesOut++;
                    }
                }
                documents++;
                context.itemTook(target.row, (System.nanoTime() - began) / 1_000_000L);
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception fileFailed) {
                String why = String.format("%s: %s", target.key.isEmpty() ? "Row " + (i + 1) : target.key, fileFailed.getMessage());
                if (fileFailed.getMessage() != null && fileFailed.getMessage().startsWith(target.fileName() + ": Page")) {
                    // A page's failure with onError fail: said once, as the page's, and the step fails.
                    throw fileFailed;
                }
                context.itemFailed(target.row, fileFailed.getMessage() == null ? fileFailed.getClass().getSimpleName() : fileFailed.getMessage());
                context.itemTook(target.row, (System.nanoTime() - began) / 1_000_000L);
                filesFailed++;
                if ("fail".equals(onError) || fileFailed instanceof IllegalStateException) {
                    throw new IllegalStateException(why, fileFailed);
                }
                context.warn(why);
                if ("skip".equals(onError)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>(target.row);
                row.put(PAGE, null);
                row.put(PAGE_COUNT, null);
                row.put(PAGE_TEXT, null);
                row.put(PAGE_CHARS, null);
                row.put(PAGE_IMAGE, null);
                row.put(STATUS, "failed");
                row.put(ERROR, fileFailed.getMessage());
                out.add(row);
            } finally {
                Files.deleteIfExists(spool);
            }
        }
        Limits.requireShape(Math.max(0, pagesOut), columns.size(), "The pages");
        context.log(String.format("%d document(s) split into %d page(s)%s%s; %d row(s) out.", documents, pagesOut, pagesFailed == 0 ? "" : String.format(", %d page(s) with no text", pagesFailed),
            filesFailed == 0 ? "" : String.format(", %d file(s) not read", filesFailed), out.size()));
        if (pagesFailed > 0) {
            context.warn(String.format("%d page(s) had no text: %s.", pagesFailed, "skip".equals(onError) ? "skipped" : "kept with page_error"));
        }
        return StepResult.streamed(out.size());
    }
}
