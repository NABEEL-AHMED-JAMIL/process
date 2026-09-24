package process.time;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MIG-28 / MIG-96 / MIG-163: which timestamp columns V100 converts, which it leaves, and why -- and the
 * reconciliation arithmetic the migration tests and the full-copy rehearsal share.
 *
 * The inventory is asserted against a database built from the changelog: after V100 the columns still
 * `timestamp without time zone` must be exactly {@link #LEFT_NAIVE}. A column added later as a naive
 * timestamp, or one V100 missed, fails that test by name.
 */
public final class TimestampColumns {

    /** Converted by V100: process's own tables, every value America/Chicago wall-clock. Mirrors V100's list. */
    public static final Map<String, List<String>> CONVERTED;

    /** Still naive after V100 in a database built from the changelog, each with the reason it stays. */
    public static final Map<String, String> LEFT_NAIVE;

    static {
        Map<String, List<String>> converted = new LinkedHashMap<>();
        converted.put("app_user", Arrays.asList("date_created", "last_login_at"));
        converted.put("job_audit_logs", Collections.singletonList("date_created"));
        converted.put("job_queue", Arrays.asList("callback_token_expires_at", "date_created", "end_time", "next_attempt_at",
            "prepare_lease_until", "prepared_at", "refused_callback_at", "skip_time", "start_time"));
        converted.put("kafka_connection_profile", Arrays.asList("date_created", "last_tested_at"));
        converted.put("lookup_data", Collections.singletonList("date_created"));
        converted.put("page_access_profile", Arrays.asList("date_created", "date_updated"));
        converted.put("pipeline", Collections.singletonList("date_created"));
        converted.put("scheduler", Arrays.asList("date_created", "date_updated", "next_run_at"));
        converted.put("shedlock", Arrays.asList("lock_until", "locked_at"));
        converted.put("source_job", Arrays.asList("date_created", "last_job_run"));
        converted.put("tenant", Collections.singletonList("date_created"));
        converted.put("tenant_request", Arrays.asList("date_created", "decided_at"));
        converted.put("tenant_task_type_kafka_route", Collections.singletonList("date_created"));
        converted.put("user_page_access", Collections.singletonList("date_created"));
        converted.put("worker_callback_receipt", Collections.singletonList("received_at"));
        // identity-prep's V68: not built by this branch's changelog, converted wherever it exists.
        converted.put("identity_signing_key", Arrays.asList("created_at", "retired_at"));
        CONVERTED = Collections.unmodifiableMap(converted);

        Map<String, String> left = new TreeMap<>();
        String liquibase = "Liquibase's own bookkeeping";
        left.put("databasechangelog.dateexecuted", liquibase);
        left.put("databasechangeloglock.lockgranted", liquibase);
        left.put("timestamptz_v100_unrepresentable.original", "V100's own: the naive string it could not convert, for its rollback");
        String ai = "read-only retention copy (V63); the live table is ai_db's, ai-service owns its type";
        for (String column : new String[] {"ai_agent.date_created", "ai_model_connection.date_created", "ai_model_connection.last_tested_at",
            "ai_prompt.date_created", "ai_prompt_run.date_created", "ai_prompt_version.date_created"}) {
            left.put(column, ai);
        }
        String analytics = "read-only retention copy (V62); the live table is analytics_db's";
        for (String column : new String[] {"analytics_analysis.date_created", "analytics_analysis.date_updated",
            "analytics_benchmark_result.date_created", "analytics_dashboard.date_created", "analytics_dashboard.date_updated",
            "analytics_dashboard_widget.date_created", "analytics_dashboard_widget.date_updated", "analytics_dataset.date_created",
            "analytics_query.date_created", "analytics_query.date_updated", "analytics_query_run.date_created"}) {
            left.put(column, analytics);
        }
        String billing = "read-only retention copy (V61); the live table is billing_db's";
        for (String column : new String[] {"billing_account.date_created", "billing_account.date_updated", "billing_document.issued_at",
            "invoice.date_created", "invoice.date_updated", "invoice.due_at", "invoice.issued_at", "invoice.paid_at", "invoice.voided_at",
            "payment.date_created", "payment.received_at", "payment.verified_at"}) {
            left.put(column, billing);
        }
        String storage = "read-only retention copy (V57); the live table is storage_db's";
        left.put("storage_connection.date_created", storage);
        left.put("storage_connection.last_tested_at", storage);
        LEFT_NAIVE = Collections.unmodifiableMap(left);
    }

    /** ISO-8601 in UTC to the microsecond, rendered by Postgres whatever the session's zone. */
    static final String UTC_TEXT = "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'";

    private TimestampColumns() {
    }

    /** The table's primary key as one text expression, parts joined by '|', in key order. */
    public static String keyExpression(JdbcTemplate sql, String table) {
        String key = sql.queryForObject("SELECT string_agg(format('%I::text', a.attname), ' || ''|'' || ' ORDER BY k.ord) "
            + "FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) "
            + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum "
            + "WHERE i.indrelid = to_regclass('public.' || ?) AND i.indisprimary", String.class, table);
        if (key == null) {
            throw new IllegalStateException(table + " has no primary key to reconcile rows by");
        }
        return key;
    }

    public static boolean exists(JdbcTemplate sql, String table, String column) {
        return sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name = ? AND column_name = ?", Integer.class, table, column) == 1;
    }

    public static String dataType(JdbcTemplate sql, String table, String column) {
        return sql.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name = ? AND column_name = ?", String.class, table, column);
    }

    public static long rows(JdbcTemplate sql, String table) {
        return sql.queryForObject("SELECT count(*) FROM public." + table, Long.class);
    }

    /** md5 over every row's key and the column's stored text: byte-for-byte identity of a naive column. */
    public static String storedChecksum(JdbcTemplate sql, String table, String column) {
        String key = keyExpression(sql, table);
        return sql.queryForObject(String.format("SELECT md5(coalesce(string_agg(%1$s || '=' || coalesce(%2$s::text, 'null'), ',' "
            + "ORDER BY %1$s), '')) FROM public.%3$s", key, column, table), String.class);
    }

    /**
     * md5 over every row's key and the INSTANT the column means, in UTC: before V100 the naive value read as
     * America/Chicago, after it the timestamptz itself. Equal before and after is "every row still means the
     * same moment" -- the interpretation surviving, which is the point, rather than the storage.
     */
    public static String instantChecksum(JdbcTemplate sql, String table, String column) {
        String key = keyExpression(sql, table);
        String instant = "timestamp without time zone".equals(dataType(sql, table, column))
            ? String.format("((%s AT TIME ZONE 'America/Chicago') AT TIME ZONE 'UTC')", column)
            : String.format("(%s AT TIME ZONE 'UTC')", column);
        return sql.queryForObject(String.format("SELECT md5(coalesce(string_agg(%1$s || '=' || coalesce(to_char(%2$s, %3$s), 'null'), ',' "
            + "ORDER BY %1$s), '')) FROM public.%4$s", key, instant, UTC_TEXT, table), String.class);
    }
}
