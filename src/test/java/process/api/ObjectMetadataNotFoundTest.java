package process.api;

import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.model.service.StorageBrowserService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * objectMetadata said "the server broke" for an object that simply is not there. Every adapter wraps
 * its SDK's not-found in a plain RuntimeException, and only the download endpoint had learned to see
 * through that (StorageNotFound). Found through media-service (MIG-48): it reads metadata over HTTP
 * before extracting, so a missing file came back as "Storage could not deliver this file just now"
 * -- an outage -- because a 500 is exactly what an outage looks like from the outside.
 */
class ObjectMetadataNotFoundTest {

    private final StorageBrowserService storage = mock(StorageBrowserService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StorageBrowserRestApi(this.storage)).build();

    @Test
    void aMissingObjectIsA404InTheDownloadEndpointsWords() throws Exception {
        ErrorResponse noSuchKey = new ErrorResponse("NoSuchKey", "message", "etl-bucket", "e2e/nope/missing.txt", "req", "host", "id");
        when(this.storage.getObjectMetadata("b", "e2e/nope/missing.txt")).thenThrow(new RuntimeException(
            "Could not fetch MinIO object metadata etl-bucket/e2e/nope/missing.txt", new ErrorResponseException(noSuchKey, null, null)));

        this.mvc.perform(get("/storage.json/objectMetadata").param("bucket", "b").param("key", "e2e/nope/missing.txt").accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value("No object at b/e2e/nope/missing.txt."));
    }

    @Test
    void aStoreThatIsDownIsStillA500() throws Exception {
        // The other half matters more: an outage reported as 404 would read as "your data is gone".
        when(this.storage.getObjectMetadata("b", "k")).thenThrow(new RuntimeException(
            "Could not fetch MinIO object metadata etl-bucket/k", new java.net.ConnectException("Connection refused")));

        this.mvc.perform(get("/storage.json/objectMetadata").param("bucket", "b").param("key", "k"))
            .andExpect(status().isInternalServerError());
    }
}
