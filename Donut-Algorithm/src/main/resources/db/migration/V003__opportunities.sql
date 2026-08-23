CREATE TABLE IF NOT EXISTS opportunities (
    opportunity_id TEXT PRIMARY KEY,
    listing_key TEXT NOT NULL,
    item_fingerprint TEXT NOT NULL,
    detected_at INTEGER NOT NULL,
    purchase_price TEXT NOT NULL,
    fair_value TEXT NOT NULL,
    estimated_profit TEXT NOT NULL,
    roi_percent TEXT NOT NULL,
    confidence_percent TEXT NOT NULL,
    state TEXT NOT NULL,
    rejection_reason TEXT,
    evaluation_json TEXT,
    evaluation_version TEXT NOT NULL,
    FOREIGN KEY (listing_key) REFERENCES auction_listings(listing_key),
    FOREIGN KEY (item_fingerprint) REFERENCES item_fingerprints(fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_opportunities_fingerprint_detected
    ON opportunities(item_fingerprint, detected_at DESC);

CREATE INDEX IF NOT EXISTS idx_opportunities_state_detected
    ON opportunities(state, detected_at DESC);

CREATE TABLE IF NOT EXISTS opportunity_state_changes (
    change_id INTEGER PRIMARY KEY AUTOINCREMENT,
    opportunity_id TEXT NOT NULL,
    previous_state TEXT,
    new_state TEXT NOT NULL,
    changed_at INTEGER NOT NULL,
    reason TEXT,
    FOREIGN KEY (opportunity_id) REFERENCES opportunities(opportunity_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_opportunity_changes_id_time
    ON opportunity_state_changes(opportunity_id, changed_at);
