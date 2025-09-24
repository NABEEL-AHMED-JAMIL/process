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
    public String listSourceTaskQuery(boolean isCount, String startDate, String endDate, String columnName, String order, SearchTextDto searchTextDto) {
        // SELECT portion
        String selectPortion = isCount ? "SELECT COUNT(*) AS result\n" : "SELECT st.task_detail_id, st.task_name, st.task_payload, "
            + "ld1.lookup_type AS home_page_id, ld2.lookup_type AS pipeline_id, " + "st.task_status, stt.*, COUNT(sj.job_id) AS total_link_jobs\n";
        // FROM and JOINs
        StringBuilder query = new StringBuilder();
        query.append(selectPortion).append("FROM source_task st\n").append("INNER JOIN source_task_type stt ON stt.source_task_type_id = st.source_task_type_id\n");
        if (!isCount) {
            query.append("LEFT JOIN source_job sj ON sj.task_detail_id = st.task_detail_id\n")
                .append("LEFT JOIN lookup_data ld1 ON CAST(ld1.lookup_id AS VARCHAR(10)) = st.home_page_id\n")
                .append("LEFT JOIN lookup_data ld2 ON CAST(ld2.lookup_id AS VARCHAR(10)) = st.pipeline_id\n");
        }
        // WHERE conditions
        query.append("WHERE 1=1\n");
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
                query.append(String.format("AND CAST(st.date_created AS DATE) BETWEEN '%s' AND '%s'\n", startDate, endDate));
            } else if (startDate != null && !startDate.isEmpty()) {
                query.append(String.format("AND CAST(st.date_created AS DATE) >= '%s'\n", startDate));
            } else {
                query.append(String.format("AND CAST(st.date_created AS DATE) <= '%s'\n", endDate));
            }
        }
        // Search filters
        if (searchTextDto != null && searchTextDto.getItemName() != null && searchTextDto.getItemValue() != null) {
            String name = searchTextDto.getItemName().toLowerCase();
            String value = (String) searchTextDto.getItemValue();
            switch (name) {
                case "task_detail_id":
                    query.append("AND CAST(st.task_detail_id AS VARCHAR) LIKE '%").append(value).append("%'\n");
                    break;
                case "task_name":
                    query.append("AND UPPER(st.task_name) LIKE UPPER('%").append(value).append("%')\n");
                    break;
                case "task_status":
                    query.append("AND CAST(st.task_status AS VARCHAR) LIKE '%").append(value).append("%'\n");
                    break;
                case "source_task_type_id":
                    query.append("AND CAST(stt.source_task_type_id AS VARCHAR) LIKE '%").append(value).append("%'\n");
                    break;
                case "service_name":
                    query.append("AND UPPER(stt.service_name) LIKE UPPER('%").append(value).append("%')\n");
                    break;
                default:
                    break;
            }
        }
        // GROUP BY and ORDER BY
        if (!isCount) {
            query.append("GROUP BY st.task_detail_id, stt.source_task_type_id, ld1.lookup_id, ld2.lookup_id\n");
            if (order != null && columnName != null) {
                query.append(String.format("ORDER BY %s %s\n", columnName, order));
            }
        }
        return query.toString();
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
    public String fetchAllLinkJobsWithSourceTaskQuery(boolean isCount, Long taskDetailId, String startDate, String endDate, SearchTextDto searchTextDto) {
        StringBuilder query = new StringBuilder();
        if (isCount) {
            query.append("SELECT COUNT(*) AS result ");
        } else {
            query.append("SELECT sj.job_id, sj.job_name, sj.job_status, sj.execution, sj.job_running_status, ")
                .append("CAST(sj.last_job_run AS varchar), sj.priority, CAST(sj.date_created AS varchar) ");
        }
        query.append("FROM source_task st ")
            .append("INNER JOIN source_job sj ON sj.task_detail_id = st.task_detail_id ")
            .append("WHERE st.task_status IN ('Delete', 'Inactive', 'Active') ");
        if (!ProcessUtil.isNull(taskDetailId)) {
            query.append("AND st.task_detail_id = ").append(taskDetailId).append(" ");
        }
        if (!ProcessUtil.isNull(startDate) && !startDate.isEmpty() && !ProcessUtil.isNull(endDate) && !endDate.isEmpty()) {
            query.append("AND sj.date_created BETWEEN '").append(startDate).append("' AND '").append(endDate).append("' ");
        } else if (!ProcessUtil.isNull(startDate) && !startDate.isEmpty()) {
            query.append("AND sj.date_created >= '").append(startDate).append("' ");
        } else if (!ProcessUtil.isNull(endDate) && !endDate.isEmpty()) {
            query.append("AND sj.date_created <= '").append(endDate).append("' ");
        }
        if (!ProcessUtil.isNull(searchTextDto) && !ProcessUtil.isNull(searchTextDto.getItemName()) && !ProcessUtil.isNull(searchTextDto.getItemValue())) {
            String value = (String) searchTextDto.getItemValue();
            switch (searchTextDto.getItemName().toLowerCase()) {
                case "job_id":
                    query.append("AND CAST(sj.job_id AS varchar) LIKE '%").append(value).append("%' ");
                    break;
                case "job_name":
                    query.append("AND UPPER(sj.job_name) LIKE UPPER('%").append(value).append("%') ");
                    break;
                case "job_status":
                    query.append("AND sj.job_status LIKE '%").append(value).append("%' ");
                    break;
                case "date_created":
                    query.append("AND CAST(sj.date_created AS varchar) LIKE '%").append(value).append("%' ");
                    break;
            }
        }
        return query.toString();
    }


    /**
     * method use to fetch the job status statistics
     * @return string
     * */
    public String jobStatusStatistics() {
        return "SELECT job_status, COUNT(*) AS total_count " +
            "FROM source_job " +
            "GROUP BY job_status " +
            "ORDER BY job_status";
    }

    /**
     * method use to fetch the job running statistics
     * @return string
     * */
    public String jobRunningStatistics() {
        return "SELECT job_running_status, COUNT(*) AS total_count " +
            "FROM source_job " +
            "WHERE job_running_status IN ('Queue', 'Start', 'Running', 'Completed') " +
            "GROUP BY job_running_status " +
            "ORDER BY job_running_status";
    }

    /**
     * method use to fetch the job running statistics
     * @param startDate
     * @param endDate
     * @return string
     * */
    public String weeklyRunningJobStatistics(String startDate, String endDate) {
        return String.format(
            "SELECT TRIM(TO_CHAR(date_created, 'Dy')) AS daycode, " +
                "COUNT(*) AS total " +
                "FROM job_queue " +
                "WHERE date_created >= '%s' AND date_created < '%s' " +
                "GROUP BY daycode " +
                "ORDER BY MIN(date_created)", startDate, endDate
        );
    }


    /**
     * method use to fetch the job running statistics
     * @param startDate
     * @param endDate
     * @return string
     * */
    public String weeklyHrsRunningJobStatistics(String startDate, String endDate) {
        return String.format(
            "SELECT " +
                "TRIM(TO_CHAR(date_created, 'Day')) AS daycode, " +
                "EXTRACT(HOUR FROM date_created) AS hr, " +
                "CAST(date_created AS DATE) AS date, " +
                "COUNT(*) AS total " +
                "FROM job_queue " +
                "WHERE date_created >= '%s' AND date_created < '%s' " +
                "GROUP BY daycode, hr, date " +
                "ORDER BY date, hr", startDate, endDate
        );
    }


    /**
     * method use to fetch the view running job statistics
     * @param targetDate
     * @param targetHr
     * @return string
     * */
    public String weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) {
        return String.format(
            "WITH job_stats AS (\n" +
                "    SELECT job_queue.job_id, source_job.job_name, job_queue.job_status\n" +
                "    FROM job_queue\n" +
                "    INNER JOIN source_job ON source_job.job_id = job_queue.job_id\n" +
                "    WHERE date(job_queue.date_created) = '%s' AND extract(hour from job_queue.date_created) = %d\n" +
                ")\n" +
                "SELECT job_id, job_name,\n" +
                "    COUNT(CASE WHEN job_status = 'Queue' THEN 1 END) AS Queue,\n" +
                "    COUNT(CASE WHEN job_status = 'Start' THEN 1 END) AS Start,\n" +
                "    COUNT(CASE WHEN job_status = 'Running' THEN 1 END) AS Running,\n" +
                "    COUNT(CASE WHEN job_status = 'Failed' THEN 1 END) AS Failed,\n" +
                "    COUNT(CASE WHEN job_status = 'Completed' THEN 1 END) AS Completed,\n" +
                "    COUNT(CASE WHEN job_status = 'Stop' THEN 1 END) AS Stop,\n" +
                "    COUNT(CASE WHEN job_status = 'Skip' THEN 1 END) AS Skip,\n" +
                "    COUNT(CASE WHEN job_status = 'Interrupt' THEN 1 END) AS Interrupt,\n" +
                "    COUNT(*) AS total\n" +
                "FROM job_stats\n" +
                "GROUP BY job_id, job_name\n" +
                "UNION ALL\n" +
                "SELECT NULL, NULL,\n" +
                "    COUNT(CASE WHEN job_status = 'Queue' THEN 1 END),\n" +
                "    COUNT(CASE WHEN job_status = 'Start' THEN 1 END),\n" +
                "    COUNT(CASE WHEN job_status = 'Running' THEN 1 END),\n" +
                "    COUNT(CASE WHEN job_status = 'Failed' THEN 1 END),\n" +
                "    COUNT(CASE WHEN job_status = 'Completed' THEN 1 END),\n" +
                "    COUNT(CASE WHEN job_status = 'Stop' THEN 1 END),\n" +
                "    COUNT(CASE WHEN job_status = 'Skip' THEN 1 END),\n" +
                "    COUNT(CASE WHEN job_status = 'Interrupt' THEN 1 END),\n" +
                "    COUNT(*)\n" +
                "FROM job_stats\n ORDER BY job_id ASC", targetDate, targetHr
        );
    }

    /**
     * method use to fetch the job statistics by id
     * @param jobId
     * @return string
     * */
    public String statisticsBySourceJobId(Long jobId) {
        return String.format(
            "SELECT " +
                "COUNT(CASE WHEN job_queue.job_status = 'Queue' THEN job_queue.job_id END) AS Queue, " +
                "COUNT(CASE WHEN job_queue.job_status = 'Start' THEN job_queue.job_id END) AS Start, " +
                "COUNT(CASE WHEN job_queue.job_status = 'Running' THEN job_queue.job_id END) AS Running, " +
                "COUNT(CASE WHEN job_queue.job_status = 'Failed' THEN job_queue.job_id END) AS Failed, " +
                "COUNT(CASE WHEN job_queue.job_status = 'Completed' THEN job_queue.job_id END) AS Completed, " +
                "COUNT(CASE WHEN job_queue.job_status = 'Stop' THEN job_queue.job_id END) AS Stop, " +
                "COUNT(CASE WHEN job_queue.job_status = 'Skip' THEN job_queue.job_id END) AS Skip, " +
                "COUNT(CASE WHEN job_queue.job_status = 'Interrupt' THEN job_queue.job_id END) AS Interrupt, " +
                "COUNT(*) AS total " +
                "FROM job_queue " +
                "INNER JOIN source_job ON source_job.job_id = job_queue.job_id " +
                "WHERE source_job.job_id = %d", jobId
        );
    }

    /**
     * Method use to fetch the weekly hr running statistics  dimension detail
     * @param targetDate
     * @param targetHr
     * @param jobStatus
     * @param jobId
     * @return String
     * */
    public String weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) {
        StringBuilder query = new StringBuilder("SELECT job_queue.* FROM job_queue " +
            "INNER JOIN source_job ON source_job.job_id = job_queue.job_id WHERE 1=1 ");
        if (!ProcessUtil.isNull(targetDate)) {
            query.append(String.format("AND DATE(job_queue.date_created) = '%s' ", targetDate));
        }
        if (!ProcessUtil.isNull(targetHr)) {
            query.append(String.format("AND EXTRACT(HOUR FROM job_queue.date_created) = %d ", targetHr));
        }
        if (!ProcessUtil.isNull(jobId)) {
            query.append(String.format("AND job_queue.job_id = %d ", jobId));
        }
        if (!ProcessUtil.isNull(jobStatus)) {
            query.append(String.format("AND job_queue.job_status = '%s' ", jobStatus));
        }
        query.append("ORDER BY job_queue.job_queue_id DESC");
        return query.toString();
    }


    /**
     * Method use to fetch the job queue logs
     * @param messageQSearch
     * @param isState
     * @return String
     * */
    public String fetchJobQLog(MessageQSearchDto messageQSearch, boolean isState) {
        StringBuilder query = new StringBuilder();
        if (isState) {
            query.append("SELECT job_status, COUNT(*) AS total_count ");
        } else {
            query.append("SELECT * ");
        }
        query.append("FROM job_queue ");
        if (!isState) {
            query.append("WHERE date_created BETWEEN '").append(messageQSearch.getFromDate()).append("' AND '").append(messageQSearch.getToDate()).append("' ");
            if (!ProcessUtil.isNull(messageQSearch.getJobId()) && !messageQSearch.getJobId().isEmpty()) {
                String jobIds = messageQSearch.getJobId().stream().map(Object::toString).collect(Collectors.joining(","));
                query.append("AND job_id IN (").append(jobIds).append(") ");
            }
            if (!ProcessUtil.isNull(messageQSearch.getJobQId()) && !messageQSearch.getJobQId().isEmpty()) {
                String jobQIds = messageQSearch.getJobQId().stream().map(Object::toString).collect(Collectors.joining(","));
                query.append("AND job_queue_id IN (").append(jobQIds).append(") ");
            }
            if (!ProcessUtil.isNull(messageQSearch.getJobStatuses()) && !messageQSearch.getJobStatuses().isEmpty()) {
                String jobStatuses = messageQSearch.getJobStatuses().stream().map(status -> "'" + status + "'").collect(Collectors.joining(","));
                query.append("AND job_status IN (").append(jobStatuses).append(") ");
            }
        }
        // state
        if (isState) {
            query.append("GROUP BY job_status");
        } else {
            query.append("ORDER BY job_queue_id DESC");
        }
        return query.toString();
    }


    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
