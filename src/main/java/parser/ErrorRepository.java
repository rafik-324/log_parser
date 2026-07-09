package parser;

import dbconnection.DbConnection;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Schreibt nicht parsebare Log-Blöcke zusammen mit dem zugehörigen Fehlertext
 * in die Tabelle {@code error_log}.
 * <p>
 * Fehler beim Insert selbst werden bewusst verschluckt (nur nach stderr), damit
 * ein Schreibfehler im Error-Logging nicht den laufenden Parser-Durchlauf abbricht.
 */
public class ErrorRepository {

    private static final String INSERT_SQL =
            "INSERT INTO error_log (fehlertext, roh_block) VALUES (?, ?)";

    /**
     * Persistiert einen Fehler. Wirft nie: Schlägt der Insert fehl, wird nur
     * nach stderr geloggt (siehe Klassen-Doc).
     */
    public void insertError(String rohBlock, String fehlertext) {
        try (PreparedStatement stmt = DbConnection.getConnection().prepareStatement(INSERT_SQL)) {

            stmt.setString(1, TextSanitizer.sanitize(fehlertext));
            stmt.setString(2, TextSanitizer.sanitize(rohBlock));

            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Konnte Fehler nicht in error_log schreiben: " + e.getMessage());
        }
    }
}