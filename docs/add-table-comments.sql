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
-- 확인 쿼리
-- ============================================
-- 테이블 주석 확인
SELECT TABLE_NAME, TABLE_COMMENT
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('trades', 'daily_stats', 'key_value_store', 'audit_logs');

-- 컬럼 주석 확인 (trades 테이블 예시)
SELECT COLUMN_NAME, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'trades';
