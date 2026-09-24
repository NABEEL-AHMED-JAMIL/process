package process.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import java.util.Collections;
import org.mockito.Mockito;
import org.springframework.web.client.ResourceAccessException;

/**
 * The encoding of the _bulk body, and the losses that not setting it caused.
 *
 * This is the root cause of "we are not creating the vector data". Spring's
 * StringHttpMessageConverter picks an encoding in getContentTypeCharset: the content type's own
 * charset if it has one, else UTF-8 only if the type isCompatibleWith(application/json), else its
 * DEFAULT_CHARSET -- which is ISO-8859-1. The bulk body was sent as "application/x-ndjson" with no
 * charset, and x-ndjson is not compatible with application/json, so every chunk this platform has
 * ever indexed went out as Latin-1. Two separate losses followed:
 *
 *  - A character above U+00FF has no Latin-1 byte and was replaced with '?'.
 *  - A character in U+0080..U+00FF encoded to one byte 0x80..0xFF, which is not valid UTF-8, so
 *    OpenSearch rejected that document outright and the chunk vanished from the middle of a file.
 *
 * Both were measured on the live index before the fix: across 225 chunks of CVs, PDFs, CSVs and
 * Markdown there was not ONE character above U+007F, and there were 108 literal question marks.
 *
 * The tests below drive the real converter rather than asserting on a header, because the header
 * is only interesting for what Spring does with it -- and what Spring does with it is the bug.
 *
 * @author Nabeel Ahmed
 */
public class RagBulkEncodingTest {

    private static final String BASE_URL = "http://opensearch.test:9200";

    /** U+00A7 SECTION SIGN -- the character that cost the I-94 PDF its middle chunk. */
    private static final String SECTION_SIGN = "§";
    /** Characters above U+00FF, which Latin-1 cannot hold at all. */
    private static final String CURLY_QUOTES = "“quoted”";
    private static final String EM_DASH = "—";
    private static final String EURO = "€";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final OpenSearchRagClient client = new OpenSearchRagClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<HttpEntity<String>> posted = new ArrayList<>();

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(this.client, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(this.client, "restTemplate", this.restTemplate);
        ReflectionTestUtils.setField(this.client, "objectMapper", this.objectMapper);
        ReflectionTestUtils.setField(this.client, "indexEnsured", true);
        this.posted.clear();
        // indexChunks writes only for the caller's own tenant (MIG-10).
        TenantContext.set(1000L, "TENANT_USER", 1L, "rag-test");
    }

    @AfterEach
    void clearTheCaller() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------------------------------
    // 1-6. What Spring actually does with each content type. The premise, asserted rather than
    //      assumed -- a test of our own code that rests on an unverified belief about a framework
    //      is a test of the belief.
    // ------------------------------------------------------------------------------------------

    private static byte[] writeThrough(MediaType contentType, String payload) throws Exception {
        StringHttpMessageConverter converter = new StringHttpMessageConverter();
        MockHttpOutputMessage message = new MockHttpOutputMessage();
        converter.write(payload, contentType, message);
        return message.getBodyAsBytes();
    }

    @Test
    @DisplayName("1. the premise: x-ndjson with no charset is written as ISO-8859-1, not UTF-8")
    void ndjsonWithoutCharsetIsLatin1() throws Exception {
        byte[] written = writeThrough(MediaType.parseMediaType("application/x-ndjson"), SECTION_SIGN);
        // One byte, 0xA7 -- the Latin-1 encoding. UTF-8 would be two bytes, 0xC2 0xA7.
        assertEquals(1, written.length, "x-ndjson with no charset should have gone out as Latin-1");
        assertEquals((byte) 0xA7, written[0]);
    }

    @Test
    @DisplayName("2. the premise: that single byte is not valid UTF-8, which is why OpenSearch rejected it")
    void latin1ByteIsNotValidUtf8() throws Exception {
        byte[] written = writeThrough(MediaType.parseMediaType("application/x-ndjson"), SECTION_SIGN);
        String decodedAsUtf8 = new String(written, StandardCharsets.UTF_8);
        assertFalse(SECTION_SIGN.equals(decodedAsUtf8),
            "if this round-tripped, the rejection this suite explains could not have happened");
        assertEquals("�", decodedAsUtf8, "a lone 0xA7 decodes to the replacement character");
    }

    @Test
    @DisplayName("3. the premise: a character above U+00FF becomes a question mark under Latin-1")
    void aboveLatin1BecomesQuestionMark() throws Exception {
        byte[] written = writeThrough(MediaType.parseMediaType("application/x-ndjson"), EM_DASH);
        assertEquals("?", new String(written, StandardCharsets.ISO_8859_1),
            "this is where the 108 question marks in the live index came from");
    }

    @Test
    @DisplayName("4. application/json is the exception that hid the bug: it is special-cased to UTF-8")
    void applicationJsonIsSpecialCasedToUtf8() throws Exception {
        byte[] written = writeThrough(MediaType.APPLICATION_JSON, SECTION_SIGN);
        assertEquals(SECTION_SIGN, new String(written, StandardCharsets.UTF_8),
            "every OTHER call in the client uses application/json, which is why only bulk was broken");
    }

    @Test
    @DisplayName("5. naming the charset is what fixes it")
    void ndjsonWithCharsetRoundTrips() throws Exception {
        MediaType withCharset = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);
        assertEquals(SECTION_SIGN, new String(writeThrough(withCharset, SECTION_SIGN), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("6. and it holds for characters Latin-1 cannot represent at all")
    void ndjsonWithCharsetKeepsCharactersAboveLatin1() throws Exception {
        MediaType withCharset = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);
        String payload = CURLY_QUOTES + EM_DASH + EURO;
        assertEquals(payload, new String(writeThrough(withCharset, payload), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------
    // 7-14. The client's own bulk request.
    // ------------------------------------------------------------------------------------------

    private void stubBulk(String responseBody) {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        doReturn(new ResponseEntity<>(responseBody, HttpStatus.OK))
            .when(this.restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        ReflectionTestUtils.setField(this.client, "restTemplate", this.restTemplate);
        this.bulkCaptor = captor;
    }

    private ArgumentCaptor<HttpEntity> bulkCaptor;

    private HttpEntity<String> lastBulkEntity() {
        List<HttpEntity> all = this.bulkCaptor.getAllValues();
        assertFalse(all.isEmpty(), "no bulk request was sent");
        return all.get(all.size() - 1);
    }

    private OpenSearchRagClient.IndexOutcome index(List<String> texts, String responseBody) {
        stubBulk(responseBody);
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            vectors.add(new float[] { 0.1f, 0.2f, 0.3f });
        }
        return this.client.indexChunks(1000L, "etl-bucket", "doc.pdf", "etag-1",
            texts, vectors, "nomic-embed-text");
    }

    private static String bulkOk(int items) {
        StringBuilder out = new StringBuilder("{\"errors\":false,\"items\":[");
        for (int i = 0; i < items; i++) {
            out.append(i == 0 ? "" : ",").append("{\"index\":{\"status\":201}}");
        }
        return out.append("]}").toString();
    }

    @Test
    @DisplayName("7. the bulk request names UTF-8 on its content type")
    void bulkRequestDeclaresUtf8() {
        index(Arrays.asList("alpha", "beta"), bulkOk(2));
        MediaType sent = lastBulkEntity().getHeaders().getContentType();
        assertNotNull(sent, "the bulk request must set a content type");
        assertEquals("application", sent.getType());
        assertEquals("x-ndjson", sent.getSubtype());
        assertEquals(StandardCharsets.UTF_8, sent.getCharset(),
            "without this, Spring falls back to ISO-8859-1 and mangles every chunk");
    }

    @Test
    @DisplayName("8. a chunk carrying a section sign survives the write end to end")
    void sectionSignSurvives() throws Exception {
        String chunkText = "Under " + SECTION_SIGN + " 235 of the Act, a record of admission.";
        index(Arrays.asList(chunkText), bulkOk(1));
        HttpEntity<String> entity = lastBulkEntity();
        byte[] onTheWire = writeThrough(entity.getHeaders().getContentType(), entity.getBody());
        assertTrue(new String(onTheWire, StandardCharsets.UTF_8).contains(SECTION_SIGN),
            "this exact character is what removed chunkIndex 1 from the I-94 PDF");
    }

    @Test
    @DisplayName("9. curly quotes, em dashes and currency symbols all survive")
    void charactersAboveLatin1Survive() throws Exception {
        String chunkText = CURLY_QUOTES + " cost " + EURO + "40 " + EM_DASH + " per seat.";
        index(Arrays.asList(chunkText), bulkOk(1));
        HttpEntity<String> entity = lastBulkEntity();
        String decoded = new String(
            writeThrough(entity.getHeaders().getContentType(), entity.getBody()), StandardCharsets.UTF_8);
        assertTrue(decoded.contains(CURLY_QUOTES), "curly quotes became ? in every indexed file");
        assertTrue(decoded.contains(EURO));
        assertTrue(decoded.contains(EM_DASH));
        assertFalse(decoded.contains("?"), "nothing in this chunk should have degraded to a question mark");
    }

    @Test
    @DisplayName("10. the body is newline-delimited with an action line per document")
    void bodyIsNdjson() {
        index(Arrays.asList("one", "two", "three"), bulkOk(3));
        String[] lines = lastBulkEntity().getBody().split("\n");
        assertEquals(6, lines.length, "three chunks means three action lines and three documents");
        for (int i = 0; i < 6; i += 2) {
            assertTrue(lines[i].contains("\"index\""), "line " + i + " should be an action line");
        }
    }

    @Test
    @DisplayName("11. every document carries the embedding model, so retrieval can find it again")
    void everyDocumentCarriesTheModel() {
        index(Arrays.asList("one", "two"), bulkOk(2));
        String body = lastBulkEntity().getBody();
        int occurrences = body.split("nomic-embed-text", -1).length - 1;
        assertEquals(2, occurrences,
            "a chunk written without embeddingModel is unreachable: every query scopes on it");
    }

    // ------------------------------------------------------------------------------------------
    // 12-19. What the bulk response is allowed to hide. A bulk write answers 200 even when it
    //        rejected individual items, so the body is the only place the truth is written down.
    // ------------------------------------------------------------------------------------------

    private static String bulkWithRejection(int items, int rejectedIndex) {
        StringBuilder out = new StringBuilder("{\"errors\":true,\"items\":[");
        for (int i = 0; i < items; i++) {
            out.append(i == 0 ? "" : ",");
            if (i == rejectedIndex) {
                out.append("{\"index\":{\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\","
                    + "\"reason\":\"failed to parse field [chunkText]\"}}}");
            } else {
                out.append("{\"index\":{\"status\":201}}");
            }
        }
        return out.append("]}").toString();
    }

    @Test
    @DisplayName("12. a clean write reports every chunk stored")
    void cleanWriteIsComplete() {
        OpenSearchRagClient.IndexOutcome outcome = index(Arrays.asList("a", "b", "c"), bulkOk(3));
        assertEquals(3, outcome.getAttempted());
        assertEquals(3, outcome.getStored());
        assertTrue(outcome.isComplete());
        assertNull(outcome.getFailureSummary());
    }

    @Test
    @DisplayName("13. a rejected item is counted, not swallowed")
    void rejectedItemIsCounted() {
        OpenSearchRagClient.IndexOutcome outcome = index(Arrays.asList("a", "b", "c"), bulkWithRejection(3, 1));
        assertEquals(3, outcome.getAttempted());
        assertEquals(2, outcome.getStored(), "this is the exact shape of the I-94 PDF's loss");
        assertFalse(outcome.isComplete());
    }

    @Test
    @DisplayName("14. the failure summary names how many and why, which the old single WARN did not")
    void failureSummaryNamesTheReason() {
        OpenSearchRagClient.IndexOutcome outcome = index(Arrays.asList("a", "b", "c"), bulkWithRejection(3, 1));
        assertNotNull(outcome.getFailureSummary());
        assertTrue(outcome.getFailureSummary().contains("1 of 3"), outcome.getFailureSummary());
        assertTrue(outcome.getFailureSummary().contains("mapper_parsing_exception"), outcome.getFailureSummary());
    }

    @Test
    @DisplayName("15. every item rejected is reported as nothing stored, not as a success")
    void allRejected() {
        OpenSearchRagClient.IndexOutcome outcome = index(Arrays.asList("a", "b"), bulkWithRejection(2, 0)
            .replace("{\"index\":{\"status\":201}}",
                "{\"index\":{\"status\":400,\"error\":{\"type\":\"x\",\"reason\":\"y\"}}}"));
        assertEquals(0, outcome.getStored());
        assertFalse(outcome.isComplete());
    }

    @Test
    @DisplayName("16. an empty response body is not read as a successful write")
    void emptyBodyIsNotSuccess() {
        OpenSearchRagClient.IndexOutcome outcome = index(Arrays.asList("a"), null);
        assertEquals(0, outcome.getStored());
        assertFalse(outcome.isComplete());
        assertNotNull(outcome.getFailureSummary());
    }

    @Test
    @DisplayName("17. a transport failure reports nothing stored and names the cause")
    void transportFailureIsReported() {
        doReturn(null).when(this.restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        Mockito.doThrow(new ResourceAccessException("connect timed out"))
            .when(this.restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        List<float[]> vectors = Arrays.asList(new float[] { 1f });
        OpenSearchRagClient.IndexOutcome outcome = this.client.indexChunks(1000L, "b", "k", "e",
            Arrays.asList("text"), vectors, "m");
        assertEquals(0, outcome.getStored());
        assertTrue(outcome.getFailureSummary().contains("connect timed out"), outcome.getFailureSummary());
    }

    @Test
    @DisplayName("18. nothing to index is complete rather than a failure")
    void nothingToIndexIsComplete() {
        OpenSearchRagClient.IndexOutcome outcome = this.client.indexChunks(1000L, "b", "k", "e",
            Collections.<String>emptyList(), Collections.<float[]>emptyList(), "m");
        assertEquals(0, outcome.getAttempted());
        assertTrue(outcome.isComplete());
        assertNull(outcome.getFailureSummary());
    }

    @Test
    @DisplayName("19. a body that will not parse does not claim chunks were lost")
    void unparseableBodyDoesNotInventALoss() {
        OpenSearchRagClient.IndexOutcome outcome = index(Arrays.asList("a", "b"), "not json at all");
        assertEquals(2, outcome.getStored(),
            "the HTTP call succeeded; guessing at a loss we cannot see would be its own lie");
        assertTrue(outcome.isComplete());
    }

    // ------------------------------------------------------------------------------------------
    // 41-50. Completeness, decided from the chunk indexes rather than from how many came back.
    //
    // This is the half of the encoding bug that made it invisible. A chunk rejected at write time
    // is missing from the count AND from the fetch, so the two agree perfectly with each other
    // about a document that has a hole in it -- and the old code read that agreement as proof
    // nothing had been left out, handing the survivors to the model under the heading
    // "FILE CONTENT" with no caveat. Live at the time of writing: the I-94 PDF held chunkIndex
    // 0 and 2, the count said 2, and 2 <= topK.
    // ------------------------------------------------------------------------------------------

    /** Answers the size:0 count query, then the document-order fetch, in that order. */
    private void stubCountThenFetch(int count, int... presentIndexes) {
        StringBuilder hits = new StringBuilder();
        for (int i = 0; i < presentIndexes.length; i++) {
            hits.append(i == 0 ? "" : ",")
                .append("{\"_source\":{\"chunkIndex\":").append(presentIndexes[i])
                .append(",\"chunkText\":\"chunk ").append(presentIndexes[i]).append("\"}}");
        }
        String countBody = "{\"hits\":{\"total\":{\"value\":" + count + "},\"hits\":[]}}";
        String fetchBody = "{\"hits\":{\"total\":{\"value\":" + presentIndexes.length
            + "},\"hits\":[" + hits + "]}}";
        doReturn(countBody, fetchBody).when(this.restTemplate)
            .postForObject(anyString(), any(), eq(String.class));
    }

    private OpenSearchRagClient.RetrievalResult retrieve() {
        return this.client.searchRelevantChunks("etl-bucket", "doc.pdf", "etag-1",
            new float[] { 1f, 0f, 0f }, 8);
    }

    @Test
    @DisplayName("41. a contiguous run of chunks is reported complete")
    void contiguousIsComplete() {
        stubCountThenFetch(3, 0, 1, 2);
        OpenSearchRagClient.RetrievalResult result = retrieve();
        assertEquals(3, result.chunks.size());
        assertTrue(result.complete);
    }

    @Test
    @DisplayName("42. a gap in the middle is NOT complete, even though the count agrees with the fetch")
    void gapIsNotComplete() {
        stubCountThenFetch(2, 0, 2);
        OpenSearchRagClient.RetrievalResult result = retrieve();
        assertEquals(2, result.chunks.size(), "both surviving chunks are still returned");
        assertFalse(result.complete, "this is the exact shape of the I-94 PDF");
    }

    @Test
    @DisplayName("43. a missing FIRST chunk is caught too")
    void missingFirstChunkIsCaught() {
        stubCountThenFetch(2, 1, 2);
        assertFalse(retrieve().complete);
    }

    @Test
    @DisplayName("44. chunks arriving out of order are sorted, not mistaken for a gap")
    void outOfOrderIsSortedNotRejected() {
        stubCountThenFetch(3, 2, 0, 1);
        OpenSearchRagClient.RetrievalResult result = retrieve();
        assertTrue(result.complete, "OpenSearch does not promise document order; sorting is not a loss");
        assertEquals("chunk 0", result.chunks.get(0));
        assertEquals("chunk 2", result.chunks.get(2));
    }

    @Test
    @DisplayName("45. the surviving chunks are still in document order after a gap")
    void survivorsKeepDocumentOrder() {
        stubCountThenFetch(2, 2, 0);
        OpenSearchRagClient.RetrievalResult result = retrieve();
        assertEquals("chunk 0", result.chunks.get(0));
        assertEquals("chunk 2", result.chunks.get(1));
    }

    @Test
    @DisplayName("46. a single chunk file is complete")
    void singleChunkIsComplete() {
        stubCountThenFetch(1, 0);
        assertTrue(retrieve().complete);
    }

    @Test
    @DisplayName("47. a file with no chunks at all is the one empty result worth re-indexing on")
    void zeroChunksIsCompleteAndEmpty() {
        doReturn("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")
            .when(this.restTemplate).postForObject(anyString(), any(), eq(String.class));
        OpenSearchRagClient.RetrievalResult result = retrieve();
        assertTrue(result.chunks.isEmpty());
        assertTrue(result.complete, "an empty index for this file is a fact, not a partial answer");
        assertFalse(result.failed);
    }

    @Test
    @DisplayName("48. an empty question vector is a failure, not an empty file")
    void emptyQuestionVectorIsAFailure() {
        OpenSearchRagClient.RetrievalResult result =
            this.client.searchRelevantChunks("b", "k", "e", new float[0], 8);
        assertTrue(result.failed,
            "reported as empty, the caller would re-extract and re-embed the whole file forever");
    }

    @Test
    @DisplayName("49. a disabled client answers complete-and-empty rather than pretending to search")
    void disabledClientIsHonest() {
        OpenSearchRagClient disabled = new OpenSearchRagClient();
        ReflectionTestUtils.setField(disabled, "baseUrl", "");
        OpenSearchRagClient.RetrievalResult result =
            disabled.searchRelevantChunks("b", "k", "e", new float[] { 1f }, 8);
        assertTrue(result.chunks.isEmpty());
        assertFalse(result.failed);
    }

    @Test
    @DisplayName("50. the completeness flag is what tells the reader an answer was partial")
    void completenessIsTheSignalThatReachesTheReader() {
        // FileChatServiceImpl builds FileContext(..., partial = !result.complete) and only then
        // does appendContentCaveats add a caveat line. So this boolean is the whole mechanism by
        // which "some of this file is missing" ever reaches a person -- which is why deriving it
        // from a document count rather than from the indexes was not a cosmetic bug.
        stubCountThenFetch(2, 0, 2);
        assertFalse(retrieve().complete);
        stubCountThenFetch(2, 0, 1);
        assertTrue(retrieve().complete);
    }
}
