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
                          payload     JSONB
);

CREATE INDEX idx_sdxp_timestamp  ON sdxp_log ("timestamp");
CREATE INDEX idx_sdxp_sender     ON sdxp_log (sender);
CREATE INDEX idx_sdxp_empfaenger ON sdxp_log (empfaenger);
CREATE INDEX idx_sdxp_richtung   ON sdxp_log (richtung);
CREATE INDEX idx_sdxp_seq        ON sdxp_log (seq);
CREATE INDEX idx_sdxp_pairing    ON sdxp_log (seq, sender, empfaenger, richtung);

CREATE OR REPLACE VIEW sdxp_response_times AS
SELECT
    req."timestamp" AS request_ts,
    res."timestamp" AS response_ts,
    req.sender      AS sender,
    req.empfaenger  AS empfaenger,
    req.seq         AS seq,
    EXTRACT(EPOCH FROM (res."timestamp" - req."timestamp")) * 1000 AS response_ms
FROM sdxp_log req
         JOIN sdxp_log res
              ON req.seq        = res.seq
                  AND req.sender     = res.empfaenger
                  AND req.empfaenger = res.sender
                  AND res."timestamp" >= req."timestamp"
WHERE req.richtung = 'SENDF'
  AND res.richtung = 'RECVF'
  AND req.seq IS NOT NULL;

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