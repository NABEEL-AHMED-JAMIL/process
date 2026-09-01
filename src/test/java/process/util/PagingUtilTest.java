package process.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both numbers come off the query string, so anything a caller can type has to produce a page
 * rather than the internal-error response.
 */
class PagingUtilTest {

    @Test
    void aOneBasedPageBecomesAZeroBasedIndex() {
        Pageable paging = PagingUtil.ApplyPaging("jobName", "asc", 3L, 25L);
        assertThat(paging.getPageNumber()).isEqualTo(2);
        assertThat(paging.getPageSize()).isEqualTo(25);
        assertThat(paging.getSort().getOrderFor("jobName").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    /** page=0 is the natural guess for the first page, and used to answer with a 500. */
    @ParameterizedTest
    @ValueSource(longs = { 0L, -1L, -9999L })
    void aPageBelowOneFallsBackToTheFirstPage(long page) {
        assertThat(PagingUtil.ApplyPaging("id", "desc", page, 10L).getPageNumber()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = { 0L, -5L })
    void aSizeBelowOneFallsBackToTheDefault(long limit) {
        assertThat(PagingUtil.ApplyPaging("id", "desc", 1L, limit).getPageSize()).isEqualTo(10);
    }

    @Test
    void aMissingPageOrSizeIsTheDefault() {
        Pageable paging = PagingUtil.ApplyPaging(null, null, null, null);
        assertThat(paging.getPageNumber()).isZero();
        assertThat(paging.getPageSize()).isEqualTo(10);
        assertThat(paging.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    /** A value past Integer.MAX_VALUE wraps negative when it is narrowed without a ceiling. */
    @Test
    void aValueTooLargeForAnIntIsCappedRatherThanWrapped() {
        Pageable paging = PagingUtil.ApplyPaging("id", "asc", 5_000_000_000L, 5_000_000_000L);
        assertThat(paging.getPageNumber()).isEqualTo(Integer.MAX_VALUE);
        assertThat(paging.getPageSize()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void theSortDirectionIsCaseInsensitiveAndDefaultsToAscending() {
        assertThat(PagingUtil.ApplyPaging("id", "DESC", 1L, 10L).getSort().getOrderFor("id").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        assertThat(PagingUtil.ApplyPaging("id", "nonsense", 1L, 10L).getSort().getOrderFor("id").getDirection())
            .isEqualTo(Sort.Direction.ASC);
    }

}
