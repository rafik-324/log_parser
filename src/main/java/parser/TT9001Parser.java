package parser;

import dbconnection.DbConnection;
import parser.model.LogEntry;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Parst mehrzeilige TT9001-Blöcke (Header-Zeile + darüber hinausgehender XML-Payload)
 * und schreibt sie gebatcht in {@code tt9001_log}.
 * <p>
 * Ein Block gilt nur dann als vollständig, wenn er den End-Marker {@code TT9001:} enthält.
 */
public class TT9001Parser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)-LOGICAL\\b");

    private static final Pattern HEADER_PATTERN_ARROW =
            Pattern.compile("(\\S+)\\s*<->\\s*(\\S+)");

    private static final String XML_END_MARKER = "TT9001:";

    /**
     * Parst einen kompletten TT9001-Block (erste Zeile = Header, Folgezeilen = XML).
     * <p>
     * Der Header steht im 3. {@code |}-Feld und wird in zwei Formaten unterstützt:
     * {@code eigene<->fremd} (Format A) oder {@code eigene-fremd} (Format B, erstes
     * {@code -} trennt). Sender/Empfänger ergeben sich anschließend aus der Richtung
     * (bei SENDF sind wir Sender, bei RECVF Empfänger).
     *
     * @throws IllegalArgumentException bei fehlendem {@code |}, fehlender Richtung,
     *         unklarem Header, zu wenigen {@code |}-Feldern oder fehlendem End-Marker
     *         {@code TT9001:} (unvollständiger Block)
     */
    public LogEntry parseBlock(String block) {
        if (block == null || block.isBlank()) {
            throw new IllegalArgumentException("Leerer Block");
        }

        String[] blockLines = block.split("\n", -1);
        String firstLine = blockLines[0];

        int firstPipe = firstLine.indexOf('|');
        if (firstPipe < 0) {
            throw new IllegalArgumentException("Kein '|' gefunden: " + firstLine);
        }
        LocalDateTime timestamp = LocalDateTime.parse(firstLine.substring(0, firstPipe).trim(), TS_FORMAT);

        Matcher d = DIRECTION_PATTERN.matcher(firstLine);
        String richtung = null;
        int directionEnd = -1;
        while (d.find()) {
            richtung = d.group(1);
            directionEnd = d.end();
        }
        if (richtung == null) {
            throw new IllegalArgumentException("Keine Richtung (SENDF/RECVF-LOGICAL) gefunden: " + firstLine);
        }

        String[] pipeFields = firstLine.split("\\|", 5);
        if (pipeFields.length < 5) {
            throw new IllegalArgumentException("Erwarte mind. 5 durch '|' getrennte Felder: " + firstLine);
        }
        String headerField = pipeFields[2].trim();

        String eigeneKennung;
        String fremdeKennung;

        Matcher h = HEADER_PATTERN_ARROW.matcher(headerField);
        if (h.find()) {
            // Format A: "<eigeneKennung><->fremdeKennung>"
            eigeneKennung = h.group(1).trim();
            fremdeKennung = h.group(2).trim();
        } else {
            // Format B: erstes '-' trennt eigene von fremder Kennung
            int firstDash = headerField.indexOf('-');
            if (firstDash < 0) {
                throw new IllegalArgumentException(
                        "Kein '<->' und kein '-' im Header gefunden: " + headerField);
            }
            eigeneKennung = headerField.substring(0, firstDash).trim();
            fremdeKennung = headerField.substring(firstDash + 1).trim();
        }

        // evtl. angehängtes Instanz-/Portsuffix hinter erstem '-' abschneiden
        int dashIdx = fremdeKennung.indexOf('-');
        if (dashIdx >= 0) {
            fremdeKennung = fremdeKennung.substring(0, dashIdx);
        }

        // eigene Kennung "1" bezeichnet immer den MFS
        if ("1".equals(eigeneKennung)) {
            eigeneKennung = "MFS";
        }

        // Sender/Empfänger richten sich nach der Senderichtung
        String sender;
        String empfaenger;
        if ("SENDF".equals(richtung)) {
            sender = eigeneKennung;
            empfaenger = fremdeKennung;
        } else {
            sender = fremdeKennung;
            empfaenger = eigeneKennung;
        }

        String afterDirection = firstLine.substring(directionEnd);
        int colonIdx = afterDirection.indexOf(':');
        String tail = colonIdx >= 0 ? afterDirection.substring(colonIdx + 1) : afterDirection;

        // alles vor dem ersten '<' ist der Typ, ab '<' beginnt das XML
        int xmlStartInTail = tail.indexOf('<');
        String typ;
        StringBuilder xml = new StringBuilder();
        if (xmlStartInTail < 0) {
            typ = tail.trim();
        } else {
            typ = tail.substring(0, xmlStartInTail).trim();
            xml.append(tail.substring(xmlStartInTail));
        }

        for (int i = 1; i < blockLines.length; i++) {
            if (xml.length() > 0) {
                xml.append('\n');
            }
            xml.append(blockLines[i]);
        }

        // ohne End-Marker ist der Block abgeschnitten -> nicht als gültig akzeptieren
        if (!xml.toString().contains(XML_END_MARKER)) {
            throw new IllegalArgumentException(
                    "Block enthält kein '" + XML_END_MARKER + "' - unvollständiger TT9001-Block: " + block);
        }

        var payload = TextNode.valueOf(xml.toString().trim());

        return new LogEntry(timestamp, typ, sender, empfaenger, richtung, payload);
    }

    private static final String INSERT_SQL =
            "INSERT INTO tt9001_log (\"timestamp\", typ, sender, empfaenger, richtung, payload_xml) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";

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
                    ? TextSanitizer.sanitize(entry.payload().asText())
                    : null);
            insertStmt.addBatch();
            batchCount++;

            if (batchCount >= BATCH_SIZE) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Insert in tt9001_log fehlgeschlagen: " + entry, e);
        }
    }

    public void flush() {
        try {
            if (insertStmt != null && batchCount > 0) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Flush von tt9001_log fehlgeschlagen", e);
        }
    }

}