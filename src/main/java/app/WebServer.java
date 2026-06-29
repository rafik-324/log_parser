package app;

import dbconnection.DbConnection;
import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public class WebServer {

    public static void main(String[] args) {
        Javalin app = Javalin.create(config ->
                config.staticFiles.add("/public", Location.CLASSPATH)
        ).start(8080);

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

            try {
                ProcessingResult result = LogProcessor.process(tempFile);
                ctx.json(toJson(result));
            } catch (Exception e) {
                System.err.println("Fehler bei der Verarbeitung des Uploads: " + e.getMessage());
                ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unbekannter Fehler"));
            } finally {
                Files.deleteIfExists(tempFile);
            }
        });

        // Sauberes Herunterfahren (z.B. bei "docker compose down"): Verbindung
        // noch committen + schliessen, damit nichts auf der Strecke bleibt.
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
