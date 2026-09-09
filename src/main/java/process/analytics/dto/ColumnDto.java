package process.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One column of a dataset, as the reader inferred it.
 *
 * The type is DuckDB's own name (VARCHAR, BIGINT, TIMESTAMP) rather than a normalised one. It is
 * shown to the user and it is what they would write in SQL once SQL Studio exists, so translating
 * it into a house vocabulary here would mean translating it back there.
 *
 * @author Nabeel Ahmed
 */
public class ColumnDto {

    /** A column the analysis grouped by. */
    public static final String ROLE_DIMENSION = "DIMENSION";

    /** A column the analysis aggregated into. */
    public static final String ROLE_MEASURE = "MEASURE";

    private String name;
    private String type;

    /**
     * What this column IS in an analysis, or null when the question does not arise.
     *
     * Null on a dataset schema and on a SQL Studio result, and that is not an omission: a column of
     * a file is just a column, and a column of a statement a person wrote is whatever they meant by
     * it -- nothing here is entitled to guess. It is set only on an Analysis Canvas result, where
     * the server chose the grouping and the aggregate itself and therefore knows.
     *
     * It is the field that lets a client satisfy 07's "every result row should carry enough context
     * to reproduce its filter state" without being told the analysis separately: the DIMENSION cells
     * of a row are exactly the equalities that reproduce it, and the MEASURE cells are what those
     * equalities produce.
     *
     * Omitted from the JSON rather than sent as null, so the schema and preview payloads -- which
     * are the two biggest things this DTO appears in, once per column of a wide file -- do not each
     * grow a field that never has a value in them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String role;

    public ColumnDto() {}

    public ColumnDto(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public ColumnDto(String name, String type, String role) {
        this(name, type);
        this.role = role;
    }

    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return this.type; }
    public void setType(String type) { this.type = type; }

    public String getRole() { return this.role; }
    public void setRole(String role) { this.role = role; }
}
