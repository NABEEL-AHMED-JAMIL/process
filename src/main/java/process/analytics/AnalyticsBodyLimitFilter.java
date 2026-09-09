package process.analytics;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Refuses an analytics request body that is too large to parse safely.
 *
 * <b>This exists because a depth guard cannot guard the thing that runs before it.</b>
 * {@code FilterClause} is self-recursive, and Jackson binds it from the request body BEFORE any
 * analytics code executes -- so {@code FilterCompiler.MAX_DEPTH}, whose own javadoc says it "keeps
 * a deliberately deep payload from being a StackOverflowError", is checked several thousand frames
 * after the point where that error is thrown. Measured on this project's jackson-databind 2.11.1:
 * a body of {@code {"op":"AND","clauses":[} repeated twenty thousand times, about 500KB, is a
 * StackOverflowError during binding. It is an Error rather than an Exception, so it sails past the
 * controller's {@code catch (Exception)} exactly as that javadoc predicts, and takes the request
 * thread with it. Jackson 2.11 predates StreamReadConstraints, so there is no depth cap to set.
 *
 * A byte ceiling is the honest guard here. It is not a depth check and does not pretend to be one:
 * it refuses a body no legitimate analysis produces, before anything tries to understand it, which
 * is the only place a recursion bomb can be stopped without a parser that counts. The real depth
 * rule still runs afterwards and still refuses a nine-deep filter in a small body.
 *
 * Scoped to the analytics endpoints rather than applied globally, because a limit that is right for
 * an analysis request is not right for a file upload, and this filter knows nothing about those.
 *
 * @author Nabeel Ahmed
 */
@Component
@Order(1)
public class AnalyticsBodyLimitFilter extends OncePerRequestFilter {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsBodyLimitFilter.class);

    /**
     * The ceiling, in bytes.
     *
     * Generous by two orders of magnitude against real traffic: the largest analysis this UI can
     * build -- three dimensions, a measure, Top-N and a filter tree at the eight-deep limit with
     * the full two hundred clauses -- is a few kilobytes. Small enough that the deepest body it
     * admits, about ten thousand nested groups, is still thousands of frames short of the stack.
     */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final Gson gson = new Gson();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath() == null ? "" : request.getServletPath();
        // Only the endpoints that take a recursive body. /analytics.json also serves GETs with no
        // body at all, and they cost nothing to skip.
        return !(path.startsWith("/analytics.json") || path.startsWith("/analyticsExport.json")
            || path.startsWith("/analyticsWorkspace.json"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {

        // Content-Length rather than reading the stream: consuming it here would leave nothing for
        // the parser. A chunked request has -1 and is passed through -- this filter is the outer
        // of two guards, not the only one, and refusing every chunked request to close a gap that
        // needs an authenticated caller and a deliberately malformed body would cost more than it
        // buys.
        int declared = request.getContentLength();
        if (declared > MAX_BODY_BYTES) {
            logger.warn("Refused an analytics request of {} bytes to {}; the ceiling is {}.",
                declared, request.getServletPath(), MAX_BODY_BYTES);
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(this.gson.toJson(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "That request is too large to read. An analysis, however many filters it carries, "
                + "is a few kilobytes; this one was " + (declared / 1024) + "KB.")));
            response.getWriter().flush();
            return;
        }
        chain.doFilter(request, response);
    }
}
