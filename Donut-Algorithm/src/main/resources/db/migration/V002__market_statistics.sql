CREATE TABLE IF NOT EXISTS market_statistics (
    statistics_key TEXT PRIMARY KEY,
    item_fingerprint TEXT NOT NULL,
    computed_at INTEGER NOT NULL,
    window_start INTEGER NOT NULL,
    window_end INTEGER NOT NULL,
    sample_count INTEGER NOT NULL CHECK (sample_count >= 0),
    minimum_price TEXT,
    maximum_price TEXT,
    median_price TEXT,
    statistics_json TEXT,
    FOREIGN KEY (item_fingerprint) REFERENCES item_fingerprints(fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_statistics_fingerprint_computed
    ON market_statistics(item_fingerprint, computed_at DESC);
