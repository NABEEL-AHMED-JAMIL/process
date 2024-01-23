package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.payload.request.QueryRequest;
import process.model.payload.response.AppResponse;
import process.model.service.SettingApiService;
import javax.transaction.Transactional;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class SettingApiServiceImpl implements SettingApiService {

    private Logger logger = LoggerFactory.getLogger(SettingApiServiceImpl.class);

    @Autowired
    private QueryService queryService;

    @Override
    public AppResponse dynamicQueryResponse(QueryRequest payload) {
        if (isNull(payload.getQuery())) {
            return new AppResponse(ERROR, "Query missing.");
        }
        payload.setQuery(payload.getQuery().trim());
        if (!payload.getQuery().toLowerCase().startsWith("select")) {
            return new AppResponse(ERROR, "Only select query execute.");
        }
        return new AppResponse(SUCCESS, "Data fetch successfully.",
            this.queryService.executeQueryResponse(payload.getQuery()));
    }


}
