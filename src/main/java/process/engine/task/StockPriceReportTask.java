package process.engine.task;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.engine.BulkAction;
import process.model.dao.JDBCHelper;
import process.model.dto.StockPriceDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobHistory;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import javax.annotation.PostConstruct;
import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This task use for monthly report purpose of the task test the pdf report
 * @author Nabeel Ahmed
 */
@Component
public class StockPriceReportTask implements Runnable {

    public Logger logger = LogManager.getLogger(HelloWorldTask.class);

    @Autowired
    private BulkAction bulkAction;
    private Map<String, Object> data;
    private JsonObject mainObject;
    private Connection connection;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private final String FETCH_QUERY = "select id, close_price, high_price, low_price, no_of_shares, no_of_trades, open_price, spread_close_open,\n" +
            "spread_high_low, total_turnover, wap from stock_price OFFSET %d LIMIT %d";
    public StockPriceReportTask() { }

    public Map<String, Object> getData() {
        return data;
    }
    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @PostConstruct
    public void init() throws UnsupportedEncodingException {
        ClassLoader cl = this.getClass().getClassLoader();
        InputStream inputStream = cl.getResourceAsStream(ProcessUtil.STOCK_PRICE_REPORT_DETAIL_JSON);
        Reader reader = new InputStreamReader(inputStream, ProcessUtil.UTF);
        JsonReader readerResult = new JsonReader(reader);
        JsonElement jsonElement = new JsonParser().parse(readerResult);
        this.mainObject = jsonElement.getAsJsonObject();
    }

    @Override
    public void run() {
        // change the status into the running status
        JobHistory jobHistory = (JobHistory) this.getData().get(ProcessUtil.JOB_HISTORY);
        try {
            this.bulkAction.changeJobLastJobRun(jobHistory.getJobId(), jobHistory.getStartTime());
            this.bulkAction.changeJobHistoryStatus(jobHistory.getJobHistoryId(), JobStatus.Running);
            this.bulkAction.saveJobAuditLogs(jobHistory.getJobId(), jobHistory.getJobHistoryId(),
                    String.format("Job %s now in the running.", jobHistory.getJobId()));
            // process for the current job.... open connection
            JsonObject dataSourceObject = this.mainObject.getAsJsonObject(ProcessUtil.DATA_SOURCE);
            this.connection = JDBCHelper.getConnection(String.format("jdbc:postgresql://%s:%d/%s",
                dataSourceObject.get(ProcessUtil.IP).getAsString(), dataSourceObject.get(ProcessUtil.PORT).getAsInt(),
                dataSourceObject.get(ProcessUtil.DATABASE).getAsString()), dataSourceObject.get(ProcessUtil.USERNAME).getAsString(),
                dataSourceObject.get(ProcessUtil.PASSWORD).getAsString());
            int totalCount = dataSourceObject.get(ProcessUtil.TOTAL_COUNT).getAsInt();
            for (int i=0; i<totalCount/500; i++) {
                String sql = String.format(FETCH_QUERY, i*500, 500); // offset, limit
                this.preparedStatement = this.connection.prepareStatement(sql);
                this.resultSet = this.preparedStatement.executeQuery();
                List<StockPriceDto> stockPriceList = new ArrayList<>();
                while (this.resultSet.next()) {
                    StockPriceDto stockPriceDto = new StockPriceDto();
                    stockPriceDto.setId(this.resultSet.getString("id" ));
                    stockPriceDto.setClosePrice(this.resultSet.getDouble("close_price"));
                    stockPriceDto.setHighPrice(this.resultSet.getDouble("high_price"));
                    stockPriceDto.setLowPrice(this.resultSet.getDouble("low_price"));
                    stockPriceDto.setNoOfShares(this.resultSet.getInt("no_of_shares"));
                    stockPriceDto.setNoOfTrades(this.resultSet.getInt("no_of_trades"));
                    stockPriceDto.setOpenPrice(this.resultSet.getDouble("open_price"));
                    stockPriceDto.setSpreadCloseOpen(this.resultSet.getDouble("spread_close_open"));
                    stockPriceDto.setSpreadHighLow(this.resultSet.getDouble("spread_high_low"));
                    stockPriceDto.setTotalTurnover(this.resultSet.getDouble("total_turnover"));
                    stockPriceDto.setWap(this.resultSet.getDouble("wap"));
                    stockPriceList.add(stockPriceDto);
                }
                // put the data into the file
                if (stockPriceList.size() > 0) {
                    logger.info(stockPriceList);
                }
            }
            this.bulkAction.changeJobHistoryStatus(jobHistory.getJobHistoryId(), JobStatus.Completed);
            this.bulkAction.saveJobAuditLogs(jobHistory.getJobId(), jobHistory.getJobHistoryId(),
                    String.format("Job %s now complete.", jobHistory.getJobId()));
            this.bulkAction.changeJobHistoryEndDate(jobHistory.getJobHistoryId(), LocalDateTime.now());
        } catch (Exception ex) {
            // change the status into the running status
            this.bulkAction.changeJobHistoryStatus(jobHistory.getJobHistoryId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobHistory.getJobId(), jobHistory.getJobHistoryId(),
                    String.format("Job %s fail due to %s .", jobHistory.getJobId(), ExceptionUtil.getRootCauseMessage(ex)));
            this.bulkAction.changeJobHistoryEndDate(jobHistory.getJobHistoryId(), LocalDateTime.now());
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        } finally {
            try {
                JDBCHelper.closeResultSet(this.resultSet);
                JDBCHelper.closePrepaerdStatement(this.preparedStatement);
                JDBCHelper.closeConnection(this.connection);
            } catch (SQLException ex) {
                logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            }
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
