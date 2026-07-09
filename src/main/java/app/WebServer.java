package app;

import dbconnection.DbConnection;
import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Startet einen Javalin-Webserver auf Port 8080, der den LogParser als
 * Web-Anwendung bereitstellt: Er liefert statische Dateien (u. a. das
 * Dashboard) aus, nimmt Log-Dateien per Upload entgegen und übergibt sie an
 * den {@link LogProcessor}, und stellt REST-Endpunkte zum Leeren der
 * Datenbank sowie zum Abfragen der vorhandenen Steuerungen bereit. Beim
 * Herunterfahren wird die Datenbankverbindung über einen Shutdown-Hook
 * sauber geschlossen.
 */

public class WebServer {

    public static void main(String[] args) {
        Javalin app = Javalin.create(config ->
                config.staticFiles.add("/public", Location.CLASSPATH)
        ).start(8080);

        app.post("/api/clear", ctx -> {
            try {
                DbConnection.truncateAllTables();
                ctx.result("OK");
            } catch (Exception e) {
                ctx.status(500).result(e.getMessage());
            }
        });

        app.get("/api/steuerungen", ctx -> {
            List<String> steuerungen = new ArrayList<>();
            String sql = "SELECT DISTINCT split_part(payload ->> 'rpMark', '/', 1) AS line " +
                    "FROM sdxp_log WHERE payload ->> 'rpMark' IS NOT NULL ORDER BY line";
            try {
                Connection conn = DbConnection.getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        steuerungen.add(rs.getString("line"));
                    }
                }
                ctx.json(steuerungen);
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unbekannter Fehler"));
            }
        });

        app.get("/dashboard", ctx -> {
            ctx.html(new String(WebServer.class.getResourceAsStream("/public/dashboard.html").readAllBytes()));
        });

        app.post("/api/parse", ctx -> {
            UploadedFile file = ctx.uploadedFile("file");
            if (file == null) {
                ctx.status(400).json(Map.of("error", "Keine Datei im Feld 'file' gefunden"));
                return;
            }

            Path tempFile = Files.createTempFile("upload-", ".log");
            try (var in = file.content()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            String clearParam = ctx.formParam("clear");
            boolean clearFirst = (clearParam == null) || "true".equalsIgnoreCase(clearParam);

            try {
                ProcessingResult result = LogProcessor.process(tempFile, clearFirst);
                ctx.json(toJson(result));
            } catch (Exception e) {
                System.err.println("Fehler bei der Verarbeitung des Uploads: " + e.getMessage());
                ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unbekannter Fehler"));
            } finally {
                Files.deleteIfExists(tempFile);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                DbConnection.closeConnection();
            } catch (Exception e) {
                System.err.println("Fehler beim Schliessen der DB-Verbindung: " + e.getMessage());
            }
        }));

        System.out.println("LogPraser-Webserver laeuft auf Port 8080");
    }

    private static Map<String, Object> toJson(ProcessingResult result) {
        Map<String, Object> json = new HashMap<>();
        json.put("total", result.total());
        json.put("ok", result.erfolgreich());
        json.put("fail", result.fehlgeschlagen());
        json.put("breakdown", result.breakdown());
        return json;
    }
}
