package process.model.enums;

/**
 * Lifecycle of a single query_execution row (manual "Run" click or a QuerySchedule firing) --
 * distinct from the ETL-pipeline JobStatus enum (Queue/Start/Running/...) on purpose: a query
 * execution is a single in-process JDBC-to-CSV-to-storage operation with no external worker
 * hand-off, so it doesn't share JobStatus's Queue/Skip/Interrupt states, which describe a
 * fundamentally different (Kafka-dispatched, externally-executed) job model.
 * @author Nabeel Ahmed
 */
public enum QueryExecutionStatus {
    PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
}
