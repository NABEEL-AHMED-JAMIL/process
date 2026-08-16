package process.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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

@Component
public class OpenSearchAuditLogClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenSearchAuditLogClient.class);
    private static final String INDEX_NAME = "job-audit-logs";
    private static final int MAX_HITS = 5000;

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

    @Value("${opensearch.url:}")
    private String baseUrl;

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
