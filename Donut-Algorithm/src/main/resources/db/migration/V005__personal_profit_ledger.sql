CREATE TABLE IF NOT EXISTS tracked_flip_positions (
    position_id TEXT PRIMARY KEY,
    opportunity_id TEXT NOT NULL UNIQUE,
    listing_key TEXT NOT NULL,
    item_fingerprint TEXT NOT NULL,
    raw_item_id TEXT NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count > 0),
    acquisition_cost TEXT NOT NULL,
    purchased_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'REALIZED')),
    matched_sale_key TEXT UNIQUE,
    sale_proceeds TEXT,
    realized_profit TEXT,
    sold_at INTEGER,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tracked_positions_open_match
    ON tracked_flip_positions(status, item_fingerprint, item_count, purchased_at);

CREATE INDEX IF NOT EXISTS idx_tracked_positions_sold_at
    ON tracked_flip_positions(sold_at)
    WHERE status = 'REALIZED';
