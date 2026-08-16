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
import process.model.pojo.KafkaConnectionProfile;
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

@Service
public class SettingServiceImpl implements SettingService {

    private Logger logger = LoggerFactory.getLogger(SettingServiceImpl.class);

    private final String PARENT_LOOKUP_DATA = "parentLookupData";
    private final String LOOKUP_DATA = "lookupDatas";
    private final String SOURCE_TASK_TYPE = "sourceTaskTypes";

    private final String BUCKET_LIST = "BUCKET_LIST";

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

    @Override
    public ResponseDto dynamicQueryResponse(ItemResponse itemResponse) {

        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform admin can run a dynamic query.");
        }
        if (isNull(itemResponse.getQuery())) {
            return new ResponseDto(ERROR, "Query missing.");
        }
        return new ResponseDto(SUCCESS, "Data fetch successfully.", this.queryService.executeQueryResponse(itemResponse.getQuery()));
    }

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

        List<SourceTaskTypeProjection> sourceTaskTypeProjections = TenantContext.isPlatformAdmin()
            ? this.sourceTaskTypeRepository.fetchAllSourceTaskType()
            : this.sourceTaskTypeRepository.fetchAllSourceTaskTypeForTenant(TenantContext.getTenantId());
        List<Long> profileIds = sourceTaskTypeProjections.stream()
            .map(SourceTaskTypeProjection::getKafkaConnectionProfileId)
            .filter(id -> !isNull(id))
            .distinct()
            .collect(Collectors.toList());
        Map<Long, String> profileNameById = profileIds.isEmpty() ? Collections.emptyMap()
            : this.kafkaConnectionProfileRepository.findAllById(profileIds).stream()
                .collect(Collectors.toMap(KafkaConnectionProfile::getKafkaConnectionProfileId, KafkaConnectionProfile::getProfileName));
        List<SourceTaskTypeDto> sourceTaskTypeList = sourceTaskTypeProjections
            .stream().map(projection -> this.mapSourceTaskTypeProjectionToDto(projection, profileNameById)).collect(Collectors.toList());
        appSettingDetail.put(SOURCE_TASK_TYPE, sourceTaskTypeList);
        return new ResponseDto(SUCCESS, "Data fetch successfully.",appSettingDetail);
    }

    private SourceTaskTypeDto mapSourceTaskTypeProjectionToDto(SourceTaskTypeProjection projection, Map<Long, String> profileNameById) {
        SourceTaskTypeDto dto = new SourceTaskTypeDto();
        dto.setSourceTaskTypeId(projection.getSourceTaskTypeId());
        dto.setServiceName(projection.getServiceName());
        dto.setDescription(projection.getDescription());
        dto.setQueueTopicPartition(projection.getQueueTopicPartition());
        dto.setStatus(projection.getStatus());
        dto.setTotalTaskLink(projection.getTotalTaskLink());
        dto.setKafkaConnectionProfileId(projection.getKafkaConnectionProfileId());
        if (!isNull(projection.getKafkaConnectionProfileId())) {
            dto.setKafkaConnectionProfileName(profileNameById.get(projection.getKafkaConnectionProfileId()));
        }
        return dto;
    }

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

        Long ownerTenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
        this.kafkaTemplateProvider.ensureTopicExists(
            this.kafkaConnectionResolver.resolve(ownerTenantId, sourceTaskType.getSourceTaskTypeId()),
            parsedTopic.get().getTopic(), parsedTopic.get().minimumPartitionCount());
        return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", sourceTaskType.getSourceTaskTypeId()));
    }

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

            if (!isNull(sourceTaskTypeDto.getStatus())) {
                this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeDto.getSourceTaskTypeId(), sourceTaskTypeDto.getStatus().name());
                sourceTaskType.get().setStatus(sourceTaskTypeDto.getStatus());
            }
            this.sourceTaskTypeRepository.save(sourceTaskType.get());

            Long ownerTenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
            this.kafkaTemplateProvider.ensureTopicExists(
                this.kafkaConnectionResolver.resolve(ownerTenantId, sourceTaskTypeDto.getSourceTaskTypeId()),
                parsedTopic.get().getTopic(), parsedTopic.get().minimumPartitionCount());
            return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTaskType not found with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
    }

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

        this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeId, Status.Delete.name());
        sourceTaskType.get().setStatus(Status.Delete);
        this.sourceTaskTypeRepository.save(sourceTaskType.get());
        return new ResponseDto(SUCCESS, String.format("SourceTaskType delete with %s.", sourceTaskTypeId));
    }

    private boolean isSourceTaskTypeOwnedByCaller(SourceTaskType sourceTaskType) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        if (sourceTaskType.getTenantId() == null) {
            return false;
        }
        return Objects.equals(sourceTaskType.getTenantId(), TenantContext.getTenantId());
    }

    private boolean isSourceTaskTypeVisibleToCaller(SourceTaskType sourceTaskType) {
        if (TenantContext.isPlatformAdmin() || sourceTaskType.getTenantId() == null) {
            return true;
        }
        return Objects.equals(sourceTaskType.getTenantId(), TenantContext.getTenantId());
    }

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
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeId);
        if (!sourceTaskType.isPresent() || !this.isSourceTaskTypeVisibleToCaller(sourceTaskType.get())) {
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

        this.lookupDataCacheService.initializeCache();
        return new ResponseDto(SUCCESS, String.format("LookupData save with %d.", lookupData.getLookupId()));
    }

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

        if (!wasEncrypted && !hasNewValue) {
            return new ResponseDto(ERROR, "LookupData value missing.");
        }
        if (hasNewValue) {
            lookupData.setLookupValue(nowEncrypted
                ? this.encryptionUtil.encrypt(tempLookupData.getLookupValue())
                : tempLookupData.getLookupValue());
        } else if (wasEncrypted && !nowEncrypted) {

            lookupData.setLookupValue(this.encryptionUtil.decrypt(lookupData.getLookupValue()));
        }

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

    private boolean isLookupOwnedByCaller(LookupData lookupData) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        if (lookupData.getTenantId() == null) {
            return false;
        }
        return Objects.equals(lookupData.getTenantId(), TenantContext.getTenantId());
    }

    private void fillLookupDateDto(LookupData lookupData, LookupDataDto lookupDataDto) {
        lookupDataDto.setLookupId(lookupData.getLookupId());
        lookupDataDto.setEncrypted(lookupData.getEncrypted());

        lookupDataDto.setLookupValue(Boolean.TRUE.equals(lookupData.getEncrypted())
            ? MASKED_LOOKUP_VALUE
            : lookupData.getLookupValue());
        lookupDataDto.setLookupType(lookupData.getLookupType());
        lookupDataDto.setDescription(lookupData.getDescription());
        lookupDataDto.setDateCreated(lookupData.getDateCreated());
        lookupDataDto.setTenantId(lookupData.getTenantId());
    }

    private SourceTaskType getSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) {
        SourceTaskType sourceTaskType = new SourceTaskType();

        sourceTaskType.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
        sourceTaskType.setServiceName(sourceTaskTypeDto.getServiceName());
        sourceTaskType.setDescription(sourceTaskTypeDto.getDescription());
        sourceTaskType.setQueueTopicPartition(sourceTaskTypeDto.getQueueTopicPartition());
        sourceTaskType.setKafkaConnectionProfileId(sourceTaskTypeDto.getKafkaConnectionProfileId());
        sourceTaskType.setStatus(Status.Active);
        return sourceTaskType;
    }

    private String validateKafkaProfileOwnership(Long kafkaConnectionProfileId) {
        if (isNull(kafkaConnectionProfileId)) {
            return null;
        }
        if (TenantContext.isPlatformAdmin()) {
            return null;
        }

        boolean visible = this.kafkaConnectionProfileRepository.findById(kafkaConnectionProfileId)
            .filter(profile -> isNull(profile.getTenantId()) || profile.getTenantId().equals(TenantContext.getTenantId()))
            .isPresent();
        return visible ? null : "Kafka connection profile not found.";
    }

}
