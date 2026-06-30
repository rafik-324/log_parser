package parser;

/**
 * Zentrale Stelle zum Bereinigen von Strings vor dem Insert in Postgres.
 * Postgres lehnt 0x00-Bytes in TEXT-Spalten ab ("invalid byte sequence for
 * encoding UTF8"). Solche Bytes können in rohen Logdaten vorkommen, z.B. wenn
 * ein fest-breites Binärfeld (wie eine TU-ID) mit Null-Bytes aufgefüllt wird.
 */
public final class TextSanitizer {

    private TextSanitizer() {
        // keine Instanzen
    }

    public static String sanitize(String s) {
        return s == null ? null : s.replace("\u0000", "");
    }
}
