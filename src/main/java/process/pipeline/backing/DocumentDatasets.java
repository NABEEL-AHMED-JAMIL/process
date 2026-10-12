package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MIG-364: Document Intelligence's documents as a pipeline reads them -- ai-service's trusted read
 * (/internal/ai/documents/dataset): a type's approved dataset, or its documents in review or rejected, a page at a time
 * in a stable order (dataset row, or extraction), as the run's workspace only. A run's read is billed there as a trusted
 * read (one read op and the bytes per page).
 */
public interface DocumentDatasets {

    String APPROVED = "Approved";
    String REVIEW = "Review";
    String REJECTED = "Rejected";

    /** The most rows one page holds. */
    int MAX_PAGE = 1000;

    /** What to read. */
    final class Request {
        public long tenantId;
        public long documentTypeId;
        public String status = APPROVED;
        /** Rows after this cursor (a dataset row for Approved, an extraction for the others); 0 from the start. */
        public long after;
        /** A date (2026-10-01) or wall-clock date and time; null for no bound. {@code to}'s date is included. */
        public String from;
        public String to;
        /** Only these documents; null for every one. */
        public List<Long> extractionIds;
        public int limit = 500;
        /** Whether each row names its file (Media's); off saves a call when no file name is wanted. */
        public boolean names = true;
        /** The run reading (billed); null for Core's own look at a type. */
        public Long jobQueueId;
        /** The run, attempt, step and try: with the cursor, the meter's dedupe key. */
        public String readKey;
    }

    /** One page: the type's columns (every version's) and its documents; {@code next} is the cursor, null at the end. */
    final class Page {
        public long documentTypeId;
        public String typeKey;
        public String name;
        public List<String> fields = new ArrayList<>();
        public List<String> tables = new ArrayList<>();
        public Map<String, List<String>> tableColumns = new LinkedHashMap<>();
        /** Each: datasetRowId, extractionId, ocrDocumentId, fileName, typeVersion, status, approval, approvedBy, dateApproved,
         *  dateDecided, rulesFailed, rejectReason, fields {}, tables {}. */
        public List<JsonNode> rows = new ArrayList<>();
        public Long next;
    }

    /** One page; an exception, saying why, when ai-service refuses (the type is not the workspace's) or cannot be reached. */
    Page read(Request request) throws Exception;

    /** Why documents cannot be read from this Core (no ai-service configured), or empty. */
    default Optional<String> unavailable() {
        return Optional.empty();
    }
}
