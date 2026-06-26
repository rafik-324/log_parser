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

public class TT1411Parser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)-LOGICAL\\b");

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
            throw new RuntimeException("Insert in tt1411_log fehlgeschlagen: " + entry, e);
        }
    }

    public static void main(String[] args) {
        TT1411Parser parser = new TT1411Parser();
        String testLine = """
                2026-02-20 13:31:01.190 | DEBUG |  mfsBin-WaSocDaemon(NGKP2:201<->101)-436 |        | RECVF-LOGICAL: TT1411(101->201)#853401: LHD1:[PUT 100061422->R(1:1,8,R,2)|14001(SUCCESSFUL_COMPLETION)][relevantTelBlock=1] // TT1411Subtype1x2yVersion0:senderId=101|receiverId=201""";

        var entry = parser.parseLine(testLine);

        System.out.println("Zeitstempel: " + entry.timestamp());
        System.out.println("Typ:         " + entry.typ());
        System.out.println("Sender:      " + entry.sender());
        System.out.println("Empfänger:   " + entry.empfaenger());
        System.out.println("Richtung:    " + entry.richtung());
        System.out.println("Payload:     " + (entry.payload() != null ? entry.payload().asText() : "kein Payload"));
    }
}
