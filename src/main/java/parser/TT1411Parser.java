package parser;

import com.fasterxml.jackson.databind.node.TextNode;
import dbconnection.DbConnection;
import parser.model.LogEntry;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TT1411Parser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)-LOGICAL\\b");

    // typ(sender->empfaenger)
    private static final Pattern BODY_PATTERN =
            Pattern.compile("([^\\s(|]+)\\((.+?)->(.+?)\\)");

    private static final Pattern PAYLOAD_PATTERN =
            Pattern.compile("#[^:]*:\\s*(.+)$");

    public LogEntry parseLine(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Leere Zeile");
        }

        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) {
            throw new IllegalArgumentException("Kein '|' gefunden: " + line);
        }
        LocalDateTime timestamp = LocalDateTime.parse(line.substring(0, firstPipe).trim(), TS_FORMAT);

        // Nur der Teil ab SENDF/RECVF-LOGICAL wird betrachtet, egal wie viele
        // ignorierbare Felder davor stehen.
        Matcher d = DIRECTION_PATTERN.matcher(line);
        String richtung = null;
        int directionStart = -1;
        while (d.find()) {
            richtung = d.group(1);
            directionStart = d.start();
        }
        if (richtung == null) {
            throw new IllegalArgumentException("Keine Richtung (SENDF/RECVF-LOGICAL) gefunden: " + line);
        }

        String relevantPart = line.substring(directionStart);

        Matcher b = BODY_PATTERN.matcher(relevantPart);
        if (!b.find()) {
            throw new IllegalArgumentException("Kein typ(sender->empfaenger) gefunden: " + relevantPart);
        }
        String typ = b.group(1).trim();
        String sender = b.group(2).trim();
        String empfaenger = b.group(3).trim();

        String afterBody = relevantPart.substring(b.end());
        Matcher p = PAYLOAD_PATTERN.matcher(afterBody);
        String payloadText = null;
        if (p.find()) {
            payloadText = p.group(1).trim();
        }

        var payload = payloadText != null ? TextNode.valueOf(payloadText) : null;

        return new LogEntry(timestamp, typ, sender, empfaenger, richtung, payload);
    }

    private static final String INSERT_SQL =
            "INSERT INTO tt1411_log (\"timestamp\", typ, sender, empfaenger, richtung, payload_text) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";

    private static final int BATCH_SIZE = 500;

    // PreparedStatement bleibt ueber mehrere insert()-Aufrufe hinweg bestehen,
    // damit Zeilen gesammelt (addBatch) statt einzeln ausgefuehrt werden.
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
                    ? TextSanitizer.sanitize(entry.payload().asText())
                    : null);
            insertStmt.addBatch();
            batchCount++;

            if (batchCount >= BATCH_SIZE) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Insert in tt1411_log fehlgeschlagen: " + entry, e);
        }
    }

    // Schreibt alle noch gesammelten, aber noch nicht ausgefuehrten Batch-Eintraege weg.
    // Am Ende des gesamten Laufs einmal aufrufen (aus Main, nach der Verarbeitungs-Schleife).
    public void flush() {
        try {
            if (insertStmt != null && batchCount > 0) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Flush von tt1411_log fehlgeschlagen", e);
        }
    }
}
