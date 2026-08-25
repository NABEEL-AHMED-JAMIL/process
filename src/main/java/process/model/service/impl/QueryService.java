package process.model.service.impl;

import com.google.gson.Gson;
import org.hibernate.query.internal.NativeQueryImpl;
import org.hibernate.transform.AliasToEntityMapResultTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.model.dto.MessageQSearchDto;
import process.model.dto.SearchTextDto;
import process.model.projection.ItemResponse;
import process.security.TenantContext;
import process.util.ProcessUtil;
import javax.persistence.*;
import javax.transaction.Transactional;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class QueryService {

    private Logger logger = LoggerFactory.getLogger(QueryService.class);

    @PersistenceContext
    private EntityManager _em;

    public Object executeQueryForSingleResult(String queryStr) {
        logger.info("Execute Query :- {}.", queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getSingleResult();
    }

    public List<Object[]> executeQuery(String queryStr) {
        logger.info("Execute Query :- {}.", queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getResultList();
    }

    public List<Object[]> executeQuery(String queryStr, Pageable paging) {
        logger.info("Execute Query :- {}.", queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        if (paging != null) {
            query.setFirstResult(paging.getPageNumber() * paging.getPageSize());
            query.setMaxResults(paging.getPageSize());
        }
        return query.getResultList();
    }

    public ItemResponse executeQueryResponse(String queryString) {
        logger.info("Execute Query :- {}. ", queryString);
        Query query = this._em.createNativeQuery(queryString);
        NativeQueryImpl nativeQuery = (NativeQueryImpl) query;
        nativeQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE);
        List<Map<String,Object>> result = nativeQuery.getResultList();
        ItemResponse itemResponse=new ItemResponse();
        if (result != null && !result.isEmpty()) {
            itemResponse.setQuery(queryString);
            itemResponse.setData(result);
            itemResponse.setColumn(result.get(0).keySet());
        }
        return itemResponse;
    }

    public String listSourceTaskQuery(boolean isCount, String startDate, String endDate, String columnName,
        String order, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result\n";
        } else {
            selectPortion = "select st.task_detail_id, st.task_name, st.task_payload, ld1.lookup_type as home_page_id, " +
                "ld2.lookup_type as pipeline_id, ld3.lookup_type as group_id, st.task_status, stt.source_task_type_id, stt.service_name, " +
                "stt.description, stt.queue_topic_partition, stt.task_type_status, stt.kafka_connection_profile_id, " +
                "st.bucket, st.input_folder, st.output_folder, " +
                "count(sj.job_id) as total_link_jobs\n";
        }
        String query = selectPortion + " from source_task st inner join source_task_type stt on stt.source_task_type_id = st.source_task_type_id\n";
        if (!isCount) {
            query += "left join source_job sj on sj.task_detail_id = st.task_detail_id and sj.job_status in ('Active', 'Inactive')\n";
            query += "left join lookup_data ld1 on cast(ld1.lookup_id as varchar(10)) = st.home_page_id\n";
            query += "left join lookup_data ld2 on cast(ld2.lookup_id as varchar(10)) = st.pipeline_id\n";
            query += "left join lookup_data ld3 on cast(ld3.lookup_id as varchar(10)) = st.group_id\n";
        }
        query += "where st.task_status in ('Active', 'Inactive') " + this.tenantClause("st");
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and cast(st.date_created as date) between '%s' and '%s' ",
                    this.requireValidDate(startDate), this.requireValidDate(endDate));
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and cast(st.date_created as date) >= '%s' ", this.requireValidDate(startDate));
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and cast(st.date_created as date) <= '%s' ", this.requireValidDate(endDate));
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
            query += "\ngroup by st.task_detail_id, stt.source_task_type_id, ld1.lookup_id, ld2.lookup_id, ld3.lookup_id\n";
            if (order != null && columnName != null) {
                query += String.format("order by %s %s ", this.sanitizeSortColumn(columnName), this.sanitizeSortOrder(order));
            }
        }
        return query;
    }

    public String fetchAllLinkJobsWithSourceTaskQuery(boolean isCount, Long taskDetailId,
        String startDate, String endDate, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result ";
        } else {

            selectPortion = "select sj.job_id, sj.job_name, sj.job_status, sj.execution, sj.job_running_status, " +
                "to_char(sj.last_job_run, 'YYYY-MM-DD HH24:MI:SS'), sj.priority, cast(sj.date_created AS varchar) ";
        }
        String query = selectPortion + "from source_task st inner join source_job sj on sj.task_detail_id = st.task_detail_id ";
        query += "where st.task_status in ('Active', 'Inactive') and sj.job_status in ('Active', 'Inactive') " + this.tenantClause("sj");
        if (taskDetailId != null) {
            query += String.format(" and st.task_detail_id = %d ", taskDetailId);
        }
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and cast(sj.date_created as date) between '%s' and '%s' ",
                    this.requireValidDate(startDate), this.requireValidDate(endDate));
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and cast(sj.date_created as date) >= '%s' ", this.requireValidDate(startDate));
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and cast(sj.date_created as date) <= '%s' ", this.requireValidDate(endDate));
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
                query += "and cast(sj.date_created as varchar) like ('%" + itemValue + "%') ";
            }
        }
        return query;
    }

    private String dateRangeFilter(String column, String startDate, String endDate) {
        if (!isValidDate(startDate) || !isValidDate(endDate)) {
            return "";
        }
        return String.format("and date(%s) between '%s' and '%s' ", column, startDate, endDate);
    }

    private boolean isValidDate(String date) {
        return date != null && date.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private String requireValidDate(String date) {
        if (!this.isValidDate(date)) {
            throw new IllegalArgumentException("Invalid date -- expected yyyy-MM-dd.");
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

    private String sanitizeSortOrder(String order) {
        return "asc".equalsIgnoreCase(order) ? "asc" : "desc";
    }

    private static final Set<String> JOB_QUEUE_STATUSES = new HashSet<>(Arrays.asList(
        "QUEUE", "START", "RUNNING", "FAILED", "COMPLETED", "STOP", "SKIP", "INTERRUPT", "MISSED"
    ));

    private String sanitizeJobStatus(String jobStatus) {
        if (jobStatus != null && JOB_QUEUE_STATUSES.contains(jobStatus.toUpperCase())) {
            return jobStatus.toUpperCase();
        }
        throw new IllegalArgumentException("Invalid jobStatus -- expected one of " + JOB_QUEUE_STATUSES);
    }

    private String tenantClause(String tableAlias) {
        if (TenantContext.isPlatformAdmin() || ProcessUtil.isNull(TenantContext.getTenantId())) {
            return "";
        }
        return String.format(" and %s.tenant_id = %d ", tableAlias, TenantContext.getTenantId());
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
     * Per-user totals, for "who owns what and how is it going".
     *
     * Jobs carry assigned_user_id so they attribute directly. Tasks do not carry an owner at
     * all, so a user's task count is the distinct tasks their jobs point at rather than
     * anything they are recorded as owning -- which is the honest reading of the schema.
     *
     * The left joins matter: a user with no jobs still belongs in the answer, at zero. An
     * inner join would quietly drop everyone who has not been given work yet, which is
     * exactly the group this is most often opened to find.
     */
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

        String dateFilter = this.dateRangeFilter("q.start_time", startDate, endDate);
        return "select coalesce(st.task_name, '(no task)') as task, "
            + "q.job_status as status, "
            + "coalesce(u.full_name, u.username, 'Unassigned') as owner, "
            + "to_char(q.start_time, 'YYYY-MM-DD') as day, "
            + "case when q.end_time is null then -1 "
            + "else round(extract(epoch from (q.end_time - q.start_time))) end as seconds, "
            + "sj.job_name as job, q.job_queue_id as run_id "
            + "from job_queue q "
            + "join source_job sj on sj.job_id = q.job_id "
            + "left join source_task st on st.task_detail_id = sj.task_detail_id "
            + "left join app_user u on u.app_user_id = sj.assigned_user_id "
            // A deleted job's runs are not history any more, and every other statistic here
            // already leaves them out -- a report that counted them would disagree with the
            // dashboard beside it, on the same data.
            + "where q.start_time is not null and upper(sj.job_status) <> 'DELETE' "
            + dateFilter + this.tenantClause("sj")
            + "order by q.job_queue_id desc";
    }

    public String userStatistics(String startDate, String endDate) {

        String dateFilter = this.dateRangeFilter("jq.date_created", startDate, endDate);
        return "select u.app_user_id, u.username, u.full_name, u.user_role, u.status, "
            + "u.avatar_bucket, u.avatar_key, "
            + "count(distinct sj.job_id) as job_count, "
            + "count(distinct sj.job_id) filter (where sj.job_status = 'Active') as active_jobs, "
            + "count(distinct sj.task_detail_id) as task_count, "
            + "count(jq.job_queue_id) as run_count, "
            + "count(jq.job_queue_id) filter (where jq.job_status = 'Completed') as completed_count, "
            + "count(jq.job_queue_id) filter (where jq.job_status = 'Failed') as failed_count "
            + "from app_user u "
            + "left join source_job sj on sj.assigned_user_id = u.app_user_id "
            + "and sj.job_status in ('Active','Inactive') "
            + "left join job_queue jq on jq.job_id = sj.job_id " + dateFilter
            + "where u.status in ('Active','Inactive') " + this.tenantClause("u")
            + "group by u.app_user_id, u.username, u.full_name, u.user_role, u.status, "
            + "u.avatar_bucket, u.avatar_key "
            + "order by job_count desc, u.full_name asc";
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
            "select job_queue_id, to_char(cast(jq.date_created as date), 'Dy') as daycode,\n" +
            "cast(jq.date_created as date) as runDate\n" +
            "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id where date(jq.date_created) between '%s' and '%s' and UPPER(sj.job_status) in ('ACTIVE','INACTIVE')" +
            this.tenantClause("sj") + ") as weekData\n" +
            "group by weekData.runDate, weekData.daycode\n" +
            "order by weekData.runDate", this.requireValidDate(startDate), this.requireValidDate(endDate));
    }

    public String weeklyHrsRunningJobStatistics(String startDate, String endDate) {

        return String.format("select weekData.daycode, weekData.hr, weekData.date, count(*)\n" +
            "from (select job_queue_id, to_char(cast(jq.date_created as date), 'Day') as daycode,\n" +
            "cast(jq.date_created as date) as date, cast(jq.date_created as time) as time, \n" +
            "extract(hour from cast(jq.date_created as time)) as hr\n" +
            "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id where date(jq.date_created) between '%s' and '%s' and UPPER(sj.job_status) in ('ACTIVE','INACTIVE')" +
            this.tenantClause("sj") + ") as weekData\n" +
            "group by weekData.daycode, weekData.hr, weekData.date", this.requireValidDate(startDate), this.requireValidDate(endDate));
    }

    public String weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) {

        targetDate = this.requireValidDate(targetDate);
        String tenantFilter = this.tenantClause("source_job");
        return String.format(
            "SELECT * FROM (\n" +
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
                "        COUNT(*) AS total\n" +
                "    FROM job_queue\n" +
                "    INNER JOIN source_job ON source_job.job_id = job_queue.job_id\n" +
                "    WHERE DATE(job_queue.date_created) = '%s'\n" +
                "      AND EXTRACT(HOUR FROM job_queue.date_created) = %d\n" +
                "      AND UPPER(source_job.job_status) IN ('ACTIVE','INACTIVE')\n" +
                "      " + tenantFilter + "\n" +
                "    GROUP BY job_queue.job_id, source_job.job_name\n" +
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
                "        COUNT(*)\n" +
                "    FROM job_queue\n" +
                "    INNER JOIN source_job ON source_job.job_id = job_queue.job_id\n" +
                "    WHERE DATE(job_queue.date_created) = '%s'\n" +
                "      AND EXTRACT(HOUR FROM job_queue.date_created) = %d\n" +
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
        String query = "select job_queue.* from job_queue\n" +
                "inner join source_job on source_job.job_id = job_queue.job_id where 1=1\n" +
                this.tenantClause("source_job") + "\n";
        if (!ProcessUtil.isNull(targetDate)) {
            query += String.format(" and date(job_queue.date_created) = '%s' \n", this.requireValidDate(targetDate));
        }
        if (!ProcessUtil.isNull(targetHr)) {
            query += String.format(" and extract(hour from cast(job_queue.date_created as time)) = %d\n", targetHr);
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
            query += String.format("where cast(jq.date_created as date) between '%s' and '%s' \n",
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
