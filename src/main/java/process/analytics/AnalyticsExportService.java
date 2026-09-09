package process.analytics;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import process.analytics.dto.QueryResultDto;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.service.StorageBrowserService;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The three ways an analytics result leaves the browser: a download, a bucket, and an event.
 *
 * Grouped in one class because they are one decision rather than three. Each is a copy of somebody's
 * data crossing out of the request that produced it, and each one is where the module's careful
 * rules about locations stop being enforced by the shape of the API and start having to be enforced
 * on purpose. What is NOT here is the shape this was deliberately scoped away from: there is no
 * submit-to-an-endpoint destination, no field naming a URL, and no outbound HTTP client anywhere in
 * this file. ReportExportServiceImpl has one and reports gaps 1-4 are still open against it -- a
 * TENANT_USER can POST a file to any address they type and read the reply, behind an address guard
 * weaker than its sibling's, on a RestTemplate with no timeouts. Copying its download and bucket
 * halves and not its third one is the whole point; adding that destination later would be adding
 * those four gaps, not adding a feature.
 *
 * <b>Download and write-back are not two renderings of one thing.</b> A download is built HERE, in
 * Java, from a QueryResultDto the engine already returned -- so it can carry a truncation notice
 * inside its own bytes, and its cells can be defused against a spreadsheet reading them as formulas.
 * A write-back is built by the ENGINE, streamed straight into the bucket by COPY, so it can carry
 * neither. That difference is why the two answer the completeness question in different ways, and
 * section "what a reader can tell about a file" below says how.
 *
 * <b>What a reader can tell about a file.</b> A downloaded CSV or TSV that stopped at the row
 * ceiling ends with a marker row saying so, and its filename ends "-partial"; a downloaded JSON
 * carries truncated and a notice as fields of the object it is. A written-back object needs none of
 * that, because a truncated one is never written: the row count is measured before the COPY and a
 * query that would overflow the ceiling is refused with nothing put in the bucket. A file in a
 * bucket that exists is complete.
 *
 * <b>Where the destination comes from.</b> Nowhere in this class does a caller name a bucket or a
 * URL. The caller names the same connection alias every other analytics endpoint takes, and the
 * output key is resolved through DatasetResolver like any other path -- so the tenant check, the
 * status check, the provider check and the bucket-from-the-record rule all apply to the place a
 * file is WRITTEN exactly as they apply to the place one is read. The key is allow-listed here
 * first, and AnalyticsQueryService checks the composed URL again before it goes into SQL, because
 * phase three already showed what one uninspected half of a name costs.
 *
 * @author Nabeel Ahmed
 */
@Service
public class AnalyticsExportService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsExportService.class);

    /**
     * What an output key may contain.
     *
     * DatasetResolver's read allow-list minus three things, and each subtraction is a location a
     * write could otherwise reach. The glob characters go because a read of many files is a
     * feature and a write to many files is a file with a star in its name that nobody can find
     * again. The space goes because no key this class generates needs one and a trailing space is
     * the classic invisible difference between two objects. Everything the read list already
     * refused -- the quote that ends a SQL literal, the backslash that escapes one, the colon that
     * starts a scheme -- stays refused here for the same reasons.
     */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._/=+-]+");

    /** What a caller's file name is reduced to before it is used. Letters, digits and hyphens. */
    private static final Pattern UNSAFE_IN_A_NAME = Pattern.compile("[^a-z0-9]+");

    /** S3 allows 1024 bytes; this is far under it, and a key nobody can read is not a good key. */
    private static final int MAX_KEY_LENGTH = 400;

    private static final int MAX_NAME_LENGTH = 60;

    /** Where an export lands when the caller does not say. Beside the data, not among it. */
    private static final String DEFAULT_FOLDER = "analytics-exports";

    private static final String DEFAULT_NAME = "analytics-export";

    /**
     * Cells opening with these are formulas to a spreadsheet, and a downloaded CSV exists to be
     * opened in one. The values came out of somebody's data, so the first character of a cell is
     * not ours to trust; a leading apostrophe makes Excel read the rest as text. The same defence
     * ReportExportServiceImpl already applies, and deliberately NOT applied to a written-back
     * object, whose reader is a pipeline that would then have to strip the apostrophes back out.
     */
    private static final Pattern FORMULA_LEAD = Pattern.compile("^[=+\\-@\\t\\r]");

    /** Reads as a comment to most tools and as text to every spreadsheet. */
    private static final String TRUNCATION_MARKER = "# INCOMPLETE EXPORT";

    /**
     * The topic a completed query is announced on.
     *
     * A constant rather than a property, and the reason is worth stating because the module's own
     * rule is that an operator must be able to discover every setting. A property read through an
     * @Value default and declared in no profile is the third of the four gaps this file was scoped
     * to avoid inheriting -- so rather than add an undeclared one, the name is fixed here. Making
     * it configurable is three lines in the three profiles and one entry in
     * ApplicationPropertiesDeclarationTest, and belongs with whoever owns those files.
     */
    public static final String QUERY_COMPLETED_TOPIC = "analytics.query.completed";

    /** How many announcements may be waiting before the oldest are dropped rather than queued. */
    private static final int ANNOUNCEMENT_BACKLOG = 64;

    /**
     * Milliseconds, not seconds.
     *
     * At second resolution two write-backs of the same dataset into the same folder produced an
     * IDENTICAL key, and copyTo does not check whether anything is already there, so the second
     * silently replaced the first. The stamp's stated purpose is to stop an export overwriting the
     * dataset it was derived from; it did not stop an export overwriting another export, in a
     * class whose own argument is that a storage connection has no undo.
     *
     * Milliseconds narrow the window rather than close it, which is why the existence check below
     * exists as well. Two mechanisms because the cost of being wrong is somebody's data.
     */
    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final DatasetResolver datasetResolver;
    private final AnalyticsQueryService analyticsQueryService;
    private final AnalyticsLimits limits;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;
    /**
     * Only to ask whether a target key is already taken before writing over it.
     *
     * Reads through the platform's own storage service rather than a second client, which is what
     * specification 04 asks for and what keeps one storage walk in the application.
     */
    private final StorageBrowserService storageBrowserService;
    private final Gson gson = new Gson();

    /**
     * The thread an event is published on, and the reason there is one at all.
     *
     * KafkaTemplate.send() is asynchronous in its result and not in its start: a producer with no
     * metadata for the topic blocks in send() for max.block.ms, which defaults to a minute. On a
     * request thread that turns a broker nobody noticed was down into a minute-long analytics
     * query, which is a worse outcome than the event being lost. The rule for this event is that it
     * cannot fail a query; a thread of its own is what makes it also unable to SLOW one.
     *
     * Bounded, and dropping is deliberate. An announcement that has been waiting behind sixty-four
     * others is describing a query the user has long since forgotten, and growing the queue to keep
     * it would trade a lost audit line for heap.
     */
    private final ThreadPoolExecutor announcements;

    public AnalyticsExportService(DatasetResolver datasetResolver,
        AnalyticsQueryService analyticsQueryService, AnalyticsLimits limits,
        KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver,
        StorageBrowserService storageBrowserService) {
        this.datasetResolver = datasetResolver;
        this.analyticsQueryService = analyticsQueryService;
        this.limits = limits;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
        this.storageBrowserService = storageBrowserService;
        this.announcements = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(ANNOUNCEMENT_BACKLOG),
            work -> {
                // Daemon, like the query watchdog: an audit side effect must never be the reason
                // the application will not shut down.
                Thread thread = new Thread(work, "analytics-query-events");
                thread.setDaemon(true);
                return thread;
            },
            (work, pool) -> logger.warn("An analytics query event was dropped: {} are already "
                + "waiting to be published.", ANNOUNCEMENT_BACKLOG));
    }

    /**
     * Where a download or a write-back sent its result. Carried on the event, never a URL.
     */
    public enum Destination {
        /** A query that was run and returned, which is what /analytics.json/query does. */
        NONE,
        DOWNLOAD,
        BUCKET
    }

    /**
     * A result handed back as a file for the browser to save.
     *
     * The rows come from the same query() every other caller uses, so the row ceiling, the gate and
     * the governor apply unchanged and a result that hit the ceiling arrives here already flagged.
     * A blank statement reads the whole dataset, which is the "just give me this file as CSV" case
     * and is a query like any other -- it is composed here as SELECT * FROM dataset rather than
     * given a path of its own, so there is still only one way to run SQL in this module.
     *
     * Parquet is not offered. Writing one needs either the engine, which cannot reach a local
     * destination and must not be given one, or parquet-mr, which is not a dependency of this
     * project and is not worth becoming one for a file a browser is about to save. Write-back
     * offers Parquet because there the engine is already writing to a place it is allowed to write.
     */
    public ResponseDto download(Map<String, String> request) {
        Map<String, String> body = request == null ? Collections.emptyMap() : request;
        long startedAt = System.currentTimeMillis();
        String format = value(body.get("format"), "csv").toLowerCase(Locale.ROOT);
        if (!"csv".equals(format) && !"tsv".equals(format) && !"json".equals(format)) {
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "A download can be CSV, TSV or JSON. Parquet is written to a bucket, not downloaded.");
        }

        DatasetRef primary;
        DatasetRef secondary;
        QueryResultDto result;
        try {
            primary = this.datasetResolver.resolve(body.get("connection"), body.get("path"));
            secondary = this.secondDataset(body);
            result = this.analyticsQueryService.query(primary, secondary, statementOf(body));
        } catch (AnalyticsException ex) {
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage());
        }

        String payload = "json".equals(format)
            ? this.toJson(primary, result)
            : this.toDelimited(result, "tsv".equals(format) ? '\t' : ',');
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        String filename = this.fileName(body.get("fileName"), primary, format, result.isTruncated());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filename", filename);
        data.put("contentType", contentTypeOf(format));
        data.put("content", Base64.getEncoder().encodeToString(bytes));
        data.put("bytes", bytes.length);
        data.put("rowCount", result.getRowCount());
        data.put("truncated", result.isTruncated());
        if (result.isTruncated()) {
            data.put("notice", this.truncationNotice());
        }

        // info, and not debug, for the reason AnalyticsQueryService gives its own read line: this
        // is the only durable record that a copy of somebody's data left the application. The
        // event below is better and can be lost -- a broker that is down produces a warning and
        // nothing else -- so the answer to "who took a copy" cannot rest on it alone.
        logger.info("Analytics export: {} rows of {} downloaded as {} by {} of tenant {}{}",
            result.getRowCount(), primary, format, TenantContext.getUsername(),
            TenantContext.getTenantId(), result.isTruncated() ? ", truncated" : "");
        this.publishQueryCompleted(primary, secondary, "SUCCESS", (long) result.getRowCount(),
            result.isTruncated(), System.currentTimeMillis() - startedAt, Destination.DOWNLOAD, null);
        // The message says it too, because a caller that ignores the flag still shows the message.
        return new ResponseDto(ProcessUtil.SUCCESS, result.isTruncated()
            ? "Export ready, and incomplete -- " + this.truncationNotice()
            : "Export ready: " + result.getRowCount() + " row(s).", data);
    }

    /**
     * A result written into the storage connection it was read from.
     *
     * The caller names a folder and, if they like, a base file name; they never name the bucket,
     * and they do not name the file either. The stamp this class adds is not decoration: an export
     * that could be given an exact key could overwrite the dataset it was derived from, and a
     * storage connection has no undo.
     *
     * Written whole or not written. See AnalyticsQueryService.copyTo -- the row count is taken
     * before the COPY, and a query over the ceiling is refused with nothing in the bucket, because
     * an object cannot carry the "there may be more" that a download says in its own last line.
     */
    public ResponseDto writeBack(Map<String, String> request) {
        Map<String, String> body = request == null ? Collections.emptyMap() : request;
        long startedAt = System.currentTimeMillis();
        String format = value(body.get("format"), "csv").toLowerCase(Locale.ROOT);
        if (!"csv".equals(format) && !"tsv".equals(format) && !"json".equals(format)
            && !"parquet".equals(format)) {
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "An export is written as CSV, TSV, JSON or Parquet.");
        }

        try {
            DatasetRef primary = this.datasetResolver.resolve(body.get("connection"),
                body.get("path"));
            DatasetRef secondary = this.secondDataset(body);
            // The alias is the one the caller already named for the dataset. There is no second
            // field, so "write these rows into that other connection's bucket" has nowhere to be
            // written -- and copyTo refuses a target on any other connection even if one arrives.
            DatasetRef target = this.outputTarget(body, primary, format);
            this.refuseToOverwrite(primary, target, body);

            long rows = this.analyticsQueryService.copyTo(primary, secondary, statementOf(body),
                target);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connection", primary.getConnection().getAlias());
            // The bucket is named back to the caller, as /analytics.json/schema already names it.
            // It is not on the event, which travels further than this response does.
            data.put("bucket", target.getBucket());
            data.put("key", target.getPath());
            data.put("format", target.getFormat().name());
            data.put("rowCount", rows);
            // Stated rather than implied. Every object this endpoint writes is the whole result,
            // and a caller should be able to read that off the response instead of inferring it.
            data.put("truncated", false);

            // The same reasoning as the download line, one degree more so: reports gap 20 is
            // "no record of a successful bucket write", still open on the exporter this one was
            // modelled on, and a write leaves an object somebody else's pipeline will read.
            // DatasetRef.toString names the location without the credentials that reach it, which
            // is what makes it safe to put in a log at all.
            logger.info("Analytics export: {} rows written to {} by {} of tenant {}",
                rows, target, TenantContext.getUsername(), TenantContext.getTenantId());
            this.publishQueryCompleted(primary, secondary, "SUCCESS", rows, false,
                System.currentTimeMillis() - startedAt, Destination.BUCKET, target);
            return new ResponseDto(ProcessUtil.SUCCESS,
                "Wrote " + rows + " row(s) to " + target.getBucket() + "/" + target.getPath() + ".",
                data);
        } catch (AnalyticsException ex) {
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage());
        }
    }

    /**
     * Announces that a query finished, on the platform's own producer.
     *
     * Public because the query endpoint should call it too and this is the only class in the module
     * that holds a producer; the export paths call it for themselves. Nothing here can fail a
     * query: the payload is built inside a try, the send happens on another thread, and a broker
     * that is down produces a warning in the log and nothing else. An audit side effect that can
     * fail a read is worse than no audit side effect.
     *
     * <b>What the event carries, and what it must never carry.</b> The same rule AnalyticsQueryRun
     * states for its columns: a connection ALIAS and a path, never a credential and never a
     * resolved bucket URL. A history row is behind a tenant filter in this application's own
     * database; an event is on a topic that other systems subscribe to, so if anything the rule is
     * stricter here -- which is why the bucket is on the write-back RESPONSE and not on this.
     *
     * The SQL is not on it either. An event is read by machines deciding what to do next, and the
     * statement is neither necessary for that nor free of the reporting logic somebody wrote; the
     * history table is where a statement is kept, and it is readable only inside the workspace that
     * ran it.
     */
    public void publishQueryCompleted(DatasetRef primary, DatasetRef secondary, String status,
        Long rowCount, boolean truncated, long durationMs, Destination destination,
        DatasetRef target) {
        try {
            if (this.kafkaTemplateProvider == null || this.kafkaConnectionResolver == null
                || primary == null) {
                return;
            }
            // Read on THIS thread. TenantContext is a ThreadLocal, and the publishing thread has
            // no principal on it at all -- reading it there would attribute every event to nobody.
            Long tenantId = TenantContext.getTenantId();
            String username = TenantContext.getUsername();

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", QUERY_COMPLETED_TOPIC);
            event.put("occurredAt", Instant.now().toString());
            event.put("tenantId", tenantId);
            event.put("username", username);
            event.put("connectionAlias", primary.getConnection().getAlias());
            event.put("datasetPath", primary.getPath());
            if (secondary != null) {
                event.put("secondConnectionAlias", secondary.getConnection().getAlias());
                event.put("secondDatasetPath", secondary.getPath());
            }
            event.put("status", status);
            event.put("rowCount", rowCount);
            event.put("truncated", truncated);
            event.put("durationMs", durationMs);
            event.put("destination", (destination == null ? Destination.NONE : destination).name());
            if (target != null) {
                // The key alone. Not the bucket, not the URL -- a subscriber that needs to fetch
                // this object goes through the connection alias like everything else does.
                event.put("outputPath", target.getPath());
                event.put("outputFormat", target.getFormat().name());
            }
            String payload = this.gson.toJson(event);
            // Keyed by workspace so one tenant's events stay in order relative to each other,
            // which is the only ordering a consumer of this topic can use.
            String key = tenantId == null ? "platform" : String.valueOf(tenantId);

            this.announcements.execute(() -> this.send(tenantId, key, payload));
        } catch (Exception ex) {
            // Includes the executor refusing the task, which its own handler has already logged.
            logger.warn("An analytics query event could not be prepared: {}", ex.getMessage());
        }
    }

    private void send(Long tenantId, String key, String payload) {
        try {
            KafkaTemplate<String, String> template = this.kafkaTemplateProvider.getTemplate(
                // No source task type: this event belongs to a workspace rather than to a job, so
                // the resolver falls through to the tenant's default profile and then to the
                // platform's -- the same fallback every dispatch already uses.
                this.kafkaConnectionResolver.resolve(tenantId, null));
            template.send(QUERY_COMPLETED_TOPIC, key, payload).addCallback(
                sent -> logger.debug("Published an analytics query event on {}.",
                    QUERY_COMPLETED_TOPIC),
                failed -> logger.warn("An analytics query event was not published: {}",
                    failed.getMessage()));
        } catch (Exception ex) {
            logger.warn("An analytics query event was not published: {}", ex.getMessage());
        }
    }

    /** Stops the publishing thread with the application, for the reason shutdown() gives. */
    @PreDestroy
    public void shutdown() {
        this.announcements.shutdownNow();
    }

    // ---- the destination ----------------------------------------------------------------------

    /**
     * The object an export will be written to, as a DatasetRef and therefore as something
     * DatasetResolver agreed to.
     *
     * Reusing the read resolver for a write is the point of this method rather than a shortcut.
     * Every rule it applies to a path being read -- the caller owns this connection, the connection
     * is Active, the provider is one the engine can reach, the bucket comes from the record and not
     * from the request -- is a rule that has to hold for a path being written, and re-implementing
     * them beside it would be two lists that agree until one of them is edited.
     *
     * What the resolver will not do is refuse a glob, because a glob is legal in a path being READ.
     * So the key is checked here first, against a list that has never admitted one, and the
     * resolved ref is then checked to be the same key it was handed -- a resolver that normalised
     * or reinterpreted the string would otherwise be admitting a location this method never saw.
     */
    /**
     * Refuses to write over an object that is already there.
     *
     * The reliability half of the specification's "idempotent export/write operations", and the
     * defect it closes was real: with a second-resolution stamp, two write-backs of the same
     * dataset into the same folder inside one second built the same key, and nothing anywhere
     * looked to see whether that key was taken. The second write replaced the first and reported
     * success. An object store has no undo and this class says so to the user; it was not saying
     * it to itself.
     *
     * An explicit "overwrite": "true" is honoured, because replacing yesterday's export on purpose
     * is a real thing to want. What is refused is doing it by accident.
     *
     * The lookup asks by CONNECTION ALIAS, not by bucket name. StorageBrowserService's first
     * parameter is named "bucket" and is resolved with findByAliasAndStatus, so an alias is what
     * it wants -- passing DatasetRef.getBucket() there was a real bug in the benchmark harness,
     * silent because the two happen to be equal in this environment and are not equal in general.
     *
     * A storage error is NOT treated as "the object is absent". Failing open here would restore
     * exactly the clobber this method exists to prevent, so an unreadable answer refuses the write
     * and says why.
     */
    private void refuseToOverwrite(DatasetRef primary, DatasetRef target, Map<String, String> body)
        throws AnalyticsException {

        if (Boolean.parseBoolean(value(body.get("overwrite"), "false"))) {
            return;
        }
        String alias = primary.getConnection().getAlias();
        try {
            if (this.storageBrowserService.getObjectMetadata(alias, target.getPath()) != null) {
                throw new AnalyticsException("There is already a file at " + target.getPath()
                    + ". Nothing was written. Choose another name, or send overwrite=true if you "
                    + "meant to replace it -- a storage connection has no undo.");
            }
        } catch (AnalyticsException refusal) {
            throw refusal;
        } catch (Exception ex) {
            // "Not found" is the answer this method is hoping for and every store spells it
            // differently, so it is recognised by shape rather than by class: a message that says
            // the object is not there is an absence, and anything else is an unknown.
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            boolean absent = message.contains("not found") || message.contains("nosuchkey")
                || message.contains("does not exist") || message.contains("404");
            if (!absent) {
                logger.warn("Could not check whether {} already exists before writing it back.",
                    target, ex);
                throw new AnalyticsException("Could not check whether something is already at "
                    + target.getPath() + ", so nothing was written. Try again, or send "
                    + "overwrite=true to write regardless.");
            }
        }
    }

    private DatasetRef outputTarget(Map<String, String> body, DatasetRef primary, String format)
        throws AnalyticsException {

        String folder = value(body.get("folder"), DEFAULT_FOLDER).trim()
            .replaceAll("^/+", "").replaceAll("/+$", "");
        if (folder.isEmpty()) {
            folder = DEFAULT_FOLDER;
        }
        String key = folder + "/" + this.fileName(body.get("fileName"), primary, format, false);
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new AnalyticsException("That folder contains characters an export cannot be "
                + "written to. Letters, digits, dots, dashes and slashes.");
        }
        // After the allow-list rather than instead of it, exactly as DatasetResolver argues: ".."
        // is made of permitted characters, and climbing is the one shape an allow-list cannot
        // exclude. The empty segment is here for the same reason -- "a//b" is a key that reads as
        // one thing to a person and addresses another object in the store.
        // A "." segment too. StorageBrowserServiceImpl.isSafeKey (:405-418) refuses both
        // "." and ".." as segments, and this refused only the second -- so an export could
        // put an object into a tenant's own bucket that the application's Object Browser
        // then refuses to preview, download or read metadata for. "./x" and "x" are also
        // the same object to a gateway that normalises and two different objects to one
        // that does not, which is the exact hazard the "//" rule above was added for.
        if (key.contains("..") || key.contains("//") || key.startsWith("/") || key.endsWith("/")
            || hasDotSegment(key)) {
            throw new AnalyticsException("An export folder cannot contain \"..\", an empty step, "
                + "or a leading slash.");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new AnalyticsException("That export folder is too long.");
        }

        DatasetRef target = this.datasetResolver.resolve(
            primary.getConnection().getAlias(), key);
        if (!key.equals(target.getPath()) || target.isMultiFile()
            || target.getFormat() != formatOf(format)) {
            // Not expected to fire. It is the assertion that this method and the resolver still
            // agree about what the string means, and the day they stop is the day a location check
            // has a hole in it that nothing else would notice.
            throw new AnalyticsException("That is not a location an export can be written to.");
        }
        return target;
    }

    /**
     * The file name an export is given: a base the caller may choose, a stamp they may not.
     *
     * The stamp is what makes a write-back safe to offer at all. Without it a caller could name an
     * exact key, and the most obvious key to name is the one the dataset they are reading already
     * occupies.
     */
    private String fileName(String requested, DatasetRef primary, String format,
        boolean truncated) {
        String base = value(requested, defaultNameFor(primary)).toLowerCase(Locale.ROOT);
        base = UNSAFE_IN_A_NAME.matcher(base).replaceAll("-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = DEFAULT_NAME;
        }
        if (base.length() > MAX_NAME_LENGTH) {
            base = base.substring(0, MAX_NAME_LENGTH).replaceAll("-+$", "");
        }
        // In the NAME as well as in the bytes. A file gets renamed, mailed and copied out of the
        // browser that knew it was partial, and the last line of a CSV is the first thing a
        // spreadsheet user scrolls past.
        String partial = truncated ? "-partial" : "";
        return base + "-" + LocalDateTime.now().format(FILE_STAMP) + partial + "." + format;
    }

    /** The dataset's own file name, so an export is recognisable without being told what to call it. */
    private static String defaultNameFor(DatasetRef primary) {
        String path = primary.getPath();
        int slash = path.lastIndexOf('/');
        String last = slash < 0 ? path : path.substring(slash + 1);
        int dot = last.lastIndexOf('.');
        return dot <= 0 ? last : last.substring(0, dot);
    }

    private static DatasetRef.Format formatOf(String format) {
        return DatasetRef.Format.valueOf(format.toUpperCase(Locale.ROOT));
    }

    /** The real one for each, not "text/tsv", which no reader has ever been configured for. */
    private static String contentTypeOf(String format) {
        switch (format) {
            case "json": return "application/json";
            case "tsv":  return "text/tab-separated-values";
            default:     return "text/csv";
        }
    }

    // ---- the file -------------------------------------------------------------------------------

    /**
     * The result as delimited text, with the one thing it cannot leave out.
     *
     * The marker row is a full-width row rather than a single cell, and that is a choice between
     * two imperfect answers. A one-cell row is a ragged record that a strict parser rejects -- loud,
     * which has something to be said for it, but it turns "your export is short" into "your export
     * is broken" for a script that was going to read it correctly otherwise. A full-width row parses
     * everywhere and shows up in the first column of every spreadsheet. What it costs is that a
     * reader counting rows counts one more than the data has, which is why the marker says so in
     * words in the cell they will read, and why the file name says it as well.
     */
    private String toDelimited(QueryResultDto result, char delimiter) {
        List<String> columns = result.getColumns() == null
            ? Collections.<String>emptyList() : result.getColumns();
        StringBuilder out = new StringBuilder();
        appendRow(out, columns, delimiter);
        if (result.getRows() != null) {
            for (List<String> row : result.getRows()) {
                out.append('\n');
                appendRow(out, row, delimiter);
            }
        }
        if (result.isTruncated()) {
            List<String> marker = new ArrayList<>();
            marker.add(TRUNCATION_MARKER + ": " + this.truncationNotice());
            while (marker.size() < columns.size()) {
                marker.add("");
            }
            out.append('\n');
            appendRow(out, marker, delimiter);
        }
        return out.append('\n').toString();
    }

    private void appendRow(StringBuilder out, List<String> cells, char delimiter) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(delimiter);
            }
            out.append(escape(cells.get(i), delimiter));
        }
    }

    private static String escape(String value, char delimiter) {
        if (value == null) {
            // A null and the four characters "null" are different facts, and only one of them is
            // in the data. Empty is how every CSV reader spells the first.
            return "";
        }
        String text = value;
        if (FORMULA_LEAD.matcher(text).find() && !isNumeric(text)) {
            // Numbers are left alone: prefixing one turns a figure into text a sheet cannot total,
            // and a leading minus sign is far more often a negative number than a formula.
            text = "'" + text;
        }
        if (text.indexOf(delimiter) >= 0 || text.indexOf('"') >= 0
            || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static boolean isNumeric(String text) {
        try {
            Double.parseDouble(text.trim());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * The result as JSON, in the same shape the API already returns it in.
     *
     * Rows are arrays with the column names in a list beside them, rather than a list of objects,
     * which is the friendlier form and is not what this does. Two reasons, and the weaker one first
     * so it is not mistaken for the argument: an object's keys have to be unique and its order is
     * only conventionally preserved, while a result's columns are ordered because the query said so.
     * DuckDB turns out to defend the uniqueness half by itself -- measured on 1.1.3,
     * "SELECT id AS a, region AS a" comes back labelled a and a_1 -- so that is a hazard this shape
     * avoids rather than one it fixes. The real reason is that this file and QueryResultDto
     * describe the same result, and a reader who has seen one should not have to learn a second
     * shape to read the other.
     *
     * Because this file IS an object, the completeness answer goes inside it as a field rather than
     * as a marker row -- which is the one place in this class where the honest answer is also the
     * tidy one.
     */
    private String toJson(DatasetRef primary, QueryResultDto result) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("exportedAt", Instant.now().toString());
        // Provenance by alias and path, the same pair AnalyticsQueryRun keeps and for the same
        // reason: it stays true when a connection is repointed, and it is not a location.
        document.put("connection", primary.getConnection().getAlias());
        document.put("path", primary.getPath());
        document.put("columns", result.getColumns());
        document.put("rowCount", result.getRowCount());
        document.put("truncated", result.isTruncated());
        if (result.isTruncated()) {
            document.put("notice", this.truncationNotice());
        }
        document.put("rows", result.getRows());
        return this.gson.toJson(document);
    }

    private String truncationNotice() {
        return "this export stopped at the " + this.limits.getMaxRows()
            + "-row limit and there may be more rows.";
    }

    // ---- the request ------------------------------------------------------------------------------

    /**
     * The second dataset, asked for only when named.
     *
     * Either half being present is enough to ask, which is the rule /analytics.json/query already
     * follows: a caller who sent half a second dataset has made a mistake and is better told which
     * half than quietly given a one-dataset answer.
     */
    private DatasetRef secondDataset(Map<String, String> body) throws AnalyticsException {
        if (hasText(body.get("connection2")) || hasText(body.get("path2"))) {
            return this.datasetResolver.resolve(body.get("connection2"), body.get("path2"));
        }
        return null;
    }

    /**
     * The statement to run, which is the caller's when they wrote one.
     *
     * A blank statement is not an error and is not a second code path: exporting a dataset is
     * exporting the result of reading all of it, so it is composed into the same SELECT anybody
     * else would have typed and goes through the same gate.
     */
    private static String statementOf(Map<String, String> body) {
        String sql = body.get("sql");
        return hasText(sql) ? sql : "SELECT * FROM " + AnalyticsQueryService.DATASET;
    }

    private static String value(String given, String fallback) {
        return hasText(given) ? given.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Whether any path segment is "." — a step that means "here" and addresses nothing.
     *
     * Its own method because two layers check it and a copy that drifted would leave one of them
     * admitting what the other refuses. Split with a -1 limit so a trailing segment is not dropped.
     */
    private static boolean hasDotSegment(String key) {
        for (String segment : key.split("/", -1)) {
            if (".".equals(segment)) {
                return true;
            }
        }
        return false;
    }

}
