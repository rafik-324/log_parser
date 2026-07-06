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
import java.sql.Types;

public class SdxpParser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("\\(([^:]+):(.+?)<->([^)(]+)\\)?");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)\\b");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String normalizeId(String id) {
        if (id == null) return null;
        return id.replaceAll("-\\d+$", "");
    }

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
            typ        = h.group(1).trim();
            sender     = normalizeId(h.group(2).trim());
            empfaenger = normalizeId(h.group(3).trim());
        } else {
            JsonNode src = payload.get("src");
            JsonNode dst = payload.get("dst");
            if (src == null || dst == null) {
                throw new IllegalArgumentException(
                        "Kein Klammer-Header und kein src/dst im JSON gefunden: " + line);
            }
            typ        = "SDXP";
            sender     = normalizeId(src.asText());
            empfaenger = normalizeId(dst.asText());
        }

        return new LogEntry(timestamp, typ, sender, empfaenger, richtung, payload);
    }

    private String extractJson(String line, int start) {
        int depth = 0;
        boolean inString = false, escape = false;
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                return line.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("Kein schließendes '}' gefunden: " + line);
    }

    private static final String INSERT_SQL =
            "INSERT INTO sdxp_log (\"timestamp\", typ, sender, empfaenger, richtung, seq, ackseq, payload) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)";

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

            JsonNode payload = entry.payload();

            // seq aus dem JSON extrahieren (eingehende RECVF-Telegramme tragen "seq")
            JsonNode seqNode = payload != null ? payload.get("seq") : null;
            if (seqNode != null && seqNode.isNumber()) {
                insertStmt.setLong(6, seqNode.asLong());
            } else {
                insertStmt.setNull(6, Types.BIGINT);
            }

            // ackSeq aus dem JSON extrahieren (ausgehende SENDF-Antworten tragen "ackSeq" - camelCase!)
            JsonNode ackSeqNode = payload != null ? payload.get("ackSeq") : null;
            if (ackSeqNode != null && ackSeqNode.isNumber()) {
                insertStmt.setLong(7, ackSeqNode.asLong());
            } else {
                insertStmt.setNull(7, Types.BIGINT);
            }

            insertStmt.setString(8, payload != null
                    ? TextSanitizer.sanitize(payload.toString())
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
}