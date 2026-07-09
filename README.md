# LogParser

Parst Log-Dateien verschiedener Fördertechnik-/MFS-Protokolle (SDXP, TT14XX, TT9001) und stellt die extrahierten Kennzahlen über PostgreSQL und Grafana als Dashboards bereit. Lokal erreichbar über einen eingebetteten Javalin-Webserver.

## Voraussetzungen

- Docker
- Java 21 und Maven (falls lokal gebaut wird)

## Installation

1. Repo klonen

   ```bash
   git clone https://github.com/rafik-324/log_parser 
   cd log_praser
   ```

2. `.env` Datei anlegen

3. Container starten

   ```bash
   docker-compose up --build
   ```

   Beim nächsten Start:
   ```bash
   docker-compose up -d
   ```

   Startet: App (Javalin-Server + Parser), PostgreSQL (Schema aus `db/init/01_schema.sql`), Grafana (Dashboards/Datasource werden automatisch provisioniert).

4. Zugriff

    - App (Datei-Upload): `http://localhost:8080`

## Ports

| Port | Dienst |
|---|---|
| `8080` | App (Javalin) |
| `3000` | Grafana |
| `5432` | PostgreSQL |

Alle Dienste laufen ausschließlich lokal (`localhost`) und sind standardmäßig nicht im Netzwerk erreichbar.

## Nutzung

1. Log-Datei im Browser hochladen: **http://localhost:8080**
2. Die Datei wird serverseitig anhand der Zeitstempel in Blöcke unterteilt. Innerhalb einer Datei dürfen verschiedene Log-Typen gemischt vorkommen — jeder Block wird automatisch dem passenden Parser zugeordnet (`SdxpParser`, `TT14Parser`, `TT1411Parser`, `TT1434Parser`, `TT9001Parser`).
3. Die geparsten Einträge werden in PostgreSQL geschrieben.
4. Grafana liest die Daten aus PostgreSQL und stellt sie mit diversen berechneten KPIs in Dashboard Form dar.

Nicht erkannte/fehlerhafte Blöcke landen im `ErrorRepository` statt den Upload abzubrechen.

## Unterstützte Log-Formate

Formate vom Typ SDXP, TT9001 und TT14XX.

Fehlerhafte/nicht erkannte Zeilen werden über `ErrorRepository` protokolliert statt den Lauf abzubrechen. Sie sind anschließend sowohl in Grafana als auch in der DB detailliert einsehbar.

## Datenmodell

Das DB-Schema liegt in [`db/init/01_schema.sql`](db/init/01_schema.sql), dort direkt kommentiert.

## Grafana Dashboards

Vorgefertigte Dashboards liegen in [`grafana/provisioning/dashboards/`](grafana/provisioning/dashboards/) (`log_dahboard.json`, `sdxp_dashboard.json`) und werden beim `docker-compose up` automatisch geladen. Datasource-Konfiguration in [`grafana/provisioning/datasources/datasource.yml`](grafana/provisioning/datasources/datasource.yml).

Für Anpassungen an den Dashboards oder zum Einsehen der Logik `GF_AUTH_DISABLE_LOGIN_FORM: "true"` in der Compose auf `"false"` setzen. Anschließend unter http://localhost:3000/login anmelden. Im Reiter Dashboards stehen diese dann vollständig zur Einsicht und Bearbeitung zur Verfügung.

## Troubleshooting

| Problem | Lösung |
|---|---|
| Container startet nicht | `.env` prüfen, falls nicht vorhanden erstellen |
| Keine Daten in Grafana | Zeitraum im Dashboard prüfen, Zeitzone der Logs vs. Grafana checken |
| Checkpoint (z.B. C03) fehlt in Auswertung | Kann an `rpMark = NULL` liegen — siehe Kommentare in `01_schema.sql` |
| Log-Zeile wird nicht geparst | Format entspricht keinem der unterstützten Parser — Eintrag landet in `ErrorRepository`, dort nachschauen |
| Login für Grafana funktioniert nicht | Browser-Cookies löschen und `docker compose up --build` |

## Projektstruktur

```
db/
  init/
    01_schema.sql       → Tabellen, Views, Berechnungslogik (kommentiert)
grafana/
  provisioning/
    dashboards/         → dashboard.yml + exportierte Dashboard-JSONs
    datasources/        → datasource.yml
src/main/java/app/
  Main                  → Einstiegspunkt
  WebServer             → Javalin-Server, Endpunkte
  LogProcessor          → orchestriert Parsing
  ProcessingResult      → Ergebnis-Objekt eines Parse-Durchlaufs
  dbconnection/
    DbConnection
  parser/
    ErrorRepository     → Sammelt/loggt nicht parsbare Zeilen
    TextSanitizer       → Vorverarbeitung der Log-Zeilen
    SdxpParser, TT14Parser, TT1411Parser, TT1434Parser, TT9001Parser
    model/
      LogEntry          → Datenmodell einer geparsten Zeile
docker-compose.yml
Dockerfile
pom.xml
```

## Dev

Version 1.0 — entwickelt von Rafael Krafczyk