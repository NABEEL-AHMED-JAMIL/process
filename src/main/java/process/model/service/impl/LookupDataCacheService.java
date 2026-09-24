package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.LookupDataDto;
import process.model.pojo.LookupData;
import process.model.repository.LookupDataRepository;
import process.util.EncryptionUtil;
import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class LookupDataCacheService {

    private Logger logger = LoggerFactory.getLogger(LookupDataCacheService.class);

    /**
     * Volatile because initializeCache replaces this reference rather than editing the map behind
     * it, and the replacement is published to request threads that are reading the cache at the
     * same time -- see initializeCache for why it is rebuilt rather than updated in place.
     */
    private volatile Map<String, LookupDataDto> lookupCacheMap = new HashMap<>();

    private final LookupDataRepository lookupDataRepository;
    private final EncryptionUtil encryptionUtil;

    public LookupDataCacheService(LookupDataRepository lookupDataRepository, EncryptionUtil encryptionUtil) {
        this.lookupDataRepository = lookupDataRepository;
        this.encryptionUtil = encryptionUtil;
    }

    @PostConstruct
    public void initialize() {
        try {
            initializeCache();
        } catch (Exception ex) {
            logger.error("Failed to initialize lookup cache: {}", ex.getMessage());
        }
    }

    /**
     * Rebuilds the cache from scratch and publishes the finished map in one assignment.
     *
     * This is not only the startup path: SettingServiceImpl calls it after every add, update and
     * delete of a lookup, so it is the only thing that ever brings the cache back in line with the
     * table. It used to write the parents it found into the existing map and nothing else, which
     * made it incapable of expressing a removal. Deleting the top-level BUCKET_LIST row left its
     * DTO -- and every child bucket hanging off it -- still answering getParentLookupById, so
     * StorageBrowserServiceImpl.collectBuckets went on listing and resolving buckets that no longer
     * existed in every workspace's object browser until the JVM was restarted. Renaming a parent's
     * lookupType broke the same way from the other end: the new key was added and the old one stayed
     * behind, so both names resolved.
     *
     * Emptying the map first and refilling it would express removals but would open a window in
     * which the cache is empty, and this runs on a request thread while other requests are reading:
     * a reader landing in that window sees no lookups at all rather than the previous ones. Building
     * the replacement in a local map and swapping it onto the volatile field is a single write, so
     * every reader sees either the whole previous map or the whole new one and never a half-built
     * state.
     */
    @Transactional(readOnly = true)
    public void initializeCache() {
        logger.info("****************Cache-Lookup-Start***************************");
        Map<String, LookupDataDto> rebuiltCache = new HashMap<>();
        // Children fetched in the same read (MIG-67): this runs from @PostConstruct, where the self-call
        // bypasses the proxy and there is no transaction, and the lazy children only loaded while
        // enable_lazy_load_no_trans let them. With it off the rebuild threw, was logged, and left the
        // cache empty until something else rebuilt it.
        Iterable<LookupData> lookupDataList = this.lookupDataRepository.findRootsWithChildren();
        lookupDataList.forEach(lookupData ->
            rebuiltCache.put(lookupData.getLookupType(), getLookupDataDetail(lookupData)));
        this.lookupCacheMap = rebuiltCache;
        logger.info("***************Cache-Lookup-End********************************");
    }

    private LookupDataDto getLookupDataDetail(LookupData lookupData) {
        LookupDataDto parentLookupData = this.toLookupDataDto(lookupData);
        // A row that has just been saved has no children collection at all -- the association
        // is lazy and was never initialised -- so this threw and every add returned a 500.
        if (lookupData.getChildren() != null && !lookupData.getChildren().isEmpty()) {
            parentLookupData.setChildren(lookupData.getChildren()
                .stream().map(this::toLookupDataDto)
                .collect(Collectors.toSet()));
        }
        return parentLookupData;
    }

    public LookupDataDto getParentLookupById(String lookupType) {
        return this.lookupCacheMap.get(lookupType);
    }

    public Map<String, LookupDataDto> getLookupCacheMap() {
        return lookupCacheMap;
    }

    private LookupDataDto toLookupDataDto(LookupData lookupData) {
        LookupDataDto lookupDataDto = new LookupDataDto();
        lookupDataDto.setLookupId(lookupData.getLookupId());
        lookupDataDto.setLookupType(lookupData.getLookupType());
        lookupDataDto.setEncrypted(lookupData.getEncrypted());
        lookupDataDto.setTenantId(lookupData.getTenantId());
        lookupDataDto.setLookupValue(Boolean.TRUE.equals(lookupData.getEncrypted())
            ? this.encryptionUtil.decrypt(lookupData.getLookupValue())
            : lookupData.getLookupValue());
        lookupDataDto.setDescription(lookupData.getDescription());
        lookupDataDto.setDateCreated(lookupData.getDateCreated());
        return lookupDataDto;
    }
}
