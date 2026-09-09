package process.analytics;

import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;

import java.util.Locale;

/**
 * A location Analytics Studio has decided it is willing to read.
 *
 * This type exists to make one rule structural rather than remembered: a caller names a storage
 * connection and a path INSIDE it, and the server turns that into a URL. The browser never sends
 * a URL, and no code downstream builds one from raw request fields, because the only way to get
 * one is to hold an instance of this class and the only way to get an instance is through the
 * resolver, which does the tenant and format checks first.
 *
 * Everything here has already been validated. Once a DatasetRef exists, its url() is safe to
 * interpolate into a scan.
 *
 * @author Nabeel Ahmed
 */
public final class DatasetRef {

    /** The formats DuckDB is asked to read. Anything else is refused before a session is opened. */
    public enum Format {
        CSV, TSV, JSON, PARQUET;

        /**
         * The format a key implies, or null when the extension is not one we read.
         *
         * Detection is by extension because that is what the user sees in the browser; a file
         * whose contents disagree with its name fails in the scan with DuckDB's own message,
         * which says more about the mismatch than a guess made here would.
         */
        public static Format of(String key) {
            if (key == null) {
                return null;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            String extension = dot < 0 ? lower : lower.substring(dot + 1);
            switch (extension) {
                case "csv":   return CSV;
                case "tsv":   return TSV;
                case "json":
                case "jsonl":
                case "ndjson": return JSON;
                case "parquet": return PARQUET;
                default: return null;
            }
        }
    }

    private final StorageConnection connection;
    private final String bucket;
    private final String path;
    private final Format format;

    DatasetRef(StorageConnection connection, String bucket, String path, Format format) {
        this.connection = connection;
        this.bucket = bucket;
        this.path = path;
        this.format = format;
    }

    public StorageConnection getConnection() {
        return this.connection;
    }

    public String getBucket() {
        return this.bucket;
    }

    public String getPath() {
        return this.path;
    }

    public Format getFormat() {
        return this.format;
    }

    /** True when the path names a set of files rather than one, and row counts cover all of them. */
    public boolean isMultiFile() {
        return this.path.contains("*") || this.path.contains("?");
    }

    /**
     * The URL DuckDB scans.
     *
     * s3:// for both S3 and MinIO, because MinIO speaks the S3 protocol and the endpoint that
     * separates them is on the secret, not in the URL. azure:// for Azure Blob, whose extension
     * takes the container as the first path segment in the same shape.
     */
    public String url() {
        String scheme = this.connection.getProvider() == StorageProvider.AZURE ? "azure://" : "s3://";
        return scheme + this.bucket + "/" + this.path;
    }

    /**
     * The scan expression for this dataset, with the reader chosen from the format.
     *
     * union_by_name is on for multi-file datasets so a folder whose files gained a column over
     * time still reads as one table rather than failing on the first mismatch; filename is on so
     * a row can be traced back to the file it came from, which is the first thing anyone asks of
     * a folder-shaped dataset.
     */
    public String scanExpression() {
        String url = "'" + this.url().replace("'", "''") + "'";
        boolean many = this.isMultiFile();
        switch (this.format) {
            case PARQUET:
                return "read_parquet(" + url + (many ? ", union_by_name=true, filename=true" : "") + ")";
            case JSON:
                return "read_json_auto(" + url + (many ? ", union_by_name=true, filename=true" : "") + ")";
            case TSV:
                return "read_csv_auto(" + url + ", delim='\\t'"
                    + (many ? ", union_by_name=true, filename=true" : "") + ")";
            case CSV:
            default:
                return "read_csv_auto(" + url + (many ? ", union_by_name=true, filename=true" : "") + ")";
        }
    }

    @Override
    public String toString() {
        // Names the location without the credentials that reach it, so this is safe to log.
        return this.connection.getProvider() + ":" + this.bucket + "/" + this.path
            + " (" + this.format + ")";
    }
}
