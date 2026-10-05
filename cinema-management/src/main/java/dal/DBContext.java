package dal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBContext {

    private static final String HOST = getEnv("DB_HOST", "localhost\\SQLEXPRESS");
    private static final String PORT = getEnv("DB_PORT", "1433");
    private static final String DATABASE = getEnv("DB_NAME", "CinemaManagement");
    private static final String USER = getEnv("DB_USER", "sa");
    private static final String PASSWORD = getEnv("DB_PASSWORD", "long");

    private static final String URL =
        "jdbc:sqlserver://" + HOST + ":" + PORT + ";"
        + "databaseName=" + DATABASE + ";"
        + "encrypt=false;"
        + "trustServerCertificate=true;"
        // Bắt buộc gửi tham số dưới dạng NVARCHAR (Unicode) để tiếng Việt
        // không bị mojibake khi insert/select. Mặc định mssql-jdbc đã true,
        // nhưng ép tường minh để chống môi trường deploy khác nhau.
        + "sendStringParametersAsUnicode=true;"
        + "useUnicode=true;";

    private static String getEnv(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
