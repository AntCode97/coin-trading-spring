-- Guided trades precision alignment (idempotent)
-- Re-running this script is safe because MODIFY to the same type is a no-op.

ALTER TABLE guided_trades
    MODIFY COLUMN target_amount_krw DECIMAL(20,2) NOT NULL,
    MODIFY COLUMN average_entry_price DECIMAL(20,8) NOT NULL,
    MODIFY COLUMN entry_quantity DECIMAL(20,8) NOT NULL,
    MODIFY COLUMN remaining_quantity DECIMAL(20,8) NOT NULL,
    MODIFY COLUMN stop_loss_price DECIMAL(20,8) NOT NULL,
    MODIFY COLUMN take_profit_price DECIMAL(20,8) NOT NULL,
    MODIFY COLUMN trailing_trigger_percent DECIMAL(10,4) NOT NULL,
    MODIFY COLUMN trailing_offset_percent DECIMAL(10,4) NOT NULL,
    MODIFY COLUMN trailing_peak_price DECIMAL(20,8) NULL,
    MODIFY COLUMN trailing_stop_price DECIMAL(20,8) NULL,
    MODIFY COLUMN dca_step_percent DECIMAL(10,4) NOT NULL,
    MODIFY COLUMN half_take_profit_ratio DECIMAL(10,4) NOT NULL,
    MODIFY COLUMN cumulative_exit_quantity DECIMAL(20,8) NOT NULL,
    MODIFY COLUMN average_exit_price DECIMAL(20,8) NOT NULL,
    MODIFY COLUMN realized_pnl DECIMAL(20,2) NOT NULL,
    MODIFY COLUMN realized_pnl_percent DECIMAL(10,4) NOT NULL;

ALTER TABLE guided_trade_events
    MODIFY COLUMN price DECIMAL(20,8) NULL,
    MODIFY COLUMN quantity DECIMAL(20,8) NULL;

ALTER TABLE guided_trades
    ADD COLUMN IF NOT EXISTS pnl_confidence VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN IF NOT EXISTS pnl_reconciled_at DATETIME(6) NULL;
