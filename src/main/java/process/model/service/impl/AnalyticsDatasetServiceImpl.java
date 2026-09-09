package process.model.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.analytics.AnalyticsException;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsDataset;
import process.model.repository.AnalyticsDatasetRepository;
import process.model.service.AnalyticsDatasetService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TenantOwnership;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class AnalyticsDatasetServiceImpl implements AnalyticsDatasetService {

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_ALIAS_LENGTH = 255;

    @PersistenceContext
    private EntityManager entityManager;

    private final AnalyticsDatasetRepository analyticsDatasetRepository;
    private final TenantFilterHelper tenantFilterHelper;
    private final UserNameResolver userNameResolver;

    /**
     * The resolver, used for its verdict and not for its location.
     *
     * This is the module's only service outside process.analytics that holds one, so it is worth
     * saying what it is for. Registration asks the resolver one question -- may this caller read
     * this alias and this path, and what format is it -- and keeps the answer's format and
     * cleaned path. It never calls url() or scanExpression(), stores nothing the DatasetRef
     * resolved, and opens no session: the rule that every analytics query goes through
     * AnalyticsQueryService's governed path is untouched, because nothing here runs a query.
     *
     * The alternative was to derive the format from the path here, which would have made a third
     * copy of the extension list in a module whose design record already names the two existing
     * copies as a known problem. This way the label a listing shows is by construction the format
     * the reader will actually choose.
     */
    private final DatasetResolver datasetResolver;

    public AnalyticsDatasetServiceImpl(AnalyticsDatasetRepository analyticsDatasetRepository,
        TenantFilterHelper tenantFilterHelper, UserNameResolver userNameResolver,
        DatasetResolver datasetResolver) {
        this.analyticsDatasetRepository = analyticsDatasetRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.userNameResolver = userNameResolver;
        this.datasetResolver = datasetResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAllDatasets() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<AnalyticsDataset> datasets = ownedByCaller(
            this.analyticsDatasetRepository.findAllByOrderByAnalyticsDatasetIdDesc());
        this.userNameResolver.attachNames(datasets);
        return new ResponseDto(SUCCESS, "Data fetched successfully.", datasets);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchDatasetById(Long analyticsDatasetId) throws Exception {
        if (isNull(analyticsDatasetId)) {
            return new ResponseDto(ERROR, "AnalyticsDataset analyticsDatasetId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsDataset> dataset = this.scopedFind(analyticsDatasetId);
        if (!dataset.isPresent()) {
            return this.notFound(analyticsDatasetId);
        }
        this.userNameResolver.attachNames(Arrays.asList(dataset.get()));
        return new ResponseDto(SUCCESS, "Data fetched successfully.", dataset.get());
    }

    @Override
    @Transactional
    public ResponseDto registerDataset(AnalyticsDataset payload) throws Exception {
        if (payload == null) {
            return new ResponseDto(ERROR, "AnalyticsDataset payload missing.");
        }
        String problem = this.validate(payload);
        if (problem != null) {
            return new ResponseDto(ERROR, problem);
        }
        /*
         * The owning tenant is taken from the signed-in context and never from the payload, which
         * is why this method builds a row instead of saving the one it was handed. The endpoint
         * binds the request straight onto the entity, so a caller can put any tenantId, createdBy
         * or id they like on the wire; none of them are read.
         *
         * A null owner here means a platform admin, and the entity's plain-equality filter makes
         * that a row only platform admins can see rather than one shared with everybody. Anyone
         * else with no tenant to their name owns nothing and is refused -- the same fail-closed
         * reading TenantOwnership settled for the whole application.
         */
        if (TenantContext.getTenantId() == null && !TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "A registered dataset needs a workspace to belong to.");
        }

        DatasetRef resolved;
        try {
            // The authorization check that makes this more than a note-to-self: a caller may only
            // register a location they could open right now. The refusal is the resolver's own
            // sentence, which says the same thing for "no such connection" and "not yours", so
            // registration cannot be walked to learn which aliases other workspaces hold.
            resolved = this.datasetResolver.resolve(payload.getConnectionAlias(), payload.getDatasetPath());
        } catch (AnalyticsException ex) {
            return new ResponseDto(ERROR, ex.getMessage());
        }

        AnalyticsDataset target = new AnalyticsDataset();
        target.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
        target.setDatasetName(payload.getDatasetName().trim());
        target.setConnectionAlias(payload.getConnectionAlias().trim());
        // The resolver's cleaned path rather than the caller's, so what is stored is exactly the
        // string that passed the path allow-list -- not one that merely contained it.
        target.setDatasetPath(resolved.getPath());
        // From the resolver, never from the payload. The column is a label for a listing screen,
        // and a label the caller could set to anything would be a listing that lies.
        target.setDatasetFormat(resolved.getFormat().name());
        target.setDateCreated(new Timestamp(System.currentTimeMillis()));
        target = this.analyticsDatasetRepository.save(target);
        return new ResponseDto(SUCCESS, String.format("Dataset registered with %d.",
            target.getAnalyticsDatasetId()), target);
    }

    @Override
    @Transactional
    public ResponseDto deleteDataset(Long analyticsDatasetId) throws Exception {
        if (isNull(analyticsDatasetId)) {
            return new ResponseDto(ERROR, "AnalyticsDataset analyticsDatasetId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsDataset> existing = this.scopedFind(analyticsDatasetId);
        if (!existing.isPresent()) {
            return this.notFound(analyticsDatasetId);
        }
        this.analyticsDatasetRepository.delete(existing.get());
        return new ResponseDto(SUCCESS, String.format("Dataset deleted with %d.", analyticsDatasetId));
    }

    /**
     * Drops anything the caller does not own, after the database has already filtered it out.
     *
     * A listing is a query, so the Hibernate tenant filter genuinely does apply to it -- unlike a
     * load by id -- and in a correct system this loop removes nothing. It is here because the
     * filter is enabled by a call the next person to write a listing can forget, and because a
     * dataset row answers "which connection do they use and what do they read" -- which is not
     * recoverable once shown.
     */
    private static List<AnalyticsDataset> ownedByCaller(List<AnalyticsDataset> rows) {
        List<AnalyticsDataset> owned = new ArrayList<>(rows.size());
        for (AnalyticsDataset row : rows) {
            if (TenantOwnership.isOwnedByCaller(row.getTenantId())) {
                owned.add(row);
            }
        }
        return owned;
    }

    /**
     * One dataset, by id, for this caller only.
     *
     * A Hibernate @Filter applies to queries and NOT to a load by primary key, so findById hands
     * back another workspace's row without the filter ever being consulted. This check is what
     * stops it, and it is the same check AnalyticsQueryLibraryServiceImpl.scopedFind makes.
     *
     * <b>One difference from that one, stated rather than left to be discovered.</b> Over there
     * the load goes through AnalyticsQueryRepository.findByAnalyticsQueryId -- a derived query, so
     * the tenant filter applies to it as well and the rule has two enforcement points.
     * AnalyticsDatasetRepository has no such finder, and adding one was outside this change's
     * scope, so on this path the service check is the only one. It is sufficient -- it is the same
     * predicate the filter would apply, and it is the one that also covers the platform admin the
     * filter is disabled for -- but a findByAnalyticsDatasetId on that repository would restore
     * the second layer, and is worth adding the next time it is opened.
     */
    private Optional<AnalyticsDataset> scopedFind(Long analyticsDatasetId) {
        Optional<AnalyticsDataset> found =
            this.analyticsDatasetRepository.findById(analyticsDatasetId);
        if (!found.isPresent()) {
            return Optional.empty();
        }
        if (!TenantOwnership.isOwnedByCaller(found.get().getTenantId())) {
            return Optional.empty();
        }
        return found;
    }

    /**
     * Deliberately the same sentence for "no such row" and "somebody else's row".
     *
     * This is spec 11's data-leakage clause in one method. A distinct "that belongs to another
     * tenant" confirms the id exists, which turns the endpoint into a way of counting another
     * workspace's registered datasets by walking the ids.
     */
    private ResponseDto notFound(Long analyticsDatasetId) {
        return new ResponseDto(ERROR, String.format("Dataset not found with %s.", analyticsDatasetId));
    }

    private String validate(AnalyticsDataset payload) {
        if (isBlank(payload.getDatasetName())) {
            return "AnalyticsDataset datasetName missing.";
        }
        if (payload.getDatasetName().trim().length() > MAX_NAME_LENGTH) {
            return String.format("A dataset name is at most %d characters.", MAX_NAME_LENGTH);
        }
        if (isBlank(payload.getConnectionAlias())) {
            return "AnalyticsDataset connectionAlias missing.";
        }
        if (payload.getConnectionAlias().trim().length() > MAX_ALIAS_LENGTH) {
            return String.format("A connection alias is at most %d characters.", MAX_ALIAS_LENGTH);
        }
        if (isBlank(payload.getDatasetPath())) {
            return "AnalyticsDataset datasetPath missing.";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
