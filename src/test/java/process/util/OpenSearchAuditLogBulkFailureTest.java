package process.util;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a partly rejected _bulk has to tell the caller.
 *
 * The caller writes audit lines to the database only when OpenSearch did not take them, so a
 * response read as "all stored" when four of twenty-five were rejected leaves those four in
 * neither store -- and the screen that merges the two shows nothing at all where they were.
 *
 * @author Nabeel Ahmed
 */
public class OpenSearchAuditLogBulkFailureTest {

    private final OpenSearchAuditLogClient client = new OpenSearchAuditLogClient();

    private List<Object[]> entries(int count) {
        List<Object[]> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            entries.add(new Object[] { "id-" + index, 42L, "line " + index, new Timestamp(0L) });
        }
        return entries;
    }

    /** A _bulk response as OpenSearch sends it: one item per document, in request order. */
    private String bulkResponse(int... statuses) {
        boolean errors = false;
        for (int status : statuses) {
            errors = errors || status > 299;
        }
        StringBuilder body = new StringBuilder("{\"took\":7,\"errors\":").append(errors).append(",\"items\":[");
        for (int index = 0; index < statuses.length; index++) {
            if (index > 0) {
                body.append(",");
            }
            body.append("{\"index\":{\"_index\":\"job-audit-logs\",\"_id\":\"id-").append(index)
                .append("\",\"status\":").append(statuses[index]);
            if (statuses[index] > 299) {
                body.append(",\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse\"}");
            }
            body.append("}}");
        }
        return body.append("]}").toString();
    }

    @Test
    void aCleanBulkLeavesNothingForTheDatabase() {
        List<Object[]> entries = entries(3);
        assertTrue(this.client.rejectedEntries(bulkResponse(201, 201, 201), entries).isEmpty(),
            "nothing should fall back when every line was indexed");
    }

    @Test
    void onlyTheRejectedLinesComeBack() {
        // One mapping conflict and one write-queue rejection out of four.
        List<Object[]> entries = entries(4);
        List<Object[]> rejected = this.client.rejectedEntries(bulkResponse(201, 400, 201, 429), entries);
        assertEquals(2, rejected.size());
        assertSame(entries.get(1), rejected.get(0), "position is what pairs a rejection with its line");
        assertSame(entries.get(3), rejected.get(1));
    }

    @Test
    void aBodyThatCannotBeLinedUpCountsAsRejected() {
        // Every one of these says the same thing: we do not know which lines survived. Writing
        // a line twice is readable, dropping it is not, so the whole batch falls back.
        List<Object[]> entries = entries(3);
        assertEquals(3, this.client.rejectedEntries(null, entries).size(), "no body at all");
        assertEquals(3, this.client.rejectedEntries("{}", entries).size(), "not a bulk response");
        assertEquals(3, this.client.rejectedEntries("<html>gateway timeout</html>", entries).size(),
            "not even json");
        assertEquals(3, this.client.rejectedEntries(bulkResponse(201, 400), entries).size(),
            "fewer items than documents sent");
        assertEquals(3, this.client.rejectedEntries("{\"errors\":true,\"items\":[{\"index\":{\"status\":201}},"
            + "{\"index\":{\"status\":201}},{\"index\":{\"status\":201}}]}", entries).size(),
            "errors reported but no item admits to being one");
    }

    @Test
    void anUnconfiguredClientStoresNothingAndSaysSo() {
        // baseUrl is unset here, as it is wherever OpenSearch is not deployed.
        List<Object[]> entries = entries(2);
        assertFalse(this.client.indexAll(entries));
        assertEquals(2, this.client.indexAllReturningFailures(entries).size(),
            "with no OpenSearch every line has to go to the database");
    }

}
