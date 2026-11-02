package process.pipeline.tasks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-450: Route sends each row down the first branch it meets, else Otherwise, and says the split. */
class RouteStepTaskTest {

    private final RouteStepTask task = new RouteStepTask();
    private final List<Map<String, Object>> referrals = Arrays.asList(
        row("id", 1L, "priority", "urgent", "score", 9),
        row("id", 2L, "priority", "routine", "score", 8),
        row("id", 3L, "priority", "urgent", "score", 2),
        row("id", 4L, "priority", "", "score", null),
        row("id", 5L, "priority", "routine", "score", 1));

    private static Map<String, Object> branch(String name, String match, Object... conditions) {
        return config("name", name, "match", match, "conditions", Arrays.asList(conditions));
    }

    private List<String> taken(Object... config) throws Exception {
        List<String> branches = new ArrayList<>();
        for (Map<String, Object> row : this.task.run(TaskContext.of(config(config), this.referrals)).getOutput().getRows()) {
            branches.add(row.get("id") + ":" + row.get("branch"));
        }
        return branches;
    }

    @Test
    void theFirstBranchARowMeetsWinsAndTheRestGoOtherwise() throws Exception {
        assertThat(this.taken("branches", Arrays.asList(
            branch("urgent", "all", config("column", "priority", "operator", "eq", "value", "urgent")),
            branch("high_score", "all", config("column", "score", "operator", "gte", "value", 8)))))
            .containsExactly("1:urgent", "2:high_score", "3:urgent", "4:otherwise", "5:otherwise");
    }

    @Test
    void anyOfABranchsConditionsAndItsOwnNamesForOtherwiseAndTheColumn() throws Exception {
        Map<String, Object> look = branch("look", "any", config("column", "priority", "operator", "eq", "value", "urgent"),
            config("column", "score", "operator", "lt", "value", 2));
        List<Map<String, Object>> rows = this.task.run(TaskContext.of(config("branches", Collections.singletonList(look),
            "otherwise", "rest", "column", "lane"), this.referrals)).getOutput().getRows();
        assertThat(rows).extracting(r -> r.get("id") + ":" + r.get("lane")).containsExactly("1:look", "2:rest", "3:look", "4:rest", "5:look");
        assertThat(rows.get(0)).containsKeys("id", "priority", "score", "lane").doesNotContainKey("branch");
    }

    @Test
    void itSaysTheSplitAndRefusesTwoBranchesOfOneName() throws Exception {
        assertThat(this.task.run(TaskContext.of(config("branches", Collections.singletonList(
            branch("urgent", "all", config("column", "priority", "operator", "eq", "value", "urgent")))), this.referrals)).getNote())
            .isEqualTo("urgent 2 · otherwise 3");
        assertThat(this.task.check(config("branches", Arrays.asList(
            branch("a", "all", config("column", "x", "operator", "eq", "value", 1)),
            branch("a", "all", config("column", "x", "operator", "eq", "value", 2))))))
            .extracting(p -> p.getPath() + " " + p.getMessage()).containsExactly("branches[1].name 'a' is already a branch of this route");
        assertThat(this.task.check(config("otherwise", "a", "branches", Collections.singletonList(
            branch("a", "all", config("column", "x", "operator", "lt")))))).extracting(p -> p.getPath())
            .containsExactly("branches[0].name", "branches[0].conditions[0].value");
    }
}
