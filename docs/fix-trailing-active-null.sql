-- Fix trailingActive null values in meme_scalper_trades table
-- Issue: trailingActive column has NULL values causing entity mapping errors
-- Error: "Can not set boolean field ... to null value"

-- 1. Check current null count
SELECT COUNT(*) as null_count FROM meme_scalper_trades WHERE trailing_active IS NULL;

-- 2. Update all NULL values to false (default)
UPDATE meme_scalper_trades
SET trailing_active = false
WHERE trailing_active IS NULL;

-- 3. Verify fix
SELECT COUNT(*) as null_count_after FROM meme_scalper_trades WHERE trailing_active IS NULL;

-- 4. Optionally alter column to NOT NULL (if desired)
-- ALTER TABLE meme_scalper_trades MODIFY COLUMN trailing_active TINYINT(1) NOT NULL DEFAULT 0;
