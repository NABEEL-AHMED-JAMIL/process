package process.schema;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The empty database etl-platform/scripts/schema-gate.sh made for process (MIG-129), named by
 * SCHEMA_GATE_DB_URL, _USER and _PASSWORD. The script creates it and drops it; this only builds and checks it.
 *
 * Refuses -- before anything is written -- a database whose name has no "_gate_" or that already holds a
 * table: the gate never runs a changelog against a database that existed before it, etl_job least of all.
 */
final class SchemaGate {

    static final String URL_VARIABLE = "SCHEMA_GATE_DB_URL";

    final String url;
    final String user;
    final String password;

    private SchemaGate(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    static SchemaGate fromEnvironment() {
        return open(System.getenv(URL_VARIABLE), System.getenv("SCHEMA_GATE_DB_USER"), System.getenv("SCHEMA_GATE_DB_PASSWORD"));
    }

    static SchemaGate open(String url, String user, String password) {
        SchemaGate gate = new SchemaGate(url, user, password);
        String name = url.replaceAll("^.*/([^/?]+)(\\?.*)?$", "$1");
        if (!name.contains("_gate_")) {
            throw new IllegalStateException(URL_VARIABLE + " names " + name + ", which is not a _gate_ database: refused");
        }
        try (Connection c = gate.connection(); Statement s = c.createStatement();
             ResultSet tables = s.executeQuery("SELECT count(*) FROM information_schema.tables "
                 + "WHERE table_schema NOT IN ('pg_catalog', 'information_schema')")) {
            tables.next();
            if (tables.getLong(1) != 0) {
                throw new IllegalStateException(name + " already holds tables: the gate builds an empty database only");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("the gate database " + name + " cannot be reached", ex);
        }
        return gate;
    }

    Connection connection() throws SQLException {
        return DriverManager.getConnection(this.url, this.user, this.password);
    }

    /** The changesets of this changelog the database has not run: none, once the service has started on it. */
    List<String> unrun(String changelog, String contexts) throws Exception {
        try (Connection c = this.connection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            Liquibase liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(), database);
            List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(contexts), new LabelExpression());
            return unrun.stream().map(ChangeSet::getId).collect(Collectors.toList());
        }
    }

    /** Rows in databasechangelog: every changeset, run once. */
    long ran() throws SQLException {
        try (Connection c = this.connection(); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery("SELECT count(*) FROM databasechangelog")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
