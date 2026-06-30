package app;

import parser.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static final Pattern TIMESTAMP_START_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");

    private static final Pattern TYPE_DETECT_PATTERN =
            Pattern.compile("(?:SENDF|RECVF)-LOGICAL:\\s*(.*)", Pattern.DOTALL);

    private static final Pattern TT1434_DETECT_PATTERN =
            Pattern.compile("^\\w+\\(\\d+\\s*->\\s*\\d+\\)\\(#\\d+\\)");

    private static final SdxpParser sdxpParser = new SdxpParser();
    private static final TT1411Parser tt1411Parser = new TT1411Parser();
    private static final TT9001Parser tt9001Parser = new TT9001Parser();
    private static final TT1434Parser tt1434Parser = new TT1434Parser();
    private static final ErrorRepository errorRepository = new ErrorRepository();

    public static void main(String[] args) throws IOException {
        String pfad = args.length > 0 ? args[0] : "testlog.txt";

        List<String> lines = Files.readAllLines(Path.of(pfad));
        List<String> blocks = splitIntoBlocks(lines);

        int erfolgreich = 0;
        int fehlgeschlagen = 0;

        for (String block : blocks) {
            try {
                verarbeiteBlock(block);
                erfolgreich++;
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

        // Alle gesammelten Batches aller Parser wegschreiben.
        sdxpParser.flush();
        tt1411Parser.flush();
        tt9001Parser.flush();
        tt1434Parser.flush();

        try {
            dbconnection.DbConnection.closeConnection();
        } catch (java.sql.SQLException e) {
            System.err.println("Fehler beim abschliessenden Commit/Schliessen der Verbindung: " + e.getMessage());
        }

        System.out.println("Fertig. Erfolgreich: " + erfolgreich + ", Fehlgeschlagen: " + fehlgeschlagen);
    }

    private static List<String> splitIntoBlocks(List<String> lines) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;

        for (String line : lines) {
            if (TIMESTAMP_START_PATTERN.matcher(line).find()) {
                if (current != null) {
                    blocks.add(current.toString());
                }
                current = new StringBuilder(line);
            } else if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (current != null) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private static void verarbeiteBlock(String block) {
        Matcher m = TYPE_DETECT_PATTERN.matcher(block);
        if (!m.find()) {
            throw new IllegalArgumentException("Keine Richtung (SENDF/RECVF-LOGICAL) im Block gefunden");
        }
        String tail = m.group(1).trim();

        if (tail.startsWith("TT9001:")) {
            var entry = tt9001Parser.parseBlock(block);
            tt9001Parser.insert(entry);
        } else if (tail.startsWith("{")) {
            var entry = sdxpParser.parseLine(block);
            sdxpParser.insert(entry);
        } else if (tail.startsWith("TT1411(")) {
            var entry = tt1411Parser.parseLine(block);
            tt1411Parser.insert(entry);
        } else if (TT1434_DETECT_PATTERN.matcher(tail).find()) {
            var entry = tt1434Parser.parseLine(block);
            tt1434Parser.insert(entry);
        } else {
            throw new IllegalArgumentException(
                    "Unbekannter Log-Typ, Block beginnt nach LOGICAL: mit: "
                            + tail.substring(0, Math.min(tail.length(), 40)));
        }
    }
}
