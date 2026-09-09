package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsDashboardWidget;
import process.model.pojo.AnalyticsQuery;
import process.model.pojo.AnalyticsQueryRun;
import process.model.repository.AnalyticsDashboardWidgetRepository;
import process.model.repository.AnalyticsQueryRepository;
import process.model.repository.AnalyticsQueryRunRepository;
import process.model.service.AnalyticsQueryLibraryService;
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
import java.util.function.Function;
import java.util.regex.Pattern;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class AnalyticsQueryLibraryServiceImpl implements AnalyticsQueryLibraryService {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsQueryLibraryServiceImpl.class);

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_ALIAS_LENGTH = 255;

    /**
     * How much history a single request may take.
     *
     * The table is deliberately never pruned (V32__analytics_query.sql says why), which only
     * works while every read of it is bounded. A caller asking for more receives the maximum,
     * the same bargain the preview page size already makes.
     */
    private static final int DEFAULT_RUN_LIMIT = 50;
    private static final int MAX_RUN_LIMIT = 200;

    /**
     * Anything shaped like a URL, so a failure can be recorded without one being kept.
     *
     * The same expression AnalyticsQueryService guards its responses with, applied again on the
     * way into the table. Duplicated on purpose: a location that leaks into a response is seen
     * once by the person who caused it, and a location that leaks into this column is kept for
     * as long as the history is, readable by everyone in the workspace.
     */
    private static final Pattern LOCATION = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s'\"()]*");

    /**
     * Words that have no business in a message a user was shown, and every business being in an
     * engine string. A recorded message containing one is replaced whole rather than redacted in
     * part: half a credential in a history row is still a credential in a history row.
     */
    private static final List<String> NEVER_RECORDED = Arrays.asList(
        "secret", "password", "accesskey", "access_key", "credential", "authorization");

    private static final String UNRECORDABLE_FAILURE = "The query failed. See the server log for the details.";

    private static final int MAX_ERROR_LENGTH = 1000;

    @PersistenceContext
    private EntityManager entityManager;

    private final AnalyticsQueryRepository analyticsQueryRepository;
    private final AnalyticsQueryRunRepository analyticsQueryRunRepository;
    /**
     * Only for the delete path: a saved query a dashboard is showing cannot vanish silently.
     * V34 added a cascade that made this endpoint remove tiles without saying so.
     */
    private final AnalyticsDashboardWidgetRepository analyticsDashboardWidgetRepository;
    private final TenantFilterHelper tenantFilterHelper;
    private final UserNameResolver userNameResolver;

    public AnalyticsQueryLibraryServiceImpl(AnalyticsQueryRepository analyticsQueryRepository,
        AnalyticsQueryRunRepository analyticsQueryRunRepository,
        AnalyticsDashboardWidgetRepository analyticsDashboardWidgetRepository,
        TenantFilterHelper tenantFilterHelper, UserNameResolver userNameResolver) {
        this.analyticsQueryRepository = analyticsQueryRepository;
        this.analyticsQueryRunRepository = analyticsQueryRunRepository;
        this.analyticsDashboardWidgetRepository = analyticsDashboardWidgetRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.userNameResolver = userNameResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAllQueries() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<AnalyticsQuery> queries = ownedByCaller(
            this.analyticsQueryRepository.findAllByOrderByAnalyticsQueryIdDesc(), AnalyticsQuery::getTenantId);
        this.userNameResolver.attachNames(queries);
        return new ResponseDto(SUCCESS, "Data fetched successfully.", queries);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchQueryById(Long analyticsQueryId) throws Exception {
        if (isNull(analyticsQueryId)) {
            return new ResponseDto(ERROR, "AnalyticsQuery analyticsQueryId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsQuery> query = this.scopedFind(analyticsQueryId);
        if (!query.isPresent()) {
            return this.notFound(analyticsQueryId);
        }
        this.userNameResolver.attachNames(Arrays.asList(query.get()));
        return new ResponseDto(SUCCESS, "Data fetched successfully.", query.get());
    }

    @Override
    @Transactional
    public ResponseDto saveQuery(AnalyticsQuery payload) throws Exception {
        if (payload == null) {
            return new ResponseDto(ERROR, "AnalyticsQuery payload missing.");
        }
        String problem = this.validate(payload);
        if (problem != null) {
            return new ResponseDto(ERROR, problem);
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);

        AnalyticsQuery target;
        if (payload.getAnalyticsQueryId() != null) {
            Optional<AnalyticsQuery> existing = this.scopedFind(payload.getAnalyticsQueryId());
            if (!existing.isPresent()) {
                return this.notFound(payload.getAnalyticsQueryId());
            }
            target = existing.get();
            target.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        } else {
            /*
             * The owning tenant is taken from the signed-in context and never from the payload,
             * which is the whole reason this method builds a row instead of saving the one it was
             * handed. The endpoint binds the request straight onto the entity, so a caller can
             * put any tenantId, createdBy or id they like on the wire; none of them are read.
             *
             * A null owner here means a platform admin, and the entity's plain-equality filter
             * makes that a row only platform admins can see rather than one shared with
             * everybody. Anyone else with no tenant to their name owns nothing and is refused --
             * the same fail-closed reading TenantOwnership settled for the whole application.
             */
            if (TenantContext.getTenantId() == null && !TenantContext.isPlatformAdmin()) {
                return new ResponseDto(ERROR, "A saved query needs a workspace to belong to.");
            }
            target = new AnalyticsQuery();
            target.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
            target.setDateCreated(new Timestamp(System.currentTimeMillis()));
        }
        target.setQueryName(payload.getQueryName().trim());
        target.setConnectionAlias(payload.getConnectionAlias().trim());
        target.setDatasetPath(payload.getDatasetPath().trim());
        target.setQueryText(payload.getQueryText());
        target = this.analyticsQueryRepository.save(target);
        return new ResponseDto(SUCCESS, String.format("Saved query stored with %d.",
            target.getAnalyticsQueryId()), target);
    }

    @Override
    @Transactional
    public ResponseDto renameQuery(Long analyticsQueryId, String queryName) throws Exception {
        if (isNull(analyticsQueryId)) {
            return new ResponseDto(ERROR, "AnalyticsQuery analyticsQueryId missing.");
        }
        if (isBlank(queryName)) {
            return new ResponseDto(ERROR, "AnalyticsQuery queryName missing.");
        }
        if (queryName.trim().length() > MAX_NAME_LENGTH) {
            return new ResponseDto(ERROR, String.format("A saved query name is at most %d characters.", MAX_NAME_LENGTH));
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsQuery> existing = this.scopedFind(analyticsQueryId);
        if (!existing.isPresent()) {
            return this.notFound(analyticsQueryId);
        }
        AnalyticsQuery query = existing.get();
        query.setQueryName(queryName.trim());
        query.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        return new ResponseDto(SUCCESS, "Saved query renamed.", this.analyticsQueryRepository.save(query));
    }

    @Override
    @Transactional
    public ResponseDto deleteQuery(Long analyticsQueryId) throws Exception {
        if (isNull(analyticsQueryId)) {
            return new ResponseDto(ERROR, "AnalyticsQuery analyticsQueryId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsQuery> existing = this.scopedFind(analyticsQueryId);
        if (!existing.isPresent()) {
            return this.notFound(analyticsQueryId);
        }
        // The run rows that pointed at this one keep everything they need to stay readable and
        // lose only the link, which the changeset's ON DELETE SET NULL does. Deleting a bookmark
        // is not permission to delete the record that the data was read.
        //
        // Dashboard widgets are a different matter and are handled EXPLICITLY, the way
        // AnalyticsWorkspaceServiceImpl.deleteAnalysis handles the same case. V34 added
        // ON DELETE CASCADE from widget to saved query, which silently changed what this endpoint
        // does: a person deleting a query they no longer wanted also removed every dashboard tile
        // showing it, and was told only "Saved query deleted". Two reasons not to leave it to the
        // database. The message should name what else went, because a dashboard losing a tile
        // without explanation is indistinguishable from a bug. And the cascade is keyed on
        // (id, tenant_id): Postgres does not enforce a composite foreign key when a column is
        // null, so it never fires for a platform admin's rows, leaving exactly the caller who can
        // see every dashboard looking at widgets that point at nothing.
        List<AnalyticsDashboardWidget> showing =
            this.analyticsDashboardWidgetRepository.findByAnalyticsQueryId(analyticsQueryId);
        if (!showing.isEmpty()) {
            this.analyticsDashboardWidgetRepository.deleteAll(showing);
        }
        this.analyticsQueryRepository.delete(existing.get());
        return new ResponseDto(SUCCESS, showing.isEmpty()
            ? String.format("Saved query deleted with %d.", analyticsQueryId)
            : String.format("Saved query deleted with %d, and %d dashboard widget(s) that showed it.",
                analyticsQueryId, showing.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchRecentRuns(Long analyticsQueryId, Integer limit) throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        int window = limit == null || limit < 1 ? DEFAULT_RUN_LIMIT : Math.min(limit, MAX_RUN_LIMIT);
        List<AnalyticsQueryRun> runs;
        if (analyticsQueryId == null) {
            runs = this.analyticsQueryRunRepository
                .findAllByOrderByDateCreatedDescAnalyticsQueryRunIdDesc(PageRequest.of(0, window));
        } else {
            // Asked for one saved query's history, so the saved query is checked first. The runs
            // are tenant-filtered anyway, but answering "no such query" and "that query has no
            // runs" with the same sentence is what stops the endpoint being usable to find out
            // which ids exist in another workspace.
            if (!this.scopedFind(analyticsQueryId).isPresent()) {
                return this.notFound(analyticsQueryId);
            }
            runs = this.analyticsQueryRunRepository
                .findByAnalyticsQueryIdOrderByDateCreatedDescAnalyticsQueryRunIdDesc(
                    analyticsQueryId, PageRequest.of(0, window));
        }
        runs = ownedByCaller(runs, AnalyticsQueryRun::getTenantId);
        this.userNameResolver.attachNames(runs);
        return new ResponseDto(SUCCESS, "Data fetched successfully.", runs);
    }

    /**
     * In its own transaction, and it swallows its own failures.
     *
     * Both for the same reason: this is called from inside a query that has already run, and a
     * history table that cannot be written to must not be able to turn a successful read into a
     * failed request. Joining the caller's transaction would let a constraint violation here mark
     * that transaction rollback-only, which is exactly the outcome being avoided.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalyticsQueryRun recordRun(AnalyticsQueryRun run) {
        try {
            if (run == null || isBlank(run.getConnectionAlias()) || isBlank(run.getDatasetPath())
                || isBlank(run.getQueryText()) || isBlank(run.getRunStatus())) {
                this.logger.warn("An analytics run was not recorded: it did not say what ran.");
                return null;
            }
            if (TenantContext.getTenantId() == null && !TenantContext.isPlatformAdmin()) {
                // Nobody to attribute the read to. Scheduled work and Kafka callbacks run this
                // way, and AuditListener already refuses to invent an author for them; a history
                // row with neither a tenant nor a user would say a read happened without saying
                // whose, which is the one thing this table exists to avoid.
                this.logger.warn("An analytics run was not recorded: there is no signed-in caller to attribute it to.");
                return null;
            }
            AnalyticsQueryRun record = new AnalyticsQueryRun();
            // Same rule as saveQuery: the caller describes what it observed, and the row's
            // identity is taken from the context.
            record.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
            record.setAnalyticsQueryId(this.linkedQueryId(run.getAnalyticsQueryId()));
            record.setConnectionAlias(run.getConnectionAlias().trim());
            record.setDatasetPath(run.getDatasetPath().trim());
            record.setQueryText(run.getQueryText());
            record.setRunStatus(run.getRunStatus().trim());
            record.setRowCount(run.getRowCount());
            record.setDurationMs(run.getDurationMs());
            record.setErrorMessage(this.recordableMessage(run.getErrorMessage()));
            record.setDateCreated(new Timestamp(System.currentTimeMillis()));
            return this.analyticsQueryRunRepository.save(record);
        } catch (Exception ex) {
            this.logger.error("An error occurred while recording an analytics query run.", ex);
            return null;
        }
    }

    /**
     * The saved query to link this run to, or null.
     *
     * An id the caller does not own becomes an ad-hoc run rather than a refusal: the read really
     * did happen and is still worth recording, and attributing it to somebody else's saved query
     * would put a row in their history that they did not cause.
     */
    private Long linkedQueryId(Long analyticsQueryId) {
        if (analyticsQueryId == null) {
            return null;
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        return this.scopedFind(analyticsQueryId).isPresent() ? analyticsQueryId : null;
    }

    /**
     * What may be kept of a failure message.
     *
     * explain() has already turned the engine's words into a sentence for a person, so anything
     * still carrying a URL or a credential word arrived by a path that did not go through it.
     * That message is dropped whole and logged instead of being trimmed: a redacted engine string
     * is still an engine string, and the log is where the original was always going to be.
     */
    private String recordableMessage(String message) {
        if (isBlank(message)) {
            return null;
        }
        String trimmed = message.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        boolean carriesSomethingPrivate = LOCATION.matcher(trimmed).find();
        for (String word : NEVER_RECORDED) {
            carriesSomethingPrivate = carriesSomethingPrivate || lower.contains(word);
        }
        if (carriesSomethingPrivate) {
            this.logger.warn("An analytics failure message was not recorded as given; "
                + "it named a location or a credential. Original: {}", trimmed);
            return UNRECORDABLE_FAILURE;
        }
        return trimmed.length() > MAX_ERROR_LENGTH ? trimmed.substring(0, MAX_ERROR_LENGTH) : trimmed;
    }

    /**
     * Drops anything the caller does not own, after the database has already filtered it out.
     *
     * A listing is a query, so the Hibernate tenant filter genuinely does apply to it -- unlike a
     * load by id -- and in a correct system this loop removes nothing. It is here because the
     * filter is enabled by a call the next person to write a listing can forget, and because both
     * of these tables answer questions ("which connection do they use", "what did they read")
     * that are not recoverable once shown. Returning a short page when the filter has failed is a
     * better outcome than returning a complete one belonging to somebody else.
     */
    private static <T> List<T> ownedByCaller(List<T> rows, Function<T, Long> tenantIdOf) {
        List<T> owned = new ArrayList<>(rows.size());
        for (T row : rows) {
            if (TenantOwnership.isOwnedByCaller(tenantIdOf.apply(row))) {
                owned.add(row);
            }
        }
        return owned;
    }

    /**
     * One saved query, by id, for this caller only.
     *
     * The tenant check is here and not left to the Hibernate filter because a @Filter applies to
     * queries and not to a load by primary key: findById would hand back another workspace's row
     * without the filter ever being consulted. The repository's finder is a query, so the filter
     * does apply to it -- and this check runs on top of it anyway, because a tenancy rule with
     * exactly one enforcement point is a tenancy rule one refactor away from being none.
     */
    private Optional<AnalyticsQuery> scopedFind(Long analyticsQueryId) {
        Optional<AnalyticsQuery> found = this.analyticsQueryRepository.findByAnalyticsQueryId(analyticsQueryId);
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
     * A distinct "that belongs to another tenant" is itself a cross-tenant disclosure: it
     * confirms the id exists and turns the endpoint into a way of counting another workspace's
     * saved work.
     */
    private ResponseDto notFound(Long analyticsQueryId) {
        return new ResponseDto(ERROR, String.format("Saved query not found with %s.", analyticsQueryId));
    }

    private String validate(AnalyticsQuery payload) {
        if (isBlank(payload.getQueryName())) {
            return "AnalyticsQuery queryName missing.";
        }
        if (payload.getQueryName().trim().length() > MAX_NAME_LENGTH) {
            return String.format("A saved query name is at most %d characters.", MAX_NAME_LENGTH);
        }
        if (isBlank(payload.getConnectionAlias())) {
            return "AnalyticsQuery connectionAlias missing.";
        }
        if (payload.getConnectionAlias().trim().length() > MAX_ALIAS_LENGTH) {
            return String.format("A connection alias is at most %d characters.", MAX_ALIAS_LENGTH);
        }
        if (isBlank(payload.getDatasetPath())) {
            return "AnalyticsQuery datasetPath missing.";
        }
        if (isBlank(payload.getQueryText())) {
            return "AnalyticsQuery queryText missing.";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
