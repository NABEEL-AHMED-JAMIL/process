package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.storage.remote.StorageServiceClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files a customer named for a run, by the ULID file ids POST /v1/files answered (MIG-332): storage-service says, for
 * one workspace, which exist and where each lives. An id of another workspace is simply not in the answer.
 */
@Component
public class RunFiles {

    /** At most this many files on one run. */
    public static final int MAX_FILES = 20;

    private final StorageServiceClient storage;

    public RunFiles(StorageServiceClient storage) {
        this.storage = storage;
    }

    /** The workspace's files among these ids, by id, with id, name, bucket, key, sha256, bytes and contentType. */
    public Map<String, Map<String, Object>> of(long tenantId, Collection<String> ids) {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("tenantId", String.valueOf(tenantId));
        query.put("ids", String.join(",", ids));
        JsonNode answer = this.storage.directoryGet("/files", query);
        if (answer == null) {
            return found;
        }
        for (JsonNode file : answer.path("files")) {
            Map<String, Object> one = new LinkedHashMap<>();
            for (String field : new String[] {"id", "name", "bucket", "key", "sha256", "contentType"}) {
                one.put(field, file.path(field).isMissingNode() || file.path(field).isNull() ? null : file.path(field).asText());
            }
            one.put("bytes", file.path("bytes").asLong());
            found.put(file.path("id").asText(), one);
        }
        return found;
    }

    /** The ids as a list of strings; anything else in the list is refused by the caller. */
    static List<String> idsOf(JsonNode files) {
        List<String> ids = new ArrayList<>();
        if (files != null && files.isArray()) {
            for (JsonNode id : files) {
                ids.add(id.isTextual() ? id.asText() : null);
            }
        }
        return ids;
    }
}
