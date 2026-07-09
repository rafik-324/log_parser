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

/**
 * Parst TT14-Zeilen für die Subtypen {@code TT1411} und {@code TT1434} und schreibt
 * sie gebatcht in {@code tt14_log}. Der jeweilige Subtyp wird an
 * {@link #parseLine(String, String)} / {@link #insert(LogEntry, String)} übergeben
 * und steuert die Parsing-Regeln.
 */
public class TT14Parser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)-LOGICAL\\b");

    // TT1411: TT1411(sender->empfaenger) #xxx: payload
    private static final Pattern TT1411_BODY_PATTERN =
            Pattern.compile("([^\\s(|]+)\\((.+?)->(.+?)\\)");

    private static final Pattern TT1411_PAYLOAD_PATTERN =
            Pattern.compile("#[^:]*:\\s*(.+)$");

    // TT1434: TT1434(102->201)(#0) rest...
    private static final Pattern TT1434_HEADER_PATTERN =
            Pattern.compile("^(\\w+)\\((\\d+)\\s*->\\s*(\\d+)\\)(.*)$", Pattern.DOTALL);

    /**
     * Parst eine TT14-Zeile abhängig vom {@code subtype}.
     * <p>
     * Nach der Richtungsangabe wird ab dem ersten {@code :} abgeschnitten; der Rest
     * wird für {@code TT1411} als {@code typ(sender->empfaenger) #..: payload}, sonst
     * ({@code TT1434}) als {@code TYP(sender->empfaenger) payload} zerlegt. Bei TT1411
     * ist der Payload optional (dann {@code null}).
     *
     * @param subtype {@code "TT1411"}, alles andere wird als {@code TT1434} behandelt
     * @throws IllegalArgumentException bei fehlendem {@code |}, fehlender Richtung
     *         oder nicht erkennbarem Header
     */
    public LogEntry parseLine(String line, String subtype) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Leere Zeile");
        }

        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) {
            throw new IllegalArgumentException("Kein '|' gefunden: " + line);
        }
        LocalDateTime timestamp = LocalDateTime.parse(line.substring(0, firstPipe).trim(), TS_FORMAT);

        Matcher d = DIRECTION_PATTERN.matcher(line);
        String richtung = null;
        int directionEnd = -1;
        // letzte Richtungsangabe gewinnt (frühere Treffer können in Präfix-Feldern stehen)
        while (d.find()) {
            richtung = d.group(1);
            directionEnd = d.end();
        }
        if (richtung == null) {
            throw new IllegalArgumentException("Keine Richtung (SENDF/RECVF-LOGICAL) gefunden: " + line);
        }

        String afterDirection = line.substring(directionEnd);
        int colonIdx = afterDirection.indexOf(':');
        String tail = (colonIdx >= 0 ? afterDirection.substring(colonIdx + 1) : afterDirection).trim();

        String typ, sender, empfaenger;
        String payloadText = null;

        if ("TT1411".equals(subtype)) {
            Matcher b = TT1411_BODY_PATTERN.matcher(tail);
            if (!b.find()) {
                throw new IllegalArgumentException("Kein typ(sender->empfaenger) gefunden: " + tail);
            }
            typ        = b.group(1).trim();
            sender     = b.group(2).trim();
            empfaenger = b.group(3).trim();

            String afterBody = tail.substring(b.end());
            Matcher p = TT1411_PAYLOAD_PATTERN.matcher(afterBody);
            if (p.find()) {
                payloadText = p.group(1).trim();
            }
        } else {
            // TT1434
            Matcher h = TT1434_HEADER_PATTERN.matcher(tail);
            if (!h.find()) {
                throw new IllegalArgumentException("Kein TYP(sender->empfaenger)... gefunden: " + tail);
            }
            typ        = h.group(1).trim();
            sender     = h.group(2).trim();
            empfaenger = h.group(3).trim();
            payloadText = h.group(4).trim();
        }

        var payload = payloadText != null ? TextNode.valueOf(payloadText) : null;
        return new LogEntry(timestamp, typ, sender, empfaenger, richtung, payload);
    }

    private static final String INSERT_SQL =
            "INSERT INTO tt14_log (\"timestamp\", subtype, typ, sender, empfaenger, richtung, payload_text) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final int BATCH_SIZE = 500;

    private PreparedStatement insertStmt;
    private int batchCount = 0;

    public void insert(LogEntry entry, String subtype) {
        try {
            if (insertStmt == null) {
                insertStmt = DbConnection.getConnection().prepareStatement(INSERT_SQL);
            }
            insertStmt.setTimestamp(1, Timestamp.valueOf(entry.timestamp()));
            insertStmt.setString(2, subtype);
            insertStmt.setString(3, TextSanitizer.sanitize(entry.typ()));
            insertStmt.setString(4, TextSanitizer.sanitize(entry.sender()));
            insertStmt.setString(5, TextSanitizer.sanitize(entry.empfaenger()));
            insertStmt.setString(6, TextSanitizer.sanitize(entry.richtung()));
            insertStmt.setString(7, entry.payload() != null
                    ? TextSanitizer.sanitize(entry.payload().asText())
                    : null);
            insertStmt.addBatch();
            batchCount++;

            if (batchCount >= BATCH_SIZE) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Insert in tt14_log fehlgeschlagen: " + entry, e);
        }
    }

    public void flush() {
        try {
            if (insertStmt != null && batchCount > 0) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Flush von tt14_log fehlgeschlagen", e);
        }
    }
}