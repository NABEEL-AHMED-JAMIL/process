package process.analytics.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.analytics.AnalyticsEngine;
import process.analytics.AnalyticsException;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.analytics.canvas.dto.AnalysisResultDto;
import process.analytics.dto.ColumnDto;
import process.analytics.dto.QueryResultDto;
import process.security.TenantContext;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Analytics Canvas: an analysis model in, a drawable result out, and one governed query between.
 *
 * <b>What this class does that the endpoints must not.</b> It resolves the dataset through
 * {@link DatasetResolver}, so the tenant check happens before anything is opened and the bucket
 * still comes from the connection record. It hands the ENGINE a composer rather than a statement,
 * so the schema that validates the fields is read inside the same session on the same permit. And
 * it shapes what comes back -- the Other bucket, the pivot grid, the crumbs -- after the session
 * has closed, which is the same division profileOf draws between what costs a query and what does
 * not.
 *
 * <b>Drilling is composed here and never by the client.</b> /analyze/drill appends the clicked step
 * to the trail; /analyze/drill-up drops steps from its end; both then run the ordinary analysis
 * path. So a drilled result is not a special kind of result -- it is the same statement with more
 * filters and a substituted dimension, which is what makes it reproducible, cancellable and
 * bounded in exactly the way an undrilled one is.
 *
 * <b>It injects {@link AnalyticsEngine} rather than AnalyticsQueryService.</b> That service's own
 * javadoc says pointing callers at the interface is a one-line change that should happen and had not
 * because three of the four callers were being edited at the time. A new caller has no such excuse,
 * and this one needs nothing the front door adds -- it does not cancel, and cancellation is
 * deliberately not an engine method.
 *
 * @author Nabeel Ahmed
 */
@Service
public class AnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);

    /** What a rolled-up Top-N row is called when the caller does not say. */
    private static final String DEFAULT_OTHER_LABEL = "Other";

    /**
     * The widest grid the pivot section will draw.
     *
     * A grid's cell count is rows times columns, so the column dimension is the axis that turns a
     * readable answer into an unusable payload: two hundred rows against fifty columns is ten
     * thousand cells, and against five thousand columns it is a million. Past this the pivot is
     * withheld with a flag that says why, rather than being sent as a table nobody can read and a
     * browser struggles to hold. The rows themselves are not withheld -- they are the same answer,
     * and analytics.query.max-rows already bounds them.
     */
    private static final int MAX_PIVOT_COLUMNS = 200;

    /**
     * Distinguishes the roll-up row from a dataset value spelled the same way, inside the pivot's
     * key space only. A NUL is used because an object store key cannot contain one, so no real
     * dimension value can collide with it. It never reaches the response.
     */
    private static final String ROLLUP_KEY = "\u0000rollup\u0000";

    /** The root of every breadcrumb trail: the analysis before anything narrowed it. */
    private static final String ROOT_CRUMB = "All rows";

    /** For the roll-up's value list, which the engine returns as JSON so it can be read exactly. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DatasetResolver datasetResolver;
    private final AnalyticsEngine engine;

    /**
     * The clock relative date windows resolve against, or null for the system's.
     *
     * A field so a test can name a day instead of arranging for one. Production leaves it null,
     * which is FilterCompiler's "now on this server" -- see its javadoc for why that is the clock
     * and not the browser's.
     */
    private final LocalDate today;

    /**
     * The bean, wired to the module's one engine.
     *
     * Annotated even though a single-constructor class would not need it, because there are two:
     * without this Spring cannot choose, and the failure is a context that will not start rather
     * than a compile error.
     */
    @Autowired
    public AnalysisService(DatasetResolver datasetResolver, AnalyticsEngine engine) {
        this(datasetResolver, engine, null);
    }

    public AnalysisService(DatasetResolver datasetResolver, AnalyticsEngine engine,
        LocalDate today) {
        this.datasetResolver = datasetResolver;
        this.engine = engine;
        this.today = today;
    }

    /** The analysis as asked for. */
    public AnalysisResultDto analyze(AnalysisRequest request) throws AnalyticsException {
        return this.run(require(request).normalised());
    }

    /**
     * One step further in: the clicked value becomes a filter and the dimension it was on is
     * replaced by the next one.
     *
     * The composition is the whole of this method, and it is three lines because the drill trail is
     * the only state a drill has. Everything that makes the narrowed analysis correct -- the EQ
     * filter, the substituted dimension, the crumb -- is derived from that trail in
     * {@link AnalysisQueryBuilder}, so a drill and a hand-built equivalent produce the same
     * statement.
     */
    public AnalysisResultDto drill(AnalysisRequest request) throws AnalyticsException {
        AnalysisRequest analysis = require(request).normalised();
        AnalysisRequest.Drill step = analysis.getInto();
        if (step == null || step.getDimension() == null || step.getDimension().trim().isEmpty()) {
            throw new AnalyticsException("A drill has to say which dimension was clicked.");
        }
        analysis.getDrillPath().add(step);
        return this.run(analysis);
    }

    /**
     * Back out again: the last steps are dropped, and the dimensions they replaced come back.
     *
     * <b>More steps than there are is not an error, it is the first breadcrumb.</b> Clicking "All
     * rows" is a request to undo everything, and a client that sent the trail length plus one has
     * asked for exactly that. Refusing it would be the application correcting a user who was right.
     */
    public AnalysisResultDto drillUp(AnalysisRequest request) throws AnalyticsException {
        AnalysisRequest analysis = require(request).normalised();
        int steps = analysis.getSteps() == null ? 1 : analysis.getSteps();
        if (steps < 1) {
            throw new AnalyticsException("Drilling up removes at least one step.");
        }
        List<AnalysisRequest.Drill> path = analysis.getDrillPath();
        for (int i = 0; i < steps && !path.isEmpty(); i++) {
            path.remove(path.size() - 1);
        }
        return this.run(analysis);
    }

    /**
     * The one path all three endpoints run down.
     *
     * The composer closure is where the two halves of 07's safety rule meet: it is called with the
     * dataset's own columns, inside the session, and everything it returns is either a name that was
     * in that list or a bound parameter. Nothing between the request body and the statement is a
     * string a caller supplied.
     */
    private AnalysisResultDto run(AnalysisRequest analysis) throws AnalyticsException {
        DatasetRef dataset = this.datasetResolver.resolve(analysis.getConnection(),
            analysis.getPath());
        AnalysisQueryBuilder builder = new AnalysisQueryBuilder(this.today);

        // Held so the result can be read afterwards. The plan is produced INSIDE the engine call,
        // because it needs the schema the engine reads, and it is needed OUTSIDE it, because the
        // columns it describes have to be interpreted once the rows are back. A one-element array
        // would do the same job less legibly.
        AnalysisQueryBuilder.Plan[] planned = new AnalysisQueryBuilder.Plan[1];
        QueryResultDto result = this.engine.analyze(dataset, columns -> {
            planned[0] = builder.plan(analysis, columns);
            return planned[0].getStatement();
        }, analysis.getQueryId());

        AnalysisQueryBuilder.Plan plan = planned[0];
        // The one line anywhere that records an analysis was run. Names no value from the request:
        // the SQL carries none, and a filter's operands are a customer's data.
        logger.info("Analytics canvas ran {} dimensions with {} filters on {} for tenant {} in {} ms",
            plan.getDimensions().size(), plan.getStatement().getParameters().size(), dataset,
            TenantContext.getTenantId(), result.getDurationMs());

        return shape(analysis, plan, result);
    }

    /**
     * The engine's rows, turned into the answer.
     *
     * Everything here happens with no session open and no permit held, which is deliberate and is
     * the same division profileOf draws: the Other bucket, the pivot and the crumbs are all
     * re-arrangements of numbers already computed, and a second query for any of them would be a
     * second permit against a ceiling of four.
     */
    private static AnalysisResultDto shape(AnalysisRequest analysis,
        AnalysisQueryBuilder.Plan plan, QueryResultDto result) {

        int visible = plan.getVisibleColumns();
        List<ColumnDto> columns = new ArrayList<>();
        List<ColumnDto> engineColumns = result.getColumnMeta();
        for (int i = 0; i < visible && i < engineColumns.size(); i++) {
            ColumnDto column = engineColumns.get(i);
            columns.add(new ColumnDto(column.getName(), column.getType(),
                i < plan.getDimensions().size() ? ColumnDto.ROLE_DIMENSION : ColumnDto.ROLE_MEASURE));
        }

        String otherLabel = analysis.getTopN() == null || analysis.getTopN().getOtherLabel() == null
            || analysis.getTopN().getOtherLabel().trim().isEmpty()
            ? DEFAULT_OTHER_LABEL : analysis.getTopN().getOtherLabel().trim();

        List<List<String>> rows = new ArrayList<>(result.getRows().size());
        AnalysisResultDto.OtherBucketDto other = null;
        /** Which result rows are the roll-up, so a real value spelled "Other" stays distinct. */
        List<Integer> rollupRows = new ArrayList<>();
        for (List<String> raw : result.getRows()) {
            List<String> row = new ArrayList<>(raw.subList(0, Math.min(visible, raw.size())));
            if (plan.getRollupMarkerIndex() >= 0 && plan.getRollupMarkerIndex() < raw.size()
                && "false".equalsIgnoreCase(raw.get(plan.getRollupMarkerIndex()))) {
                // The roll-up row. Its first dimension came back as NULL by construction -- the
                // CASE that built the grouping key produced no value for it -- so the label is put
                // in here rather than in the SQL. Doing it in SQL would have meant a literal in the
                // statement and, worse, a row that is indistinguishable from a real value spelled
                // "Other"; the marker column is what tells them apart, and it never leaves.
                row.set(0, otherLabel);
                // Remembered by INDEX, not inferred from the label later.
                //
                // The comment above used to end "the marker column is what tells them apart, and
                // it never leaves" -- and that was the defect rather than the safeguard. Because
                // it never left, nothing downstream could tell a roll-up row from a dataset value
                // genuinely spelled "Other", including the pivot builder in this same class: it
                // keyed its grid on the label, so a real "Other" region and the roll-up collided
                // and one of them was silently dropped. Measured, a 500 vanished from a 740 total
                // with nothing on the response saying a row had gone.
                rollupRows.add(rows.size());
                // MERGED across every roll-up row, not assigned. With two or three dimensions the
                // roll-up is one row PER value of the later dimensions, each carrying the members
                // of its own group -- so assigning here reported whichever came last, undercounted
                // the members, and left values that were rolled up named nowhere in the response.
                other = merge(other, bucket(otherLabel, raw, plan));
            }
            rows.add(row);
        }

        AnalysisResultDto answer = new AnalysisResultDto();
        answer.setColumns(columns);
        answer.setRows(rows);
        answer.setRowCount(rows.size());
        answer.setTruncated(result.isTruncated());
        answer.setDimensions(plan.getDimensions());
        answer.setMeasure(plan.getMeasureAlias());
        answer.setQueryId(result.getQueryId());
        answer.setStatus(result.getStatus());
        answer.setDurationMs(result.getDurationMs());
        answer.setOther(other);
        answer.setCrumbs(crumbs(plan));
        answer.setDrillPath(plan.getDrillPath());
        answer.setPivot(pivot(plan, rows, rollupRows));
        answer.setResolvedWindows(plan.getResolvedWindows().isEmpty()
            ? null : new LinkedHashMap<>(plan.getResolvedWindows()));
        return answer;
    }

    /**
     * What the roll-up stands for, read out of the two columns the builder added for the purpose.
     *
     * The values arrive as JSON because the builder asked DuckDB for JSON. Its own list rendering
     * is "[north, south]" -- unquoted, separated by a comma and a space -- in which a single value
     * containing ", " is indistinguishable from two values, and taking that apart would be a guess
     * dressed as a parse. JSON has an escaping rule and this side has a parser for it.
     *
     * The count comes from a separate exact aggregate rather than from the length of the list,
     * because the list is capped and the count is the number the response actually promises.
     */
    /**
     * Combines the roll-up rows into one description of what was rolled up.
     *
     * With one dimension there is a single roll-up row and this is a no-op. With two or three
     * there is one per value of the later dimensions, each naming only ITS OWN members -- so the
     * bucket has to be a union, and the count has to be a count of the union rather than the last
     * row's. Measured on a five-region, two-department case: the response used to say two values
     * were rolled up where three were, and never named the third anywhere.
     *
     * Distinctness is by value, nulls included, because "the group with no value" is one member
     * and can legitimately appear under several of the later dimension's values.
     */
    private static AnalysisResultDto.OtherBucketDto merge(AnalysisResultDto.OtherBucketDto into,
        AnalysisResultDto.OtherBucketDto next) {

        if (into == null) {
            return next;
        }
        List<String> union = new ArrayList<>(into.getValues());
        for (String value : next.getValues()) {
            if (!union.contains(value)) {
                union.add(value);
            }
        }
        // The count is the union's size once every row has been seen. The per-row counts cannot
        // simply be added -- the same member appears under several of the later dimension's
        // values -- and taking the largest would under-report just as the old code did.
        boolean capped = into.isValuesTruncated() || next.isValuesTruncated();
        long count = capped ? Math.max(into.getValueCount(), next.getValueCount()) : union.size();
        return new AnalysisResultDto.OtherBucketDto(into.getLabel(), union, count, capped);
    }

    private static AnalysisResultDto.OtherBucketDto bucket(String label, List<String> raw,
        AnalysisQueryBuilder.Plan plan) {

        List<String> values = new ArrayList<>();
        long count = 0L;
        if (plan.getOtherValuesIndex() >= 0 && plan.getOtherValuesIndex() < raw.size()) {
            values = listOf(raw.get(plan.getOtherValuesIndex()));
        }
        if (plan.getOtherCountIndex() >= 0 && plan.getOtherCountIndex() < raw.size()) {
            try {
                count = Long.parseLong(String.valueOf(raw.get(plan.getOtherCountIndex())).trim());
            } catch (NumberFormatException ex) {
                // A count that will not parse is a count this response does not claim. It falls
                // back to the length of the list, which is exact whenever nothing was capped.
                count = values.size();
            }
        }
        return new AnalysisResultDto.OtherBucketDto(label, values, count, count > values.size());
    }

    /**
     * The JSON array of rolled-up values, as a list.
     *
     * A null element is a real answer -- the group whose dimension has no value can be one of the
     * ones rolled up -- so it is kept as a null rather than turned into the word. An array that
     * will not parse yields an empty list and the count still stands: this is a description of the
     * bucket, and a description that cannot be read is better absent than invented.
     */
    private static List<String> listOf(String json) {
        List<String> values = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return values;
        }
        try {
            for (JsonNode element : JSON.readTree(json)) {
                values.add(element.isNull() ? null : element.asText());
            }
        } catch (IOException ex) {
            logger.warn("An analysis roll-up returned a value list that could not be read: {}",
                ex.getMessage());
        }
        return values;
    }

    /** The trail, with the root in front of it. */
    private static List<AnalysisResultDto.CrumbDto> crumbs(AnalysisQueryBuilder.Plan plan) {
        List<AnalysisResultDto.CrumbDto> crumbs = new ArrayList<>();
        crumbs.add(new AnalysisResultDto.CrumbDto(ROOT_CRUMB, null, null));
        for (AnalysisQueryBuilder.AnalysisResultCrumb step : plan.getCrumbs()) {
            // A drilled null is labelled rather than left blank: "region: (none)" is a step a user
            // can see they took, and an empty label after a colon reads as a rendering fault.
            String shown = step.getValue() == null ? "(none)" : step.getValue();
            crumbs.add(new AnalysisResultDto.CrumbDto(step.getField() + ": " + shown,
                step.getField(), step.getValue()));
        }
        return crumbs;
    }

    /**
     * The grid, when the analysis has exactly two dimensions.
     *
     * Built from the rows already returned, in one pass, preserving the result's own ordering on
     * both axes -- so the grid's first row is the result's first row and its first column is the
     * column value that appeared first. A grid that re-sorted either axis would disagree with the
     * table beside it.
     *
     * A missing combination stays missing. Filling it with zero would put a point on a chart where
     * there is no data, and a month with no sales is not a month with sales of nothing.
     */
    private static AnalysisResultDto.PivotDto pivot(AnalysisQueryBuilder.Plan plan,
        List<List<String>> rows, List<Integer> rollupRows) {

        if (plan.getDimensions().size() != 2) {
            return null;
        }
        String rowDimension = plan.getDimensions().get(0);
        String columnDimension = plan.getDimensions().get(1);

        Set<String> columnValues = new LinkedHashSet<>();
        for (List<String> row : rows) {
            columnValues.add(row.get(1));
        }
        if (columnValues.size() > MAX_PIVOT_COLUMNS) {
            return new AnalysisResultDto.PivotDto(rowDimension, columnDimension, null, null, true);
        }

        List<String> headers = new ArrayList<>(columnValues);
        // Null keys throughout, which LinkedHashMap and LinkedHashSet both take. The obvious
        // alternative -- String.valueOf on the way in -- would fold a group that has no value into
        // one whose value is the four letters "null", and a dataset is entitled to contain that
        // word. The two are different rows in the table beside the grid and must stay different in
        // it.
        Map<String, Map<String, String>> grid = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            // Keyed on whether this is the ROLL-UP as well as on the label. Keying on the label
            // alone merged a dataset value genuinely spelled "Other" into the roll-up and lost
            // whichever landed first -- 500 disappearing out of a 740 total, silently. The two are
            // different rows in the table beside the grid, and the grid has to agree with it.
            // Concatenation only on the roll-up branch. Prefixing unconditionally turned a NULL
            // row key into the four-letter string "null" -- the precise fold the comment above
            // exists to prevent, reintroduced by the fix for the one below it. A non-roll-up key
            // is passed through untouched, null included.
            String label = row.get(0);
            String key = rollupRows.contains(index) ? ROLLUP_KEY + label : label;
            grid.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                .put(row.get(1), row.get(2));
        }

        List<AnalysisResultDto.PivotRowDto> gridRows = new ArrayList<>(grid.size());
        for (Map.Entry<String, Map<String, String>> entry : grid.entrySet()) {
            List<String> cells = new ArrayList<>(headers.size());
            for (String header : headers) {
                cells.add(entry.getValue().get(header));
            }
            // The marker is a key, not a label. It exists to keep two rows apart in the map and
            // must not reach the screen, where the roll-up is named by the same word the table
            // beside the grid uses.
            String rowLabel = entry.getKey() == null ? null
                : entry.getKey().startsWith(ROLLUP_KEY)
                    ? entry.getKey().substring(ROLLUP_KEY.length()) : entry.getKey();
            gridRows.add(new AnalysisResultDto.PivotRowDto(rowLabel, cells));
        }
        return new AnalysisResultDto.PivotDto(rowDimension, columnDimension, headers, gridRows,
            false);
    }

    private static AnalysisRequest require(AnalysisRequest request) throws AnalyticsException {
        if (request == null) {
            throw new AnalyticsException("There is no analysis to run.");
        }
        return request;
    }
}
