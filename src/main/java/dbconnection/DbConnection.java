package dbconnection;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DbConnection {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    private static final String DB_HOST = env("DB_HOST", "localhost");
    private static final String DB_PORT = "5432";
    private static final String DB_NAME = "log_praser";
    private static final String DB_USER = env("POSTGRES_USER", null);
    private static final String DB_PASSWORD = env("POSTGRES_PASSWORD", null);

    private static String env(String key, String defaultValue) {
        String fromDotenv = dotenv.get(key);
        if (fromDotenv != null) {
            return fromDotenv;
        }
        String fromSystemEnv = System.getenv(key);
        return fromSystemEnv != null ? fromSystemEnv : defaultValue;
    }

    private static final String URL =
            "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
                    + "?reWriteBatchedInserts=true";

    private static Connection sharedConnection;

    private DbConnection() {
        // Utility-Klasse, keine Instanzen
    }

    public static synchronized Connection getConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            sharedConnection = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
            sharedConnection.setAutoCommit(false);
        }
        return sharedConnection;
    }

    public static synchronized void commit() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.commit();
        }
    }

    public static synchronized void closeConnection() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.commit();
            sharedConnection.close();
        }
    }
    public static synchronized void truncateAllTables() throws SQLException {
        String sql = """
            TRUNCATE TABLE sdxp_log, tt1411_log, tt9001_log, tt1434_log, error_log
            RESTART IDENTITY;
        """;
        try (var stmt = getConnection().createStatement()) {
            stmt.execute(sql);
            commit();
        }
    }

}