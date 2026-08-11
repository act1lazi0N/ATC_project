ALTER TABLE settlement_items
    ADD COLUMN settlement_item_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE settlement_items
    ADD CONSTRAINT chk_settlement_items_type
        CHECK (settlement_item_type IN ('NORMAL', 'ADJUSTMENT'));
