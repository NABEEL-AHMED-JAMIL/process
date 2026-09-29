package process.pipeline.backing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writing rows into a workspace's database (MIG-231: Write Database). New and risky: MIG-229's connections are
 * read-only by design, so nothing writes yet -- the task is registered, off by default, and unavailable until
 * integration-service has a write path (process.pipeline.integration.database-write).
 */
public interface DatabaseWriter {

    /** Rows per call. */
    int BATCH = 500;

    Optional<String> unavailable();

    /** How many rows the database took. */
    long write(WriteCall call) throws Exception;

    final class WriteCall {
        public long tenantId;
        public long jobQueueId;
        public String stepKey;
        public long connectionId;
        public String table;
        /** insert or upsert. */
        public String mode;
        public List<String> keyColumns;
        public List<String> columns;
        public List<Map<String, Object>> rows;
    }
}
