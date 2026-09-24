package process.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The platform's own databases hold its configuration and each service's records -- never a
 * pipeline's data. A task that names one as the database it reads from or loads into is refused
 * when it is saved (owner's rule, 2026-09-24: the CSV -> Postgres test pipeline had loaded
 * demo_orders_import and two more tables into etl_job, with etl_job's own login in its payload).
 * A pipeline that moves data through Postgres gets a database of its own.
 */
public final class PlatformDatabases {

    static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
        "etl_job", "notifications_db", "media_db", "storage_db", "billing_db", "analytics_db", "ai_db", "identity_db"));

    /** The value of a database-naming tag: <db_name>, <database>, <dbname>. */
    private static final Pattern NAME_TAG = Pattern.compile("<(db_name|database|dbname)>\\s*([^<]*?)\\s*</\\1>", Pattern.CASE_INSENSITIVE);

    /** The database of a JDBC or libpq URL anywhere in the payload: ...//host:port/<database>. */
    private static final Pattern URL = Pattern.compile("(?:jdbc:)?postgres(?:ql)?://[^/\\s<]+/([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);

    private PlatformDatabases() {
    }

    /** Why this task payload may not be saved, or empty when it names no platform database. */
    public static Optional<String> refusal(String taskPayload) {
        if (taskPayload == null) {
            return Optional.empty();
        }
        Matcher tag = NAME_TAG.matcher(taskPayload);
        while (tag.find()) {
            Optional<String> named = platform(tag.group(2));
            if (named.isPresent()) {
                return named.map(PlatformDatabases::sentence);
            }
        }
        Matcher url = URL.matcher(taskPayload);
        while (url.find()) {
            Optional<String> named = platform(url.group(1));
            if (named.isPresent()) {
                return named.map(PlatformDatabases::sentence);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> platform(String database) {
        String name = database.trim().toLowerCase(Locale.ROOT);
        return NAMES.contains(name) ? Optional.of(name) : Optional.empty();
    }

    private static String sentence(String database) {
        return String.format("A pipeline cannot read from or load into %s: it is one of the platform's own databases, "
            + "which hold configuration, not pipeline data. Point the task at a database of its own.", database);
    }
}
