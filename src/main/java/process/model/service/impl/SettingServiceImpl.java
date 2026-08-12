package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.LookupData;
import process.model.pojo.SourceTaskType;
import process.model.projection.ItemResponse;
import process.model.projection.SourceTaskTypeProjection;
import process.model.pojo.TenantTaskTypeKafkaRoute;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.SettingService;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.KafkaTopicPartitionUtil;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SettingServiceImpl implements SettingService {

    private Logger logger = LoggerFactory.getLogger(SettingServiceImpl.class);

    private final String PARENT_LOOKUP_DATA = "parentLookupData";
    private final String LOOKUP_DATA = "lookupDatas";
    private final String SOURCE_TASK_TYPE = "sourceTaskTypes";
    /** Parent lookupType under which the Bucket Browser's real, per-tenant storage
     * buckets/containers live -- see StorageBrowserServiceImpl's own javadoc. */
    private final String BUCKET_LIST = "BUCKET_LIST";
    /** Shown to the UI in place of an encrypted lookup's real value — never send plaintext back over the API. */
    private final String MASKED_LOOKUP_VALUE = "••••••••";

    private final LookupDataRepository lookupDataRepository;
    private final SourceJobRepository sourceJobRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final KafkaConnectionProfileRepository kafkaConnectionProfileRepository;
    private final TenantTaskTypeKafkaRouteRepository tenantTaskTypeKafkaRouteRepository;
    private final QueryService queryService;
    private final EncryptionUtil encryptionUtil;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;
    private final LookupDataCacheService lookupDataCacheService;

    public SettingServiceImpl(LookupDataRepository lookupDataRepository,
        SourceJobRepository sourceJobRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        KafkaConnectionProfileRepository kafkaConnectionProfileRepository,
        TenantTaskTypeKafkaRouteRepository tenantTaskTypeKafkaRouteRepository,
        QueryService queryService,
        EncryptionUtil encryptionUtil,
        KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver,
        LookupDataCacheService lookupDataCacheService) {
        this.lookupDataRepository = lookupDataRepository;
        this.sourceJobRepository = sourceJobRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.kafkaConnectionProfileRepository = kafkaConnectionProfileRepository;
        this.tenantTaskTypeKafkaRouteRepository = tenantTaskTypeKafkaRouteRepository;
        this.queryService = queryService;
        this.encryptionUtil = encryptionUtil;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
        this.lookupDataCacheService = lookupDataCacheService;
    }

    /**
     * Method use to fetch the dynamic query response
     * @param itemResponse
     * @return ResponseDto
     * */
    @Override
    public ResponseDto dynamicQueryResponse(ItemResponse itemResponse) {
        // This runs the request body's query string as-is via createNativeQuery -- arbitrary
        // SQL, not just SELECT, and native queries bypass tenant-scoping filters entirely. Every
        // other authenticated role (TENANT_ADMIN/TENANT_USER) must not reach it; only
        // PLATFORM_ADMIN (unscoped, cross-tenant by design) is trusted with raw DB access.
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform admin can run a dynamic query.");
        }
        if (isNull(itemResponse.getQuery())) {
            return new ResponseDto(ERROR, "Query missing.");
        }
        return new ResponseDto(SUCCESS, "Data fetch successfully.", this.queryService.executeQueryResponse(itemResponse.getQuery()));
    }

    /**
     * Method use to fetch the lookup data and source task type. Cached, keyed per-caller
     * (platform admin vs. each tenant) -- LookupData is global, but SourceTaskType is NOT: the
     * body below calls fetchAllSourceTaskTypeForTenant(TenantContext.getTenantId()) for any
     * non-platform-admin caller, which returns that tenant's own rows plus shared/global ones
     * (see SourceTaskTypeRepository's own javadoc). A single un-keyed cache entry here would let
     * whichever tenant hits this endpoint first populate the shared Redis entry with ITS
     * SourceTaskType list (service names, Kafka connection profile links, queue topic/partition
     * config) for up to the 10-minute TTL (RedisConfig) -- every other tenant would be served
     * that tenant's data back instead of their own for the rest of the TTL, a cross-tenant leak.
     * Keying by tenantId (or a fixed 'platform' key for PLATFORM_ADMIN, since getTenantId() is
     * null there and unscoped by design) gives each caller class its own cache entry; every
     * write method in this class still evicts with allEntries=true, so all of them are cleared
     * together regardless of key.
     * @return ResponseDto
     * */
    @Override
    @Cacheable(value = "appSetting",
        key = "T(process.security.TenantContext).isPlatformAdmin() ? 'platform' : T(process.security.TenantContext).getTenantId()")
    public ResponseDto appSetting() throws Exception {
        Map<String, Object> appSettingDetail = new HashMap<>();
        List<LookupDataDto> lookupDataList = new ArrayList<>();
        for (LookupData lookup: this.lookupDataRepository.findByParentLookupIdIsNull()) {
            LookupDataDto lookupDataDto = new LookupDataDto();
            this.fillLookupDateDto(lookup, lookupDataDto);
            if (!isNull(lookup.getParent())) {
                LookupDataDto lookupDataDto2 = new LookupDataDto();
                this.fillLookupDateDto(lookup.getParent(), lookupDataDto2);
                lookupDataDto.setParent(lookupDataDto2);
            }
            lookupDataList.add(lookupDataDto);
        }
        appSettingDetail.put(LOOKUP_DATA, lookupDataList);
        // fetchAllSourceTaskType() returns SourceTaskTypeProjection -- a Spring Data JPA
        // interface projection, backed by a JDK dynamic proxy at runtime. That proxy serializes
        // to JSON fine but can't be deserialized back (no constructor Jackson can call), which
        // only breaks on a Redis cache HIT (a MISS never round-trips) -- mapping to a plain DTO
        // here avoids ever putting a proxy into the cached response.
        List<SourceTaskTypeProjection> sourceTaskTypeProjections = TenantContext.isPlatformAdmin()
            ? this.sourceTaskTypeRepository.fetchAllSourceTaskType()
            : this.sourceTaskTypeRepository.fetchAllSourceTaskTypeForTenant(TenantContext.getTenantId());
        List<SourceTaskTypeDto> sourceTaskTypeList = sourceTaskTypeProjections
            .stream().map(this::mapSourceTaskTypeProjectionToDto).collect(Collectors.toList());
        appSettingDetail.put(SOURCE_TASK_TYPE, sourceTaskTypeList);
        return new ResponseDto(SUCCESS, "Data fetch successfully.",appSettingDetail);
    }

    /**
     * Method use to map a SourceTaskTypeProjection (proxy-backed) to a plain SourceTaskTypeDto.
     * @param projection
     * @return SourceTaskTypeDto
     * */
    private SourceTaskTypeDto mapSourceTaskTypeProjectionToDto(SourceTaskTypeProjection projection) {
        SourceTaskTypeDto dto = new SourceTaskTypeDto();
        dto.setSourceTaskTypeId(projection.getSourceTaskTypeId());
        dto.setServiceName(projection.getServiceName());
        dto.setDescription(projection.getDescription());
        dto.setQueueTopicPartition(projection.getQueueTopicPartition());
        dto.setStatus(projection.getStatus());
        dto.setTotalTaskLink(projection.getTotalTaskLink());
        dto.setKafkaConnectionProfileId(projection.getKafkaConnectionProfileId());
        if (!isNull(projection.getKafkaConnectionProfileId())) {
            this.kafkaConnectionProfileRepository.findById(projection.getKafkaConnectionProfileId())
                .ifPresent(profile -> dto.setKafkaConnectionProfileName(profile.getProfileName()));
        }
        return dto;
    }

    /**
     * Method use to add the source task type
     * @param sourceTaskTypeDto
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto addSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception {
        if (isNull(sourceTaskTypeDto.getServiceName())) {
            return new ResponseDto(ERROR, "SourceTaskType serviceName missing.");
        } else if (isNull(sourceTaskTypeDto.getDescription())) {
            return new ResponseDto(ERROR, "SourceTaskType description missing.");
        } else if (isNull(sourceTaskTypeDto.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition missing.");
        }
        Optional<KafkaTopicPartitionUtil.Parsed> parsedTopic = KafkaTopicPartitionUtil.parse(sourceTaskTypeDto.getQueueTopicPartition());
        if (!parsedTopic.isPresent()) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition format invalid, expected topic=<name>&partitions=[<n>|*].");
        }
        if (parsedTopic.get().exceedsMaxPartitionIndex()) {
            return new ResponseDto(ERROR, String.format("Partition index must be between 0 and %d.", KafkaTopicPartitionUtil.MAX_PARTITION_INDEX));
        }
        String kafkaProfileError = this.validateKafkaProfileOwnership(sourceTaskTypeDto.getKafkaConnectionProfileId());
        if (kafkaProfileError != null) {
            return new ResponseDto(ERROR, kafkaProfileError);
        }
        SourceTaskType sourceTaskType = this.getSourceTaskType(sourceTaskTypeDto);
        this.sourceTaskTypeRepository.save(sourceTaskType);
        // Provision the topic on whichever Kafka cluster this type actually resolves to (its
        // own default profile, the caller's tenant default, or the platform-wide/env fallback)
        // -- generic and data-driven off this row instead of a fixed, hardcoded topic list.
        Long ownerTenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
        this.kafkaTemplateProvider.ensureTopicExists(
            this.kafkaConnectionResolver.resolve(ownerTenantId, sourceTaskType.getSourceTaskTypeId()),
            parsedTopic.get().getTopic(), parsedTopic.get().minimumPartitionCount());
        return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", sourceTaskType.getSourceTaskTypeId()));
    }

    /**
     * Method use to update the source task type
     * @param sourceTaskTypeDto
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto updateSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception {
        if (isNull(sourceTaskTypeDto.getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "SourceTaskType sourceTaskTypeId missing.");
        } else if (isNull(sourceTaskTypeDto.getServiceName())) {
            return new ResponseDto(ERROR, "SourceTaskType serviceName missing.");
        } else if (isNull(sourceTaskTypeDto.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition missing.");
        }
        Optional<KafkaTopicPartitionUtil.Parsed> parsedTopic = KafkaTopicPartitionUtil.parse(sourceTaskTypeDto.getQueueTopicPartition());
        if (!parsedTopic.isPresent()) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition format invalid, expected topic=<name>&partitions=[<n>|*].");
        }
        if (parsedTopic.get().exceedsMaxPartitionIndex()) {
            return new ResponseDto(ERROR, String.format("Partition index must be between 0 and %d.", KafkaTopicPartitionUtil.MAX_PARTITION_INDEX));
        }
        String kafkaProfileError = this.validateKafkaProfileOwnership(sourceTaskTypeDto.getKafkaConnectionProfileId());
        if (kafkaProfileError != null) {
            return new ResponseDto(ERROR, kafkaProfileError);
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeDto.getSourceTaskTypeId());
        if (sourceTaskType.isPresent() && !this.isSourceTaskTypeOwnedByCaller(sourceTaskType.get())) {
            return new ResponseDto(ERROR, String.format("SourceTaskType not found with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
        }
        if (sourceTaskType.isPresent()) {
            sourceTaskType.get().setServiceName(sourceTaskTypeDto.getServiceName());
            sourceTaskType.get().setDescription(sourceTaskTypeDto.getDescription());
            sourceTaskType.get().setQueueTopicPartition(sourceTaskTypeDto.getQueueTopicPartition());
            sourceTaskType.get().setKafkaConnectionProfileId(sourceTaskTypeDto.getKafkaConnectionProfileId());
            /**
             * Note :- Source Task Type Status Impact on Source Job
             * like :- if active the source job active
             * if delete then source job delete
             * if inactive then source job inactive
             * */
            // Status is optional on this DTO (a plain rename/re-point doesn't have to touch it) --
            // .getStatus().name() unconditionally below used to throw an NPE for exactly that
            // "no status in the request" case, turned into a generic 500 by GlobalExceptionHandler.
            // Only cascade to linked SourceJobs when a status change was actually requested.
            if (!isNull(sourceTaskTypeDto.getStatus())) {
                this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeDto.getSourceTaskTypeId(), sourceTaskTypeDto.getStatus().name());
                sourceTaskType.get().setStatus(sourceTaskTypeDto.getStatus());
            }
            this.sourceTaskTypeRepository.save(sourceTaskType.get());
            // Provision the (possibly changed) topic on whichever Kafka cluster this type
            // actually resolves to -- generic and data-driven off this row instead of a fixed,
            // hardcoded topic list.
            Long ownerTenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
            this.kafkaTemplateProvider.ensureTopicExists(
                this.kafkaConnectionResolver.resolve(ownerTenantId, sourceTaskTypeDto.getSourceTaskTypeId()),
                parsedTopic.get().getTopic(), parsedTopic.get().minimumPartitionCount());
            return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTaskType not found with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
    }

    /**
     * Method use to delete source task type
     * @param sourceTaskTypeId
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto deleteSourceTaskType(Long sourceTaskTypeId) throws Exception {
        if (isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "SourceTaskType sourceTaskTypeId missing.");
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeId);
        if (!sourceTaskType.isPresent() || !this.isSourceTaskTypeOwnedByCaller(sourceTaskType.get())) {
            return new ResponseDto(ERROR, String.format("SourceTaskType not found with %s.", sourceTaskTypeId));
        }
        /**
         * Note :- if the queue delete then all the link-source task delete all the source job stop and job status into delete state
         * */
        this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeId, Status.Delete.name());
        sourceTaskType.get().setStatus(Status.Delete);
        this.sourceTaskTypeRepository.save(sourceTaskType.get());
        return new ResponseDto(SUCCESS, String.format("SourceTaskType delete with %s.", sourceTaskTypeId));
    }

    /**
     * Method use to check whether the caller may update/delete a given SourceTaskType --
     * PLATFORM_ADMIN always can. A tenant admin only for a row that carries their own tenantId
     * (their own private type -- see SourceTaskType's javadoc); a null tenantId is a genuinely
     * shared/global catalog entry, PLATFORM_ADMIN-only to write, same convention as
     * SettingServiceImpl.isLookupOwnedByCaller. Without this, updateSourceTaskType/
     * deleteSourceTaskType did a bare findById with no ownership check at all -- any tenant
     * admin could rename, re-point the Kafka connection, or hard-cascade-delete (via
     * statusChangeSourceJobLinkWithSourceTaskTypeId, which stops/deletes every linked SourceJob)
     * ANY tenant's or the platform's SourceTaskType just by knowing its id.
     * @param sourceTaskType
     * @return boolean
     * */
    private boolean isSourceTaskTypeOwnedByCaller(SourceTaskType sourceTaskType) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        if (sourceTaskType.getTenantId() == null) {
            return false;
        }
        return Objects.equals(sourceTaskType.getTenantId(), TenantContext.getTenantId());
    }

    /**
     * Method use to fetch the caller's own tenant routing override for a source task type
     * @param sourceTaskTypeId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto fetchKafkaRoute(Long sourceTaskTypeId) throws Exception {
        if (isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "SourceTaskType id missing.");
        }
        if (TenantContext.isPlatformAdmin()) {
            return new ResponseDto(SUCCESS, "Platform Admin has no tenant routing override.", null);
        }
        return this.tenantTaskTypeKafkaRouteRepository
            .findByTenantIdAndSourceTaskTypeId(TenantContext.getTenantId(), sourceTaskTypeId)
            .map(route -> new ResponseDto(SUCCESS, "Route fetched.", route.getKafkaConnectionProfileId()))
            .orElseGet(() -> new ResponseDto(SUCCESS, "No routing override set -- using the type's default.", null));
    }

    /**
     * Method use to create/replace the caller's own tenant routing override for a source task
     * type -- see TenantTaskTypeKafkaRoute's own javadoc for what this is for.
     * @param sourceTaskTypeId
     * @param kafkaConnectionProfileId
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto setKafkaRoute(Long sourceTaskTypeId, Long kafkaConnectionProfileId) throws Exception {
        if (isNull(sourceTaskTypeId) || isNull(kafkaConnectionProfileId)) {
            return new ResponseDto(ERROR, "SourceTaskType id and Kafka connection profile id are both required.");
        }
        if (TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Platform Admin publishes unscoped -- tenant routing overrides don't apply.");
        }
        Long tenantId = TenantContext.getTenantId();
        String kafkaProfileError = this.validateKafkaProfileOwnership(kafkaConnectionProfileId);
        if (kafkaProfileError != null) {
            return new ResponseDto(ERROR, kafkaProfileError);
        }
        if (!this.sourceTaskTypeRepository.existsById(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, String.format("SourceTaskType not found with %d.", sourceTaskTypeId));
        }
        TenantTaskTypeKafkaRoute route = this.tenantTaskTypeKafkaRouteRepository
            .findByTenantIdAndSourceTaskTypeId(tenantId, sourceTaskTypeId)
            .orElseGet(TenantTaskTypeKafkaRoute::new);
        route.setTenantId(tenantId);
        route.setSourceTaskTypeId(sourceTaskTypeId);
        route.setKafkaConnectionProfileId(kafkaConnectionProfileId);
        this.tenantTaskTypeKafkaRouteRepository.save(route);
        return new ResponseDto(SUCCESS, "Kafka routing override saved.");
    }

    /**
     * Method use to remove the caller's own tenant routing override for a source task type
     * @param sourceTaskTypeId
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto deleteKafkaRoute(Long sourceTaskTypeId) throws Exception {
        if (isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "SourceTaskType id missing.");
        }
        if (TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Platform Admin has no tenant routing override to remove.");
        }
        this.tenantTaskTypeKafkaRouteRepository.deleteByTenantIdAndSourceTaskTypeId(TenantContext.getTenantId(), sourceTaskTypeId);
        return new ResponseDto(SUCCESS, "Kafka routing override removed -- back to the type's own default.");
    }

    /**
     * Method use to add the new lookup data
     * @param tempLookupData
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto addLookupData(LookupDataDto tempLookupData) throws Exception {
        if (isNull(tempLookupData.getLookupValue()) || tempLookupData.getLookupValue().trim().isEmpty()) {
            return new ResponseDto(ERROR, "LookupData value missing.");
        } else if (isNull(tempLookupData.getLookupType())) {
            return new ResponseDto(ERROR, "LookupData type missing.");
        }
        Optional<LookupData> parentLookupData = !isNull(tempLookupData.getParentLookupId())
            ? this.lookupDataRepository.findById(tempLookupData.getParentLookupId()) : Optional.empty();
        boolean isBucketListChild = parentLookupData.isPresent() && BUCKET_LIST.equals(parentLookupData.get().getLookupType());
        // Only a BUCKET_LIST child is an actual tenant-owned resource (a real storage bucket --
        // see StorageBrowserServiceImpl); anything else (a brand-new top-level lookup type, or a
        // new child under any other parent -- Pipeline, HomePage, AI_PROVIDER, ...) becomes
        // global/shared reference data every tenant's own config can reference, so only a
        // PLATFORM_ADMIN may create one -- same reasoning as isLookupOwnedByCaller's own javadoc
        // for why a tenant admin can't be allowed to touch these.
        if (!isBucketListChild && !TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform admin can add this kind of lookup entry -- it's shared reference data other tenants may depend on.");
        }
        boolean encrypted = Boolean.TRUE.equals(tempLookupData.getEncrypted());
        LookupData lookupData = new LookupData();
        lookupData.setLookupValue(encrypted
            ? this.encryptionUtil.encrypt(tempLookupData.getLookupValue())
            : tempLookupData.getLookupValue());
        lookupData.setEncrypted(encrypted);
        lookupData.setLookupType(tempLookupData.getLookupType());
        if (!isNull(tempLookupData.getDescription())) {
            lookupData.setDescription(tempLookupData.getDescription());
        }
        parentLookupData.ifPresent(lookupData::setParent);
        if (isBucketListChild) {
            lookupData.setTenantId(TenantContext.getTenantId());
        }
        this.lookupDataRepository.save(lookupData);
        // Refresh the internal-use lookup cache (BUCKET_LIST, EMAIL_RECEIVER, etc. -- read by
        // StorageBrowserServiceImpl/EmailMessagesFactory) -- it's only ever populated once at
        // startup otherwise, so a new/changed lookup wouldn't take effect until a restart.
        this.lookupDataCacheService.initializeCache();
        return new ResponseDto(SUCCESS, String.format("LookupData save with %d.", lookupData.getLookupId()));
    }

    /**
     * Method use to update the lookup date
     * @param tempLookupData
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto updateLookupData(LookupDataDto tempLookupData) throws Exception {
        if (isNull(tempLookupData.getLookupId())) {
            return new ResponseDto(ERROR, "LookupData id missing.");
        } else if (isNull(tempLookupData.getLookupType())) {
            return new ResponseDto(ERROR, "LookupData type missing.");
        }
        Optional<LookupData> lookupDataOpt = this.lookupDataRepository.findById(tempLookupData.getLookupId());
        if (!lookupDataOpt.isPresent() || !this.isLookupOwnedByCaller(lookupDataOpt.get())) {
            return new ResponseDto(ERROR, String.format("LookupData not found with %d.", tempLookupData.getLookupId()));
        }
        LookupData lookupData = lookupDataOpt.get();
        boolean wasEncrypted = Boolean.TRUE.equals(lookupData.getEncrypted());
        boolean nowEncrypted = !isNull(tempLookupData.getEncrypted()) ? tempLookupData.getEncrypted() : wasEncrypted;
        boolean hasNewValue = !isNull(tempLookupData.getLookupValue()) && !tempLookupData.getLookupValue().trim().isEmpty();
        /**
         * Note :- lookupValue is only required when there's no existing encrypted value to fall
         * back to. An already-encrypted lookup can be saved with lookupValue left blank in the
         * UI (masked, never sent back as plaintext) to keep its current value unchanged.
         * */
        if (!wasEncrypted && !hasNewValue) {
            return new ResponseDto(ERROR, "LookupData value missing.");
        }
        if (hasNewValue) {
            lookupData.setLookupValue(nowEncrypted
                ? this.encryptionUtil.encrypt(tempLookupData.getLookupValue())
                : tempLookupData.getLookupValue());
        } else if (wasEncrypted && !nowEncrypted) {
            // Turning encryption off without a new value -- decrypt the existing value and store it as plain text.
            lookupData.setLookupValue(this.encryptionUtil.decrypt(lookupData.getLookupValue()));
        }
        // else: no new value and still encrypted -- keep the existing ciphertext untouched.
        lookupData.setEncrypted(nowEncrypted);
        lookupData.setLookupType(tempLookupData.getLookupType());
        if (!isNull(tempLookupData.getDescription())) {
            lookupData.setDescription(tempLookupData.getDescription());
        }
        if (!isNull(tempLookupData.getParentLookupId())) {
            Optional<LookupData> parentLookupData = this.lookupDataRepository.findById(tempLookupData.getParentLookupId());
            parentLookupData.ifPresent(lookupData::setParent);
        }
        this.lookupDataRepository.save(lookupData);
        this.lookupDataCacheService.initializeCache();
        return new ResponseDto(SUCCESS, String.format("LookupData update with %d.", tempLookupData.getLookupId()));
    }

    /**
     * Method use to fetch teh sub lookup by parent id
     * @param parentLookUpId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto fetchSubLookupByParentId(Long parentLookUpId) throws Exception {
        if (isNull(parentLookUpId)) {
            return new ResponseDto(ERROR, "LookupData id missing.");
        }
        Map<String, Object> appSettingDetail = new HashMap<>();
        List<LookupDataDto> lookupDataList = new ArrayList<>();
        Optional<LookupData> parentLookup = this.lookupDataRepository.findById(parentLookUpId);
        if (parentLookup.isPresent()) {
            LookupDataDto lookupDataDto = new LookupDataDto();
            this.fillLookupDateDto(parentLookup.get(), lookupDataDto);
            appSettingDetail.put(PARENT_LOOKUP_DATA, lookupDataDto);
            // Every other lookup group is global/shared reference data (see LookupData's
            // javadoc) -- BUCKET_LIST's children are the one group that's a real tenant-owned
            // resource, so this is the one place a non-admin's view needs filtering. Without
            // this, any tenant admin opening Settings -> Lookup -> Bucket List saw every other
            // tenant's real bucket names/values, even though the Object Browser itself
            // (StorageBrowserServiceImpl) was already correctly scoped.
            boolean isBucketList = BUCKET_LIST.equals(parentLookup.get().getLookupType());
            boolean isPlatformAdmin = TenantContext.isPlatformAdmin();
            Long callerTenantId = TenantContext.getTenantId();
            if (!isNull(parentLookup.get().getChildren())) {
                for (LookupData lookup: parentLookup.get().getChildren()) {
                    if (isBucketList && !isPlatformAdmin && !Objects.equals(lookup.getTenantId(), callerTenantId)) {
                        continue;
                    }
                    LookupDataDto lookupDataDto2 = new LookupDataDto();
                    this.fillLookupDateDto(lookup, lookupDataDto2);
                    lookupDataList.add(lookupDataDto2);
                }
            }
            appSettingDetail.put(LOOKUP_DATA, lookupDataList);
            return new ResponseDto(SUCCESS, "Data fetch successfully.", appSettingDetail);
        }
        return new ResponseDto(ERROR, String.format("LookupData not found with %d.", parentLookUpId));
    }

    /**
     * Method use to delete the lookup data
     * @param tempLookupData
     * @return ResponseDto
     * */
    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto deleteLookupData(LookupDataDto tempLookupData) throws Exception {
        if (isNull(tempLookupData.getLookupId())) {
            return new ResponseDto(ERROR, "LookupData id missing.");
        }
        Optional<LookupData> lookupDataOpt = this.lookupDataRepository.findById(tempLookupData.getLookupId());
        if (!lookupDataOpt.isPresent() || !this.isLookupOwnedByCaller(lookupDataOpt.get())) {
            return new ResponseDto(ERROR, String.format("LookupData not found with %d.", tempLookupData.getLookupId()));
        }
        this.lookupDataRepository.deleteById(tempLookupData.getLookupId());
        this.lookupDataCacheService.initializeCache();
        return new ResponseDto(SUCCESS, String.format("LookupData delete with %d.", tempLookupData.getLookupId()));
    }

    /**
     * Method use to check whether the caller may edit/delete a given LookupData row --
     * PLATFORM_ADMIN always can. A tenant user only for a row that carries their own tenantId
     * (currently just their own BUCKET_LIST children -- see LookupData's javadoc); a null
     * tenantId means global/shared reference data (Pipeline, HomePage, EMAIL_RECEIVER,
     * AI_PROVIDER, TASK_GROUPS, ...) that every tenant's own Source Task/job configuration can
     * reference -- ANY tenant admin being able to edit or hard-delete those (as opposed to just
     * reading them, which every tenant still needs for its own dropdowns) let one tenant destroy
     * shared config another tenant's real jobs depend on, with no ownership boundary at all.
     * Confirmed live: a brand-new tenant's own admin account deleted an existing PIPELINE_IDS
     * entry ("F768924", a real pipeline task definition) that wasn't theirs to touch. Only
     * PLATFORM_ADMIN may now write to a global (null-tenantId) row; reads are unaffected
     * (appSetting/fetchSubLookupByParentId never called this method).
     * @param lookupData
     * @return boolean
     * */
    private boolean isLookupOwnedByCaller(LookupData lookupData) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        if (lookupData.getTenantId() == null) {
            return false;
        }
        return Objects.equals(lookupData.getTenantId(), TenantContext.getTenantId());
    }

    /**
     * Method use to fill the lookup data dto
     * @param lookupData
     * @param lookupDataDto
     * */
    private void fillLookupDateDto(LookupData lookupData, LookupDataDto lookupDataDto) {
        lookupDataDto.setLookupId(lookupData.getLookupId());
        lookupDataDto.setEncrypted(lookupData.getEncrypted());
        // Never send an encrypted value's plaintext (or ciphertext) back to the UI over the API.
        lookupDataDto.setLookupValue(Boolean.TRUE.equals(lookupData.getEncrypted())
            ? MASKED_LOOKUP_VALUE
            : lookupData.getLookupValue());
        lookupDataDto.setLookupType(lookupData.getLookupType());
        lookupDataDto.setDescription(lookupData.getDescription());
        lookupDataDto.setDateCreated(lookupData.getDateCreated());
        lookupDataDto.setTenantId(lookupData.getTenantId());
    }

    /**
     * Method use to get teh source task type
     * @param sourceTaskTypeDto
     * @return SourceTaskType
     * */
    private SourceTaskType getSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) {
        SourceTaskType sourceTaskType = new SourceTaskType();
        // A TENANT_ADMIN-created type is private to their tenant (tenant_id set) so it doesn't
        // silently become visible to every other tenant's Task List/Settings screen -- only
        // PLATFORM_ADMIN can add a genuinely shared/global catalog entry (tenant_id null), same
        // "null = unscoped, only PLATFORM_ADMIN reaches it" convention used everywhere else in
        // this app (AppUser.tenantId, LookupData.tenantId, ...).
        sourceTaskType.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
        sourceTaskType.setServiceName(sourceTaskTypeDto.getServiceName());
        sourceTaskType.setDescription(sourceTaskTypeDto.getDescription());
        sourceTaskType.setQueueTopicPartition(sourceTaskTypeDto.getQueueTopicPartition());
        sourceTaskType.setKafkaConnectionProfileId(sourceTaskTypeDto.getKafkaConnectionProfileId());
        sourceTaskType.setStatus(Status.Active);
        return sourceTaskType;
    }

    /**
     * Method use to validate that a SourceTaskType's chosen default Kafka profile is actually
     * usable by the caller -- either platform-wide (tenantId null) or owned by the caller's own
     * tenant. Without this, a TENANT_ADMIN could point their task type's publishing at another
     * tenant's Kafka cluster (and its credentials) just by naming that profile's id.
     * @param kafkaConnectionProfileId
     * @return String error message, or null if valid/not set
     * */
    private String validateKafkaProfileOwnership(Long kafkaConnectionProfileId) {
        if (isNull(kafkaConnectionProfileId)) {
            return null;
        }
        if (TenantContext.isPlatformAdmin()) {
            return null;
        }
        // NOT ".map(profile -> (String) null).orElse(...)" -- Optional.map() treats a
        // null-returning mapper as "no value" (it wraps the result in Optional.ofNullable
        // internally), so that always produced an empty Optional and this always returned the
        // error, regardless of whether the profile was actually found/owned.
        boolean visible = this.kafkaConnectionProfileRepository.findById(kafkaConnectionProfileId)
            .filter(profile -> isNull(profile.getTenantId()) || profile.getTenantId().equals(TenantContext.getTenantId()))
            .isPresent();
        return visible ? null : "Kafka connection profile not found.";
    }

}
