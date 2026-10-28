package process.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner 2026-10-27 ("add a sort on each table"): the paged lists' sort is a fixed allow-list. A request names a column of it
 * and a direction; anything else is refused with the columns it may name, and only SQL written in code reaches a statement.
 */
class ListSortTest {

    private static final Map<String, ListSort.Column> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("run", ListSort.Column.of("q.job_queue_id"));
        COLUMNS.put("ended", ListSort.Column.nullable("q.end_time"));
        COLUMNS.put("status", ListSort.Column.of("q.job_status"));
    }

    @Test
    void noSortIsTheListsOwnOrder() {
        assertThat(ListSort.parse(null, null, COLUMNS.keySet())).isNull();
        assertThat(ListSort.parse("  ", "desc", COLUMNS.keySet())).as("a direction alone changes nothing").isNull();
    }

    @Test
    void everyAllowedColumnSortsBothWays() {
        for (String column : COLUMNS.keySet()) {
            ListSort.Choice asc = ListSort.parse(column, "asc", COLUMNS.keySet());
            ListSort.Choice desc = ListSort.parse(column, "DESC", COLUMNS.keySet());
            assertThat(asc.column).isEqualTo(column);
            assertThat(asc.dir).isEqualTo(ListSort.Dir.ASC);
            assertThat(desc.dir).isEqualTo(ListSort.Dir.DESC);
        }
        assertThat(ListSort.parse("ended", null, COLUMNS.keySet()).dir).as("asc when no direction is named").isEqualTo(ListSort.Dir.ASC);
    }

    @Test
    void anUnknownColumnIsRefusedNamingTheAllowedOnes() {
        assertThatThrownBy(() -> ListSort.parse("job_status; drop table job_queue", "asc", COLUMNS.keySet()))
            .isInstanceOf(ListSort.Refused.class).hasMessage("sort must be one of: run, ended, status.");
        assertThatThrownBy(() -> ListSort.parse("Run", "asc", COLUMNS.keySet())).as("the names are exact")
            .isInstanceOf(ListSort.Refused.class).hasMessageContaining("run, ended, status");
    }

    @Test
    void aDirectionOtherThanAscOrDescIsRefused() {
        assertThatThrownBy(() -> ListSort.parse("run", "up", COLUMNS.keySet()))
            .isInstanceOf(ListSort.Refused.class).hasMessage("dir must be asc or desc.");
        assertThatThrownBy(() -> ListSort.parse(null, "sideways", COLUMNS.keySet())).isInstanceOf(ListSort.Refused.class);
    }

    @Test
    void theOrderIsTheMapsSqlWithNullsLastAndTheIdTiebreak() {
        assertThat(ListSort.orderBy(ListSort.parse("ended", "desc", COLUMNS.keySet()), COLUMNS, "q.job_queue_id"))
            .isEqualTo("q.end_time DESC NULLS LAST, q.job_queue_id DESC");
        assertThat(ListSort.orderBy(ListSort.parse("ended", "asc", COLUMNS.keySet()), COLUMNS, "q.job_queue_id"))
            .isEqualTo("q.end_time ASC NULLS LAST, q.job_queue_id ASC");
        // A column that is never empty keeps plain ASC/DESC, so an index on it serves both directions.
        assertThat(ListSort.orderBy(ListSort.parse("status", "desc", COLUMNS.keySet()), COLUMNS, "q.job_queue_id"))
            .isEqualTo("q.job_status DESC, q.job_queue_id DESC");
        // The id column needs no tiebreak of itself.
        assertThat(ListSort.orderBy(ListSort.parse("run", "asc", COLUMNS.keySet()), COLUMNS, "q.job_queue_id"))
            .isEqualTo("q.job_queue_id ASC");
    }

    private static final class Row {
        final long id;
        final Integer value;

        Row(long id, Integer value) {
            this.id = id;
            this.value = value;
        }
    }

    @Test
    void inJavaEmptyValuesGoLastBothWaysAndTiesKeepTheIdOrderOfTheDirection() {
        List<Row> rows = Arrays.asList(new Row(1, 5), new Row(2, null), new Row(3, 7), new Row(4, 5), new Row(5, null));
        List<Row> asc = new ArrayList<>(rows);
        asc.sort(ListSort.comparator(r -> r.value, ListSort.Dir.ASC, (a, b) -> Long.compare(a.id, b.id)));
        assertThat(asc.stream().map(r -> r.id).collect(Collectors.toList())).containsExactly(1L, 4L, 3L, 2L, 5L);
        List<Row> desc = new ArrayList<>(rows);
        desc.sort(ListSort.comparator(r -> r.value, ListSort.Dir.DESC, (a, b) -> Long.compare(a.id, b.id)));
        assertThat(desc.stream().map(r -> r.id).collect(Collectors.toList())).containsExactly(3L, 4L, 1L, 5L, 2L);
    }
}
