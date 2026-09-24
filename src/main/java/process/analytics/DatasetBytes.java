package process.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.service.StorageBrowserService;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How many bytes a scan of a dataset reads: what analytics.gb_scanned bills (MIG-104). DuckDB 1.1.3's
 * profiler counts rows scanned, not bytes, so the figure is Storage's -- one object's size, or for a
 * glob the sum of the objects it matches, expanded as DuckDB expands it: '*' and '?' within one folder,
 * '**' across folders. Read as the signed-in caller, through the same Storage calls the browser makes.
 *
 * A glob past {@link #MAX_OBJECTS} objects is summed that far and marked partial rather than walked
 * without end; a size Storage cannot give is empty -- never guessed, since a wrong size is a wrong bill.
 */
@Component
public class DatasetBytes {

    static final int MAX_OBJECTS = 10_000;
    private static final int PAGE = 1000;
    private static final Logger logger = LoggerFactory.getLogger(DatasetBytes.class);

    private final StorageBrowserService storage;

    public DatasetBytes(StorageBrowserService storage) {
        this.storage = storage;
    }

    /** What a scan reads: bytes, how many objects, and whether every object was counted. */
    public static final class Size {
        public final long bytes;
        public final int objects;
        public final boolean complete;

        public Size(long bytes, int objects, boolean complete) {
            this.bytes = bytes;
            this.objects = objects;
            this.complete = complete;
        }
    }

    public Optional<Size> of(String connectionAlias, String path) {
        if (connectionAlias == null || connectionAlias.trim().isEmpty() || path == null || path.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return isGlob(path) ? this.globbed(connectionAlias, path.trim()) : this.single(connectionAlias, path.trim());
        } catch (RuntimeException unreadable) {
            logger.warn("Could not size {} on {} for analytics.gb_scanned: {}", path, connectionAlias, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Size> single(String alias, String path) {
        ObjectMetadataDto meta = this.storage.getObjectMetadataCached(alias, path);
        return meta == null || meta.getSize() == null ? Optional.empty() : Optional.of(new Size(meta.getSize(), 1, true));
    }

    private Optional<Size> globbed(String alias, String glob) {
        Pattern matcher = Pattern.compile(regexOf(glob));
        boolean recursive = glob.contains("**");
        int depth = depthOf(glob);
        long bytes = 0;
        int objects = 0;
        Deque<String> folders = new ArrayDeque<>();
        folders.add(staticPrefixOf(glob));
        while (!folders.isEmpty()) {
            String prefix = folders.poll();
            String token = null;
            do {
                BrowseObjectsResponseDto page = this.storage.listObjects(alias, prefix, token, PAGE);
                for (ObjectSummaryDto entry : page.getObjects()) {
                    if (entry.isFolder()) {
                        // Down a folder only where the glob can still match: anywhere under '**', else to its own
                        // depth -- a folder's key ends in '/', so it has the depth of the objects inside it.
                        if (recursive || depthOf(entry.getKey()) <= depth) {
                            folders.add(entry.getKey());
                        }
                    } else if (matcher.matcher(entry.getKey()).matches()) {
                        if (objects == MAX_OBJECTS) {
                            return Optional.of(new Size(bytes, objects, false));
                        }
                        bytes += entry.getSize() == null ? 0 : entry.getSize();
                        objects++;
                    }
                }
                token = page.getNextContinuationToken();
            } while (token != null);
        }
        return Optional.of(new Size(bytes, objects, true));
    }

    static boolean isGlob(String path) {
        return path.contains("*") || path.contains("?");
    }

    /** "q3/2026-*.csv" -> "q3/": the folder the glob starts in. */
    static String staticPrefixOf(String glob) {
        int first = firstGlobChar(glob);
        int slash = glob.lastIndexOf('/', first);
        return slash < 0 ? "" : glob.substring(0, slash + 1);
    }

    private static int firstGlobChar(String glob) {
        int star = glob.indexOf('*');
        int question = glob.indexOf('?');
        return star < 0 ? question : question < 0 ? star : Math.min(star, question);
    }

    private static int depthOf(String key) {
        int depth = 0;
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    /** DuckDB's glob as a regex: '**' any folders deep (including none), '*' and '?' within one folder. */
    static String regexOf(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                regex.append(slashAfter ? "(?:.*/)?" : ".*");
                i += slashAfter ? 2 : 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
