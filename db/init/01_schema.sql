-- =========================================================
-- Tabelle 1: SDXP-Telegramme
-- =========================================================
CREATE TABLE sdxp_log (
                          id          BIGSERIAL PRIMARY KEY,
                          "timestamp" TIMESTAMP    NOT NULL,
                          typ         VARCHAR(100) NOT NULL,
                          sender      VARCHAR(100) NOT NULL,
                          empfaenger  VARCHAR(100) NOT NULL,
                          richtung    VARCHAR(10)  NOT NULL,
                          seq         BIGINT,
                          ackseq      BIGINT,
                          payload     JSONB
);

CREATE INDEX idx_sdxp_timestamp  ON sdxp_log ("timestamp");
CREATE INDEX idx_sdxp_sender     ON sdxp_log (sender);
CREATE INDEX idx_sdxp_empfaenger ON sdxp_log (empfaenger);
CREATE INDEX idx_sdxp_richtung   ON sdxp_log (richtung);

-- Gezielte Indizes fuer das Request/Response-Pairing der View:
--   Requests  = eingehende RECVF-Telegramme mit seq
--   Responses = ausgehende SENDF-Telegramme mit ackseq
CREATE INDEX idx_sdxp_req ON sdxp_log (seq, "timestamp")
    WHERE richtung = 'RECVF' AND seq IS NOT NULL;
CREATE INDEX idx_sdxp_res ON sdxp_log (ackseq, "timestamp")
    WHERE richtung = 'SENDF' AND ackseq IS NOT NULL;

-- =========================================================
-- View: SDXP-Antwortzeiten
-- ---------------------------------------------------------
-- Miss die MFS-interne Bearbeitungszeit pro Telegramm:
--   Anfrage  = RECVF (kommt rein, traegt "seq")
--   Antwort  = SENDF (geht raus,  traegt "ackSeq" = seq der Anfrage)
--   response_ms = Zeit von Anfrage-Empfang bis Antwort-Versand
--
-- Robustheit gegen mehrfach vergebene seq-Nummern:
--   - Es wird nur innerhalb derselben Linie (Praefix von rpMark, z.B. "C01") gepaart.
--   - Pro Antwort wird die zeitlich naechste vorausgehende Anfrage gewaehlt
--     (ORDER BY ... DESC LIMIT 1) innerhalb eines 30-Sekunden-Fensters.
-- =========================================================
DROP VIEW IF EXISTS sdxp_response_times CASCADE;
CREATE VIEW sdxp_response_times AS
SELECT
    req."timestamp"                                                 AS request_ts,
    res."timestamp"                                                 AS response_ts,
    split_part(req.payload ->> 'rpMark', '/', 1)                    AS line,
    req.payload ->> 'rpMark'                                         AS rp_mark,
        req.payload ->> 'func'                                          AS func_request,
        res.payload ->> 'func'                                          AS func_response,
        req.payload ->> 'luId'                                          AS lu_id,
        req.seq                                                         AS seq,
        EXTRACT(EPOCH FROM (res."timestamp" - req."timestamp")) * 1000  AS response_ms
        FROM sdxp_log res
        CROSS JOIN LATERAL (
        SELECT q.*
        FROM sdxp_log q
        WHERE q.richtung = 'RECVF'
        AND q.seq      = res.ackseq
        AND split_part(q.payload   ->> 'rpMark', '/', 1)
        = split_part(res.payload ->> 'rpMark', '/', 1)
        AND q."timestamp" <= res."timestamp"
        AND q."timestamp" >  res."timestamp" - INTERVAL '30 seconds'
        ORDER BY q."timestamp" DESC
        LIMIT 1
        ) req
        WHERE res.richtung = 'SENDF'
        AND res.ackseq IS NOT NULL;

-- =========================================================
-- View: SDXP-Ausreisser
-- ---------------------------------------------------------
-- Baut auf sdxp_response_times auf und bewertet jede Antwortzeit
-- gegen den Durchschnitt. Ausgegeben werden:
--   pct_diff_overall  = Abweichung vom GLOBALEN Durchschnitt (%)
--   pct_diff_line     = Abweichung vom Durchschnitt DER LINIE (%)  <- meist aussagekraeftiger
--   z_score_line      = Abweichung in Standardabweichungen (robuster gegen Ausreisser)
--
-- Vorzeichen: positiv = langsamer als der Schnitt, negativ = schneller.
-- Die eigentliche Schwelle wird NICHT hier festgelegt, sondern beim Abfragen
-- (z.B. in Grafana ueber eine Variable) - so bleibt sie frei einstellbar:
--   ... WHERE pct_diff_line > 100      -> nur langsame Ausreisser, > 100% ueber Schnitt
--   ... WHERE abs(pct_diff_line) > 100 -> Abweichung in beide Richtungen
--
-- line_sample_count hilft, Linien mit zu wenig Datenpunkten auszublenden
-- (ein Durchschnitt aus 2 Werten ist noch kein belastbarer Baseline).
-- =========================================================
DROP VIEW IF EXISTS sdxp_response_outliers;
CREATE VIEW sdxp_response_outliers AS
WITH base AS (
    SELECT
        request_ts,
        response_ts,
        line,
        rp_mark,
        func_request,
        func_response,
        lu_id,
        seq,
        response_ms,
        avg(response_ms)        OVER ()                  AS overall_avg_ms,
        avg(response_ms)        OVER (PARTITION BY line) AS line_avg_ms,
        stddev_pop(response_ms) OVER (PARTITION BY line) AS line_stddev_ms,
        count(*)                OVER (PARTITION BY line) AS line_sample_count
    FROM sdxp_response_times
)
SELECT
    base.*,
    round((response_ms - overall_avg_ms) / NULLIF(overall_avg_ms, 0) * 100, 1) AS pct_diff_overall,
    round((response_ms - line_avg_ms)    / NULLIF(line_avg_ms, 0)    * 100, 1) AS pct_diff_line,
    CASE WHEN line_stddev_ms > 0
             THEN round((response_ms - line_avg_ms) / line_stddev_ms, 2)
         ELSE NULL END                                                          AS z_score_line
FROM base;

-- =========================================================
-- Tabelle 2: TT14-Telegramme (TT1411 + TT1434 kombiniert)
-- =========================================================
CREATE TABLE tt14_log (
                          id           BIGSERIAL PRIMARY KEY,
                          "timestamp"  TIMESTAMP    NOT NULL,
                          subtype      VARCHAR(10)  NOT NULL,   -- 'TT1411' oder 'TT1434'
                          typ          VARCHAR(100) NOT NULL,
                          sender       VARCHAR(100) NOT NULL,
                          empfaenger   VARCHAR(100) NOT NULL,
                          richtung     VARCHAR(10)  NOT NULL,
                          payload_text TEXT
);

CREATE INDEX idx_tt14_timestamp  ON tt14_log ("timestamp");
CREATE INDEX idx_tt14_subtype    ON tt14_log (subtype);
CREATE INDEX idx_tt14_sender     ON tt14_log (sender);
CREATE INDEX idx_tt14_empfaenger ON tt14_log (empfaenger);

-- =========================================================
-- Tabelle 3: TT9001-Telegramme
-- =========================================================
CREATE TABLE tt9001_log (
                            id          BIGSERIAL PRIMARY KEY,
                            "timestamp" TIMESTAMP    NOT NULL,
                            typ         VARCHAR(100) NOT NULL,
                            sender      VARCHAR(100) NOT NULL,
                            empfaenger  VARCHAR(100) NOT NULL,
                            richtung    VARCHAR(10)  NOT NULL,
                            payload_xml TEXT
);

CREATE INDEX idx_tt9001_timestamp  ON tt9001_log ("timestamp");
CREATE INDEX idx_tt9001_sender     ON tt9001_log (sender);
CREATE INDEX idx_tt9001_empfaenger ON tt9001_log (empfaenger);
CREATE INDEX idx_tt9001_richtung   ON tt9001_log (richtung);

-- =========================================================
-- Tabelle 4: Fehlerhafte Eintraege
-- =========================================================
CREATE TABLE error_log (
                           id         BIGSERIAL PRIMARY KEY,
                           erfasst_am TIMESTAMP NOT NULL DEFAULT now(),
                           fehlertext TEXT,
                           roh_block  TEXT NOT NULL
);

CREATE INDEX idx_error_erfasst_am ON error_log (erfasst_am);