package process.model.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.LookupDataDto;
import process.model.pojo.LookupData;
import process.model.repository.LookupDataRepository;
import process.util.EncryptionUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * What happens to the lookup cache when a top-level lookup stops existing.
 *
 * initializeCache is not only the startup path -- SettingServiceImpl runs it after every add,
 * update and delete on /settings/lookup, and it is the only thing that ever reconciles the cache
 * with the table. Writing the parents it found into the map it already had made it structurally
 * incapable of expressing a removal: deleting the BUCKET_LIST row left its DTO, and the child
 * buckets under it, still answering getParentLookupById, so StorageBrowserServiceImpl.collectBuckets
 * kept offering buckets that were gone in every workspace's object browser until the process was
 * restarted. A rename produced two live keys for one row for the same reason.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class LookupDataCacheRebuildTest {

    private static final String BUCKET_LIST = "BUCKET_LIST";
    private static final String QUEUE_FETCH_LIMIT = "QUEUE_FETCH_LIMIT";

    @Mock private LookupDataRepository lookupDataRepository;
    @Mock private EncryptionUtil encryptionUtil;

    private LookupDataCacheService cacheService() {
        return new LookupDataCacheService(this.lookupDataRepository, this.encryptionUtil);
    }

    private LookupData parent(long lookupId, String lookupType, String lookupValue) {
        LookupData lookupData = new LookupData();
        lookupData.setLookupId(lookupId);
        lookupData.setLookupType(lookupType);
        lookupData.setLookupValue(lookupValue);
        lookupData.setEncrypted(false);
        return lookupData;
    }

    /** The table as it reads on successive calls: the first list, then the second. */
    private void tableReads(List<LookupData> first, List<LookupData> second) {
        when(this.lookupDataRepository.findRootsWithChildren())
            .thenReturn(new ArrayList<LookupData>(first))
            .thenReturn(new ArrayList<LookupData>(second));
    }

    @Test
    void dropsALookupThatNoLongerExistsWhenTheCacheIsRebuilt() {
        LookupData bucketList = parent(11L, BUCKET_LIST, "etl-bucket");
        LookupData fetchLimit = parent(12L, QUEUE_FETCH_LIMIT, "5000");
        tableReads(Arrays.asList(bucketList, fetchLimit), Collections.singletonList(fetchLimit));

        LookupDataCacheService service = cacheService();
        service.initializeCache();
        assertThat(service.getParentLookupById(BUCKET_LIST)).isNotNull();

        // The platform admin deletes the BUCKET_LIST row; deleteLookupData calls this straight after.
        service.initializeCache();

        // Without the rebuild the deleted parent is still in the map, and collectBuckets still
        // offers its buckets for the life of the JVM.
        assertThat(service.getParentLookupById(BUCKET_LIST)).isNull();
        assertThat(service.getLookupCacheMap()).containsOnlyKeys(QUEUE_FETCH_LIMIT);
    }

    @Test
    void leavesOnlyTheNewKeyBehindWhenAParentIsRenamed() {
        LookupData before = parent(21L, "OLD_BUCKET_LIST", "etl-bucket");
        LookupData after = parent(21L, BUCKET_LIST, "etl-bucket");
        tableReads(Collections.singletonList(before), Collections.singletonList(after));

        LookupDataCacheService service = cacheService();
        service.initializeCache();
        service.initializeCache();

        // One row must not resolve under two names -- the old one used to keep answering.
        assertThat(service.getParentLookupById("OLD_BUCKET_LIST")).isNull();
        LookupDataDto renamed = service.getParentLookupById(BUCKET_LIST);
        assertThat(renamed).isNotNull();
        assertThat(renamed.getLookupId()).isEqualTo(21L);
    }

    /**
     * The other half of the same fix, and the reason the rebuild happens in a local map rather than
     * by clearing the live one. initializeCache runs on a request thread while other requests are
     * reading the cache, so a reader that arrived between a clear and the repopulate would see no
     * lookups at all. A reader holding the map from before the rebuild must keep seeing what it
     * held, which is only true if the new contents went into a different map.
     */
    @Test
    void publishesTheRebuiltCacheWithoutEmptyingTheOneReadersAlreadyHold() {
        LookupData bucketList = parent(31L, BUCKET_LIST, "etl-bucket");
        LookupData fetchLimit = parent(32L, QUEUE_FETCH_LIMIT, "5000");
        tableReads(Arrays.asList(bucketList, fetchLimit), Collections.singletonList(fetchLimit));

        LookupDataCacheService service = cacheService();
        service.initializeCache();
        Map<String, LookupDataDto> readerHolds = service.getLookupCacheMap();

        service.initializeCache();

        assertThat(readerHolds).containsKey(BUCKET_LIST);
        assertThat(service.getLookupCacheMap()).isNotSameAs(readerHolds);
    }
}
