package parser;

import dbconnection.DbConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ErrorRepository {

    private static final String INSERT_SQL =
            "INSERT INTO error_log (fehlertext, roh_block) VALUES (?, ?)";

    public void insertError(String rohBlock, String fehlertext) {
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            stmt.setString(1, fehlertext);
            stmt.setString(2, rohBlock);

            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Konnte Fehler nicht in error_log schreiben: " + e.getMessage());
        }
    }
}