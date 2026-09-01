package process.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import process.model.dto.PagingDto;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
public class PagingUtil {

    private static final String ID = "id";
    private static final String ASC = "asc";
    private static final String DESC = "desc";
    private static final Long DEFAULT_PAGE_NUMBER = 0l;
    private static final Long DEFAULT_MAX_NO_OF_ROWS = 10l;

    public static Object convertEntityToPagingDTO(Long totalCount, Pageable page) {
        PagingDto pdto = new PagingDto();
        pdto.setPageSize(Long.valueOf(page.getPageSize()));
        pdto.setCurrentPage(Long.valueOf(page.getPageNumber() + 1));
        pdto.setTotalRecord(totalCount);
        return pdto;
    }

    /** `page` is one-based here, as the list endpoints receive it; PageRequest counts from zero. */
    public static Pageable ApplyPaging(String orderBy, String direction, Long page, Long limit) {
        return ApplyPagingAndSorting(orderBy, direction, page != null ? page - 1 : 0l, limit);
    }

    public static Pageable ApplyPagingAndSorting(String orderBy, String direction, Long page, Long limit) {
        List<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(getSortDirection(direction), orderBy != null ? orderBy: ID));
        // Both values arrive straight off the query string, and PageRequest.of throws on a
        // negative index or a size below one. Only null was being handled, so "?page=0" -- the
        // natural guess for the first page, and what a zero-based client sends -- answered with
        // the internal-error page instead of a list. Out-of-range means the default, the same
        // as leaving it off.
        if (page == null || page < 0l) {
            page = DEFAULT_PAGE_NUMBER;
        }
        if (limit == null || limit < 1l) {
            limit = DEFAULT_MAX_NO_OF_ROWS;
        }
        // Narrowed with a ceiling rather than intValue() alone: a Long past Integer.MAX_VALUE
        // wraps to a negative int, which PageRequest rejects the same way a negative page does.
        return PageRequest.of(toBoundedInt(page), toBoundedInt(limit), Sort.by(orders));
    }

    private static int toBoundedInt(Long value) {
        return (int) Math.min(value, (long) Integer.MAX_VALUE);
    }

    private static Sort.Direction getSortDirection(String direction) {
        if (direction != null && direction.equalsIgnoreCase(DESC)) {
            return Sort.Direction.DESC;
        }
        return Sort.Direction.ASC;
    }

}