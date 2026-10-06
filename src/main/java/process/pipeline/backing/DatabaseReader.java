package process.pipeline.backing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A workspace's database connection, read through integration-service (MIG-231: Read Database; MIG-229 owns the
 * connections, their passwords and the read-only enforcement). Core sends the connection id and the query and takes
 * rows; it never sees a password.
 */
public interface DatabaseReader {

    Optional<String> unavailable();

    QueryResult query(QueryCall call) throws Exception;

    final class QueryCall {
        public long tenantId;
        public long jobQueueId;
        public String stepKey;
        public long connectionId;
        public String query;
        /** At most this many rows; one more is asked for to know whether there were more. */
        public int maxRows;
    }

    final class QueryResult {
        public List<String> columns;
        public List<Map<String, Object>> rows;
        public boolean truncated;
    }
}
