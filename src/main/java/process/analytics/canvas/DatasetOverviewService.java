package process.analytics.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsQueryService;
import process.analytics.DatasetRef;
import process.analytics.canvas.dto.AnalysisResultDto;
import process.analytics.canvas.dto.DatasetOverviewDto;
import process.analytics.dto.ColumnProfileDto;
import process.analytics.dto.DatasetProfileDto;
import process.security.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The overview a dataset opens on: the profile, and the few charts worth drawing unasked.
 *
 * Which charts is decided from the profile, not guessed: a date column gives rows over time at a
 * grain that fits its span; a categorical column with a handful of values gives its top values;
 * a numeric column that is not a key gives its spread; missing values give a completeness bar.
 * Each chart is one analysis through the same {@link AnalysisService} the Canvas uses, so it
 * runs behind the same gate and governor, and its request travels with it so the Canvas can
 * open it as-is. A chart that fails is reported on its own tile; the rest still draw.
 *
 * <b>Two charts at a time.</b> Every chart is its own DuckDB session over the same object, and on
 * a remote CSV the scan is the cost: six charts in a row over a 250,000-row file took eight
 * seconds, which is too long for the tab a dataset opens on. Running them {@value #PARALLEL_CHARTS}
 * at a time roughly halves that while leaving half the governor's ceiling of four to everyone
 * else. The worker threads carry the caller's tenant with them, because the resolver decides who
 * may read a connection from the thread it runs on, and a thread with no tenant reads nothing.
 */
@Service
public class DatasetOverviewService {

    private static final Logger logger = LoggerFactory.getLogger(DatasetOverviewService.class);

    /** How many categorical and numeric columns get a chart each; beyond that is the Canvas's job. */
    public static final int TOP_VALUE_CHARTS = 3;
    public static final int SPREAD_CHARTS = 2;
    /** A "top values" column has at least two values and at most this many distinct ones. */
    public static final int TOP_VALUES_MAX_DISTINCT = 50;
    public static final int TOP_VALUES_LIMIT = 8;
    /** Distinct on at least this share of the rows and a numeric column is an identifier, not a measure. */
    static final double KEY_LIKE_SHARE = 0.9;
    static final List<String> NUMERIC_TYPES = Arrays.asList("BIGINT", "INTEGER", "SMALLINT", "TINYINT", "HUGEINT", "DOUBLE", "FLOAT", "DECIMAL", "REAL");
    static final List<String> DATE_TYPES = Arrays.asList("DATE", "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "TIMESTAMPTZ");
    /** How many of the overview's charts run at once: half the engine's permits, never all of them. */
    public static final int PARALLEL_CHARTS = 2;
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final AnalyticsQueryService queries;
    private final AnalysisService analyses;

    public DatasetOverviewService(AnalyticsQueryService queries, AnalysisService analyses) {
        this.queries = queries;
        this.analyses = analyses;
    }

    public DatasetOverviewDto overviewOf(DatasetRef dataset, String connection, String path) throws AnalyticsException {
        long startedAt = System.currentTimeMillis();
        DatasetOverviewDto out = new DatasetOverviewDto();
        DatasetProfileDto profile = this.queries.profileOf(dataset);
        out.setProfile(profile);
        List<ColumnProfileDto> columns = profile.getColumns() == null ? Collections.<ColumnProfileDto>emptyList() : profile.getColumns();
        List<Callable<DatasetOverviewDto.Chart>> charts = new ArrayList<>();
        if (profile.getTotalRows() > 0) {
            ColumnProfileDto date = firstDate(columns);
            if (date != null) charts.add(() -> this.rowsOverTime(connection, path, date));
            int drawn = 0;
            for (ColumnProfileDto column : columns) {
                if (drawn >= TOP_VALUE_CHARTS) break;
                if (isCategorical(column)) { charts.add(() -> this.topValues(connection, path, column)); drawn++; }
            }
            drawn = 0;
            for (ColumnProfileDto column : measuresFirst(columns, profile.getTotalRows())) {
                if (drawn >= SPREAD_CHARTS) break;
                charts.add(() -> this.spread(dataset, column)); drawn++;
            }
        }
        out.getCharts().addAll(this.draw(charts));
        if (columns.stream().anyMatch(c -> c.getNullPercentage() != null && c.getNullPercentage().signum() > 0)) {
            out.getCharts().add(completeness(columns));
        }
        out.setDurationMs(System.currentTimeMillis() - startedAt);
        return out;
    }

    /**
     * Runs the charts {@value #PARALLEL_CHARTS} at a time and returns them in the order they were
     * asked for, each on a thread that is, for its duration, the caller.
     */
    private List<DatasetOverviewDto.Chart> draw(List<Callable<DatasetOverviewDto.Chart>> charts) {
        List<DatasetOverviewDto.Chart> out = new ArrayList<>();
        if (charts.isEmpty()) return out;
        if (charts.size() == 1) { out.add(callQuietly(charts.get(0))); return out; }

        Long tenantId = TenantContext.getTenantId();
        String userRole = TenantContext.getUserRole();
        Long appUserId = TenantContext.getAppUserId();
        String username = TenantContext.getUsername();
        // Named, because "pool-16-thread-2" in the engine's read log says nothing about what read.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL_CHARTS, charts.size()),
            task -> { Thread t = new Thread(task, "overview-chart-" + THREAD_SEQ.incrementAndGet()); t.setDaemon(true); return t; });
        try {
            List<Future<DatasetOverviewDto.Chart>> futures = new ArrayList<>();
            for (Callable<DatasetOverviewDto.Chart> chart : charts) {
                futures.add(pool.submit(() -> {
                    TenantContext.set(tenantId, userRole, appUserId, username);
                    try {
                        return callQuietly(chart);
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            for (Future<DatasetOverviewDto.Chart> future : futures) {
                try {
                    out.add(future.get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    out.add(failed("The overview was interrupted."));
                } catch (ExecutionException ex) {
                    logger.warn("overview: a chart failed outside its own handler", ex.getCause());
                    out.add(failed("The engine could not run this chart."));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** A chart's own run() and spread() already turn failures into tiles; this is the backstop. */
    private static DatasetOverviewDto.Chart callQuietly(Callable<DatasetOverviewDto.Chart> chart) {
        try {
            return chart.call();
        } catch (Exception ex) {
            logger.warn("overview: a chart failed outside its own handler", ex);
            return failed("The engine could not run this chart.");
        }
    }

    private static DatasetOverviewDto.Chart failed(String why) {
        DatasetOverviewDto.Chart chart = chart("failed", "Chart", why, null, null);
        chart.setError(why);
        return chart;
    }

    // ---- which columns deserve a chart ---------------------------------------------------------

    public static ColumnProfileDto firstDate(List<ColumnProfileDto> columns) {
        for (ColumnProfileDto c : columns) {
            if (DATE_TYPES.contains(upper(c.getType())) && !c.isAllNull() && !c.isConstant()) return c;
        }
        return null;
    }

    public static boolean isCategorical(ColumnProfileDto c) {
        return ("VARCHAR".equals(upper(c.getType())) || "BOOLEAN".equals(upper(c.getType())))
            && !c.isAllNull() && !c.isConstant() && !c.isKeyLike()
            && c.getApproxDistinct() >= 2 && c.getApproxDistinct() <= TOP_VALUES_MAX_DISTINCT;
    }

    /**
     * A numeric column that measures something, not one that names something: not a key, not an
     * id by name, and not distinct on nearly every row -- an order number is a BIGINT and its
     * "spread" would be a picture of the row order.
     */
    public static boolean isMeasureLike(ColumnProfileDto c, long totalRows) {
        if (!NUMERIC_TYPES.contains(upper(c.getType())) || c.isAllNull() || c.isConstant() || c.isKeyLike() || c.getApproxDistinct() < 2) return false;
        if (c.getName() != null && c.getName().toLowerCase().matches(".*(^|_)(id|key|no|number)$")) return false;
        return totalRows <= 0 || c.getApproxDistinct() < totalRows * KEY_LIKE_SHARE;
    }

    /** The measures worth a spread chart, amounts (floating types) before counts (integers). */
    static List<ColumnProfileDto> measuresFirst(List<ColumnProfileDto> columns, long totalRows) {
        List<ColumnProfileDto> out = new ArrayList<>();
        for (ColumnProfileDto c : columns) if (isMeasureLike(c, totalRows)) out.add(c);
        out.sort((a, b) -> Boolean.compare(isInteger(a), isInteger(b)));
        return out;
    }

    private static boolean isInteger(ColumnProfileDto c) { return !Arrays.asList("DOUBLE", "FLOAT", "DECIMAL", "REAL").contains(upper(c.getType())); }

    /** DAY up to two months of span, WEEK up to a year, MONTH beyond: enough points to see a shape, never a thousand. */
    public static AnalysisRequest.Grain grainFor(ColumnProfileDto date) {
        try {
            LocalDate from = LocalDate.parse(text(date.getMin()).substring(0, 10)), to = LocalDate.parse(text(date.getMax()).substring(0, 10));
            long days = ChronoUnit.DAYS.between(from, to);
            if (days <= 62) return AnalysisRequest.Grain.DAY;
            if (days <= 366) return AnalysisRequest.Grain.WEEK;
            if (days <= 366 * 5) return AnalysisRequest.Grain.MONTH;
            return AnalysisRequest.Grain.YEAR;
        } catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
            return AnalysisRequest.Grain.MONTH;
        }
    }

    private static String upper(String type) { return type == null ? "" : type.toUpperCase(); }
    private static String text(String value) { return value == null ? "" : value; }

    // ---- the charts ----------------------------------------------------------------------------

    private DatasetOverviewDto.Chart rowsOverTime(String connection, String path, ColumnProfileDto date) {
        AnalysisRequest.Grain grain = grainFor(date);
        AnalysisRequest request = base(connection, path);
        request.setDimensions(Collections.singletonList(date.getName()));
        request.setGrains(Collections.singletonList(grain));
        request.setMeasure(new AnalysisRequest.Measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setSort(new AnalysisRequest.Sort(AnalysisRequest.Sort.By.DIMENSION, AnalysisRequest.Sort.Direction.ASC));
        DatasetOverviewDto.Chart chart = chart("rowsOverTime", "Rows over time", "How many rows fall in each " + grain.getPart() + " of " + date.getName() + ".", date.getName(), request);
        return this.run(chart);
    }

    private DatasetOverviewDto.Chart topValues(String connection, String path, ColumnProfileDto column) {
        AnalysisRequest request = base(connection, path);
        request.setDimensions(Collections.singletonList(column.getName()));
        request.setMeasure(new AnalysisRequest.Measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setSort(new AnalysisRequest.Sort(AnalysisRequest.Sort.By.MEASURE, AnalysisRequest.Sort.Direction.DESC));
        AnalysisRequest.TopN topN = new AnalysisRequest.TopN();
        topN.setLimit(TOP_VALUES_LIMIT); topN.setIncludeOther(true);
        request.setTopN(topN);
        DatasetOverviewDto.Chart chart = chart("topValues", "Rows by " + column.getName(),
            "Which values of " + column.getName() + " the rows carry most -- about " + column.getApproxDistinct() + " distinct.", column.getName(), request);
        return this.run(chart);
    }

    private DatasetOverviewDto.Chart spread(DatasetRef dataset, ColumnProfileDto column) {
        DatasetOverviewDto.Chart chart = chart("spread", "Spread of " + column.getName(),
            "How the figures in " + column.getName() + " are distributed, from " + text(column.getMin()) + " to " + text(column.getMax()) + ".", column.getName(), null);
        try {
            chart.setDistribution(this.queries.distributionOf(dataset, column.getName()));
        } catch (AnalyticsException ex) {
            chart.setError(ex.getMessage());
        } catch (RuntimeException ex) {
            logger.warn("overview: the spread of {} failed", column.getName(), ex);
            chart.setError("The engine could not measure this column.");
        }
        return chart;
    }

    /** Drawn from the profile alone: no query, the null share every column already carries. */
    private static DatasetOverviewDto.Chart completeness(List<ColumnProfileDto> columns) {
        DatasetOverviewDto.Chart chart = chart("completeness", "Missing values", "Which columns have gaps, as the share of rows with no value.", null, null);
        AnalysisResultDto result = new AnalysisResultDto();
        List<List<String>> rows = new ArrayList<>();
        for (ColumnProfileDto c : columns) {
            BigDecimal missing = c.getNullPercentage();
            if (missing != null && missing.signum() > 0) rows.add(Arrays.asList(c.getName(), missing.toPlainString()));
        }
        result.setRows(rows);
        result.setRowCount(rows.size());
        chart.setResult(result);
        return chart;
    }

    private DatasetOverviewDto.Chart run(DatasetOverviewDto.Chart chart) {
        try {
            chart.setResult(this.analyses.analyze(chart.getRequest()));
        } catch (AnalyticsException ex) {
            chart.setError(ex.getMessage());
        } catch (RuntimeException ex) {
            logger.warn("overview: {} failed", chart.getTitle(), ex);
            chart.setError("The engine could not run this chart.");
        }
        return chart;
    }

    private static AnalysisRequest base(String connection, String path) {
        AnalysisRequest request = new AnalysisRequest();
        request.setConnection(connection); request.setPath(path);
        return request;
    }

    private static DatasetOverviewDto.Chart chart(String kind, String title, String question, String column, AnalysisRequest request) {
        DatasetOverviewDto.Chart chart = new DatasetOverviewDto.Chart();
        chart.setKind(kind); chart.setTitle(title); chart.setQuestion(question); chart.setColumn(column); chart.setRequest(request);
        return chart;
    }
}
