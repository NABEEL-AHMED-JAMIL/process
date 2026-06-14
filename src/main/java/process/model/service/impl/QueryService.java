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
import process.util.ProcessUtil;
import javax.persistence.*;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class QueryService {

    private Logger logger = LoggerFactory.getLogger(QueryService.class);

    @PersistenceContext
    private EntityManager _em;

    /**
     * Method use to execute query response
     * @param queryStr
     * @return List<Object[]>
     * */
    public Object executeQueryForSingleResult(String queryStr) {
        logger.info("Execute Query :- {}.", queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getSingleResult();
    }

    /**
     * Method use to execute query response
     * @param queryStr
     * @return List<Object[]>
     * */
    public List<Object[]> executeQuery(String queryStr) {
        logger.info("Execute Query :- {}.", queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getResultList();
    }

    /**
     * Method use to execute query response
     * @param queryStr
     * @param paging
     * @return List<Object[]>
     * */
    public List<Object[]> executeQuery(String queryStr, Pageable paging) {
        logger.info("Execute Query :- {}.", queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        if (paging != null) {
            query.setFirstResult(paging.getPageNumber() * paging.getPageSize());
            query.setMaxResults(paging.getPageSize());
        }
        return query.getResultList();
    }

    /**
     * Method use to execute query response
     * @param queryString
     * @return ItemResponse
     * */
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

    /**
     * Query:- query help to fetch the source task
     * @param isCount
     * @param startDate
     * @param endDate
     * @param columnName
     * @param order
     * @param searchTextDto
     * @return string
     * */
    public String listSourceTaskQuery(boolean isCount, String startDate, String endDate, String columnName,
        String order, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result\n";
        } else {
            selectPortion = "select st.task_detail_id, st.task_name, st.task_payload, ld1.lookup_type as home_page_id, " +
                "ld2.lookup_type as pipeline_id, st.task_status, stt.*, count(sj.job_id) as total_link_jobs\n";
        }
        String query = selectPortion + " from source_task st inner join source_task_type stt on stt.source_task_type_id = st.source_task_type_id\n";
        if (!isCount) {
            query += "left join source_job sj on sj.task_detail_id = st.task_detail_id and sj.job_status in ('Active', 'Inactive')\n";
            query += "left join lookup_data ld1 on cast(ld1.lookup_id as varchar(10)) = st.home_page_id\n";
            query += "left join lookup_data ld2 on cast(ld2.lookup_id as varchar(10)) = st.pipeline_id\n";
        }
        query += "where st.task_status in ('Active', 'Inactive') ";
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and cast(st.date_created as date) between '%s' and '%s' ", startDate, endDate);
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and cast(st.date_created as date) >= '%s' ", startDate);
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and cast(st.date_created as date) <= '%s' ", endDate);
            }
        }
        if (searchTextDto != null && (searchTextDto.getItemName() != null && searchTextDto.getItemValue() != null)) {
            if (searchTextDto.getItemName().equalsIgnoreCase("task_detail_id")) {
                query += "and cast(st.task_detail_id as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("task_name")) {
                query += "and upper(st.task_name) like upper('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("task_status")) {
                query += "and cast(st.task_status as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("source_task_type_id")) {
                query += "and cast(stt.source_task_type_id as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("service_name")) {
                query += "and upper(stt.service_name) like upper('%" + searchTextDto.getItemValue() + "%') ";
            }
        }
        if (!isCount) {
            query += "\ngroup by st.task_detail_id, stt.source_task_type_id, ld1.lookup_id, ld2.lookup_id\n";
            if (order != null && columnName != null) {
                query += String.format("order by %s %s ", columnName, order);
            }
        }
        return query;
    }

    /**
     * Query:- query help to fetch the jobs link with task
     * @param isCount
     * @param taskDetailId
     * @param startDate
     * @param endDate
     * @param searchTextDto
     * @return string
     * */
    public String fetchAllLinkJobsWithSourceTaskQuery(boolean isCount, Long taskDetailId,
        String startDate, String endDate, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result ";
        } else {
            selectPortion = "select sj.job_id, sj.job_name, sj.job_status, sj.execution, sj.job_running_status, " +
                "cast(sj.last_job_run AS varchar), sj.priority, cast(sj.date_created AS varchar) ";
        }
        String query = selectPortion + "from source_task st inner join source_job sj on sj.task_detail_id = st.task_detail_id ";
        query += "where st.task_status in ('Active', 'Inactive') and sj.job_status in ('Active', 'Inactive') ";
        if (taskDetailId != null) {
            query += String.format(" and st.task_detail_id = %d ", taskDetailId);
        }
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and cast(sj.date_created as date) between '%s' and '%s' ", startDate, endDate);
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and cast(sj.date_created as date) >= '%s' ", startDate);
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and cast(sj.date_created as date) <= '%s' ", endDate);
            }
        }
        if (searchTextDto != null && (searchTextDto.getItemName() != null && searchTextDto.getItemValue() != null)) {
            if (searchTextDto.getItemName().equalsIgnoreCase("job_id")) {
                query += "and cast(sj.job_id as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("job_name")) {
                query += "and upper(job_name) like upper('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("job_status")) {
                query += "and cast(sj.job_status as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("date_created")) {
                query += "and cast(sj.date_created as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            }
        }
        return query;
    }

    /**
     * method use to fetch the job status statistics
     * @return string
     * */
    public String jobStatusStatistics() {
        // Return counts for Active and Inactive separately and a combined "All" (Active+Inactive) count.
        // Exclude 'Delete' status from the counts for "All".
        return "select job_status, count(job_id) as total_count from source_job\n" +
            "where job_status in ('Active','Inactive') group by job_status\n" +
            "union all\n" +
            "select 'All' as job_status, count(job_id) as total_count from source_job where job_status in ('Active','Inactive')";
    }

    /**
     * method use to fetch the job running statistics
     * @return string
     * */
    public String jobRunningStatistics() {
        // Only consider jobs which are Active or Inactive (exclude Deleted) when generating running stats
        return "select UPPER(job_running_status) as job_running_status, count(job_id) as total_count\n" +
            "from source_job\n" +
            "where UPPER(job_running_status) in ('START', 'RUNNING', 'FAILED', 'COMPLETED')\n" +
            "and UPPER(job_status) in ('ACTIVE','INACTIVE')\n" +
            "group by UPPER(job_running_status)";
    }

    /**
     * method use to fetch the job running statistics
     * @param startDate
     * @param endDate
     * @return string
     * */
    public String weeklyRunningJobStatistics(String startDate, String endDate) {
        // Only include job_queue entries for jobs that are Active or Inactive
        return String.format("select weekData.daycode, count(*) from (\n" +
            "select job_queue_id, to_char(cast(jq.date_created as date), 'Dy') as daycode,\n" +
            "cast(jq.date_created as date)\n" +
            "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id where date(jq.date_created) between '%s' and '%s' and UPPER(sj.job_status) in ('ACTIVE','INACTIVE')) as weekData\n" +
            "group by weekData.daycode", startDate, endDate);
    }

    /**
     * method use to fetch the job running statistics
     * @param startDate
     * @param endDate
     * @return string
     * */
    public String weeklyHrsRunningJobStatistics(String startDate, String endDate) {
        // Only include job_queue entries for jobs that are Active or Inactive
        return String.format("select weekData.daycode, weekData.hr, weekData.date, count(*)\n" +
            "from (select job_queue_id, to_char(cast(jq.date_created as date), 'Day') as daycode,\n" +
            "cast(jq.date_created as date) as date, cast(jq.date_created as time) as time, \n" +
            "extract(hour from cast(jq.date_created as time)) as hr\n" +
            "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id where date(jq.date_created) between '%s' and '%s' and UPPER(sj.job_status) in ('ACTIVE','INACTIVE')) as weekData\n" +
            "group by weekData.daycode, weekData.hr, weekData.date", startDate, endDate);
    }

    /**
     * method use to fetch the view running job statistics
     * @param targetDate
     * @param targetHr
     * @return string
     * */
    public String weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) {
        // Only include job_queue entries for jobs that are Active or Inactive
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
                "        COUNT(*) AS total\n" +
                "    FROM job_queue\n" +
                "    INNER JOIN source_job ON source_job.job_id = job_queue.job_id\n" +
                "    WHERE DATE(job_queue.date_created) = '%s'\n" +
                "      AND EXTRACT(HOUR FROM job_queue.date_created) = %d\n" +
                "      AND UPPER(source_job.job_status) IN ('ACTIVE','INACTIVE')\n" +
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
                "        COUNT(*)\n" +
                "    FROM job_queue\n" +
                "    INNER JOIN source_job ON source_job.job_id = job_queue.job_id\n" +
                "    WHERE DATE(job_queue.date_created) = '%s'\n" +
                "      AND EXTRACT(HOUR FROM job_queue.date_created) = %d\n" +
                "      AND UPPER(source_job.job_status) IN ('ACTIVE','INACTIVE')\n" +
                ") t\n" +
                "ORDER BY job_id ASC NULLS LAST",
                targetDate, targetHr, targetDate, targetHr
        );
    }

    /**
     * method use to fetch the job statistics by id
     * @param jobId
     * @return string
     * */
    public String statisticsBySourceJobId(Long jobId) {
        // Only include job_queue entries for jobs that are Active or Inactive
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
            "COUNT(*) AS total\n" +
            "from job_queue\n" +
            "inner join source_job on source_job.job_id = job_queue.job_id\n" +
            "where source_job.job_id = %d and UPPER(source_job.job_status) in ('ACTIVE','INACTIVE')", jobId
        );
    }

    public String weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) {
        String query = "select job_queue.* from job_queue\n" +
                "inner join source_job on source_job.job_id = job_queue.job_id where 1=1\n";
        if (!ProcessUtil.isNull(targetDate)) {
            query += String.format(" and date(job_queue.date_created) = '%s' \n", targetDate);
        }
        if (!ProcessUtil.isNull(targetHr)) {
            query += String.format(" and extract(hour from cast(job_queue.date_created as time)) = %d\n", targetHr);
        }
        if (!ProcessUtil.isNull(jobId)) {
            query += String.format("and job_queue.job_id = %d\n", jobId);
        }
        if (!ProcessUtil.isNull(jobStatus)) {
            query += String.format("and UPPER(job_queue.job_status) = UPPER('%s')\n", jobStatus);
        } else {
            // when no specific jobStatus requested, only include source jobs that are Active or Inactive
            query += "and UPPER(source_job.job_status) in ('ACTIVE','INACTIVE')\n";
        }
        query += "\norder by job_queue.job_queue_id desc";
        return query;
    }

    public String fetchJobQLog(MessageQSearchDto messageQSearch, boolean isState) {
        String selectPortion;
        if (isState) {
            // summarize by job_queue job_status (Queue/Start/Running/etc.)
            selectPortion = "select UPPER(jq.job_status) as job_status, count(*) as total_count \n";
        } else {
            // Explicitly select job_queue columns only (use jq alias) to avoid duplicate column aliases from joining source_job
            // Order must match the mapping in MessageQServiceImpl.fetchLogs
            selectPortion = "select jq.job_queue_id, jq.date_created, jq.end_time, jq.job_id, jq.job_send, jq.job_status, jq.job_status_message, jq.run_manual, jq.skip_manual, jq.skip_time, jq.start_time \n";
        }
        // always join with source_job so we can filter by source job status (Active/Inactive)
        String query = selectPortion + "from job_queue jq inner join source_job sj on sj.job_id = jq.job_id \n";
        if (!isState) {
            query += String.format("where cast(jq.date_created as date) between '%s' and '%s' \n",
                    messageQSearch.getFromDate(), messageQSearch.getToDate());
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
            // For state summary, only include job_queue rows for source jobs that are Active or Inactive
            query += "where UPPER(sj.job_status) in ('ACTIVE','INACTIVE')\n";
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
