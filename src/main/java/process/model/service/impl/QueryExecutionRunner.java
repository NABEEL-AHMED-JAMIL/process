package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import process.engine.query.CsvExportResult;
import process.engine.query.CsvExportService;
import process.engine.query.DatabaseConnectionFactory;
import process.engine.query.QueryValidator;
import process.model.enums.QueryExecutionStatus;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.pojo.QueryDefinition;
import process.model.pojo.QueryExecution;
import process.model.repository.QueryExecutionRepository;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import static process.util.ProcessUtil.isNull;

/**
 * The actual "run one query end-to-end and persist a QueryExecution row regardless of outcome"
 * logic -- split out of QueryExecutionServiceImpl into its own bean specifically so its
 * @Transactional(REQUIRES_NEW) takes effect. Spring's proxy-based @Transactional is a no-op on
 * self-invocation (a bean method calling another method on `this`): the call never goes back
 * through the Spring-managed proxy that AOP advice is woven onto, so an annotation on a method
 * called via `this.otherMethod(...)` from within the same class is silently ignored. Since
 * QueryExecutionServiceImpl.execute()/executeForSchedule() both need to call this method, it
 * has to live on a different bean to actually get its own transaction.
 * @author Nabeel Ahmed
 */
@Component
public class QueryExecutionRunner {

    private static final Logger logger = LoggerFactory.getLogger(QueryExecutionRunner.class);

    private final QueryExecutionRepository queryExecutionRepository;
    private final EncryptionUtil encryptionUtil;
    private final QueryValidator queryValidator;
    private final DatabaseConnectionFactory databaseConnectionFactory;
    private final CsvExportService csvExportService;
    private final StorageBrowserService storageBrowserService;

    public QueryExecutionRunner(QueryExecutionRepository queryExecutionRepository, EncryptionUtil encryptionUtil,
        QueryValidator queryValidator, DatabaseConnectionFactory databaseConnectionFactory,
        CsvExportService csvExportService, StorageBrowserService storageBrowserService) {
        this.queryExecutionRepository = queryExecutionRepository;
        this.encryptionUtil = encryptionUtil;
        this.queryValidator = queryValidator;
        this.databaseConnectionFactory = databaseConnectionFactory;
        this.csvExportService = csvExportService;
        this.storageBrowserService = storageBrowserService;
    }

    /**
     * Method use to run query end-to-end (decrypt+re-validate SQL, execute against profile,
     * stream to CSV, upload to bucket/prefix/fileName) and persist a QueryExecution row either
     * way -- REQUIRES_NEW so this row is always committed on its own, even if the caller's
     * surrounding transaction later fails/rolls back for an unrelated reason.
     * @param query
     * @param profile
     * @param outputBucket
     * @param outputPrefix
     * @param outputFileName may contain a "{date}" token, resolved here
     * @param scheduleId null for a manual run
     * @return QueryExecution
     * */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QueryExecution runAndRecord(QueryDefinition query, DatabaseConnectionProfile profile,
        String outputBucket, String outputPrefix, String outputFileName, Long scheduleId) {
        QueryExecution execution = new QueryExecution();
        execution.setTenantId(query.getTenantId());
        execution.setQueryId(query.getQueryId());
        execution.setDatabaseConnectionProfileId(profile.getDatabaseConnectionProfileId());
        execution.setScheduleId(scheduleId);
        execution.setStatus(QueryExecutionStatus.RUNNING);
        execution.setStartedAt(new Timestamp(System.currentTimeMillis()));
        execution.setCreatedBy(TenantContext.getAppUserId());
        execution = this.queryExecutionRepository.save(execution);

        String resolvedFileName = this.resolveFileNameTemplate(outputFileName);
        String normalizedKey = this.normalizedKey(outputPrefix, resolvedFileName);
        CsvExportResult exportResult = null;
        try {
            String decryptedSql = this.encryptionUtil.decrypt(query.getQueryText());
            // Defense in depth: re-validate immediately before execution, not just at save
            // time -- cheap, and closes any gap between "saved as valid" and "actually run"
            // however small.
            QueryValidator.ValidationResult sqlCheck = this.queryValidator.validate(decryptedSql);
            if (!sqlCheck.isValid()) {
                throw new IllegalStateException("Saved query failed re-validation: " + sqlCheck.getReason());
            }
            try (Connection connection = this.databaseConnectionFactory.openConnection(profile)) {
                exportResult = this.csvExportService.streamToCsvFile(
                    connection, sqlCheck.getNormalizedSql(), this.maxRows, this.queryTimeoutSeconds);
            }
            try (InputStream fileStream = new FileInputStream(exportResult.getFile())) {
                this.storageBrowserService.uploadObject(outputBucket, normalizedKey, fileStream,
                    exportResult.getFile().length(), "text/csv");
            }
            execution.setStatus(QueryExecutionStatus.SUCCESS);
            execution.setRowCount(exportResult.getRowCount());
            execution.setOutputBucket(outputBucket);
            execution.setOutputKey(normalizedKey);
            if (exportResult.isTruncated()) {
                execution.setErrorMessage(String.format(
                    "Result was truncated at the %,d row limit -- narrow the query if you need the full result.", this.maxRows));
            }
        } catch (Exception ex) {
            logger.error("Query execution {} failed (tenant {}, query {}): {}",
                execution.getExecutionId(), query.getTenantId(), query.getQueryId(), ex.getMessage(), ex);
            execution.setStatus(QueryExecutionStatus.FAILED);
            // Sanitized: never the raw JDBC/driver exception (can echo host/port/schema), and
            // never the query text itself.
            execution.setErrorMessage(this.sanitizeError(ex.getMessage()));
        } finally {
            if (exportResult != null) {
                exportResult.getFile().delete();
            }
        }
        execution.setCompletedAt(new Timestamp(System.currentTimeMillis()));
        return this.queryExecutionRepository.save(execution);
    }

    @Value("${query-engine.max-rows:1000000}")
    private long maxRows;

    @Value("${query-engine.query-timeout-seconds:300}")
    private int queryTimeoutSeconds;

    private String normalizedKey(String prefix, String fileName) {
        String safePrefix = isNull(prefix) || prefix.trim().isEmpty()
            ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        String safeFileName = fileName.trim();
        if (!safeFileName.toLowerCase().endsWith(".csv")) {
            safeFileName = safeFileName + ".csv";
        }
        return safePrefix + safeFileName;
    }

    /**
     * Method use to resolve the "{date}" token in a file name template -- intentionally the one
     * small piece of templating supported, rather than a general template engine nothing here
     * otherwise needs. A no-op for a manual run's plain file name (no token to replace).
     * @param template
     * @return String
     * */
    private String resolveFileNameTemplate(String template) {
        if (isNull(template) || !template.contains("{date}")) {
            return template;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        return template.replace("{date}", today);
    }

    private String sanitizeError(String rawMessage) {
        if (isNull(rawMessage)) {
            return "The query failed to execute.";
        }
        String firstLine = rawMessage.split("\n", 2)[0];
        return firstLine.length() > 300 ? firstLine.substring(0, 300) + "..." : firstLine;
    }

}
