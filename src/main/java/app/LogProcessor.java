package app;

import dbconnection.DbConnection;
import parser.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogProcessor {

    private static final Pattern TIMESTAMP_START_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");

    private static final Pattern TYPE_DETECT_PATTERN =
            Pattern.compile("(?:SENDF|RECVF)-LOGICAL:\\s*(.*)", Pattern.DOTALL);

    private static final Pattern TT1434_DETECT_PATTERN =
            Pattern.compile("^\\w+\\(\\d+\\s*->\\s*\\d+\\)\\(#\\d+\\)");

    private static final SdxpParser      sdxpParser      = new SdxpParser();
    private static final TT14Parser      tt14Parser      = new TT14Parser();
    private static final TT9001Parser    tt9001Parser    = new TT9001Parser();
    private static final ErrorRepository errorRepository = new ErrorRepository();

    public static synchronized ProcessingResult process(Path pfad) throws IOException, SQLException {
        // Standard: einzelne Datei -> vorher leeren (Rückwärtskompatibilität)
        return process(pfad, true);
    }

    public static synchronized ProcessingResult process(Path pfad, boolean clearFirst)
            throws IOException, SQLException {
        try {
            if (clearFirst) {
                truncateAll();
            }

            List<String> lines  = Files.readAllLines(pfad);
            List<String> blocks = splitIntoBlocks(lines);

            int erfolgreich    = 0;
            int fehlgeschlagen = 0;
            Map<String, Integer> breakdown = new LinkedHashMap<>();

            for (String block : blocks) {
                try {
                    String typ = verarbeiteBlock(block);
                    erfolgreich++;
                    breakdown.merge(typ, 1, Integer::sum);
                } catch (Exception e) {
                    fehlgeschlagen++;
                    String fehlertext = e.getMessage();
                    if (e.getCause() != null) {
                        fehlertext += " | Ursache: " + e.getCause().getMessage();
                    }
                    System.err.println("Fehler beim Verarbeiten eines Blocks: " + fehlertext);
                    errorRepository.insertError(block, fehlertext);
                }
            }

            sdxpParser.flush();
            tt14Parser.flush();
            tt9001Parser.flush();
            DbConnection.commit();

            return new ProcessingResult(blocks.size(), erfolgreich, fehlgeschlagen, breakdown);

        } catch (Exception e) {
            DbConnection.rollback();
            throw e;
        }
    }

    private static void truncateAll() throws SQLException {
        try (Statement stmt = DbConnection.getConnection().createStatement()) {
            stmt.execute("TRUNCATE TABLE sdxp_log, tt14_log, tt9001_log, error_log");
        }
    }

    private static List<String> splitIntoBlocks(List<String> lines) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;

        for (String line : lines) {
            if (TIMESTAMP_START_PATTERN.matcher(line).find()) {
                if (current != null) blocks.add(current.toString());
                current = new StringBuilder(line);
            } else if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (current != null) blocks.add(current.toString());
        return blocks;
    }

    private static String verarbeiteBlock(String block) {
        Matcher m = TYPE_DETECT_PATTERN.matcher(block);
        if (!m.find()) {
            throw new IllegalArgumentException("Keine Richtung (SENDF/RECVF-LOGICAL) im Block gefunden");
        }
        String tail = m.group(1).trim();

        if (tail.startsWith("TT9001:")) {
            var entry = tt9001Parser.parseBlock(block);
            tt9001Parser.insert(entry);
            return "TT9001";
        } else if (tail.startsWith("{")) {
            var entry = sdxpParser.parseLine(block);
            sdxpParser.insert(entry);
            return "SDXP";
        } else if (tail.startsWith("TT1411(")) {
            var entry = tt14Parser.parseLine(block, "TT1411");
            tt14Parser.insert(entry, "TT1411");
            return "TT1411";
        } else if (TT1434_DETECT_PATTERN.matcher(tail).find()) {
            var entry = tt14Parser.parseLine(block, "TT1434");
            tt14Parser.insert(entry, "TT1434");
            return "TT1434";
        } else {
            throw new IllegalArgumentException(
                    "Unbekannter Log-Typ, Block beginnt nach LOGICAL: mit: "
                            + tail.substring(0, Math.min(tail.length(), 40)));
        }
    }
}
