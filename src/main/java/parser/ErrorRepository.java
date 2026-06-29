package parser;

import dbconnection.DbConnection;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ErrorRepository {

    private static final String INSERT_SQL =
            "INSERT INTO error_log (fehlertext, roh_block) VALUES (?, ?)";

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