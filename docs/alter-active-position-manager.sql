-- Active Position Manager DDL 마이그레이션

-- MemeScalper: APM이 동적 갱신하는 TP/SL 필드 추가 (0 = 미조정, properties 기본값 사용)
ALTER TABLE meme_scalper_trades
    ADD COLUMN applied_stop_loss_percent DOUBLE NOT NULL DEFAULT 0.0
    COMMENT 'APM 동적 손절 비율(%). 0이면 미조정(properties 기본값 사용)';

ALTER TABLE meme_scalper_trades
    ADD COLUMN applied_take_profit_percent DOUBLE NOT NULL DEFAULT 0.0
    COMMENT 'APM 동적 익절 비율(%). 0이면 미조정(properties 기본값 사용)';

-- DCA: 진입 시점 시장 레짐 (APM 레짐 전환 감지용)
ALTER TABLE dca_positions
    ADD COLUMN entry_regime VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN'
    COMMENT '진입 시점 시장 레짐 (BULL_TREND/BEAR_TREND/SIDEWAYS/HIGH_VOLATILITY/UNKNOWN)';
