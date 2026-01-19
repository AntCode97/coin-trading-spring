-- closeAttemptCount null 문제 해결 마이그레이션
-- 2026-01-19

-- 1. volume_surge_trades 테이블: null을 0으로 업데이트
UPDATE volume_surge_trades
SET close_attempt_count = 0
WHERE close_attempt_count IS NULL;

-- 2. volume_surge_trades 컬럼 NOT NULL 제약 추가
ALTER TABLE volume_surge_trades
MODIFY COLUMN close_attempt_count INT NOT NULL DEFAULT 0;

-- 3. meme_scalper_trades 테이블: null을 0으로 업데이트
UPDATE meme_scalper_trades
SET close_attempt_count = 0
WHERE close_attempt_count IS NULL;

-- 4. meme_scalper_trades 컬럼 NOT NULL 제약 추가
ALTER TABLE meme_scalper_trades
MODIFY COLUMN close_attempt_count INT NOT NULL DEFAULT 0;

-- 확인 쿼리
SELECT 'volume_surge_trades' AS table_name, COUNT(*) AS null_count
FROM volume_surge_trades WHERE close_attempt_count IS NULL
UNION ALL
SELECT 'meme_scalper_trades' AS table_name, COUNT(*) AS null_count
FROM meme_scalper_trades WHERE close_attempt_count IS NULL;
