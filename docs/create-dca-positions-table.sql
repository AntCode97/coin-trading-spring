-- DCA 포지션 추적 테이블 생성
-- DCA 전략의 포지션을 추적하여 익절/손절 조건을 관리합니다.

CREATE TABLE IF NOT EXISTS dca_positions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    market VARCHAR(20) NOT NULL COMMENT '거래 마켓 (예: KRW-BTC)',
    total_quantity DOUBLE NOT NULL DEFAULT 0 COMMENT '총 매수 수량',
    average_price DOUBLE NOT NULL DEFAULT 0 COMMENT '평균 매입가 (KRW)',
    total_invested DOUBLE NOT NULL DEFAULT 0 COMMENT '총 투자금액 (KRW)',
    last_price_update TIMESTAMP NULL COMMENT '현재가 마지막 갱신 시각',
    last_price DOUBLE NULL COMMENT '현재가 (마지막 확인 시)',
    current_pnl_percent DOUBLE NULL COMMENT '진입가 대비 현재 수익률 (%)',
    take_profit_percent DOUBLE NOT NULL DEFAULT 15.0 COMMENT '익절 비율 (%)',
    stop_loss_percent DOUBLE NOT NULL DEFAULT -10.0 COMMENT '손절 비율 (%)',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '포지션 상태 (OPEN/CLOSED)',
    exit_reason VARCHAR(30) NULL COMMENT '청산 사유 (TAKE_PROFIT/STOP_LOSS/TIMEOUT/MANUAL)',
    exited_at TIMESTAMP NULL COMMENT '청산 시각',
    exit_price DOUBLE NULL COMMENT '청산가 (KRW)',
    realized_pnl DOUBLE NULL COMMENT '실현 손익 (KRW)',
    realized_pnl_percent DOUBLE NULL COMMENT '실현 손익률 (%)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 시각',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 시각',

    INDEX idx_dca_positions_market (market),
    INDEX idx_dca_positions_status (status),
    INDEX idx_dca_positions_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='DCA 포지션 추적 테이블';
