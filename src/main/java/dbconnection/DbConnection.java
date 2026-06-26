package dbconnection;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DbConnection {

    private static final Dotenv dotenv = Dotenv.load();

    private static final String DB_HOST = "localhost";
    private static final String DB_PORT = "5432";
    private static final String DB_NAME = "log_praser";
    private static final String DB_USER = dotenv.get("POSTGRES_USER");
    private static final String DB_PASSWORD = dotenv.get("POSTGRES_PASSWORD");

    private static final String URL =
            "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME;

    private DbConnection() {
        // Utility-Klasse, keine Instanzen
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
    }
}