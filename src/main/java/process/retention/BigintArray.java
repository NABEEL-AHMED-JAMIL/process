package process.retention;

import org.springframework.jdbc.core.SqlTypeValue;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;

/** A bigint[] statement argument ("= ANY (?)", "&lt;&gt; ALL (?)"), bound with the connection's own createArrayOf. */
final class BigintArray implements SqlTypeValue {

    private final Long[] values;

    BigintArray(Collection<Long> values) {
        this.values = values.toArray(new Long[0]);
    }

    BigintArray(Long[] values) {
        this.values = Arrays.copyOf(values, values.length);
    }

    @Override
    public void setTypeValue(PreparedStatement ps, int index, int sqlType, String typeName) throws SQLException {
        ps.setArray(index, ps.getConnection().createArrayOf("bigint", this.values));
    }
}
