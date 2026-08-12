package process.model.enums;

/**
 * Database vendors a DatabaseConnectionProfile can target. Only POSTGRES is actually wired up
 * in DatabaseConnectionFactory today -- postgresql is the only JDBC driver this project already
 * depends on (see pom.xml), and per the "don't over-engineer" rule, drivers for vendors nobody
 * has asked to connect to yet aren't added speculatively. Adding MYSQL/etc later means: add the
 * driver dependency, add one branch to DatabaseConnectionFactory.buildDataSource, done -- this
 * enum (and every other layer: entity, DTO, validation) doesn't need to change.
 * @author Nabeel Ahmed
 */
public enum DatabaseType {
    POSTGRES
}
