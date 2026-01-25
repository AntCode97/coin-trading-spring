-- 백테스팅 프레임워크 스키마
-- 백테스팅을 위한 과거 데이터 저장 및 결과 관리

-- 과거 OHLCV 캔들 저장 테이블
CREATE TABLE IF NOT EXISTS ohlcv_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    market VARCHAR(20) NOT NULL COMMENT '마켓 코드 (예: KRW-BTC)',
    interval VARCHAR(20) NOT NULL COMMENT '캔들 간격 (day, minute60 등)',
    timestamp BIGINT NOT NULL COMMENT '캔들 타임스탬프 (millis)',
    open DECIMAL(20, 8) NOT NULL COMMENT '시가',
    high DECIMAL(20, 8) NOT NULL COMMENT '고가',
    low DECIMAL(20, 8) NOT NULL COMMENT '저가',
    close DECIMAL(20, 8) NOT NULL COMMENT '종가',
    volume DECIMAL(30, 8) NOT NULL COMMENT '거래량',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_market_interval_time (market, interval, timestamp),
    INDEX idx_market_interval (market, interval),
    INDEX idx_timestamp (timestamp),
    INDEX idx_market_interval_timestamp (market, interval, timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과거 OHLCV 캔들 데이터';

-- 백테스트 결과 저장 테이블
CREATE TABLE IF NOT EXISTS backtest_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_name VARCHAR(50) NOT NULL COMMENT '전략 이름',
    market VARCHAR(20) NOT NULL COMMENT '마켓 코드',
    start_date DATE NOT NULL COMMENT '백테스트 시작일',
    end_date DATE NOT NULL COMMENT '백테스트 종료일',
    parameters JSON NOT NULL COMMENT '사용된 파라미터',
    total_trades INT NOT NULL COMMENT '총 거래 횟수',
    winning_trades INT NOT NULL COMMENT '수익 거래 횟수',
    losing_trades INT NOT NULL COMMENT '손실 거래 횟수',
    total_return DECIMAL(10, 4) NOT NULL COMMENT '총 수익률 (%)',
    max_drawdown DECIMAL(10, 4) NOT NULL COMMENT '최대 낙폭 (%)',
    sharpe_ratio DECIMAL(10, 4) NOT NULL COMMENT '샤프 비율',
    profit_factor DECIMAL(10, 4) COMMENT '프로핏 팩터',
    avg_win DECIMAL(10, 4) COMMENT '평균 수익 (%)',
    avg_loss DECIMAL(10, 4) COMMENT '평균 손실 (%)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_strategy_market (strategy_name, market),
    INDEX idx_date_range (start_date, end_date),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='백테스트 결과';

-- 백테스트 거래 상세 테이블
CREATE TABLE IF NOT EXISTS backtest_trades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    backtest_result_id BIGINT NOT NULL COMMENT '백테스트 결과 ID',
    market VARCHAR(20) NOT NULL COMMENT '마켓 코드',
    entry_time TIMESTAMP NOT NULL COMMENT '진입 시간',
    exit_time TIMESTAMP COMMENT '청산 시간',
    entry_price DECIMAL(20, 8) NOT NULL COMMENT '진입 가격',
    exit_price DECIMAL(20, 8) COMMENT '청산 가격',
    quantity DECIMAL(20, 8) NOT NULL COMMENT '수량',
    pnl DECIMAL(20, 8) COMMENT '손익 금액',
    pnl_percent DECIMAL(10, 4) COMMENT '손익률 (%)',
    entry_reason VARCHAR(500) COMMENT '진입 사유',
    exit_reason VARCHAR(100) COMMENT '청산 사유',
    holding_period_minutes INT COMMENT '보유 기간 (분)',
    FOREIGN KEY (backtest_result_id) REFERENCES backtest_results(id) ON DELETE CASCADE,
    INDEX idx_backtest_result (backtest_result_id),
    INDEX idx_market (market),
    INDEX idx_entry_time (entry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='백테스트 거래 상세';
