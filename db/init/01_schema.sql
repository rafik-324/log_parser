-- =========================================================
-- Tabelle 1: SDXP-Telegramme (Parser: SdxpParser)
-- payload = echtes JSON-Objekt
-- =========================================================
CREATE TABLE sdxp_log (
                          id          BIGSERIAL PRIMARY KEY,
                          "timestamp" TIMESTAMP NOT NULL,
                          typ         VARCHAR(100) NOT NULL,
                          sender      VARCHAR(100) NOT NULL,
                          empfaenger  VARCHAR(100) NOT NULL,
                          richtung    VARCHAR(10)  NOT NULL,   -- 'SENDF' oder 'RECVF'
                          payload     JSONB
);

CREATE INDEX idx_sdxp_timestamp  ON sdxp_log ("timestamp");
CREATE INDEX idx_sdxp_sender     ON sdxp_log (sender);
CREATE INDEX idx_sdxp_empfaenger ON sdxp_log (empfaenger);
CREATE INDEX idx_sdxp_richtung   ON sdxp_log (richtung);


-- =========================================================
-- Tabelle 2: TT1411-Telegramme (Parser: TT1411Parser)
-- kein Payload -> keine entsprechende Spalte
-- =========================================================
CREATE TABLE tt1411_log (
                            id          BIGSERIAL PRIMARY KEY,
                            "timestamp" TIMESTAMP NOT NULL,
                            typ         VARCHAR(100) NOT NULL,
                            sender      VARCHAR(100) NOT NULL,
                            empfaenger  VARCHAR(100) NOT NULL,
                            richtung    VARCHAR(10)  NOT NULL,   -- 'SENDF' oder 'RECVF'
                            payload_text TEXT
);


-- =========================================================
-- Tabelle 3: TT9001-Telegramme (Parser: TT9001Parser)
-- payload = roher XML-Block als Text (kein Parsing, 1:1 gespeichert)
-- =========================================================
CREATE TABLE tt9001_log (
                            id          BIGSERIAL PRIMARY KEY,
                            "timestamp" TIMESTAMP NOT NULL,
                            typ         VARCHAR(100) NOT NULL,
                            sender      VARCHAR(100) NOT NULL,   -- "MFS" oder Fremdkennung (z.B. "PCX")
                            empfaenger  VARCHAR(100) NOT NULL,
                            richtung    VARCHAR(10)  NOT NULL,   -- 'SENDF' oder 'RECVF'
                            payload_xml TEXT
);

-- =========================================================
-- Tabelle 3: TT1434-Telegramme (Parser: TT1434Parser)
-- payload = roher XML-Block als Text (kein Parsing, 1:1 gespeichert)
-- =========================================================
CREATE TABLE IF NOT EXISTS tt1434_log (
                            id SERIAL PRIMARY KEY,
                            "timestamp" TIMESTAMP NOT NULL,
                            typ VARCHAR(20) NOT NULL,
                            sender VARCHAR(50) NOT NULL,
                            empfaenger VARCHAR(50) NOT NULL,
                            richtung VARCHAR(5) NOT NULL,
                            payload TEXT
    );

-- =========================================================
-- Tabelle 4: Fehlerhafte Eintraege
-- Speichert den kompletten Roh-Block als String, wenn Parsing
-- oder Insert fehlschlaegt - zur spaeteren manuellen Analyse.
-- =========================================================
CREATE TABLE error_log (
                           id          BIGSERIAL PRIMARY KEY,
                           erfasst_am  TIMESTAMP NOT NULL DEFAULT now(),
                           fehlertext  TEXT,
                           roh_block   TEXT NOT NULL
);


CREATE INDEX idx_tt9001_timestamp  ON tt9001_log ("timestamp");
CREATE INDEX idx_tt9001_sender     ON tt9001_log (sender);
CREATE INDEX idx_tt9001_empfaenger ON tt9001_log (empfaenger);
CREATE INDEX idx_tt9001_richtung   ON tt9001_log (richtung);
CREATE INDEX idx_error_erfasst_am ON error_log (erfasst_am);