package process.model.service.impl;

import org.slf4j.Logger;
import process.util.UserNameResolver;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.projection.SourceTaskTypeProjection;
import process.model.projection.TopicOptionProjection;
import process.model.pojo.TenantTaskTypeKafkaRoute;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.identity.IdentityPort;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.SettingService;
import org.barco.platform.tenancy.TenantScope;
import process.security.TenantContext;
import process.util.KafkaTopicPartitionUtil;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import process.model.pojo.Pipeline;
import process.model.repository.PipelineRepository;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class SettingServiceImpl implements SettingService {

    private Logger logger = LoggerFactory.getLogger(SettingServiceImpl.class);

    private final String SOURCE_TASK_TYPE = "sourceTaskTypes";

    private final SourceJobRepository sourceJobRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final KafkaConnectionProfileRepository kafkaConnectionProfileRepository;
    private final TenantTaskTypeKafkaRouteRepository tenantTaskTypeKafkaRouteRepository;
    private final IdentityPort identity;

    /**
     * Field-injected and optional: the guard on deleting a topic is the only thing here that
     * reads pipelines, and the constructor is built by hand in several tests.
     */
    @Autowired(required = false)
    private PipelineRepository pipelineRepository;

    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;

    private final UserNameResolver userNameResolver;


    public SettingServiceImpl(SourceJobRepository sourceJobRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        KafkaConnectionProfileRepository kafkaConnectionProfileRepository,
        TenantTaskTypeKafkaRouteRepository tenantTaskTypeKafkaRouteRepository,
        IdentityPort identity,
        KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver,
        UserNameResolver userNameResolver) {
        this.userNameResolver = userNameResolver;
        this.identity = identity;
        this.sourceJobRepository = sourceJobRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.kafkaConnectionProfileRepository = kafkaConnectionProfileRepository;
        this.tenantTaskTypeKafkaRouteRepository = tenantTaskTypeKafkaRouteRepository;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
    }

    @Override
    @Cacheable(value = "appSetting",
        key = "T(process.security.TenantContext).isPlatformAdmin() ? 'platform' : T(process.security.TenantContext).getTenantId()")
    public ResponseDto appSetting() throws Exception {
        Map<String, Object> appSettingDetail = new HashMap<>();

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
        // Projections carry only the columns the query names, so the audit ids are fetched
        // separately by id rather than widening the projection interface.
        this.userNameResolver.attachToDtos(sourceTaskTypeList, this.sourceTaskTypeRepository,
            SourceTaskType::getSourceTaskTypeId);
        appSettingDetail.put(SOURCE_TASK_TYPE, sourceTaskTypeList);
        return new ResponseDto(SUCCESS, "Data fetch successfully.",appSettingDetail);
    }

    /**
     * Topics as picker rows (id, name, Kafka topic, state, profile, workspace) and nothing more;
     * see {@link TopicOptionProjection}. Three ways in, each scoped to what the caller may see:
     * {@code ids} resolves the rows a box was handed (an edited task's topic, a ?topic= link);
     * {@code kafkaConnectionProfileId} lists one profile's topics for a profile-first pick; and
     * otherwise {@code q} searches by name or Kafka topic, capped at {@code limit}, so a box
     * over ten thousand topics fetches fifty rather than all of them.
     */
    public ResponseDto topics(String q, Integer limit, List<Long> ids, Long kafkaConnectionProfileId) throws Exception {
        boolean admin = TenantContext.isPlatformAdmin();
        Long mine = TenantContext.getTenantId();
        if (!admin && mine == null) {
            return new ResponseDto(SUCCESS, "0 topic(s).", Collections.emptyList());
        }
        List<TopicOptionProjection> topics;
        if (ids != null && !ids.isEmpty()) {
            topics = this.sourceTaskTypeRepository.fetchTopicOptionsByIds(ids).stream()
                .filter(t -> admin || mine.equals(t.getTenantId()))
                .collect(Collectors.toList());
        } else if (kafkaConnectionProfileId != null) {
            Optional<KafkaConnectionProfile> profile = this.kafkaConnectionProfileRepository.findById(kafkaConnectionProfileId)
                .filter(p -> p.getStatus() != Status.Delete)
                .filter(p -> admin || (p.getTenantId() != null && p.getTenantId().equals(mine)));
            if (!profile.isPresent()) {
                return new ResponseDto(ERROR, String.format("Profile not found with %d.", kafkaConnectionProfileId));
            }
            boolean isDefault = Boolean.TRUE.equals(profile.get().getIsDefault()) && profile.get().getTenantId() != null;
            topics = this.sourceTaskTypeRepository.fetchTopicOptionsForProfile(kafkaConnectionProfileId, isDefault,
                profile.get().getTenantId() == null ? 0L : profile.get().getTenantId());
        } else {
            String term = isNull(q) || q.trim().isEmpty() ? "" : "%" + q.trim().toLowerCase() + "%";
            int cap = limit == null || limit < 1 ? 50 : Math.min(limit, 500);
            // MIG-93: "every tenant" is the platform admin's grant, not a tenant id of 0.
            TenantScope scope = TenantContext.scope();
            topics = this.sourceTaskTypeRepository.searchTopicOptions(scope.isAllTenants(),
                scope.isAllTenants() ? TenantScope.NO_TENANT_MATCHES : ((TenantScope.Scoped) scope).tenantId(), term,
                PageRequest.of(0, cap));
        }
        return new ResponseDto(SUCCESS, String.format("%d topic(s).", topics.size()), topics);
    }

    /**
     * The topics of one Kafka profile, each with its pipelines. The profile has to be one the
     * caller can see -- their own workspace's or, for a platform admin, any -- so a tenant
     * cannot read another workspace's topic names by guessing a profile id.
     */
    public ResponseDto topicsForProfile(Long kafkaConnectionProfileId) throws Exception {
        if (isNull(kafkaConnectionProfileId)) {
            return new ResponseDto(ERROR, "kafkaConnectionProfileId missing.");
        }
        Optional<KafkaConnectionProfile> profileOpt = this.kafkaConnectionProfileRepository.findById(kafkaConnectionProfileId)
            .filter(p -> p.getStatus() != Status.Delete)
            .filter(p -> TenantContext.isPlatformAdmin()
                || (p.getTenantId() != null && p.getTenantId().equals(TenantContext.getTenantId())));
        if (!profileOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Profile not found with %d.", kafkaConnectionProfileId));
        }
        KafkaConnectionProfile profile = profileOpt.get();
        boolean isDefault = Boolean.TRUE.equals(profile.getIsDefault());
        List<SourceTaskTypeProjection> projections = this.sourceTaskTypeRepository.fetchTopicsForProfile(
            kafkaConnectionProfileId, isDefault && profile.getTenantId() != null, profile.getTenantId());
        Map<Long, String> profileNameById = Collections.singletonMap(kafkaConnectionProfileId, profile.getProfileName());
        List<SourceTaskTypeDto> topics = projections.stream()
            .map(projection -> this.mapSourceTaskTypeProjectionToDto(projection, profileNameById))
            .collect(Collectors.toList());
        // Every topic's pipelines in one query, then dealt out.
        List<Long> ids = topics.stream().map(SourceTaskTypeDto::getSourceTaskTypeId).collect(Collectors.toList());
        Map<Long, List<SourceTaskTypeDto.PipelineSummary>> byTopic = new HashMap<>();
        if (!ids.isEmpty() && this.pipelineRepository != null) {
            for (Pipeline p : this.pipelineRepository.findAllBySourceTaskTypeIdInAndStatusNotOrderByPipelineNameAsc(ids, Status.Delete)) {
                byTopic.computeIfAbsent(p.getSourceTaskTypeId(), k -> new ArrayList<>()).add(
                    new SourceTaskTypeDto.PipelineSummary(p.getPipelineKey(), p.getPipelineId(), p.getPipelineName(),
                        p.getStatus() == null ? null : p.getStatus().name(), p.getFields() == null ? 0 : p.getFields().size()));
            }
        }
        topics.forEach(t -> t.setPipelines(byTopic.getOrDefault(t.getSourceTaskTypeId(), Collections.emptyList())));
        return new ResponseDto(SUCCESS, String.format("%d topic(s).", topics.size()), topics);
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
            return new ResponseDto(ERROR, "Topic name missing.");
        } else if (isNull(sourceTaskTypeDto.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "Kafka topic missing.");
        }
        Optional<KafkaTopicPartitionUtil.Parsed> parsedTopic = KafkaTopicPartitionUtil.parse(sourceTaskTypeDto.getQueueTopicPartition());
        if (!parsedTopic.isPresent()) {
            return new ResponseDto(ERROR, "Kafka topic format invalid, expected topic=<name>&partitions=[<n>|*].");
        }
        if (parsedTopic.get().exceedsMaxPartitionIndex()) {
            return new ResponseDto(ERROR, String.format("Partition index must be between 0 and %d.", KafkaTopicPartitionUtil.MAX_PARTITION_INDEX));
        }
        String kafkaProfileError = this.validateKafkaProfileOwnership(sourceTaskTypeDto.getKafkaConnectionProfileId());
        if (kafkaProfileError != null) {
            return new ResponseDto(ERROR, kafkaProfileError);
        }
        String ownerError = this.validateTaskTypeOwner(sourceTaskTypeDto);
        if (ownerError != null) {
            return new ResponseDto(ERROR, ownerError);
        }
        SourceTaskType sourceTaskType = this.getSourceTaskType(sourceTaskTypeDto);
        this.sourceTaskTypeRepository.save(sourceTaskType);

        Long ownerTenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
        this.kafkaTemplateProvider.ensureTopicExists(
            this.kafkaConnectionResolver.resolve(ownerTenantId, sourceTaskType.getSourceTaskTypeId()),
            parsedTopic.get().getTopic(), parsedTopic.get().minimumPartitionCount());
        return new ResponseDto(SUCCESS, String.format("Topic saved with %s.", sourceTaskType.getSourceTaskTypeId()));
    }

    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto updateSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception {
        if (isNull(sourceTaskTypeDto.getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "Topic id missing.");
        } else if (isNull(sourceTaskTypeDto.getServiceName())) {
            return new ResponseDto(ERROR, "Topic name missing.");
        } else if (isNull(sourceTaskTypeDto.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "Kafka topic missing.");
        }
        Optional<KafkaTopicPartitionUtil.Parsed> parsedTopic = KafkaTopicPartitionUtil.parse(sourceTaskTypeDto.getQueueTopicPartition());
        if (!parsedTopic.isPresent()) {
            return new ResponseDto(ERROR, "Kafka topic format invalid, expected topic=<name>&partitions=[<n>|*].");
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
            return new ResponseDto(ERROR, String.format("Topic not found with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
        }
        if (sourceTaskType.isPresent()) {
            sourceTaskType.get().setServiceName(sourceTaskTypeDto.getServiceName());
            sourceTaskType.get().setDescription(sourceTaskTypeDto.getDescription());
            sourceTaskType.get().setQueueTopicPartition(sourceTaskTypeDto.getQueueTopicPartition());
            // A body that says nothing about the Kafka profile keeps the one there is; it used
            // to clear the binding, so an edit that touched only the name silently unrouted the topic.
            if (!isNull(sourceTaskTypeDto.getKafkaConnectionProfileId())) {
                sourceTaskType.get().setKafkaConnectionProfileId(sourceTaskTypeDto.getKafkaConnectionProfileId());
            }

            // The jobs follow the topic's status only when that status CHANGES. The cascade used
            // to run on every save that carried a status -- the dialog always does -- so editing
            // a description re-activated every job somebody had deliberately switched off.
            if (!isNull(sourceTaskTypeDto.getStatus()) && sourceTaskTypeDto.getStatus() != sourceTaskType.get().getStatus()) {
                this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeDto.getSourceTaskTypeId(), sourceTaskTypeDto.getStatus().name());
                sourceTaskType.get().setStatus(sourceTaskTypeDto.getStatus());
            }
            this.sourceTaskTypeRepository.save(sourceTaskType.get());

            Long ownerTenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
            this.kafkaTemplateProvider.ensureTopicExists(
                this.kafkaConnectionResolver.resolve(ownerTenantId, sourceTaskTypeDto.getSourceTaskTypeId()),
                parsedTopic.get().getTopic(), parsedTopic.get().minimumPartitionCount());
            return new ResponseDto(SUCCESS, String.format("Topic saved with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
        }
        return new ResponseDto(ERROR, String.format("Topic not found with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
    }

    @Override
    @CacheEvict(value = "appSetting", allEntries = true)
    public ResponseDto deleteSourceTaskType(Long sourceTaskTypeId) throws Exception {
        if (isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "Topic id missing.");
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeId);
        if (!sourceTaskType.isPresent() || !this.isSourceTaskTypeOwnedByCaller(sourceTaskType.get())) {
            return new ResponseDto(ERROR, String.format("Topic not found with %s.", sourceTaskTypeId));
        }

        // A topic that pipelines still publish on cannot go: the pipelines would have nowhere
        // to publish and every task on them would be dispatched into nothing.
        long pipelines = this.pipelineRepository == null ? 0
            : this.pipelineRepository.countBySourceTaskTypeIdAndStatusNot(sourceTaskTypeId, Status.Delete);
        if (pipelines > 0) {
            return new ResponseDto(ERROR, String.format(
                "%d pipeline%s still publish%s on this topic. Move or delete %s first.",
                pipelines, pipelines == 1 ? "" : "s", pipelines == 1 ? "es" : "", pipelines == 1 ? "it" : "them"));
        }
        this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeId, Status.Delete.name());
        sourceTaskType.get().setStatus(Status.Delete);
        this.sourceTaskTypeRepository.save(sourceTaskType.get());
        return new ResponseDto(SUCCESS, String.format("Topic deleted with %s.", sourceTaskTypeId));
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

    /**
     * Whether the caller may SEE this task type, as opposed to edit it.
     *
     * A null owner used to answer true here -- it meant "the platform's, shared with every
     * workspace", and this is the single-row half of the rule the list query carried. V39 removed
     * that meaning: every task type has exactly one owner, so a null is now a row that should not
     * exist rather than a row everybody may read, and it is refused like anyone else's.
     */
    private boolean isSourceTaskTypeVisibleToCaller(SourceTaskType sourceTaskType) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return sourceTaskType.getTenantId() != null
            && Objects.equals(sourceTaskType.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    public ResponseDto fetchKafkaRoute(Long sourceTaskTypeId) throws Exception {
        if (isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "Topic id missing.");
        }
        if (TenantContext.isPlatformAdmin()) {
            return new ResponseDto(SUCCESS, "A platform administrator has no tenant routing override.", null);
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
            return new ResponseDto(ERROR, "Topic id and Kafka connection profile id are both required.");
        }
        if (TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "A platform administrator publishes unscoped -- tenant routing overrides don't apply.");
        }
        Long tenantId = TenantContext.getTenantId();
        String kafkaProfileError = this.validateKafkaProfileOwnership(kafkaConnectionProfileId);
        if (kafkaProfileError != null) {
            return new ResponseDto(ERROR, kafkaProfileError);
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeId);
        if (!sourceTaskType.isPresent() || !this.isSourceTaskTypeVisibleToCaller(sourceTaskType.get())) {
            return new ResponseDto(ERROR, String.format("Topic not found with %d.", sourceTaskTypeId));
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
            return new ResponseDto(ERROR, "Topic id missing.");
        }
        if (TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "A platform administrator has no tenant routing override to remove.");
        }
        this.tenantTaskTypeKafkaRouteRepository.deleteByTenantIdAndSourceTaskTypeId(TenantContext.getTenantId(), sourceTaskTypeId);
        return new ResponseDto(SUCCESS, "Kafka routing override removed -- back to the type's own default.");
    }

    private SourceTaskType getSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) {
        SourceTaskType sourceTaskType = new SourceTaskType();

        // A platform admin acts for a workspace and has to name it; everybody else can only
        // create for their own. This read `isPlatformAdmin() ? null : getTenantId()`, and that
        // null was the whole leak: it did not mean "no owner", it meant "every workspace", so a
        // task type built for one agency appeared on all of their screens. Validated in
        // addSourceTaskType, which is where a missing id can still be reported as a sentence.
        sourceTaskType.setTenantId(TenantContext.isPlatformAdmin()
            ? sourceTaskTypeDto.getTenantId() : TenantContext.getTenantId());
        sourceTaskType.setServiceName(sourceTaskTypeDto.getServiceName());
        sourceTaskType.setDescription(sourceTaskTypeDto.getDescription());
        sourceTaskType.setQueueTopicPartition(sourceTaskTypeDto.getQueueTopicPartition());
        sourceTaskType.setKafkaConnectionProfileId(sourceTaskTypeDto.getKafkaConnectionProfileId());
        sourceTaskType.setStatus(Status.Active);
        return sourceTaskType;
    }

    /**
     * The workspace a new task type will belong to, or a sentence saying why it cannot be decided.
     *
     * Only a platform admin can get this wrong: everybody else's owner comes from the thread and
     * cannot be supplied by the caller. Named here rather than left to the NOT NULL constraint,
     * which would surface as "Some internal error occurred contact with support." -- the trap
     * priority, execution and the retry policy have each been fixed for.
     */
    private String validateTaskTypeOwner(SourceTaskTypeDto sourceTaskTypeDto) {
        if (!TenantContext.isPlatformAdmin()) {
            return null;
        }
        Long tenantId = sourceTaskTypeDto.getTenantId();
        if (isNull(tenantId)) {
            return "Topic workspace missing -- a platform administrator must say which workspace "
                + "this task type belongs to.";
        }
        if (!IdentityPort.live(this.identity, tenantId).isPresent()) {
            return String.format("Topic workspace %d is not a workspace.", tenantId);
        }
        return null;
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
