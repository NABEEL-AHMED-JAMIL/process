package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.payload.request.QueryRequest;
import process.payload.response.AppResponse;
import process.service.SettingApiService;
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
    public AppResponse dynamicQueryResponse(QueryRequest queryRequest) {
        if (isNull(queryRequest.getQuery())) {
            return new AppResponse(ERROR, "Query missing.");
        }
        queryRequest.setQuery(queryRequest.getQuery().trim());
        if (!queryRequest.getQuery().toLowerCase().startsWith("select")) {
            return new AppResponse(ERROR, "Only select query execute.");
        }
        return new AppResponse(SUCCESS, "Data fetch successfully.",
            this.queryService.executeQueryResponse(queryRequest.getQuery()));
    }


}
