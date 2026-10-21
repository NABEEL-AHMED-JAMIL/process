package process.model.service.impl;

import process.model.dto.MessageQSearchDto;
import process.model.enums.JobStatus;
import process.util.EnumUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * UX review 2026-10-13 (B3): the Runs explorer's facets and histogram (POST message.json/runFacets), and how a run was
 * started, which the run rows (fetchLogs) carry too.
 *
 * <b>Triggers</b>, read from what Core records about a run, first match wins:
 * <ul>
 *   <li>{@code retry} "Retry": a later attempt of the run (job_queue.attempt above 1) -- the schedule's own retries
 *       (V38) and the ones a person or an API client asked for (MIG-345, run_attempt);</li>
 *   <li>{@code api} "API": started through the customer API (MIG-332, api_intake names the run);</li>
 *   <li>{@code document} "Document approved": started by approved documents (MIG-364, document_approval names it);</li>
 *   <li>{@code form} "Form submitted" (ZT-P15): a form submission started it (form_submission names the run);</li>
 *   <li>{@code backfill} "Backfill" (ZT-P15): one of a backfill's runs (backfill_slot names it);</li>
 *   <li>{@code file} "File arrived": started for a file (an input key on the run, or MIG-360's inbox_arrival names it);</li>
 *   <li>{@code manual} "Run now": run_manual -- Run now and Run with;</li>
 *   <li>{@code schedule} "Schedule": the rest, the scheduler's own runs.</li>
 * </ul>
 *
 * <b>Facets</b> are counted the standard faceted-search way: each with every other filter applied but not its own, so
 * picking a status still shows how many runs the other statuses hold. The total and the histogram apply every filter.
 * The histogram's slots are hours when the range is at most two days, else days, in the business time zone's wall clock
 * as every run read writes it (America/Chicago, BusinessTime), and cover the whole range, empty slots included.
 */
public final class RunFacets {

    public static final String RETRY = "retry";
    public static final String API = "api";
    public static final String DOCUMENT = "document";
    public static final String FILE = "file";
    public static final String FORM = "form";
    public static final String BACKFILL = "backfill";
    public static final String MANUAL = "manual";
    public static final String SCHEDULE = "schedule";

    /** Every trigger key with its label, in the order the console lists them. */
    public static final Map<String, String> TRIGGERS;

    static {
        Map<String, String> triggers = new LinkedHashMap<>();
        triggers.put(SCHEDULE, "Schedule");
        triggers.put(MANUAL, "Run now");
        triggers.put(FILE, "File arrived");
        triggers.put(DOCUMENT, "Document approved");
        triggers.put(FORM, "Form submitted");
        triggers.put(BACKFILL, "Backfill");
        triggers.put(API, "API");
        triggers.put(RETRY, "Retry");
        TRIGGERS = Collections.unmodifiableMap(triggers);
    }

    /** The longest range the histogram covers, in days (a year of day slots). */
    public static final int MAX_DAYS = 366;

    public static final String HOUR = "hour";
    public static final String DAY = "day";

    /** The facet rows' first column: which facet the row counts for. */
    static final String STATUS_FACET = "status";
    static final String PIPELINE_FACET = "pipeline";
    static final String TRIGGER_FACET = "trigger";
    static final String SLOT_FACET = "slot";

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private RunFacets() {
    }

    /** The trigger of a run, as SQL over a job_queue alias (the EXISTS reads are on V201/V204/V207's job_queue_id indexes). */
    public static String triggerSql(String run) {
        return "(CASE WHEN " + run + ".attempt > 1 THEN '" + RETRY + "'"
            + " WHEN EXISTS (SELECT 1 FROM api_intake ai WHERE ai.job_queue_id = " + run + ".job_queue_id) THEN '" + API + "'"
            + " WHEN EXISTS (SELECT 1 FROM document_approval da WHERE da.job_queue_id = " + run + ".job_queue_id) THEN '" + DOCUMENT + "'"
            // ZT-P15: a form submission's run carries the submission as its input key; it read as "a file arrived".
            + " WHEN EXISTS (SELECT 1 FROM form_submission fs WHERE fs.job_queue_id = " + run + ".job_queue_id) THEN '" + FORM + "'"
            // ZT-P15: a backfill's runs read "on its timetable".
            + " WHEN EXISTS (SELECT 1 FROM backfill_slot bs WHERE bs.job_queue_id = " + run + ".job_queue_id) THEN '" + BACKFILL + "'"
            + " WHEN " + run + ".input_key IS NOT NULL OR EXISTS (SELECT 1 FROM inbox_arrival ia WHERE ia.job_queue_id = " + run
            + ".job_queue_id) THEN '" + FILE + "'"
            + " WHEN " + run + ".run_manual THEN '" + MANUAL + "'"
            + " ELSE '" + SCHEDULE + "' END)";
    }

    /**
     * The trigger as a column of the FROM clause, {@code tr.run_trigger}: a lateral select of {@link #triggerSql}, so the
     * select lists stay plain column names (DrillDownColumnsTest) and a filter reads the same column.
     */
    public static String triggerJoin(String run) {
        return "left join lateral (select " + triggerSql(run) + " as run_trigger) tr on true \n";
    }

    /** The asked triggers that are real keys, as a SQL list; null when none was asked (no filter). Unknown keys match nothing. */
    static String triggerList(Collection<String> asked) {
        if (asked == null || asked.isEmpty()) {
            return null;
        }
        List<String> known = asked.stream().filter(Objects::nonNull).map(t -> t.trim().toLowerCase())
            .filter(TRIGGERS::containsKey).distinct().map(t -> "'" + t + "'").collect(Collectors.toList());
        return known.isEmpty() ? "null" : String.join(", ", known);
    }

    /** Hours for a range of at most two days, else days. */
    public static String bucketOf(String fromDate, String toDate) {
        long days = ChronoUnit.DAYS.between(LocalDate.parse(fromDate), LocalDate.parse(toDate)) + 1;
        return days <= 2 ? HOUR : DAY;
    }

    /**
     * The answer from the facet query's rows -- {facet, key, name, slot, count} each (QueryService.runFacets): {total,
     * statuses, pipelines, triggers, histogram}. The statuses and pipelines a filter asked for are listed even at 0.
     */
    public static Map<String, Object> assemble(List<Object[]> rows, MessageQSearchDto search, String bucket) {
        Map<String, Long> statuses = new LinkedHashMap<>();
        Map<Long, Object[]> pipelines = new LinkedHashMap<>();
        Map<String, Long> triggers = new LinkedHashMap<>();
        TreeMap<LocalDateTime, Map<String, Long>> slots = new TreeMap<>();
        long total = 0;
        for (Object[] row : rows == null ? Collections.<Object[]>emptyList() : rows) {
            String facet = String.valueOf(row[0]);
            long count = row[4] == null ? 0 : ((Number) row[4]).longValue();
            switch (facet) {
                case STATUS_FACET:
                    statuses.merge(statusName(row[1]), count, Long::sum);
                    break;
                case PIPELINE_FACET:
                    if (row[1] != null) {
                        long id = Long.parseLong(String.valueOf(row[1]));
                        pipelines.put(id, new Object[] {row[2] == null ? null : String.valueOf(row[2]), count});
                    }
                    break;
                case TRIGGER_FACET:
                    triggers.merge(String.valueOf(row[1]), count, Long::sum);
                    break;
                case SLOT_FACET:
                    LocalDateTime at = slotOf(row[3]);
                    if (at != null) {
                        slots.computeIfAbsent(at, k -> new LinkedHashMap<>()).merge(statusName(row[1]), count, Long::sum);
                        total += count;
                    }
                    break;
                default:
                    break;
            }
        }
        if (search.getJobStatuses() != null) {
            search.getJobStatuses().stream().filter(Objects::nonNull).forEach(s -> statuses.putIfAbsent(s.name(), 0L));
        }
        if (search.getTaskDetailIds() != null) {
            search.getTaskDetailIds().stream().filter(Objects::nonNull).forEach(id -> pipelines.putIfAbsent(id, new Object[] {null, 0L}));
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("total", total);
        answer.put("statuses", statuses.entrySet().stream()
            .sorted((a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().compareTo(b.getKey()) : Long.compare(b.getValue(), a.getValue()))
            .map(e -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", e.getKey());
                one.put("count", e.getValue());
                return one;
            }).collect(Collectors.toList()));
        answer.put("pipelines", pipelines.entrySet().stream()
            .sorted((a, b) -> {
                int byCount = Long.compare((Long) b.getValue()[1], (Long) a.getValue()[1]);
                return byCount != 0 ? byCount : String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.getValue()[0]), String.valueOf(b.getValue()[0]));
            })
            .map(e -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("taskDetailId", e.getKey());
                one.put("taskName", e.getValue()[0]);
                one.put("count", e.getValue()[1]);
                return one;
            }).collect(Collectors.toList()));
        List<Map<String, Object>> triggerList = new ArrayList<>();
        TRIGGERS.forEach((key, label) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("key", key);
            one.put("label", label);
            one.put("count", triggers.getOrDefault(key, 0L));
            triggerList.add(one);
        });
        answer.put("triggers", triggerList);

        Map<String, Object> histogram = new LinkedHashMap<>();
        histogram.put("bucket", bucket);
        List<Map<String, Object>> buckets = new ArrayList<>();
        LocalDateTime start = LocalDate.parse(search.getFromDate()).atStartOfDay();
        LocalDateTime end = LocalDate.parse(search.getToDate()).plusDays(1).atStartOfDay();
        for (LocalDateTime at = start; at.isBefore(end); at = HOUR.equals(bucket) ? at.plusHours(1) : at.plusDays(1)) {
            Map<String, Long> counts = slots.getOrDefault(at, Collections.emptyMap());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("start", SLOT.format(at));
            one.put("total", counts.values().stream().mapToLong(Long::longValue).sum());
            one.put("counts", new LinkedHashMap<>(counts));
            buckets.add(one);
        }
        histogram.put("buckets", buckets);
        answer.put("histogram", histogram);
        return answer;
    }

    /** The status as the JobStatus enum names it (Completed), however the column spells it. */
    static String statusName(Object value) {
        if (value == null) {
            return "Unknown";
        }
        String text = String.valueOf(value);
        try {
            JobStatus status = EnumUtils.parseEnum(JobStatus.class, text);
            return status == null ? text : status.name();
        } catch (RuntimeException unknown) {
            return text;
        }
    }

    private static LocalDateTime slotOf(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        return value == null ? null : LocalDateTime.parse(String.valueOf(value).trim().replace(' ', 'T'));
    }

    /** For a filter list of known enum values. */
    static String statusList(Set<JobStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        return statuses.stream().filter(Objects::nonNull).map(s -> "'" + s.name().toUpperCase() + "'").collect(Collectors.joining(","));
    }
}
