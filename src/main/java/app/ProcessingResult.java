package app;

import java.util.Map;

public record ProcessingResult(
        int total,
        int erfolgreich,
        int fehlgeschlagen,
        Map<String, Integer> breakdown
) {
}
