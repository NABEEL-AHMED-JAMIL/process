package process.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import process.payload.request.PagingRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
public class PagingUtil {

    private static final String ID = "id";
    private static final String ASC = "asc";
    private static final String DESC = "desc";
    private static final Long DEFAULT_PAGE_NUMBER = 0l;
    private static final Long DEFAULT_MAX_NO_OF_ROWS = 10l;

    public static Object convertEntityToPagingDTO(Long totalCount, Pageable page) {
        PagingRequest pdto = new PagingRequest();
        pdto.setPageSize(new Long(page.getPageSize()));
        pdto.setCurrentPage(new Long(page.getPageNumber()+1));
        pdto.setTotalRecord(totalCount);
        return pdto;
    }

    /* Page = current page And size is Limit*/
    public static Pageable ApplyPaging(String orderBy, String direction, Long page, Long limit) {
        return ApplyPagingAndSorting(orderBy, direction, page != null ? page - 1 : 0l, limit);
    }

    /* Apply If Needed */
    public static Pageable ApplyPagingAndSorting(String orderBy, String direction, Long page, Long limit) {
        List<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(getSortDirection(direction), orderBy != null ? orderBy: ID));
        if (page == null) {
            page = DEFAULT_PAGE_NUMBER;
        }
        if (limit == null) {
            limit = DEFAULT_MAX_NO_OF_ROWS;
        }
        return PageRequest.of(page.intValue(), limit.intValue(), Sort.by(orders));
    }

    private static Sort.Direction getSortDirection(String direction) {
        if (direction.equalsIgnoreCase(ASC)) {
            return Sort.Direction.ASC;
        } else if (direction.equalsIgnoreCase(DESC)) {
            return Sort.Direction.DESC;
        }
        return Sort.Direction.ASC;
    }

}