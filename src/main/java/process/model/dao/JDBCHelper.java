package process.model.dao;

import java.sql.*;

/**
 * @author Nabeel Ahmed
 */
public class JDBCHelper {

    private static Connection connection;

    public static Connection getConnection(String url, String username, String password) throws SQLException {
        connection = DriverManager.getConnection(url, username, password);
        return connection;
    }

    public static void closeConnection(Connection con) throws SQLException {
        if (con != null) {
            con.close();
        }
    }

    public static void closePrepaerdStatement(PreparedStatement stmt) throws SQLException {
        if (stmt != null) {
            stmt.close();
        }
    }

    public static void closeResultSet(ResultSet rs) throws SQLException {
        if (rs != null) {
            rs.close();
        }
    }

    /**
    public static void main(String[] args) throws SQLException {
     *  Connection connection = JDBCHelper.getConnection("jdbc:postgresql://localhost:5432/datacenter",
     *      "postgres", "admin");
     *  Statement ps = connection.createStatement();;
     *  ResultSet rs = ps.executeQuery("SELECT * FROM wallet");
     *  System.out.println("Tables in the current database: ");
     *  while(rs.next()) {
     *      System.out.print(rs.getString(1));
     *      System.out.println();
     *  }
     * }
    * */

}