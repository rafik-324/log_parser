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

public class TT1434Parser {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern DIRECTION_PATTERN =
            Pattern.compile("\\b(SENDF|RECVF)-LOGICAL\\b");

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^(\\w+)\\((\\d+)\\s*->\\s*(\\d+)\\)(.*)$", Pattern.DOTALL);

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
        int directionEnd = -1;
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

        Matcher h = HEADER_PATTERN.matcher(tail);
        if (!h.find()) {
            throw new IllegalArgumentException("Kein 'TYP(sender->empfaenger)...' gefunden: " + tail);
        }
        String typ = h.group(1).trim();
        String sender = h.group(2).trim();
        String empfaenger = h.group(3).trim();
        String payloadText = h.group(4).trim();

        var payload = TextNode.valueOf(payloadText);

        return new LogEntry(timestamp, typ, sender, empfaenger, richtung, payload);
    }

    private static final String INSERT_SQL =
            "INSERT INTO tt1434_log (\"timestamp\", typ, sender, empfaenger, richtung, payload) " +
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
            throw new RuntimeException("Insert in tt1434_log fehlgeschlagen: " + entry, e);
        }
    }

    public void flush() {
        try {
            if (insertStmt != null && batchCount > 0) {
                insertStmt.executeBatch();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Flush von tt1434_log fehlgeschlagen", e);
        }
    }

    public static void main(String[] args) {
        TT1434Parser parser = new TT1434Parser();

        String testLine = "2026-02-20 13:35:06.461 | DEBUG |  mfsBin-WaSocDaemon(NGKP2:201<->102)-411 |        | RECVF-LOGICAL: TT1434(102->201)(#0) LSD[XX--|DISABLED:UNCLEAR:----|UNCLEAR:----|UNCLEAR:----] report SRMState AUTOMATIC, waiting for followup  active: 0 buffered: 0 position: R(2:83,2,R,0) // TT14xxExternalLocationWithSmallX:aisle=2|x=83|y=2|side=2|depth=0 // TT1434LsdStateVersion1:senderId=102|receiverId=201|type=1434|subType=0|version=1|topic=1|requestId=0|systemState1={2}|systemState2={0, 2}|stateLSD1={0, 1, 7}|stateLSD2={}|stateLSD3={}|stateLSD4={}|amountOfTOsInBuffer=0|srm=2|aisle=2|x=83|y=2|side=2|depth=0|activeOrder=0|bufferedOrder=0|unused=000000010001000100010000313030303434393636000000000000000000000003E900000001000100010001000031303030353133373200";

        LogEntry entry = parser.parseLine(testLine);

        System.out.println("Zeitstempel: " + entry.timestamp());
        System.out.println("Typ:         " + entry.typ());
        System.out.println("Sender:      " + entry.sender());
        System.out.println("Empfänger:   " + entry.empfaenger());
        System.out.println("Richtung:    " + entry.richtung());
        System.out.println("Payload:     " + entry.payload().asText());
    }
}
