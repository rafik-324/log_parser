package parser;

import dbconnection.DbConnection;
import parser.model.LogEntry;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

public class TT9001Parser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)-LOGICAL\\b");

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("(\\S+)\\s*<->\\s*(\\S+)");

    private static final String XML_END_MARKER = "TT9001:";

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

        Matcher h = HEADER_PATTERN.matcher(firstLine);
        if (!h.find()) {
            throw new IllegalArgumentException("Kein <eigeneKennung><->PCX> gefunden: " + firstLine);
        }
        String eigeneKennung = h.group(1).trim();
        String fremdeKennung = h.group(2).trim();

        int dashIdx = fremdeKennung.indexOf('-');
        if (dashIdx >= 0) {
            fremdeKennung = fremdeKennung.substring(0, dashIdx);
        }

        if ("1".equals(eigeneKennung)) {
            eigeneKennung = "MFS";
        }

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

    public void insert(LogEntry entry) {
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            stmt.setTimestamp(1, Timestamp.valueOf(entry.timestamp()));
            stmt.setString(2, entry.typ());
            stmt.setString(3, entry.sender());
            stmt.setString(4, entry.empfaenger());
            stmt.setString(5, entry.richtung());
            stmt.setString(6, entry.payload() != null ? entry.payload().asText() : null);

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Insert in tt9001_log fehlgeschlagen: " + entry, e);
        }
    }

    public static void main(String[] args) {
        TT9001Parser parser = new TT9001Parser();

        String testBlock = """
                2026-02-20 13:31:40.169 | DEBUG | 1<->PCX-ORDER-START-PRINTER05(9005))-473 |        | RECVF-LOGICAL: TT9001: <?xml version="1.0" encoding="UTF-8"?>
                <response>
                <labeling box="75238435" order="label" from="AIP05"/>
                </response>
                 // TT9001:
                """;

        LogEntry entry = parser.parseBlock(testBlock);

        System.out.println("Zeitstempel:   " + entry.timestamp());
        System.out.println("Typ:           " + entry.typ());
        System.out.println("Sender:        " + entry.sender());
        System.out.println("Empfänger:     " + entry.empfaenger());
        System.out.println("Richtung:      " + entry.richtung());
        System.out.println("Payload (XML): " + entry.payload().asText());
    }
}