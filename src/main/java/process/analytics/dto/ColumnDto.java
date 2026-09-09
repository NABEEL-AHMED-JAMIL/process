package process.analytics.dto;

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

    private String name;
    private String type;

    public ColumnDto() {}

    public ColumnDto(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return this.type; }
    public void setType(String type) { this.type = type; }
}
