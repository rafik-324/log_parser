package parser;

import dbconnection.DbConnection;
import parser.model.LogEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

public class SdxpParser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("\\(([^:]+):(.+?)<->([^)(]+)\\)?");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)\\b");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public LogEntry parseLine(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Leere Zeile");
        }

        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) {
            throw new IllegalArgumentException("Kein '|' gefunden: " + line);
        }
        LocalDateTime timestamp = LocalDateTime.parse(line.substring(0, firstPipe).trim(), TS_FORMAT);

        int jsonStart = line.indexOf('{');
        if (jsonStart < 0) {
            throw new IllegalArgumentException("Kein '{' gefunden: " + line);
        }
        String beforeJson = line.substring(0, jsonStart);

        Matcher d = DIRECTION_PATTERN.matcher(beforeJson);
        String richtung = null;
        while (d.find()) {
            richtung = d.group(1);
        }
        if (richtung == null) {
            throw new IllegalArgumentException("Keine Richtung (SENDF/RECVF) gefunden: " + line);
        }

        String json = extractJson(line, jsonStart);
        JsonNode payload;
        try {
            payload = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON nicht parsebar: " + json, e);
        }

        String typ;
        String sender;
        String empfaenger;

        Matcher h = HEADER_PATTERN.matcher(beforeJson);
        if (h.find()) {
            typ = h.group(1).trim();
            sender = h.group(2).trim();
            empfaenger = h.group(3).trim();
        } else {
            JsonNode src = payload.get("src");
            JsonNode dst = payload.get("dst");
            if (src == null || dst == null) {
                throw new IllegalArgumentException(
                        "Kein Klammer-Header und kein src/dst im JSON gefunden: " + line);
            }
            typ = "SDXP";
            sender = src.asText();
            empfaenger = dst.asText();
        }

        return new LogEntry(timestamp, typ, sender, empfaenger, richtung, payload);
    }

    private String extractJson(String line, int start) {
        int depth = 0;
        boolean inString = false, escape = false;
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                return line.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("Kein schließendes '}' gefunden: " + line);
    }

    private static final String INSERT_SQL =
            "INSERT INTO sdxp_log (\"timestamp\", typ, sender, empfaenger, richtung, payload) " +
                    "VALUES (?, ?, ?, ?, ?, ?::jsonb)";

    private static final int BATCH_SIZE = 500;

    private PreparedStatement insertStmt;
    private int batchCount = 0;

    public void insert(LogEntry entry) {
        try {
            if (insertStmt == null) {
                insertStmt = DbConnection.getConnection().prepareStatement(INSERT_SQL);
            }
            insertStmt.setTimestamp(1, Timestamp.valueOf(entry.timestamp()));
            insertStmt.setString(2, TextSanitizer.sanitize(entry.typ()));
            insertStmt.setString(3, TextSanitizer.sanitize(entry.sender()));
            insertStmt.setString(4, TextSanitizer.sanitize(entry.empfaenger()));
            insertStmt.setString(5, TextSanitizer.sanitize(entry.richtung()));
            insertStmt.setString(6, entry.payload() != null
                    ? TextSanitizer.sanitize(entry.payload().toString())
                    : null);
            insertStmt.addBatch();
            batchCount++;

            if (batchCount >= BATCH_SIZE) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Insert in sdxp_log fehlgeschlagen: " + entry, e);
        }
    }

    public void flush() {
        try {
            if (insertStmt != null && batchCount > 0) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Flush von sdxp_log fehlgeschlagen", e);
        }
    }

    public static void main(String[] args) {
        SdxpParser parser = new SdxpParser();
        String testLine = """
                2026-02-20 13:30:55.689 | DEBUG | n-WaSocDaemon(SDXP:MFS<->C01(C01-1))-405 |        | RECVF-LOGICAL: {"telNo":1195,"et":"260220_123056.347","src":"C01-1","dst":"MFS","func":"RPINFO","rpMark":"C01/77/11","luId":"500001351387","luInfo":{"type":"C2","weight":98},"mfsTime":210,"seq":636} // SdxpSocTelegram""";

        LogEntry entry = parser.parseLine(testLine);

        System.out.println("Zeitstempel: " + entry.timestamp());
        System.out.println("Typ:         " + entry.typ());
        System.out.println("Sender:      " + entry.sender());
        System.out.println("Empfänger:   " + entry.empfaenger());
        System.out.println("Richtung:    " + entry.richtung());

        System.out.println("Inhalt:");
        entry.payload().fields().forEachRemaining(feld ->
                System.out.println("  " + feld.getKey() + " = " + feld.getValue().asText())
        );
    }
}