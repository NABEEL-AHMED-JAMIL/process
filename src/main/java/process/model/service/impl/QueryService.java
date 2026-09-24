package process.model.service.impl;

import java.util.TreeSet;
import java.util.Collection;
import process.util.RequestRefused;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.model.dto.MessageQSearchDto;
import process.model.dto.SearchTextDto;
import process.model.enums.JobAuditMarker;
import org.barco.platform.tenancy.TenantScope;
import process.security.TenantContext;
import process.util.ProcessUtil;
import process.util.SqlLogRedaction;
import javax.persistence.*;
import javax.transaction.Transactional;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 * */
@Service
@Transactional
public class QueryService {

    private Logger logger = LoggerFactory.getLogger(QueryService.class);

    /**
     * What the executors log (MIG-74, DEF-142). Every query here is string-built, so the composed text
     * carries the caller's tenant id and search terms; it went out at INFO. The shape, literals redacted,
     * goes to DEBUG -- which logback.xml enables everywhere, so it must be safe to ship -- and the
     * composed query only to TRACE, for a developer who turns it on locally.
     */
    private void logQuery(String queryStr) {
        if (this.logger.isTraceEnabled()) {
            this.logger.trace("Execute Query :- {}.", queryStr);
        } else if (this.logger.isDebugEnabled()) {
            this.logger.debug("Execute Query :- {}.", SqlLogRedaction.redact(queryStr));
        }
    }

    @PersistenceContext
    private EntityManager _em;

    public Object executeQueryForSingleResult(String queryStr) {
        this.logQuery(queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getSingleResult();
    }

    public List<Object[]> executeQuery(String queryStr) {
        this.logQuery(queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getResultList();
    }

    public List<Object[]> executeQuery(String queryStr, Pageable paging) {
        this.logQuery(queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        if (paging != null) {
            query.setFirstResult(paging.getPageNumber() * paging.getPageSize());
            query.setMaxResults(paging.getPageSize());
        }
        return query.getResultList();
    }

    public String listSourceTaskQuery(boolean isCount, String startDate, String endDate, String columnName,
        String order, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result\n";
        } else {
            selectPortion = "select st.task_detail_id, st.task_name, st.task_payload, ld1.lookup_type as home_page_id, " +
                "st.pipeline_id, ld3.lookup_type as group_id, st.task_status, stt.source_task_type_id, stt.service_name, " +
                "stt.description, stt.queue_topic_partition, stt.task_type_status, stt.kafka_connection_profile_id, " +
                "st.bucket, st.input_folder, st.output_folder, " +
                "count(sj.job_id) as total_link_jobs\n";
        }
        String query = selectPortion + " from source_task st inner join source_task_type stt on stt.source_task_type_id = st.source_task_type_id\n";
        if (!isCount) {
            query += "left join source_job sj on sj.task_detail_id = st.task_detail_id and sj.job_status in ('Active', 'Inactive')\n";
            // home_page_id and group_id really are lookup ids -- the Home page and Group
            // fields both store lookup_data.lookup_id. pipeline_id is NOT, and used to be
            // joined the same way here: since the PIPELINE_IDS lookup family was dropped
            // (changeset V28) and Pipeline Forms became the catalogue, source_task.pipeline_id
            // holds the raw id the worker routes on ("F768930"). Nothing in lookup_data has
            // that for an id, so the join matched nothing and the list's Pipeline column was
            // blank for every task ever created. Selected straight from source_task now, the
            // same way SourceTaskRepository and the Kafka producer already read it.
            // bigint foreign keys since V70.3 (MIG-165): joined on the key, no cast.
            query += "left join lookup_data ld1 on ld1.lookup_id = st.home_page_id\n";
            query += "left join lookup_data ld3 on ld3.lookup_id = st.group_id\n";
        }
        query += "where st.task_status in ('Active', 'Inactive') " + this.tenantClause("st");
        // source_task had no date_created until V130, so any range here was a 500. It is an instant, read as
        // Chicago's day. A task made before V130 has NULL and no range claims it.
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and date(st.date_created AT TIME ZONE 'America/Chicago') between '%s' and '%s' ",
                    this.requireValidDate(startDate), this.requireValidDate(endDate));
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and date(st.date_created AT TIME ZONE 'America/Chicago') >= '%s' ",
                    this.requireValidDate(startDate));
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and date(st.date_created AT TIME ZONE 'America/Chicago') <= '%s' ",
                    this.requireValidDate(endDate));
            }
        }
        if (searchTextDto != null && (searchTextDto.getItemName() != null && searchTextDto.getItemValue() != null)) {
            String itemValue = this.sqlEscape(searchTextDto.getItemValue());
            if (searchTextDto.getItemName().equalsIgnoreCase("task_detail_id")) {
                query += "and cast(st.task_detail_id as varchar) like ('%" + itemValue + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("task_name")) {
                query += "and upper(st.task_name) like upper('%" + itemValue + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("task_status")) {
                query += "and cast(st.task_status as varchar) like ('%" + itemValue + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("source_task_type_id")) {
                query += "and cast(stt.source_task_type_id as varchar) like ('%" + itemValue + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("service_name")) {
                query += "and upper(stt.service_name) like upper('%" + itemValue + "%') ";
            }
        }
        if (!isCount) {
            // ld2 is gone with the pipeline_id join above; st.pipeline_id needs no entry here
            // because st.task_detail_id is source_task's primary key, so every other
            // column of that table is functionally dependent on it.
            query += "\ngroup by st.task_detail_id, stt.source_task_type_id, ld1.lookup_id, ld3.lookup_id\n";
            if (order != null && columnName != null) {
                query += String.format("order by %s %s ", this.sanitizeSortColumn(columnName), this.sanitizeSortOrder(order));
            }
        }
        return query;
    }

    public String fetchAllLinkJobsWithSourceTaskQuery(boolean isCount, Long taskDetailId,
        String startDate, String endDate, SearchTextDto searchTextDto) {
        return this.fetchAllLinkJobsWithSourceTaskQuery(isCount, taskDetailId, startDate, endDate, null, null, searchTextDto);
    }

    /**
     * The linked-jobs query, optionally ordered.
     *
     * It emitted no `order by` at all, so the columnName and order the endpoint accepts went
     * nowhere and the row order was whatever the planner happened to return -- which, once the
     * rows are also paged, means a row can appear on two pages and another on none.
     */
    public String fetchAllLinkJobsWithSourceTaskQuery(boolean isCount, Long taskDetailId,
        String startDate, String endDate, String columnName, String order, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result ";
        } else {

            selectPortion = "select sj.job_id, sj.job_name, sj.job_status, sj.execution, sj.job_running_status, " +
                "to_char(sj.last_job_run AT TIME ZONE 'America/Chicago', 'YYYY-MM-DD HH24:MI:SS'), sj.priority, " +
                "sj.date_created ";
        }
        String query = selectPortion + "from source_task st inner join source_job sj on sj.task_detail_id = st.task_detail_id ";
        query += "where st.task_status in ('Active', 'Inactive') and sj.job_status in ('Active', 'Inactive') " + this.tenantClause("sj");
        if (taskDetailId != null) {
            query += String.format(" and st.task_detail_id = %d ", taskDetailId);
        }
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and cast(sj.date_created AT TIME ZONE 'America/Chicago' as date) between '%s' and '%s' ",
                    this.requireValidDate(startDate), this.requireValidDate(endDate));
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and cast(sj.date_created AT TIME ZONE 'America/Chicago' as date) >= '%s' ", this.requireValidDate(startDate));
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and cast(sj.date_created AT TIME ZONE 'America/Chicago' as date) <= '%s' ", this.requireValidDate(endDate));
            }
        }
        if (searchTextDto != null && (searchTextDto.getItemName() != null && searchTextDto.getItemValue() != null)) {
            String itemValue = this.sqlEscape(searchTextDto.getItemValue());
            if (searchTextDto.getItemName().equalsIgnoreCase("job_id")) {
                query += "and cast(sj.job_id as varchar) like ('%" + itemValue + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("job_name")) {
                query += "and upper(job_name) like upper('%" + itemValue + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("job_status")) {
                query += "and cast(sj.job_status as varchar) like ('%" + itemValue + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("date_created")) {
                query += "and cast(sj.date_created AT TIME ZONE 'America/Chicago' as varchar) like ('%" + itemValue + "%') ";
            }
        }
        if (!isCount) {
            // Always ordered, even when the caller names no column: a paged read with no order
            // is not a stable sequence of pages.
            query += String.format("order by %s %s ",
                this.sanitizeLinkedJobSortColumn(columnName), this.sanitizeSortOrder(order));
        }
        return query;
    }

    /**
     * No dates is all time. A date that is there but is not one is refused (MIG-103): it used to be
     * dropped without a word, so a typo showed all-time figures under the range the person asked for.
     *
     * The day is Chicago's (V100, MIG-163): the columns are instants now, and date() of an instant is the day
     * in whatever zone the session happens to be in. Spelled exactly as idx_job_queue_date_created_day is, so
     * a filter on job_queue.date_created still uses it (DashboardIndexPostgresTest).
     */
    private String dateRangeFilter(String column, String startDate, String endDate) {
        for (String date : new String[] {startDate, endDate}) {
            if (date != null && !date.trim().isEmpty()) {
                this.requireValidDate(date);
            }
        }
        if (!isValidDate(startDate) || !isValidDate(endDate)) {
            return "";
        }
        return String.format("and date(%s AT TIME ZONE 'America/Chicago') between '%s' and '%s' ", column, startDate, endDate);
    }

    /**
     * A real calendar date, not just its shape: "2026-13-45" has the shape, passed, and failed inside
     * Postgres's cast as a 500 -- the refusal the Dashboard is meant to give in words (MIG-103).
     */
    private boolean isValidDate(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return false;
        }
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException impossible) {
            return false;
        }
    }

    private String requireValidDate(String date) {
        if (!this.isValidDate(date)) {
            throw new RequestRefused("Invalid date -- expected yyyy-MM-dd.");
        }
        return date;
    }

    private String sqlEscape(Object value) {
        return value == null ? "" : value.toString().replace("'", "''");
    }

    private static final Set<String> SOURCE_TASK_SORT_COLUMNS = new HashSet<>(Arrays.asList(
        "st.task_detail_id", "st.task_name", "st.task_status", "st.date_created",
        "stt.source_task_type_id", "stt.service_name"
    ));

    private String sanitizeSortColumn(String columnName) {
        return SOURCE_TASK_SORT_COLUMNS.contains(columnName) ? columnName : "st.task_detail_id";
    }

    private static final Set<String> LINKED_JOB_SORT_COLUMNS = new HashSet<>(Arrays.asList(
        "sj.job_id", "sj.job_name", "sj.job_status", "sj.execution",
        "sj.job_running_status", "sj.last_job_run", "sj.priority", "sj.date_created"
    ));

    private String sanitizeLinkedJobSortColumn(String columnName) {
        return LINKED_JOB_SORT_COLUMNS.contains(columnName) ? columnName : "sj.job_id";
    }

    private String sanitizeSortOrder(String order) {
        return "asc".equalsIgnoreCase(order) ? "asc" : "desc";
    }

    /**
     * Exactly the constants of process.model.enums.JobStatus, upper-cased.
     *
     * "STOP" used to be in here and is not, and has never been, a JobStatus. It was therefore
     * the one value that passed validation and then matched no row, so a caller filtering by it
     * got a successful, permanently empty result instead of the "invalid jobStatus" this method
     * exists to give them.
     */
    private static final Set<String> JOB_QUEUE_STATUSES = new HashSet<>(Arrays.asList(
        "QUEUE", "START", "RUNNING", "FAILED", "COMPLETED", "SKIP", "INTERRUPT", "MISSED"
    ));

    private String sanitizeJobStatus(String jobStatus) {
        if (jobStatus != null && JOB_QUEUE_STATUSES.contains(jobStatus.toUpperCase())) {
            return jobStatus.toUpperCase();
        }
        throw new RequestRefused("Invalid jobStatus -- expected one of " + JOB_QUEUE_STATUSES);
    }

    /**
     * The tenant predicate spliced into every list query, and what happens when there is no tenant.
     *
     * A missing tenant used to yield no predicate at all, which is the opposite of what the by-id
     * paths do: TenantOwnership refuses a tenantless caller outright, on the stated principle that
     * a context with no tenant owns nothing. So the same account that could not open one job by id
     * was served every tenant's jobs by the list, and every tenant's tasks -- task_payload XML
     * included -- by the task list. Only a platform admin is meant to cross tenants; anyone else
     * arriving without one is a legacy or broken row, and the safe answer for them is nothing
     * rather than everything.
     */
    private String tenantClause(String tableAlias) {
        // MIG-93: the request's TenantScope. AllTenants is the one grant that adds no predicate; a caller
        // with no tenant is Scoped to NO_TENANT_MATCHES, and answered "and 1 = 0".
        TenantScope scope = TenantContext.scope();
        if (scope.isAllTenants()) {
            return "";
        }
        long tenantId = ((TenantScope.Scoped) scope).tenantId();
        if (tenantId == TenantScope.NO_TENANT_MATCHES) {
            return " and 1 = 0 ";
        }
        return String.format(" and %s.tenant_id = %d ", tableAlias, tenantId);
    }

    public String jobStatusStatistics(String startDate, String endDate) {

        String dateFilter = this.dateRangeFilter("date_created", startDate, endDate);
        String tenantFilter = this.tenantClause("source_job");
        return "select job_status, count(job_id) as total_count from source_job\n" +
            "where job_status in ('Active','Inactive') " + dateFilter + tenantFilter + "group by job_status\n" +
            "union all\n" +
            "select 'All' as job_status, count(job_id) as total_count from source_job where job_status in ('Active','Inactive') " + dateFilter + tenantFilter;
    }

    /**
     * Run rows for the report screen: one row per recorded run, with the dimensions it can be
     * grouped by and the duration it can be measured on.
     *
     * Rows rather than an aggregate, because the point of the screen is that the reader picks
     * the grouping. Aggregating here would mean a round trip for every change of dimension,
     * and the pivot is cheap over a few thousand rows in the browser.
     *
     * A run with no end time contributes -1 rather than 0: it did not take no time, it did not
     * finish, and the duration measures must exclude it rather than average it in.
     */
    public String runReportRows(String startDate, String endDate) {

        /*
         * A run that never started still happened to the schedule. Skip and Missed rows carry
         * skip_time and no start_time (BulkAction.createJobQueue), so a filter on start_time
         * alone dropped them and the report could not say that a task was skipped six times
         * this week -- it simply showed six fewer runs. The moment a run belongs to the day is
         * whichever of the two the engine stamped.
         */
        String dateFilter = this.dateRangeFilter("coalesce(q.start_time, q.skip_time)", startDate, endDate);
        return "select coalesce(st.task_name, '(no task)') as task, "
            + "q.job_status as status, "
            // The owner and the workspace are ids here; Identity names them (ReportExportServiceImpl, MIG-107).
            + "sj.assigned_user_id as owner_id, "
            + "to_char(coalesce(q.start_time, q.skip_time) AT TIME ZONE 'America/Chicago', 'YYYY-MM-DD') as day, "
            + "case when q.end_time is null then -1 "
            + "else round(extract(epoch from (q.end_time - q.start_time))) end as seconds, "
            + "sj.job_name as job, q.job_queue_id as run_id, "
            // Carried so the report can SAY whose runs these are. A platform admin has the
            // tenant filter switched off (see tenantClause), so their report already merged
            // every workspace's runs into one set of totals -- with no column, no filter and
            // nothing on screen to reveal that it had happened.
            + "sj.tenant_id as tenant_id, "
            /*
             * TRUE execution time, separated from the wait in front of it.
             *
             * job_queue stamps start_time at ENQUEUE, not at pickup, so end_time - start_time
             * above is wait + execution with no way to tell them apart -- and on this deployment
             * the wait is 99.4% of it (41.25s of a 41.48s average, for tasks that run in 0.23s),
             * because the dispatcher polls once a minute. Reporting only that number invites
             * every reader to optimise a transform that was never slow.
             *
             * The worker writes a 'Job started' audit line the moment it picks a run up, and it
             * covers every run in this database, so the pickup instant IS recorded -- just not
             * in job_queue. LEFT JOIN, so a run without the marker reports -1 and is excluded
             * from execution statistics rather than counted as instant.
             */
            // Two decimals, unlike `seconds` above. Whole seconds are the right unit for a
            // queued-to-finished figure measured in tens of seconds; they are the wrong unit
            // here, where the real answer is 0.23 and rounding it prints "0s" -- which reads as
            // "no data" and throws away the very contrast this column exists to show.
            + "case when x.exec_start is null or q.end_time is null then -1 "
            // cast(... as numeric), NOT ::numeric. This string is handed to
            // entityManager.createNativeQuery, which parses ':' as the start of a named
            // parameter -- "::numeric" made it a syntax error at the database.
            + "else round(cast(extract(epoch from (q.end_time - x.exec_start)) as numeric), 2) "
            + "end as exec_seconds "
            + "from job_queue q "
            + "join source_job sj on sj.job_id = q.job_id "
            + "left join source_task st on st.task_detail_id = sj.task_detail_id "
            + "left join (select job_queue_id, min(date_created) as exec_start "
            // The marker is JobAuditMarker.JOB_STARTED, one constant with the worker's literal behind it
            // (MIG-77): matched exactly, never with LIKE.
            + "from job_audit_logs where log_detail = '" + JobAuditMarker.JOB_STARTED.logDetail() + "' group by job_queue_id) x "
            + "on x.job_queue_id = q.job_queue_id "
            // A deleted job's runs are not history any more, and every other statistic here
            // already leaves them out -- a report that counted them would disagree with the
            // dashboard beside it, on the same data.
            + "where (q.start_time is not null or q.skip_time is not null) "
            + "and upper(sj.job_status) <> 'DELETE' "
            + dateFilter + this.tenantClause("sj")
            + "order by q.job_queue_id desc";
    }

    /**
     * Per-user totals, for "who owns what and how is it going": jobs, active jobs, distinct tasks, runs,
     * completed and failed runs -- for the people listed, and only them.
     *
     * Jobs carry assigned_user_id so they attribute directly. Tasks do not carry an owner at all, so a
     * user's task count is the distinct tasks their jobs point at rather than anything they are recorded
     * as owning. The run join stays LEFT with the date range inside it, so a job without runs in the range
     * still counts; a person with no jobs at all gets no row here and is listed at zero by the caller.
     * The caller's tenant clause stays on the jobs too, as on every builder here: the id list is the
     * scope, and this is the defence behind it. Who is listed (the caller's scope, a tenant user's own row) is decided
     * by DashboardServiceImpl through IdentityPort.members (MIG-107); this used to be a join on app_user.
     * Nobody listed is a query that matches nothing.
     */
    public String userStatistics(String startDate, String endDate, Collection<Long> appUserIds) {

        String dateFilter = this.dateRangeFilter("jq.date_created", startDate, endDate);
        String people = appUserIds == null || appUserIds.isEmpty() ? " and 1 = 0 "
            : " and sj.assigned_user_id in (" + new TreeSet<>(appUserIds).stream().map(String::valueOf)
                .collect(Collectors.joining(", ")) + ") ";
        return "select sj.assigned_user_id, "
            + "count(distinct sj.job_id) as job_count, "
            + "count(distinct sj.job_id) filter (where sj.job_status = 'Active') as active_jobs, "
            + "count(distinct sj.task_detail_id) as task_count, "
            + "count(jq.job_queue_id) as run_count, "
            + "count(jq.job_queue_id) filter (where jq.job_status = 'Completed') as completed_count, "
            + "count(jq.job_queue_id) filter (where jq.job_status = 'Failed') as failed_count "
            + "from source_job sj "
            + "left join job_queue jq on jq.job_id = sj.job_id " + dateFilter
            + "where sj.job_status in ('Active','Inactive') " + people + this.tenantClause("sj")
            + "group by sj.assigned_user_id";
    }

    public String jobRunningStatistics(String startDate, String endDate) {

        String dateFilter = this.dateRangeFilter("date_created", startDate, endDate);
        return "select UPPER(job_running_status) as job_running_status, count(job_id) as total_count\n" +
            "from source_job\n" +
            "where UPPER(job_running_status) in ('START', 'RUNNING', 'FAILED', 'COMPLETED')\n" +
            "and UPPER(job_status) in ('ACTIVE','INACTIVE') " + dateFilter + this.tenantClause("source_job") + "\n" +
            "group by UPPER(job_running_status)";
    }

    public String weeklyRunningJobStatistics(String startDate, String endDate) {

        // Grouped and ordered by the date itself, not the day name. Without the ordering the
        // aggregate came back in whatever order it was built -- the chart drew Fri, Mon, Thu,
        // Tue, Wed -- and grouping on the name alone merged the same weekday from different
        // weeks into one bar whenever the range ran longer than seven days.
        return String.format("select weekData.daycode, count(*) from (\n" +
            "select job_queue_id, to_char(cast(jq.date_created AT TIME ZONE 'America/Chicago' as date), 'Dy') as daycode,\n" +
            "cast(jq.date_created AT TIME ZONE 'America/Chicago' as date) as runDate\n" +
            "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id where date(jq.date_created AT TIME ZONE 'America/Chicago') between '%s' and '%s' and UPPER(sj.job_status) in ('ACTIVE','INACTIVE')" +
            this.tenantClause("sj") + ") as weekData\n" +
            "group by weekData.runDate, weekData.daycode\n" +
            "order by weekData.runDate", this.requireValidDate(startDate), this.requireValidDate(endDate));
    }

    public String weeklyHrsRunningJobStatistics(String startDate, String endDate) {

        return String.format("select weekData.daycode, weekData.hr, weekData.date, count(*)\n" +
            "from (select job_queue_id, to_char(cast(jq.date_created AT TIME ZONE 'America/Chicago' as date), 'Day') as daycode,\n" +
            "cast(jq.date_created AT TIME ZONE 'America/Chicago' as date) as date, cast(jq.date_created AT TIME ZONE 'America/Chicago' as time) as time, \n" +
            "extract(hour from cast(jq.date_created AT TIME ZONE 'America/Chicago' as time)) as hr\n" +
            "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id where date(jq.date_created AT TIME ZONE 'America/Chicago') between '%s' and '%s' and UPPER(sj.job_status) in ('ACTIVE','INACTIVE')" +
            this.tenantClause("sj") + ") as weekData\n" +
            "group by weekData.daycode, weekData.hr, weekData.date", this.requireValidDate(startDate), this.requireValidDate(endDate));
    }

    public String weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) {

        targetDate = this.requireValidDate(targetDate);
        String tenantFilter = this.tenantClause("source_job");
        return String.format(
            "SELECT job_id, job_name, queue, start, running, failed, completed, stop, skip, interrupt, missed, total, " +
                "tenant_id FROM (\n" +
                "    SELECT \n" +
                "        job_queue.job_id,\n" +
                "        source_job.job_name,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'QUEUE' THEN 1 END) AS queue,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'START' THEN 1 END) AS start,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'RUNNING' THEN 1 END) AS running,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'FAILED' THEN 1 END) AS failed,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'COMPLETED' THEN 1 END) AS completed,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'STOP' THEN 1 END) AS stop,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'SKIP' THEN 1 END) AS skip,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'INTERRUPT' THEN 1 END) AS interrupt,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'MISSED' THEN 1 END) AS missed,\n" +
                "        COUNT(*) AS total,\n" +
                "        source_job.tenant_id\n" +
                "    FROM job_queue\n" +
                "    INNER JOIN source_job ON source_job.job_id = job_queue.job_id\n" +
                "    WHERE DATE(job_queue.date_created AT TIME ZONE 'America/Chicago') = '%s'\n" +
                "      AND EXTRACT(HOUR FROM job_queue.date_created AT TIME ZONE 'America/Chicago') = %d\n" +
                "      AND UPPER(source_job.job_status) IN ('ACTIVE','INACTIVE')\n" +
                "      " + tenantFilter + "\n" +
                "    GROUP BY job_queue.job_id, source_job.job_name, source_job.tenant_id\n" +
                "\n" +
                "    UNION ALL\n" +
                "\n" +
                "    SELECT \n" +
                "        NULL AS job_id,\n" +
                "        'TOTAL' AS job_name,\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'QUEUE' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'START' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'RUNNING' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'FAILED' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'COMPLETED' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'STOP' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'SKIP' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'INTERRUPT' THEN 1 END),\n" +
                "        COUNT(CASE WHEN UPPER(job_queue.job_status) = 'MISSED' THEN 1 END),\n" +
                "        COUNT(*),\n" +
                "        NULL AS tenant_id\n" +
                "    FROM job_queue\n" +
                "    INNER JOIN source_job ON source_job.job_id = job_queue.job_id\n" +
                "    WHERE DATE(job_queue.date_created AT TIME ZONE 'America/Chicago') = '%s'\n" +
                "      AND EXTRACT(HOUR FROM job_queue.date_created AT TIME ZONE 'America/Chicago') = %d\n" +
                "      AND UPPER(source_job.job_status) IN ('ACTIVE','INACTIVE')\n" +
                "      " + tenantFilter + "\n" +
                ") t\n" +
                "ORDER BY job_id ASC NULLS LAST",
                targetDate, targetHr, targetDate, targetHr
        );
    }

    public String statisticsBySourceJobId(Long jobId) {

        return String.format(
            "SELECT\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'QUEUE' THEN job_queue.job_id END) AS Queue,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'START' THEN job_queue.job_id END) AS Start,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'RUNNING' THEN job_queue.job_id END) AS Running,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'FAILED' THEN job_queue.job_id END) AS Failed,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'COMPLETED' THEN job_queue.job_id END) AS Completed,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'STOP' THEN job_queue.job_id END) AS Stop,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'SKIP' THEN job_queue.job_id END) AS Skip,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'INTERRUPT' THEN job_queue.job_id END) AS Interrupt,\n" +
            "COUNT(CASE WHEN UPPER(job_queue.job_status) = 'MISSED' THEN job_queue.job_id END) AS Missed,\n" +
            "COUNT(*) AS total\n" +
            "from job_queue\n" +
            "inner join source_job on source_job.job_id = job_queue.job_id\n" +
            "where source_job.job_id = %d and UPPER(source_job.job_status) in ('ACTIVE','INACTIVE')" + this.tenantClause("source_job"), jobId
        );
    }

    public String weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) {
        // Named, in the order DashboardServiceImpl reads them. This was select job_queue.*, read by
        // position -- correct only while the table's physical column order happened to match, and a
        // job_queue provisioned anywhere new by ddl-auto puts attempt at index 1 (MIG-8, DEF-126).
        String query = "select job_queue.job_queue_id, job_queue.date_created, job_queue.end_time, job_queue.job_id, " +
                "job_queue.job_send, job_queue.job_status, job_queue.job_status_message, job_queue.run_manual, " +
                "job_queue.skip_manual, job_queue.skip_time, job_queue.start_time from job_queue\n" +
                "inner join source_job on source_job.job_id = job_queue.job_id where 1=1\n" +
                this.tenantClause("source_job") + "\n";
        if (!ProcessUtil.isNull(targetDate)) {
            query += String.format(" and date(job_queue.date_created AT TIME ZONE 'America/Chicago') = '%s' \n", this.requireValidDate(targetDate));
        }
        if (!ProcessUtil.isNull(targetHr)) {
            query += String.format(" and extract(hour from cast(job_queue.date_created AT TIME ZONE 'America/Chicago' as time)) = %d\n", targetHr);
        }
        if (!ProcessUtil.isNull(jobId)) {
            query += String.format("and job_queue.job_id = %d\n", jobId);
        }
        if (!ProcessUtil.isNull(jobStatus)) {
            query += String.format("and UPPER(job_queue.job_status) = UPPER('%s')\n", this.sanitizeJobStatus(jobStatus));
        }
        query += "and UPPER(source_job.job_status) in ('ACTIVE','INACTIVE')\n";
        query += "\norder by job_queue.job_queue_id desc";
        return query;
    }

    public String fetchJobQLog(MessageQSearchDto messageQSearch, boolean isState) {
        String selectPortion;
        if (isState) {

            selectPortion = "select UPPER(jq.job_status) as job_status, count(*) as total_count \n";
        } else {

            selectPortion = "select jq.job_queue_id, jq.date_created, jq.end_time, jq.job_id, jq.job_send, jq.job_status, jq.job_status_message, jq.run_manual, jq.skip_manual, jq.skip_time, jq.start_time \n";
        }

        String query = selectPortion + "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id \n";
        if (!isState) {
            query += String.format("where cast(jq.date_created AT TIME ZONE 'America/Chicago' as date) between '%s' and '%s' \n",
                    this.requireValidDate(messageQSearch.getFromDate()), this.requireValidDate(messageQSearch.getToDate()));
            query += "and UPPER(sj.job_status) <> 'DELETE' and UPPER(jq.status) <> 'DELETE' \n";
            query += this.tenantClause("sj") + "\n";
            if (!ProcessUtil.isNull(messageQSearch.getJobId()) && !messageQSearch.getJobId().isEmpty()) {
                String jobId = messageQSearch.getJobId().toString();
                query += String.format("and jq.job_id in (%s) \n", jobId.substring(1, jobId.length()-1));
            }
            if (!ProcessUtil.isNull(messageQSearch.getJobQId()) && !messageQSearch.getJobQId().isEmpty()) {
                String jobQId = messageQSearch.getJobQId().toString();
                query += String.format("and jq.job_queue_id in (%s) \n", jobQId.substring(1, jobQId.length()-1));
            }
            if (!ProcessUtil.isNull(messageQSearch.getJobStatuses()) && !messageQSearch.getJobStatuses().isEmpty()) {
                String jobStatus = messageQSearch.getJobStatuses().stream()
                        .map(jobStatus1 -> "'" + jobStatus1.toString().toUpperCase() + "',").collect(Collectors.joining());
                query += String.format("and UPPER(jq.job_status) in (%s)", jobStatus.substring(0,jobStatus.length()-1));
            }
        }
        if (isState) {

            query += "where UPPER(sj.job_status) in ('ACTIVE','INACTIVE') " + this.tenantClause("sj") + "\n";
            query += "\ngroup by UPPER(jq.job_status)";
        }
        if (!isState) {
            query += "\norder by job_queue_id desc";
        }
        return query;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
