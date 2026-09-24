package process.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The charset constant on an OpenSearch _bulk body (MIG-156), for the second client that sends one.
 *
 * Spring's StringHttpMessageConverter writes a body in the content type's own charset if it names one,
 * in UTF-8 if the type is compatible with application/json, and otherwise in ISO-8859-1. x-ndjson is
 * not JSON-compatible, so a bulk body sent as plain "application/x-ndjson" goes out as Latin-1. On the
 * RAG index that was live damage -- 108 question marks across 225 chunks, documents rejected mid-file,
 * "not one character above U+007F survived" -- and OpenSearchRagClient now declares UTF-8, pinned by
 * RagBulkEncodingTest.
 *
 * OpenSearchAuditLogClient's bulk path still sends the bare type. Pinned as it is, not endorsed: an
 * audit line with a character above U+00FF is stored with a '?' in its place, and one in U+0080..U+00FF
 * goes out as a byte that is not UTF-8 (OpenSearch refuses the document and the line falls back to the
 * database). The single-document path is unaffected: it posts a Map as application/json.
 *
 * And the guard for the next one: no other _bulk body in process may be sent without a charset.
 */
class AuditLogBulkEncodingTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final OpenSearchAuditLogClient client = new OpenSearchAuditLogClient();

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(this.client, "baseUrl", "http://opensearch.test:9200");
        ReflectionTestUtils.setField(this.client, "restTemplate", this.restTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private HttpEntity<String> postedBulk(String logDetail) {
        ArgumentCaptor<HttpEntity> posted = ArgumentCaptor.forClass(HttpEntity.class);
        when(this.restTemplate.exchange(anyString(), eq(HttpMethod.POST), posted.capture(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}"));
        List<Object[]> entries = new ArrayList<>();
        entries.add(new Object[] {"id-1", 91422L, logDetail, new Timestamp(0L)});

        this.client.indexAllReturningFailures(entries);

        verify(this.restTemplate).exchange(eq("http://opensearch.test:9200/_bulk"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        return posted.getValue();
    }

    private static String throughSpring(HttpEntity<String> entity) throws Exception {
        MockHttpOutputMessage wire = new MockHttpOutputMessage();
        new StringHttpMessageConverter().write(entity.getBody(), entity.getHeaders().getContentType(), wire);
        return new String(wire.getBodyAsBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @Tag("pinned-unreviewed")
    void theAuditBulkIsSentWithoutACharsetSoAnythingAboveLatin1BecomesAQuestionMark() throws Exception {
        HttpEntity<String> entity = this.postedBulk("Loaded “Q3 — Müller” in 4 s");

        MediaType type = entity.getHeaders().getContentType();
        assertThat(type).isNotNull();
        assertThat(type.toString()).isEqualTo("application/x-ndjson");
        assertThat(type.getCharset()).isNull();
        assertThat(entity.getBody()).contains("Loaded “Q3 — Müller” in 4 s");

        String onTheWire = throughSpring(entity);
        assertThat(onTheWire).contains("Loaded ?Q3 ? M").doesNotContain("—").doesNotContain("“");
        assertThat(onTheWire).as("ü went out as the single Latin-1 byte 0xFC, not UTF-8").doesNotContain("Müller");
    }

    /**
     * Every _bulk body in main code names a charset, bar the one pinned above -- which this guard
     * tolerates but does not require, so fixing it does not break the guard.
     */
    @Test
    void noOtherNdjsonBodyIsSentWithoutACharset() throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Paths.get("src", "main", "java"))) {
            files = walk.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
        List<String> bare = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String code = line.trim();
                if (!code.contains("x-ndjson") || code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) {
                    continue;
                }
                boolean charset = code.contains("UTF_8") || code.contains("charset=");
                (charset ? declared : bare).add(file.getFileName() + ": " + code);
            }
        }
        assertThat(bare).as("x-ndjson with no charset is written as ISO-8859-1")
            .isSubsetOf(Collections.singletonList(
                "OpenSearchAuditLogClient.java: headers.setContentType(MediaType.parseMediaType(\"application/x-ndjson\"));"));
        assertThat(declared).as("the RAG client's bulk declares UTF-8")
            .anySatisfy(line -> assertThat(line).startsWith("OpenSearchRagClient.java: "));
    }
}
