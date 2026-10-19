package process.pipeline.backing;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * UX review 2026-10-13 (I1): the names of what a workspace's pipelines read in integration-service -- an API request with
 * its collection, a database connection -- so Connections › Sources can say "Clinical APIs › Patients" rather than a
 * number. integration-service answers POST /internal/pipelines/describe as the named workspace (row-level security); an
 * id that is not the workspace's, or is gone, is simply not named.
 */
public interface IntegrationNames {

    /** A saved API request's name and the collection it lives in. */
    final class RequestName {
        public String name;
        public Long collectionId;
        public String collectionName;
        /** AI hard-workflow test: the {{variables}} a run must give the request (its default environment does not define them). */
        public List<String> needsVariables = new ArrayList<>();
        /** AI tools batch: the request's HTTP method (a step preview runs only reads); null from an older integration-service. */
        public String method;
    }

    /** What integration-service named, by id. */
    final class Names {
        public final Map<Long, RequestName> requests = new LinkedHashMap<>();
        public final Map<Long, String> connections = new LinkedHashMap<>();
    }

    /** The workspace's requests and connections among these ids, named; IOException or a runtime refusal when it cannot say. */
    Names describe(long tenantId, Collection<Long> requestIds, Collection<Long> connectionIds) throws IOException;
}
