package process.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.engine.async.properties.AsyncTaskProperties;

/**
 * @author Nabeel Ahmed
 */
@Configuration
@ComponentScan(basePackages = "process.*")
public class ProcessConfig {

    public Logger logger = LogManager.getLogger(ProcessConfig.class);

    @Autowired
    public AsyncTaskProperties asyncTaskProperties;

    /**
     * This method use to create the asyncDALTaskExecutor bean
     * @return AsyncDALTaskExecutor
     */
    @Bean
    @Scope("singleton")
    public AsyncDALTaskExecutor asyncDALTaskExecutor() throws Exception {
        logger.debug("===============Application-DAO-INIT===============");
        AsyncDALTaskExecutor taskExecutor = new AsyncDALTaskExecutor(
            this.asyncTaskProperties.getMinThreads(), this.asyncTaskProperties.getMaxThreads(),
            this.asyncTaskProperties.getIdleThreadLife());
        logger.debug("===============Application-DAO-END===============");
        return taskExecutor;
    }

}
