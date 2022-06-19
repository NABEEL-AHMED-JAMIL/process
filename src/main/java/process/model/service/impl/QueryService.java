package process.model.service.impl;

import com.google.gson.Gson;
import org.apache.kafka.common.protocol.types.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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

    public String listSourceTaskQuery() {
        String select = "select td.task_detail_id, td.task_name, td.task_status, stt.*, count(sj.job_id) from task_detail td ";
        String join = "inner join source_task_type stt on stt.source_task_type_id = td.source_task_type_id ";
        join += "left join source_job sj on sj.task_detail_id = td.task_detail_id ";
        String group = "group by td.task_detail_id, stt.source_task_type_id ";
        String order = "order by td.task_detail_id asc";
        return select + join + group + order;
    }

    public String listSourceJobQuery() {
        return null;
    }

    public String fetchAllLinkJobsWithSourceTask(Long taskDetailId) {
        String select = "select sj.job_id, sj.job_name, job_status, data_created from task_detail td ";
        String join = "inner join source_job sj on sj.task_detail_id = td.task_detail_id ";
        String where = String.format("where td.task_detail_id = %d ", taskDetailId);
        return select + join + where;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
