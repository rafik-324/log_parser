package parser.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record LogEntry(
        LocalDateTime timestamp,
        String typ,
        String sender,
        String empfaenger,
        String richtung,   // "SENDF" oder "RECVF"
        JsonNode payload
) {
}