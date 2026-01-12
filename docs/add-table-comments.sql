-- ============================================
-- 테이블 및 컬럼 주석 추가 SQL
-- 실행 대상: MySQL 8.x
-- 생성일: 2026-01-11
-- ============================================

-- ============================================
-- 1. trades 테이블 (거래 기록)
-- ============================================
ALTER TABLE trades COMMENT '거래 기록 테이블 - 모든 매수/매도 거래 내역 저장';

ALTER TABLE trades
    MODIFY COLUMN id BIGINT AUTO_INCREMENT COMMENT '거래 고유 ID',
    MODIFY COLUMN order_id VARCHAR(64) NOT NULL COMMENT 'Bithumb 주문 ID',
    MODIFY COLUMN market VARCHAR(20) NOT NULL COMMENT '거래 마켓 (예: KRW-BTC)',
    MODIFY COLUMN side VARCHAR(10) NOT NULL COMMENT '거래 방향 (BUY/SELL)',
    MODIFY COLUMN type VARCHAR(10) NOT NULL COMMENT '주문 유형 (LIMIT/MARKET)',
    MODIFY COLUMN price DOUBLE NOT NULL COMMENT '체결 가격 (KRW)',
    MODIFY COLUMN quantity DOUBLE NOT NULL COMMENT '체결 수량',
    MODIFY COLUMN total_amount DOUBLE NOT NULL COMMENT '총 거래 금액 (KRW)',
    MODIFY COLUMN fee DOUBLE NOT NULL COMMENT '거래 수수료 (KRW)',
    MODIFY COLUMN slippage DOUBLE COMMENT '슬리피지 (%, 기준가 대비 체결가 차이)',
    MODIFY COLUMN is_partial_fill TINYINT(1) COMMENT '부분 체결 여부',
    MODIFY COLUMN pnl DOUBLE COMMENT '실현 손익 (KRW)',
    MODIFY COLUMN pnl_percent DOUBLE COMMENT '실현 손익률 (%)',
    MODIFY COLUMN strategy VARCHAR(30) NOT NULL COMMENT '사용 전략 (DCA/GRID/MEAN_REVERSION/ORDER_BOOK)',
    MODIFY COLUMN regime VARCHAR(30) COMMENT '시장 레짐 (SIDEWAYS/BULL_TREND/BEAR_TREND/HIGH_VOLATILITY)',
    MODIFY COLUMN confidence DOUBLE NOT NULL COMMENT '신호 신뢰도 (0.0~1.0)',
    MODIFY COLUMN reason VARCHAR(500) NOT NULL COMMENT '거래 사유',
    MODIFY COLUMN simulated TINYINT(1) NOT NULL COMMENT '시뮬레이션 여부 (true=모의거래)',
    MODIFY COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '거래 시각';

-- ============================================
-- 2. daily_stats 테이블 (일일 통계)
-- ============================================
ALTER TABLE daily_stats COMMENT '일일 거래 통계 테이블 - 날짜/마켓별 집계';

ALTER TABLE daily_stats
    MODIFY COLUMN id BIGINT AUTO_INCREMENT COMMENT '통계 고유 ID',
    MODIFY COLUMN date VARCHAR(10) NOT NULL COMMENT '날짜 (YYYY-MM-DD)',
    MODIFY COLUMN market VARCHAR(20) NOT NULL COMMENT '거래 마켓',
    MODIFY COLUMN total_trades INT NOT NULL COMMENT '총 거래 횟수',
    MODIFY COLUMN win_trades INT NOT NULL COMMENT '수익 거래 횟수',
    MODIFY COLUMN loss_trades INT NOT NULL COMMENT '손실 거래 횟수',
    MODIFY COLUMN total_pnl DOUBLE NOT NULL COMMENT '총 손익 (KRW)',
    MODIFY COLUMN win_rate DOUBLE NOT NULL COMMENT '승률 (0.0~1.0)',
    MODIFY COLUMN avg_pnl_percent DOUBLE NOT NULL COMMENT '평균 손익률 (%)',
    MODIFY COLUMN max_drawdown DOUBLE NOT NULL COMMENT '최대 낙폭 (%)',
    MODIFY COLUMN strategies_json VARCHAR(1000) COMMENT '전략별 통계 (JSON)',
    MODIFY COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 갱신 시각';

-- ============================================
-- 3. key_value_store 테이블 (설정 저장소)
-- ============================================
ALTER TABLE key_value_store COMMENT '키-값 저장소 테이블 - Redis 대체용 동적 설정 관리';

ALTER TABLE key_value_store
    MODIFY COLUMN id BIGINT AUTO_INCREMENT COMMENT '설정 고유 ID',
    MODIFY COLUMN kv_key VARCHAR(100) NOT NULL COMMENT '설정 키 (예: llm.model.provider)',
    MODIFY COLUMN kv_value VARCHAR(2000) NOT NULL COMMENT '설정 값',
    MODIFY COLUMN category VARCHAR(50) COMMENT '카테고리 (llm/trading/system)',
    MODIFY COLUMN description VARCHAR(500) COMMENT '설정 설명',
    MODIFY COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 시각',
    MODIFY COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 갱신 시각';

-- ============================================
-- 4. audit_logs 테이블 (감사 로그)
-- ============================================
ALTER TABLE audit_logs COMMENT 'LLM 결정 감사 로그 테이블 - AI 의사결정 기록';

ALTER TABLE audit_logs
    MODIFY COLUMN id BIGINT AUTO_INCREMENT COMMENT '로그 고유 ID',
    MODIFY COLUMN event_type VARCHAR(50) NOT NULL COMMENT '이벤트 유형 (STRATEGY_CHANGE/PARAM_UPDATE/TRADE_DECISION)',
    MODIFY COLUMN market VARCHAR(20) COMMENT '대상 마켓',
    MODIFY COLUMN action VARCHAR(100) NOT NULL COMMENT '수행 액션',
    MODIFY COLUMN input_data TEXT COMMENT '입력 데이터 (JSON)',
    MODIFY COLUMN output_data TEXT COMMENT '출력 데이터 (JSON)',
    MODIFY COLUMN reason VARCHAR(500) COMMENT 'LLM 판단 사유',
    MODIFY COLUMN confidence DOUBLE COMMENT '신뢰도 (0.0~1.0)',
    MODIFY COLUMN applied TINYINT(1) NOT NULL DEFAULT 0 COMMENT '적용 여부',
    MODIFY COLUMN rejection_reason VARCHAR(500) COMMENT '거부 사유 (미적용 시)',
    MODIFY COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '로그 생성 시각',
    MODIFY COLUMN triggered_by VARCHAR(50) NOT NULL DEFAULT 'SYSTEM' COMMENT '트리거 주체 (SYSTEM/SCHEDULER/MANUAL)';

-- ============================================
-- 5. volume_surge_alerts 테이블 (거래량 급등 경보)
-- 추가일: 2026-01-13
-- ============================================
ALTER TABLE volume_surge_alerts COMMENT '거래량 급등 경보 테이블 - Bithumb 경보제 API에서 감지된 급등 종목';

ALTER TABLE volume_surge_alerts
    MODIFY COLUMN id BIGINT AUTO_INCREMENT COMMENT '경보 고유 ID',
    MODIFY COLUMN market VARCHAR(20) NOT NULL COMMENT '마켓 코드 (예: KRW-BTC)',
    MODIFY COLUMN alert_type VARCHAR(50) NOT NULL COMMENT '경보 유형 (TRADING_VOLUME_SUDDEN_FLUCTUATION)',
    MODIFY COLUMN volume_ratio DOUBLE COMMENT '거래량 비율 (평균 대비 배수)',
    MODIFY COLUMN detected_at TIMESTAMP NOT NULL COMMENT '경보 감지 시각',
    MODIFY COLUMN llm_filter_result VARCHAR(20) COMMENT 'LLM 필터 결과 (APPROVED/REJECTED/SKIPPED)',
    MODIFY COLUMN llm_filter_reason TEXT COMMENT 'LLM 필터 판단 근거',
    MODIFY COLUMN llm_confidence DOUBLE COMMENT 'LLM 판단 신뢰도 (0.0~1.0)',
    MODIFY COLUMN processed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '처리 완료 여부',
    MODIFY COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '레코드 생성 시각';

-- ============================================
-- 6. volume_surge_trades 테이블 (거래량 급등 매매)
-- 추가일: 2026-01-13
-- ============================================
ALTER TABLE volume_surge_trades COMMENT '거래량 급등 매매 테이블 - Volume Surge 전략 포지션 기록';

ALTER TABLE volume_surge_trades
    MODIFY COLUMN id BIGINT AUTO_INCREMENT COMMENT '트레이드 고유 ID',
    MODIFY COLUMN alert_id BIGINT COMMENT '연관 경보 ID (FK)',
    MODIFY COLUMN market VARCHAR(20) NOT NULL COMMENT '마켓 코드',
    MODIFY COLUMN entry_price DOUBLE NOT NULL COMMENT '진입 가격 (KRW)',
    MODIFY COLUMN exit_price DOUBLE COMMENT '청산 가격 (KRW)',
    MODIFY COLUMN quantity DOUBLE NOT NULL COMMENT '거래 수량',
    MODIFY COLUMN entry_time TIMESTAMP NOT NULL COMMENT '진입 시각',
    MODIFY COLUMN exit_time TIMESTAMP COMMENT '청산 시각',
    MODIFY COLUMN exit_reason VARCHAR(30) COMMENT '청산 사유 (STOP_LOSS/TAKE_PROFIT/TRAILING/TIMEOUT)',
    MODIFY COLUMN pnl_amount DOUBLE COMMENT '실현 손익 (KRW)',
    MODIFY COLUMN pnl_percent DOUBLE COMMENT '실현 손익률 (%)',
    MODIFY COLUMN entry_rsi DOUBLE COMMENT '진입 시 RSI 값',
    MODIFY COLUMN entry_macd_signal VARCHAR(10) COMMENT '진입 시 MACD 신호 (BULLISH/BEARISH/NEUTRAL)',
    MODIFY COLUMN entry_bollinger_position VARCHAR(10) COMMENT '진입 시 볼린저밴드 위치 (LOWER/MIDDLE/UPPER)',
    MODIFY COLUMN entry_volume_ratio DOUBLE COMMENT '진입 시 거래량 비율',
    MODIFY COLUMN confluence_score INT COMMENT '컨플루언스 점수 (0~100)',
    MODIFY COLUMN llm_entry_reason TEXT COMMENT 'LLM 진입 판단 근거',
    MODIFY COLUMN llm_confidence DOUBLE COMMENT 'LLM 진입 신뢰도',
    MODIFY COLUMN reflection_notes TEXT COMMENT '회고 시 기록된 메모',
    MODIFY COLUMN lesson_learned TEXT COMMENT '학습된 교훈',
    MODIFY COLUMN trailing_active TINYINT(1) NOT NULL DEFAULT 0 COMMENT '트레일링 스탑 활성화 여부',
    MODIFY COLUMN highest_price DOUBLE COMMENT '보유 중 최고가 (트레일링용)',
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '포지션 상태 (OPEN/CLOSED)',
    MODIFY COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '레코드 생성 시각',
    MODIFY COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 갱신 시각';

-- ============================================
-- 7. volume_surge_daily_summary 테이블 (일일 요약)
-- 추가일: 2026-01-13
-- ============================================
ALTER TABLE volume_surge_daily_summary COMMENT '거래량 급등 전략 일일 요약 테이블 - LLM 회고 결과 저장';

ALTER TABLE volume_surge_daily_summary
    MODIFY COLUMN id BIGINT AUTO_INCREMENT COMMENT '요약 고유 ID',
    MODIFY COLUMN date DATE NOT NULL COMMENT '날짜 (YYYY-MM-DD, UNIQUE)',
    MODIFY COLUMN total_alerts INT NOT NULL DEFAULT 0 COMMENT '총 경보 수',
    MODIFY COLUMN approved_alerts INT NOT NULL DEFAULT 0 COMMENT 'LLM 승인 경보 수',
    MODIFY COLUMN total_trades INT NOT NULL DEFAULT 0 COMMENT '총 거래 횟수',
    MODIFY COLUMN winning_trades INT NOT NULL DEFAULT 0 COMMENT '수익 거래 횟수',
    MODIFY COLUMN losing_trades INT NOT NULL DEFAULT 0 COMMENT '손실 거래 횟수',
    MODIFY COLUMN total_pnl DOUBLE NOT NULL DEFAULT 0 COMMENT '총 손익 (KRW)',
    MODIFY COLUMN win_rate DOUBLE NOT NULL DEFAULT 0 COMMENT '승률 (0.0~1.0)',
    MODIFY COLUMN avg_holding_minutes DOUBLE COMMENT '평균 보유 시간 (분)',
    MODIFY COLUMN reflection_summary TEXT COMMENT 'LLM 회고 요약',
    MODIFY COLUMN parameter_changes TEXT COMMENT '파라미터 변경 이력 (JSON)',
    MODIFY COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '레코드 생성 시각';

-- ============================================
-- 확인 쿼리
-- ============================================
-- 테이블 주석 확인
SELECT TABLE_NAME, TABLE_COMMENT
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('trades', 'daily_stats', 'key_value_store', 'audit_logs',
                     'volume_surge_alerts', 'volume_surge_trades', 'volume_surge_daily_summary');

-- 컬럼 주석 확인 (trades 테이블 예시)
SELECT COLUMN_NAME, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'trades';

-- Volume Surge 테이블 컬럼 주석 확인
SELECT COLUMN_NAME, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'volume_surge_trades';
