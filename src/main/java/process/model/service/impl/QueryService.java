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
import javax.persistence.*;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class QueryService {

    private Logger logger = LoggerFactory.getLogger(QueryService.class);

    @PersistenceContext
    private EntityManager _em;

    public Object executeQueryForSingleResult(String queryStr) {
        logger.info("Execute Query :- " + queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getSingleResult();
    }

    public List<Object[]> executeQuery(String queryStr) {
        logger.info("Execute Query :- " + queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getResultList();
    }

    public List<Object[]> executeQuery(String queryStr, Pageable paging) {
        logger.info("Execute Query :- " + queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        if (paging != null) {
            query.setFirstResult(paging.getPageNumber() * paging.getPageSize());
            query.setMaxResults(paging.getPageSize());
        }
        return query.getResultList();
    }

    public ItemResponse executeQueryResponse(String queryString) {
        logger.info("Execute Query :- " + queryString);
        Query query = this._em.createNativeQuery(queryString);
        NativeQueryImpl nativeQuery = (NativeQueryImpl) query;
        nativeQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE);
        List<Map<String,Object>> result = nativeQuery.getResultList();
        ItemResponse itemResponse=new ItemResponse();
        if (result != null && result.size() > 0) {
            itemResponse.setQuery(queryString);
            itemResponse.setData(result);
            itemResponse.setColumn(result.get(0).keySet());
        }
        return itemResponse;
    }

    /**
     * Query:- query help to fetch the source task
     * @param isCount
     * @param appUserId
     * @param startDate
     * @param endDate
     * @param columnName
     * @param order
     * @param searchTextDto
     * @return string
     * */
    public String listSourceTaskQuery(boolean isCount, Long appUserId, String startDate, String endDate, String columnName,
        String order, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result ";
        } else {
            selectPortion = "select st.task_detail_id, st.task_name, st.task_payload, st.task_home_page, st.pipeline_id, st.task_status, stt.*, count(sj.job_id) as total_link_jobs ";
        }
        String query = selectPortion + " from source_task st inner join source_task_type stt on stt.source_task_type_id = st.source_task_type_id ";
        if (!isCount) {
            query += "left join source_job sj on sj.task_detail_id = st.task_detail_id ";
        }
        query += "where st.task_status in ('Delete', 'Inactive', 'Active') ";
        if (appUserId != null) {
            query += String.format(" and st.created_by_id = %d ", appUserId);
        }
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
            query += "group by st.task_detail_id, stt.source_task_type_id ";
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
            selectPortion = "select sj.job_id, sj.job_name, sj.job_status, sj.execution, sj.job_running_status, CAST(sj.last_job_run AS varchar), sj.priority, CAST(sj.date_created AS varchar) ";
        }
        String query = selectPortion + "from source_task st inner join source_job sj on sj.task_detail_id = st.task_detail_id ";
        query += "where st.task_status in ('Delete', 'Inactive', 'Active') ";
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
        return "select job_status, count(job_id) as total_count from source_job group by job_status";
    }

    /**
     * method use to fetch the job running statistics
     * @return string
     * */
    public String jobRunningStatistics() {
        return "select job_running_status, count(job_id) as total_count\n" +
            "from source_job\n" +
            "where job_running_status in ('Running', 'Failed', 'Completed')\n" +
            "group by job_running_status";
    }

    /**
     * method use to fetch the job running statistics
     * @param startDate
     * @param endDate
     * @return string
     * */
    public String weeklyRunningJobStatistics(String startDate, String endDate) {
        return String.format("select weekData.daycode, count(*) from (\n" +
            "select job_queue_id, to_char(cast(date_created as date), 'Dy') as daycode,\n" +
            "cast(date_created as date)\n" +
            "from job_queue where date(date_created) between '%s' and '%s') as weekData\n" +
            "group by weekData.daycode", startDate, endDate);
    }

    /**
     * method use to fetch the job running statistics
     * @param startDate
     * @param endDate
     * @return string
     * */
    public String weeklyHrsRunningJobStatistics(String startDate, String endDate) {
        return String.format("select weekData.daycode, weekData.hr, weekData.date, count(*)\n" +
            "from (select job_queue_id, to_char(cast(date_created as date), 'Day') as daycode,\n" +
            "cast(date_created as date) as date, cast(date_created as time) as time, \n" +
            "extract(hour from cast(date_created as time)) as hr\n" +
            "from job_queue where date(date_created) between '%s' and '%s') as weekData\n" +
            "group by weekData.daycode, weekData.hr, weekData.date", startDate, endDate);
    }

    /**
     * method use to fetch the view running job statistics
     * @param targetDate
     * @param targetHr
     * @return string
     * */
    public String weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) {
        return String.format("select job_queue.job_id, source_job.job_name,\n" +
            "count (case when job_queue.job_status = 'Queue' then job_queue.job_id end) as Queue,\n" +
            "count (case when job_queue.job_status = 'Running' then job_queue.job_id end) as Running,\n" +
            "count (case when job_queue.job_status = 'Failed' then job_queue.job_id end) as Failed,\n" +
            "count (case when job_queue.job_status = 'Completed' then job_queue.job_id end) as Completed,\n" +
            "count (case when job_queue.job_status = 'Stop' then job_queue.job_id end) as Stop,\n" +
            "count (case when job_queue.job_status = 'Skip' then job_queue.job_id end) as Skip,\n" +
            "count (*) as total\n" +
            "from job_queue\n" +
            "inner join source_job on source_job.job_id = job_queue.job_id\n" +
            "where date(job_queue.date_created) = '%s' and extract(hour from cast(job_queue.date_created as time)) = %d\n" +
            "group by job_queue.job_id, source_job.job_name\n" +
            "union\n" +
            "select null as job_id, null as job_name,\n" +
            "count (case when job_queue.job_status = 'Queue' then job_queue.job_id end) as Queue,\n" +
            "count (case when job_queue.job_status = 'Running' then job_queue.job_id end) as Running,\n" +
            "count (case when job_queue.job_status = 'Failed' then job_queue.job_id end) as Failed,\n" +
            "count (case when job_queue.job_status = 'Completed' then job_queue.job_id end) as Completed,\n" +
            "count (case when job_queue.job_status = 'Stop' then job_queue.job_id end) as Stop,\n" +
            "count (case when job_queue.job_status = 'Skip' then job_queue.job_id end) as Skip,\n" +
            "count (*) as total\n" +
            "from job_queue\n" +
            "inner join source_job on source_job.job_id = job_queue.job_id\n" +
            "where date(job_queue.date_created) = '%s' and extract(hour from cast(job_queue.date_created as time)) = %d\n" +
            "order by job_id asc\n", targetDate, targetHr, targetDate, targetHr);
    }

    /**
     * method use to fetch the job statistics by id
     * @param jobId
     * @return string
     * */
    public String statisticsBySourceJobId(Long jobId) {
        return String.format("select\n" +
            "count (case when job_queue.job_status = 'Queue' then job_queue.job_id end) as Queue,\n" +
            "count (case when job_queue.job_status = 'Running' then job_queue.job_id end) as Running,\n" +
            "count (case when job_queue.job_status = 'Failed' then job_queue.job_id end) as Failed,\n" +
            "count (case when job_queue.job_status = 'Completed' then job_queue.job_id end) as Completed,\n" +
            "count (case when job_queue.job_status = 'Stop' then job_queue.job_id end) as Stop,\n" +
            "count (case when job_queue.job_status = 'Skip' then job_queue.job_id end) as Skip,\n" +
            "count (*) as total\n" +
            "from job_queue\n" +
            "inner join source_job on source_job.job_id = job_queue.job_id\n" +
            "where source_job.job_id = %d", jobId);
    }

    public String weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) {
        String query = "select job_queue.*\n" +
            "from job_queue\n" +
            "inner join source_job on source_job.job_id = job_queue.job_id\n" +
            "where 1=1\n";
        if (!isNull(targetDate)) {
            query += String.format(" and date(job_queue.date_created) = '%s' \n", targetDate);
        }
        if (!isNull(targetHr)) {
            query += String.format(" and extract(hour from cast(job_queue.date_created as time)) = %d\n", targetHr);
        }
        if (!isNull(jobId)) {
            query += String.format("and job_queue.job_id = %d\n", jobId);
        }
        if (!isNull(jobStatus)) {
            query += String.format("and job_queue.job_status = '%s'\n", jobStatus);
        }
        query += "\norder by job_queue.job_queue_id desc";
        return query;
    }

    public String fetchJobQLog(MessageQSearchDto messageQSearch, boolean isState) {
        String selectPortion;
        if (isState) {
            selectPortion = "select job_status, count(*) as total_count \n";
        } else {
            selectPortion = "select * \n";
        }
        String query = selectPortion + "from job_queue \n";
        if (!isState) {
            query += String.format("where cast(date_created as date) between '%s' and '%s' \n",
                messageQSearch.getFromDate(), messageQSearch.getToDate());
            if (!isNull(messageQSearch.getJobId()) && messageQSearch.getJobId().size() > 0) {
                String jobId = messageQSearch.getJobId().toString();
                query += String.format("and job_id in (%s) \n", jobId.substring(1, jobId.length()-1));
            }
            if (!isNull(messageQSearch.getJobQId()) && messageQSearch.getJobQId().size() > 0) {
                String jobQId = messageQSearch.getJobQId().toString();
                query += String.format("and job_queue_id in (%s) \n", jobQId.substring(1, jobQId.length()-1));
            }
            if (!isNull(messageQSearch.getJobStatuses()) && messageQSearch.getJobStatuses().size() > 0) {
                String jobStatus = messageQSearch.getJobStatuses().stream()
                    .map(jobStatus1 -> "'" +jobStatus1.toString()+"',").collect(Collectors.joining());
                query += String.format("and job_status in (%s)", jobStatus.substring(0,jobStatus.length()-1));
            }
        }
        if (isState) {
            query += "\ngroup by job_status";
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
