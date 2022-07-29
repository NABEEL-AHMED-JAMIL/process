package process.model.service.impl;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.model.dto.SearchTextDto;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.transaction.Transactional;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class QueryService {

    private Logger logger = LoggerFactory.getLogger(QueryService.class);

    @Autowired
    private EntityManager _em;

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

    public Object executeQueryForSingleResult(String queryStr) {
        logger.info("Execute Query :- " + queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getSingleResult();
    }

    /**
     * Query:- query help to fetch the source task
     * */
    public String listSourceTaskQuery(boolean isCount, Long appUserId, String startDate, String endDate,
        String columnName, String order, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result ";
        } else {
            selectPortion = "select td.task_detail_id, td.task_name, td.task_payload, td.task_status, stt.*, count(sj.job_id) as total_link_jobs ";
        }
        String query = selectPortion + " from task_detail td inner join source_task_type stt on stt.source_task_type_id = td.source_task_type_id ";
        if (!isCount) {
            query += "left join source_job sj on sj.task_detail_id = td.task_detail_id ";
        }
        query += "where td.task_status in ('Delete', 'Inactive', 'Active') ";
        if (appUserId != null) {
            query += String.format(" and td.created_by_id = %d ", appUserId);
        }
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and cast(td.data_created as date) between '%s' and '%s' ", startDate, endDate);
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and cast(td.data_created as date) >= '%s' ", startDate);
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and cast(td.data_created as date) <= '%s' ", endDate);
            }
        }
        if (searchTextDto != null && (searchTextDto.getItemName() != null && searchTextDto.getItemValue() != null)) {
            if (searchTextDto.getItemName().equalsIgnoreCase("task_detail_id")) {
                query += "and cast(td.task_detail_id as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("task_name")) {
                query += "and upper(td.task_name) like upper('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("task_status")) {
                query += "and cast(td.task_status as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("source_task_type_id")) {
                query += "and cast(stt.source_task_type_id as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("service_name")) {
                query += "and upper(stt.service_name) like upper('%" + searchTextDto.getItemValue() + "%') ";
            }
        }
        if (!isCount) {
            query += "group by td.task_detail_id, stt.source_task_type_id ";
            if (order != null && columnName != null) {
                query += String.format("order by %s %s ", columnName, order);
            }
        }
        return query;
    }

    public String countAllLinkJobsWithSourceTaskQuery(Long taskDetailId) {
        String selectPortion = "select count(sj.job_id) ";
        String query = selectPortion + "from task_detail td inner join source_job sj on sj.task_detail_id = td.task_detail_id ";
        if (taskDetailId != null) {
            query += String.format("where td.task_detail_id = %d ", taskDetailId);
        }
        return query;
    }

    /**
     * Query:- query help to fetch the jobs link with task
     * job_running_status
     * job_status
     * last_job_run
     * priority
     * */
    public String fetchAllLinkJobsWithSourceTaskQuery(boolean isCount, Long taskDetailId, String startDate,
        String endDate, SearchTextDto searchTextDto) {
        String selectPortion = "";
        if (isCount) {
            selectPortion = "select count(*) as result ";
        } else {
            selectPortion = "select sj.job_id, sj.job_name, sj.job_status, sj.execution, sj.job_running_status, sj.last_job_run, sj.priority, sj.data_created ";
        }
        String query = selectPortion + "from task_detail td inner join source_job sj on sj.task_detail_id = td.task_detail_id ";
        query += "where td.task_status in ('Delete', 'Inactive', 'Active') ";
        if (taskDetailId != null) {
            query += String.format(" and td.task_detail_id = %d ", taskDetailId);
        }
        if ((startDate != null && !startDate.isEmpty()) || (endDate != null && !endDate.isEmpty())) {
            if ((startDate != null && !startDate.isEmpty()) && (endDate != null && !endDate.isEmpty())) {
                query += String.format("and cast(sj.data_created as date) between '%s' and '%s' ", startDate, endDate);
            } else if (startDate != null && !startDate.isEmpty()) {
                query += String.format("and cast(sj.data_created as date) >= '%s' ", startDate);
            } else if (endDate != null && !endDate.isEmpty()) {
                query += String.format("and cast(sj.data_created as date) <= '%s' ", endDate);
            }
        }
        if (searchTextDto != null && (searchTextDto.getItemName() != null && searchTextDto.getItemValue() != null)) {
            if (searchTextDto.getItemName().equalsIgnoreCase("job_id")) {
                query += "and cast(sj.job_id as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("job_name")) {
                query += "and upper(job_name) like upper('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("job_status")) {
                query += "and cast(sj.job_status as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            } else if (searchTextDto.getItemName().equalsIgnoreCase("data_created")) {
                query += "and cast(sj.data_created as varchar) like ('%" + searchTextDto.getItemValue() + "%') ";
            }
        }
        return query;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
