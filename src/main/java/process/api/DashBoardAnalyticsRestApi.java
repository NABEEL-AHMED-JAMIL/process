package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Api use to perform crud operation on dashboard
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/jobQueue.json")
public class DashBoardAnalyticsRestApi {

    private Logger logger = LoggerFactory.getLogger(DashBoardAnalyticsRestApi.class);

}
