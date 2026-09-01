package process.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import process.model.projection.OpenSearchJobAuditLogProjection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class OpenSearchAuditLogClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenSearchAuditLogClient.class);
    private static final String INDEX_NAME = "job-audit-logs";
    private static final int MAX_HITS = 5000;

    @Value("${opensearch.url:}")
    private String baseUrl;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    public boolean isEnabled() {
        return this.baseUrl != null && !this.baseUrl.trim().isEmpty();
    }

    public boolean index(String externalId, Long jobQueueId, String logDetail, Timestamp dateCreated) {
        if (!isEnabled()) {
            return false;
        }
        try {
            String url = this.baseUrl + "/" + INDEX_NAME + "/_doc/" + externalId;
            Map<String, Object> body = new HashMap<>();
            body.put("jobQueueId", jobQueueId);
            body.put("logDetail", logDetail);
            body.put("dateCreated", dateCreated.toInstant().toString());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            this.restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
            return true;
        } catch (Exception ex) {
            logger.error("Failed to index audit log into OpenSearch externalId={} jobQueueId={}", externalId, jobQueueId, ex);
            return false;
        }
    }

    /**
     * Index many lines in one request.
     *
     * A pipeline writing fifty log lines per run was making fifty round trips here, one per
     * line. _bulk takes them all in a single call: the newline-delimited body pairs an action
     * line with its document, and the trailing newline is required rather than cosmetic.
     *
     * Returns false unless every line in the batch was stored, so the caller can fall back to
     * the database the same way the single-document path does. A partial rejection used to be
     * logged and then reported as success, which left the rejected lines in neither store.
     */
    public boolean indexAll(List<Object[]> entries) {
        if (!isEnabled() || entries == null || entries.isEmpty()) {
            return false;
        }
        return this.indexAllReturningFailures(entries).isEmpty();
    }

    /**
     * The same bulk write, handing back the entries OpenSearch would not take.
     *
     * A caller that can write the rejected lines somewhere else wants to know which ones they
     * were rather than that something went wrong, so it can fall back for those alone instead
     * of for the whole batch. Anything the response does not clearly account for counts as
     * rejected: an audit line written twice can still be read, one that was dropped cannot.
     */
    public List<Object[]> indexAllReturningFailures(List<Object[]> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        if (!isEnabled()) {
            return entries;
        }
        try {
            StringBuilder body = new StringBuilder();
            for (Object[] entry : entries) {
                String externalId = (String) entry[0];
                Long jobQueueId = (Long) entry[1];
                String logDetail = (String) entry[2];
                Timestamp dateCreated = (Timestamp) entry[3];
                body.append("{\"index\":{\"_index\":\"").append(INDEX_NAME)
                    .append("\",\"_id\":\"").append(externalId).append("\"}}\n");
                Map<String, Object> doc = new HashMap<>();
                doc.put("jobQueueId", jobQueueId);
                doc.put("logDetail", logDetail);
                doc.put("dateCreated", dateCreated.toInstant().toString());
                body.append(this.objectMapper.writeValueAsString(doc)).append("\n");
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-ndjson"));
            ResponseEntity<String> response = this.restTemplate.exchange(
                this.baseUrl + "/_bulk", HttpMethod.POST,
                new HttpEntity<>(body.toString(), headers), String.class);
            return this.rejectedEntries(response.getBody(), entries);
        } catch (Exception ex) {
            logger.error("Failed to bulk index {} audit lines into OpenSearch", entries.size(), ex);
            return entries;
        }
    }

    /**
     * Which entries of a _bulk request the response says were not stored.
     *
     * The items array comes back in request order, one element per document, so position is
     * what pairs a rejection with the line that caused it. A body that cannot be lined up
     * against the request that way tells us nothing about which lines survived, so the whole
     * batch is reported as rejected rather than assumed stored.
     */
    List<Object[]> rejectedEntries(String responseBody, List<Object[]> entries) {
        if (responseBody == null) {
            logger.warn("OpenSearch returned no body for a bulk of {} audit lines", entries.size());
            return entries;
        }
        try {
            JsonNode root = this.objectMapper.readTree(responseBody);
            JsonNode errors = root.path("errors");
            if (!errors.isBoolean()) {
                logger.warn("OpenSearch answered a bulk of {} audit lines with a body that is not a bulk response",
                    entries.size());
                return entries;
            }
            if (!errors.booleanValue()) {
                return Collections.emptyList();
            }
            JsonNode items = root.path("items");
            if (!items.isArray() || items.size() != entries.size()) {
                logger.warn("OpenSearch reported errors in a bulk of {} audit lines but returned {} items",
                    entries.size(), items.isArray() ? items.size() : 0);
                return entries;
            }
            List<Object[]> rejected = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                JsonNode result = items.get(index).path("index");
                int status = result.path("status").asInt(0);
                if (!result.path("error").isMissingNode() || status < 200 || status > 299) {
                    rejected.add(entries.get(index));
                }
            }
            if (rejected.isEmpty()) {
                logger.warn("OpenSearch reported errors in a bulk of {} audit lines without naming any", entries.size());
                return entries;
            }
            logger.warn("OpenSearch rejected {} of {} audit lines", rejected.size(), entries.size());
            return rejected;
        } catch (Exception ex) {
            logger.error("Could not read the OpenSearch bulk response for {} audit lines", entries.size(), ex);
            return entries;
        }
    }

    public List<OpenSearchJobAuditLogProjection> searchByJobQueueId(Long jobQueueId) {
        if (!isEnabled()) {
            return Collections.emptyList();
        }
        Map<String, Object> query = new HashMap<>();
        query.put("size", MAX_HITS);
        query.put("sort", Collections.singletonList(sortAsc("dateCreated")));
        query.put("query", Collections.singletonMap("term", Collections.singletonMap("jobQueueId", jobQueueId)));
        return search(query);
    }

    public List<OpenSearchJobAuditLogProjection> searchSince(Instant since) {
        if (!isEnabled()) {
            return Collections.emptyList();
        }
        Map<String, Object> range = new HashMap<>();
        range.put("gte", since.toString());
        Map<String, Object> query = new HashMap<>();
        query.put("size", MAX_HITS);
        query.put("sort", Collections.singletonList(sortAsc("dateCreated")));
        query.put("query", Collections.singletonMap("range", Collections.singletonMap("dateCreated", range)));
        return search(query);
    }

    private Map<String, Object> sortAsc(String field) {
        return Collections.singletonMap(field, Collections.singletonMap("order", "asc"));
    }

    private List<OpenSearchJobAuditLogProjection> search(Map<String, Object> queryBody) {
        List<OpenSearchJobAuditLogProjection> results = new ArrayList<>();
        try {
            String url = this.baseUrl + "/" + INDEX_NAME + "/_search";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String response = this.restTemplate.postForObject(url, new HttpEntity<>(queryBody, headers), String.class);
            JsonNode root = this.objectMapper.readTree(response);
            JsonNode hits = root.path("hits").path("hits");
            for (JsonNode hit : hits) {
                String id = hit.path("_id").asText(null);
                JsonNode source = hit.path("_source");
                if (id == null || source.isMissingNode()) {
                    continue;
                }
                results.add(new OpenSearchJobAuditLogProjection(
                    id,
                    source.path("jobQueueId").asLong(),
                    source.path("logDetail").asText(""),
                    source.path("dateCreated").asText("")
                ));
            }
        } catch (Exception ex) {

            logger.warn("OpenSearch audit log search failed, treating as no results: {}", ex.getMessage());
        }
        return results;
    }

}
