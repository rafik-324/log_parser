# Berechnungen & Kennzahlen

Die Rechenlogik ist bewusst in den PostgreSQL-Views gekapselt: Java bereitet nur
die Daten auf, Grafana visualisiert nur. So ist jede Kennzahl an einer einzigen
Stelle nachvollziehbar.

## SDXP-Antwortzeit (MFS-Bearbeitungszeit)

Gemessen wird die Zeit zwischen dem Empfang einer Anfrage und dem Versand der
zugehörigen Antwort – in drei Schichten:

**1. Java (`SdxpParser`)** extrahiert `seq` (Sequenznummer der Anfrage) und
`ackseq` (Bezug der Antwort darauf) aus dem JSON-Payload und legt sie als
indizierte Spalten in `sdxp_log` ab. Keine Berechnung – nur die Matching-Felder.

**2. View `sdxp_response_times`** paart Antwort (`SENDF`, trägt `ackseq`) mit
Anfrage (`RECVF`, trägt `seq`) über `ackseq = seq` und berechnet `response_ms`
als Zeitdifferenz. Da `seq`-Nummern im Betrieb wiederverwendet werden, wird das
Pairing eingegrenzt: nur innerhalb derselben Linie (Präfix von `rpMark`, z.B.
`C01`), nur die zeitlich nächste vorausgehende Anfrage, nur innerhalb eines
30-Sekunden-Fensters.

**3. Grafana** liest nur aus der View (z.B. `AVG(response_ms)` über die Zeit).

## Ausreißer-Erkennung (`sdxp_response_outliers`)

Baut auf `sdxp_response_times` auf und bewertet jede Antwortzeit gegen den
Durchschnitt:

| Kennzahl | Bedeutung |
|---|---|
| `pct_diff_overall` | Abweichung vom globalen Durchschnitt (%) |
| `pct_diff_line`    | Abweichung vom Linien-Durchschnitt (%) – meist aussagekräftiger |
| `z_score_line`     | Abweichung in Standardabweichungen je Linie (robuster gegen Extremwerte) |
| `line_sample_count`| Datenpunkte je Linie – zum Ausblenden zu kleiner Baselines |

Vorzeichen: positiv = langsamer, negativ = schneller als der Schnitt. Die
Ausreißer-**Schwelle** liegt bewusst nicht in der View, sondern wird erst beim
Abfragen gesetzt (z.B. Grafana-Variable) und bleibt so frei einstellbar:

```sql
... WHERE pct_diff_line > 100        -- nur langsame Ausreißer
... WHERE abs(pct_diff_line) > 100   -- beide Richtungen
```

Der Durchschnitt wird pro Linie gebildet – eine bewusste Näherung. Feiner wäre
pro `rpMark`, aber mit weniger Datenpunkten je Gruppe.

## Kette im Überblick

```
seq / ackseq (Java)  →  Spalten in sdxp_log  →  sdxp_response_times
                     →  sdxp_response_outliers  →  Grafana
```

Vollständige View-Definitionen mit Kommentaren:
[`db/init/01_schema.sql`](../db/init/01_schema.sql).