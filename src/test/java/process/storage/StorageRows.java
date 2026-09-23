package process.storage;

import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Test rows behind a mocked StorageConnectionRepository, answering the three alias lookups the way the
 * database does (MIG-53: an alias is unique per workspace, and every lookup says whose). A test says
 * which connections exist; the tenant-qualified rules then apply for real, rather than each test
 * stubbing an answer that bakes in the old platform-wide alias.
 */
public final class StorageRows {

    private static final Map<StorageConnectionRepository, List<StorageConnection>> ROWS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private StorageRows() {
    }

    /** This connection exists. A second row with the same tenant and alias replaces the first. */
    public static StorageConnection add(StorageConnectionRepository repository, StorageConnection row) {
        List<StorageConnection> rows = ROWS.computeIfAbsent(repository, StorageRows::install);
        rows.removeIf(existing -> Objects.equals(existing.getTenantId(), row.getTenantId())
            && Objects.equals(existing.getAlias(), row.getAlias()));
        rows.add(row);
        return row;
    }

    /** No connections at all, before a test says which exist. */
    public static void clear(StorageConnectionRepository repository) {
        ROWS.computeIfAbsent(repository, StorageRows::install).clear();
    }

    private static List<StorageConnection> install(StorageConnectionRepository repository) {
        List<StorageConnection> rows = Collections.synchronizedList(new ArrayList<>());
        lenient().when(repository.findByTenantIdAndAlias(any(), anyString())).thenAnswer(call -> first(rows,
            r -> r.getTenantId() != null && r.getTenantId().equals(call.getArgument(0)) && r.getAlias().equals(call.getArgument(1))));
        lenient().when(repository.findByTenantIdIsNullAndAlias(anyString())).thenAnswer(call -> first(rows,
            r -> r.getTenantId() == null && r.getAlias().equals(call.getArgument(0))));
        lenient().when(repository.findAllByAlias(anyString())).thenAnswer(call -> {
            synchronized (rows) {
                return rows.stream().filter(r -> r.getAlias().equals(call.getArgument(0))).collect(Collectors.toList());
            }
        });
        return rows;
    }

    private static Optional<StorageConnection> first(List<StorageConnection> rows,
        Predicate<StorageConnection> match) {
        synchronized (rows) {
            return rows.stream().filter(match).findFirst();
        }
    }
}
