package dbconnection;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DbConnection {

    // ignoreIfMissing(): im Docker-Container gibt es keine .env-Datei (die
    // Variablen kommen direkt von docker-compose ueber "environment:") -
    // ohne das wuerde Dotenv.load() dort mit einer Exception abbrechen.
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    // DB_HOST per Umgebungsvariable konfigurierbar:
    // - lokal aus IntelliJ gestartet (Main/LogProcessor direkt auf dem Host) -> Fallback "localhost"
    // - im Docker-Container (WebServer, siehe docker-compose.yml app-Service)  -> "postgres" (Service-Name)
    private static final String DB_HOST = env("DB_HOST", "localhost");
    private static final String DB_PORT = "5432";
    private static final String DB_NAME = "log_praser";
    private static final String DB_USER = env("POSTGRES_USER", null);
    private static final String DB_PASSWORD = env("POSTGRES_PASSWORD", null);

    // Liest zuerst aus der .env-Datei (lokale Entwicklung), sonst aus einer
    // echten Umgebungsvariable (Docker-Container), sonst den Default-Wert.
    private static String env(String key, String defaultValue) {
        String fromDotenv = dotenv.get(key);
        if (fromDotenv != null) {
            return fromDotenv;
        }
        String fromSystemEnv = System.getenv(key);
        return fromSystemEnv != null ? fromSystemEnv : defaultValue;
    }

    // reWriteBatchedInserts=true: der Postgres-JDBC-Treiber fasst addBatch()-Aufrufe
    // intern zu Multi-Row-INSERTs zusammen - kostenloser Zusatzboost fuers Batching unten.
    private static final String URL =
            "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
                    + "?reWriteBatchedInserts=true";

    // EINE Connection fuer den gesamten Programmlauf statt einer neuen pro Insert.
    // Wird lazy beim ersten Aufruf erzeugt und danach von allen Parsern wiederverwendet.
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

    // Committet alle bisher gesammelten Aenderungen, OHNE die Connection zu
    // schliessen. Wird vom Webserver nach jeder verarbeiteten Datei aufgerufen,
    // damit die Verbindung fuer den naechsten Upload offen bleibt.
    public static synchronized void commit() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.commit();
        }
    }

    // Committet alle noch offenen Aenderungen und schliesst die Connection endgueltig.
    // Genau EINMAL am Ende des Laufs aufrufen (CLI: nach main(); Webserver: im Shutdown-Hook).
    public static synchronized void closeConnection() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.commit();
            sharedConnection.close();
        }
    }
}