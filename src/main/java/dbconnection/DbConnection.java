package dbconnection;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Zentrale Verwaltung einer gemeinsam genutzten PostgreSQL-Verbindung.
 *
 * <p>Die Verbindungsdaten werden aus einer {@code .env}-Datei oder aus
 * Umgebungsvariablen gelesen. Die Klasse hält eine einzige, wiederverwendete
 * {@link Connection} mit deaktiviertem Auto-Commit und stellt statische Methoden
 * zum Abrufen der Verbindung sowie zum manuellen Steuern von Commit, Rollback
 * und Verbindungsabbau bereit.</p>
 *
 * <p>Die Klasse ist nicht instanziierbar; alle Methoden sind statisch und
 * {@code synchronized}, um den Zugriff aus mehreren Threads abzusichern.</p>
 */
public class DbConnection {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    private static final String DB_HOST     = env("DB_HOST", "localhost");
    private static final String DB_PORT     = "5432";
    private static final String DB_NAME     = "log_praser";
    private static final String DB_USER     = env("POSTGRES_USER", null);
    private static final String DB_PASSWORD = env("POSTGRES_PASSWORD", null);

    private static String env(String key, String defaultValue) {
        String fromDotenv = dotenv.get(key);
        if (fromDotenv != null) return fromDotenv;
        String fromSystemEnv = System.getenv(key);
        return fromSystemEnv != null ? fromSystemEnv : defaultValue;
    }

    private static final String URL =
            "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
                    + "?reWriteBatchedInserts=true";

    private static Connection sharedConnection;

    private DbConnection() {}

    /**
     * Liefert die gemeinsam genutzte Datenbankverbindung. Existiert noch keine
     * oder wurde sie geschlossen, wird eine neue Verbindung mit deaktiviertem
     * Auto-Commit aufgebaut. Das Transaktionshandling erfolgt dadurch manuell
     * über {@link #commit()} und {@link #rollback()}.
     *
     * @return die aktive, gemeinsam genutzte {@link Connection}
     * @throws SQLException bei einem Fehler beim Verbindungsaufbau
     */
    public static synchronized Connection getConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            sharedConnection = DriverManager.getConnection(URL, DB_USER, DB_PASSWORD);
            sharedConnection.setAutoCommit(false);
        }
        return sharedConnection;
    }

    /**
     * Bestätigt die aktuelle Transaktion, sofern eine offene Verbindung besteht.
     *
     * @throws SQLException bei einem Fehler während des Commits
     */
    public static synchronized void commit() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.commit();
        }
    }

    /**
     * Verwirft alle Änderungen der aktuellen Transaktion, sofern eine offene
     * Verbindung besteht.
     *
     * @throws SQLException bei einem Fehler während des Rollbacks
     */
    public static synchronized void rollback() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.rollback();
        }
    }

    /**
     * Bestätigt offene Änderungen und schließt anschließend die Verbindung.
     * Wird typischerweise beim Herunterfahren der Anwendung aufgerufen.
     *
     * @throws SQLException bei einem Fehler beim Commit oder Schließen
     */
    public static synchronized void closeConnection() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.commit();
            sharedConnection.close();
        }
    }

    /**
     * Leert alle Log-Tabellen ({@code sdxp_log}, {@code tt14_log},
     * {@code tt9001_log}, {@code error_log}) und setzt deren
     * Identity-Sequenzen zurück. Die Änderung wird direkt committet.
     *
     * @throws SQLException bei einem Fehler während des Truncates
     */
    public static synchronized void truncateAllTables() throws SQLException {
        try (var stmt = getConnection().createStatement()) {
            stmt.execute("TRUNCATE TABLE sdxp_log, tt14_log, tt9001_log, error_log RESTART IDENTITY");
            commit();
        }
    }
}