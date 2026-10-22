package process.pipeline.tasks;

import process.pipeline.backing.ApiRunner;
import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-420: a row's {{call_key}} and its item's outcome. In an item run the key is the row's item's (StepContext.rowTag): a
 * retry of some items reads only their rows, so an item may be row 0 now and row 1 before -- it keeps its own key and never
 * gets another item's. With Calls at once the calls finish in any order, and each item still gets its own outcome, its own
 * key and its time, told on the step's own thread in row order. A run with no items keys by the row's number, as always.
 */
class CallApiCallKeyTest {

    @Test
    void aRetriedItemSendsItsOwnKeyWhateverItsRowNumber() {
        TaskContext items = TaskContext.of(config(), Collections.<Map<String, Object>>emptyList());
        items.itemOf = r -> String.valueOf(r.get("invoice_id"));
        Map<String, Object> b = row("invoice_id", "INV-9002");
        Map<String, Object> a = row("invoice_id", "INV-9001");
        String bFirst = CallApiStepTask.callKeyOf(items, b, 1);
        String bRetried = CallApiStepTask.callKeyOf(items, b, 0);
        assertThat(bRetried).as("row 0 now, row 1 before: the same item, the same key").isEqualTo(bFirst);
        assertThat(bRetried).as("never the key the item at row 0 had").isNotEqualTo(CallApiStepTask.callKeyOf(items, a, 0))
            .isNotEqualTo(CallApiStepTask.callKey(TaskContext.TENANT, TaskContext.RUN, items.stepKey(), 0));
        TaskContext plain = TaskContext.of(config(), Collections.<Map<String, Object>>emptyList());
        assertThat(CallApiStepTask.callKeyOf(plain, b, 1)).as("no items: callKey by the row, unchanged")
            .isEqualTo(CallApiStepTask.callKey(TaskContext.TENANT, TaskContext.RUN, plain.stepKey(), 1));
    }

    @Test
    void eightCallsAtOnceFinishingOutOfOrderEachItemGetsItsOwnOutcomeKeyAndTime() throws Exception {
        CallApiStepTaskTest.Target api = new CallApiStepTaskTest.Target();
        api.answer = v -> {
            int n = Integer.parseInt(v.get("invoice").substring(4));
            try {
                Thread.sleep((16 - n) * 3L);   // the first rows answer last
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return n == 5 ? new Object[] {422, "{\"error\":\"unknown currency\"}"} : new Object[] {200, "{\"rate\":\"r-" + n + "\"}"};
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            rows.add(row("invoice_id", "INV-" + i));
        }
        TaskContext context = TaskContext.of(config("requestId", 12, "callsAtOnce", 8, "onError", "continue",
            "variables", config("invoice", "{{invoice_id}}", "key", "{{call_key}}"),
            "fields", Collections.singletonList(config("path", "rate", "target", "rate"))), rows);
        context.itemOf = r -> String.valueOf(r.get("invoice_id"));
        String step = Thread.currentThread().getName();

        Dataset out = new CallApiStepTask(api).run(context).getOutput();

        assertThat(out.getRows()).extracting(r -> r.get("invoice_id")).containsExactlyElementsOf(
            rows.stream().map(r -> r.get("invoice_id")).collect(Collectors.toList()));
        for (Map<String, Object> r : out.getRows()) {
            String n = String.valueOf(r.get("invoice_id")).substring(4);
            assertThat(r.get("rate")).as("each row its own answer").isEqualTo("5".equals(n) ? null : "r-" + n);
        }
        // Each call carried its own item's key: the same as callKeyOf gives that row, all different.
        Map<String, String> keyOf = new HashMap<>();
        for (ApiRunner.ApiCall call : api.calls) {
            keyOf.put(call.variables.get("invoice"), call.variables.get("key"));
        }
        assertThat(new HashSet<>(keyOf.values())).hasSize(16);
        for (int i = 0; i < 16; i++) {
            assertThat(keyOf.get("INV-" + i)).isEqualTo(CallApiStepTask.callKeyOf(context, rows.get(i), i));
        }
        // The items were told on the step's thread, in row order, and only INV-5 failed, in the call's words.
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            expected.add(step + " took INV-" + i);
            if (i == 5) {
                expected.add(step + " failed INV-5: HTTP 422: Unprocessable");
            }
        }
        assertThat(context.itemEvents).containsExactlyElementsOf(expected);
    }
}
