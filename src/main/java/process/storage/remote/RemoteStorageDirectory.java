package process.storage.remote;

import com.fasterxml.jackson.databind.JsonNode;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What process reads about storage connections once Storage owns them (MIG-68): each question that
 * used to be a query on process's storage_connection table, asked of storage-service instead. Present
 * only when storage.remote is on; each consumer falls back to its table while it is absent.
 *
 * The connections handed back are transient summaries without secrets -- never saved, never cached here.
 */
public class RemoteStorageDirectory {

    private final StorageServiceClient storage;

    public RemoteStorageDirectory(StorageServiceClient storage) {
        this.storage = storage;
    }

    /** Every live connection by this name, in any workspace (tenantId null for the platform's). */
    public List<StorageConnection> byAlias(String alias) {
        return this.summaries(this.storage.directoryGet("/byAlias", query("alias", alias, null, null)));
    }

    /** A workspace's live connections (no tenantId: the platform's), without secrets. */
    public List<StorageConnection> workspace(Long tenantId) {
        return this.summaries(this.storage.directoryGet("", query("tenantId", tenantId == null ? null : String.valueOf(tenantId), null, null)));
    }

    /**
     * The bytes a connection holds, for the nightly measurement: the partial sum when the bucket was
     * past the object cap, and -1 when it was not measured or could not be -- never zero for "unknown".
     */
    public long bytesIn(StorageConnection connection) {
        JsonNode size = this.storage.directoryGet("/" + connection.getStorageConnectionId() + "/size", null);
        if (size == null) {
            return -1;
        }
        String outcome = size.path("outcome").asText();
        if (("MEASURED".equals(outcome) || "PARTIAL".equals(outcome)) && size.hasNonNull("bytes")) {
            return size.get("bytes").asLong();
        }
        return -1;
    }

    private List<StorageConnection> summaries(JsonNode list) {
        if (list == null || !list.isArray()) {
            return Collections.emptyList();
        }
        List<StorageConnection> rows = new ArrayList<>();
        for (JsonNode item : list) {
            StorageConnection row = new StorageConnection();
            row.setStorageConnectionId(item.path("storageConnectionId").asLong());
            row.setTenantId(item.hasNonNull("tenantId") ? item.get("tenantId").asLong() : null);
            row.setAlias(text(item, "alias"));
            row.setConnectionName(text(item, "connectionName"));
            row.setProvider(provider(text(item, "provider")));
            row.setBucketName(text(item, "bucketName"));
            String status = text(item, "status");
            row.setStatus(status == null ? null : Status.valueOf(status));
            rows.add(row);
        }
        return rows;
    }

    private static StorageProvider provider(String name) {
        return name == null ? null : StorageProvider.valueOf(name);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Map<String, String> query(String k1, String v1, String k2, String v2) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(k1, v1);
        if (k2 != null) {
            query.put(k2, v2);
        }
        return query;
    }
}
