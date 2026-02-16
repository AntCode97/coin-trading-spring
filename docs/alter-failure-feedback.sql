-- 실패 패턴 피드백 루프 컬럼 추가
-- 2026-02-16

ALTER TABLE volume_surge_trades
    ADD COLUMN failure_pattern VARCHAR(50) NULL
    COMMENT '실패 패턴 분류 (RSI_OVERBOUGHT/VOLUME_FAKEOUT/MACD_AGAINST 등)';

ALTER TABLE volume_surge_trades
    ADD COLUMN failure_tag VARCHAR(500) NULL
    COMMENT '실패 태그 상세 JSON (진입 시점 지표 조합)';
