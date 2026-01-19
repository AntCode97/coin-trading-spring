-- ATR 기반 동적 손절 관련 컬럼 추가
-- 2026-01-19

-- volume_surge_trades 테이블에 ATR 손절 관련 컬럼 추가
ALTER TABLE volume_surge_trades
ADD COLUMN entry_atr DOUBLE NULL COMMENT '진입 시점 ATR',
ADD COLUMN entry_atr_percent DOUBLE NULL COMMENT '진입 시점 ATR 비율 (%)',
ADD COLUMN applied_stop_loss_percent DOUBLE NULL COMMENT '적용된 손절 비율 (%)',
ADD COLUMN stop_loss_method VARCHAR(20) NULL COMMENT '손절 방식 (ATR_DYNAMIC / FIXED)';

-- 기존 데이터에 대해 고정 손절 방식으로 표시
UPDATE volume_surge_trades
SET stop_loss_method = 'FIXED',
    applied_stop_loss_percent = 2.0
WHERE stop_loss_method IS NULL;

-- 확인 쿼리
SELECT
    id, market, entry_price,
    entry_atr, entry_atr_percent,
    applied_stop_loss_percent, stop_loss_method
FROM volume_surge_trades
ORDER BY created_at DESC
LIMIT 10;
