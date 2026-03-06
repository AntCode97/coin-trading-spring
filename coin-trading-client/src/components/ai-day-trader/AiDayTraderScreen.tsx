import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AiDayTraderEngine,
  DEFAULT_AI_DAY_TRADER_CONFIG,
  type AiDayTraderConfig,
  type AiDecision,
  type AiTraderEvent,
  type AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderEngine';
import {
  CODEX_MODELS,
  ZAI_MODELS,
  type LlmProviderId,
} from '../../lib/llmService';
import './AiDayTraderScreen.css';

type CenterTab = 'decisions' | 'events';

interface Props {
  onBack: () => void;
}

export default function AiDayTraderScreen({ onBack }: Props) {
  const engineRef = useRef<AiDayTraderEngine | null>(null);
  const [state, setState] = useState<AiTraderState>({
    status: 'IDLE',
    decisions: [],
    events: [],
    positions: [],
    dailyPnl: 0,
    dailyTradeCount: 0,
    totalTokensUsed: 0,
    lastScanAt: null,
    topCandidates: [],
  });
  const [config, setConfig] = useState<AiDayTraderConfig>(DEFAULT_AI_DAY_TRADER_CONFIG);
  const [centerTab, setCenterTab] = useState<CenterTab>('decisions');

  // Engine lifecycle
  useEffect(() => {
    const engine = new AiDayTraderEngine(config);
    engineRef.current = engine;
    const unsub = engine.subscribe(setState);
    return () => {
      unsub();
      engine.stop();
      engineRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleStart = useCallback(() => {
    const engine = engineRef.current;
    if (!engine) return;
    engine.updateConfig(config);
    engine.start();
  }, [config]);

  const handleStop = useCallback(() => {
    engineRef.current?.stop();
  }, []);

  const running = state.status !== 'IDLE' && state.status !== 'ERROR';
  const statusDotClass =
    state.status === 'IDLE' ? '' :
    state.status === 'ERROR' ? 'error' :
    state.status === 'SCANNING' || state.status === 'ANALYZING' ? 'scanning' :
    'active';

  const statusLabel: Record<string, string> = {
    IDLE: '대기',
    SCANNING: '스캔 중',
    ANALYZING: 'AI 분석 중',
    EXECUTING: '주문 실행 중',
    PAUSED: '일시정지',
    ERROR: '오류',
  };

  return (
    <div className="ai-trader-screen">
      {/* Top Bar */}
      <header className="ai-trader-topbar">
        <button type="button" className="ai-trader-btn back" onClick={onBack}>
          뒤로
        </button>
        <span className="ai-trader-topbar-title">AI Day Trader</span>
        <span className="ai-trader-topbar-status">
          <span className={`dot ${statusDotClass}`} />
          {statusLabel[state.status] ?? state.status}
        </span>

        <div className="ai-trader-topbar-kpis">
          <span className="ai-trader-kpi">
            <span className="ai-trader-kpi-label">PnL</span>
            <span className={`ai-trader-kpi-value ${state.dailyPnl >= 0 ? 'positive' : 'negative'}`}>
              {state.dailyPnl >= 0 ? '+' : ''}{state.dailyPnl.toLocaleString()}원
            </span>
          </span>
          <span className="ai-trader-kpi">
            <span className="ai-trader-kpi-label">거래</span>
            <span className="ai-trader-kpi-value">{state.dailyTradeCount}건</span>
          </span>
          <span className="ai-trader-kpi">
            <span className="ai-trader-kpi-label">토큰</span>
            <span className="ai-trader-kpi-value">{state.totalTokensUsed.toLocaleString()}</span>
          </span>
          <span className="ai-trader-kpi">
            <span className="ai-trader-kpi-label">포지션</span>
            <span className="ai-trader-kpi-value">{state.positions.length}/{config.maxConcurrentPositions}</span>
          </span>
        </div>

        <div className="ai-trader-topbar-actions">
          {running ? (
            <button type="button" className="ai-trader-btn stop" onClick={handleStop}>
              중지
            </button>
          ) : (
            <button type="button" className="ai-trader-btn start" onClick={handleStart}>
              시작
            </button>
          )}
        </div>
      </header>

      {/* Left: Market Candidates */}
      <aside className="ai-trader-left">
        <div className="ai-trader-panel-header">
          Top Markets ({state.topCandidates.length})
        </div>
        <div className="ai-trader-candidates">
          {state.topCandidates.length === 0 ? (
            <div className="ai-trader-empty">
              <div className="ai-trader-empty-icon">~</div>
              <div className="ai-trader-empty-text">시작 후 마켓 스캔</div>
            </div>
          ) : (
            state.topCandidates.map(m => (
              <div key={m.market} className="ai-trader-candidate">
                <div className="ai-trader-candidate-name">
                  {m.symbol}
                  <small>{m.koreanName}</small>
                </div>
                <div className="ai-trader-candidate-price">
                  {m.tradePrice.toLocaleString()}
                </div>
                <div className={`ai-trader-candidate-change ${m.changeRate >= 0 ? 'positive' : 'negative'}`}>
                  {m.changeRate >= 0 ? '+' : ''}{(m.changeRate * 100).toFixed(2)}%
                </div>
              </div>
            ))
          )}
        </div>
      </aside>

      {/* Center: Decisions / Events */}
      <main className="ai-trader-center">
        <div className="ai-trader-center-tabs">
          <button
            type="button"
            className={`ai-trader-center-tab ${centerTab === 'decisions' ? 'active' : ''}`}
            onClick={() => setCenterTab('decisions')}
          >
            AI 결정 ({state.decisions.length})
          </button>
          <button
            type="button"
            className={`ai-trader-center-tab ${centerTab === 'events' ? 'active' : ''}`}
            onClick={() => setCenterTab('events')}
          >
            이벤트 로그 ({state.events.length})
          </button>
        </div>
        <div className="ai-trader-feed">
          {centerTab === 'decisions' ? (
            state.decisions.length === 0 ? (
              <div className="ai-trader-empty">
                <div className="ai-trader-empty-icon">?</div>
                <div className="ai-trader-empty-text">AI가 시장을 분석하면 결정이 여기 표시됩니다</div>
              </div>
            ) : (
              state.decisions.map(d => <DecisionCard key={d.id} decision={d} />)
            )
          ) : (
            state.events.length === 0 ? (
              <div className="ai-trader-empty">
                <div className="ai-trader-empty-icon">...</div>
                <div className="ai-trader-empty-text">이벤트 대기 중</div>
              </div>
            ) : (
              state.events.map(e => <EventItem key={e.id} event={e} />)
            )
          )}
        </div>
      </main>

      {/* Right: Positions + Config */}
      <aside className="ai-trader-right">
        <div className="ai-trader-panel-header">보유 포지션</div>
        <div className="ai-trader-positions">
          {state.positions.length === 0 ? (
            <div className="ai-trader-empty">
              <div className="ai-trader-empty-icon">0</div>
              <div className="ai-trader-empty-text">보유 포지션 없음</div>
            </div>
          ) : (
            state.positions.map(p => <PositionCard key={p.tradeId} position={p} />)
          )}
        </div>

        <div className="ai-trader-config">
          <div className="ai-trader-panel-header" style={{ padding: '0 0 8px', borderBottom: 'none' }}>
            설정
          </div>
          <ConfigRow label="프로바이더">
            <select
              className="ai-trader-config-select"
              value={config.provider}
              disabled={running}
              onChange={e => setConfig(c => ({ ...c, provider: e.target.value as LlmProviderId }))}
            >
              <option value="openai">OpenAI</option>
              <option value="zai">z.ai</option>
            </select>
          </ConfigRow>
          <ConfigRow label="모델">
            <select
              className="ai-trader-config-select"
              value={config.model}
              disabled={running}
              onChange={e => setConfig(c => ({ ...c, model: e.target.value }))}
            >
              {(config.provider === 'openai' ? CODEX_MODELS : ZAI_MODELS).map(m => (
                <option key={m.id} value={m.id}>{m.label}</option>
              ))}
            </select>
          </ConfigRow>
          <ConfigRow label="스캔 주기">
            <select
              className="ai-trader-config-select"
              value={config.scanIntervalMs}
              disabled={running}
              onChange={e => setConfig(c => ({ ...c, scanIntervalMs: Number(e.target.value) }))}
            >
              <option value={10000}>10초</option>
              <option value={15000}>15초</option>
              <option value={30000}>30초</option>
              <option value={60000}>1분</option>
            </select>
          </ConfigRow>
          <ConfigRow label="1회 금액">
            <input
              type="number"
              className="ai-trader-config-input"
              value={config.amountKrw}
              disabled={running}
              step={5000}
              min={5000}
              onChange={e => setConfig(c => ({ ...c, amountKrw: Number(e.target.value) }))}
            />
          </ConfigRow>
          <ConfigRow label="최대 포지션">
            <input
              type="number"
              className="ai-trader-config-input"
              value={config.maxConcurrentPositions}
              disabled={running}
              min={1}
              max={10}
              onChange={e => setConfig(c => ({ ...c, maxConcurrentPositions: Number(e.target.value) }))}
            />
          </ConfigRow>
          <ConfigRow label="일일 손실한도">
            <input
              type="number"
              className="ai-trader-config-input"
              value={config.dailyLossLimitKrw}
              disabled={running}
              step={5000}
              onChange={e => setConfig(c => ({ ...c, dailyLossLimitKrw: Number(e.target.value) }))}
            />
          </ConfigRow>
        </div>
      </aside>
    </div>
  );
}

// ---------- Sub-components ----------

function ConfigRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="ai-trader-config-row">
      <span className="ai-trader-config-label">{label}</span>
      {children}
    </div>
  );
}

function DecisionCard({ decision }: { decision: AiDecision }) {
  const time = new Date(decision.timestamp);
  const timeStr = time.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  return (
    <div className="ai-decision-card">
      <div className="ai-decision-header">
        <span className={`ai-decision-action ${decision.action}`}>{decision.action}</span>
        <span className="ai-decision-market">{decision.market}</span>
        <span className="ai-decision-confidence">{(decision.confidence * 100).toFixed(0)}%</span>
      </div>
      <div className="ai-decision-reasoning">{decision.reasoning}</div>
      <div className="ai-decision-time">{timeStr}</div>
    </div>
  );
}

function EventItem({ event }: { event: AiTraderEvent }) {
  const time = new Date(event.timestamp);
  const timeStr = time.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  return (
    <div className="ai-event-item">
      <span className={`ai-event-dot ${event.type}`} />
      <div className="ai-event-content">
        <div className="ai-event-msg">{event.message}</div>
        {event.detail && <div className="ai-event-detail">{event.detail}</div>}
      </div>
      <span className="ai-event-time">{timeStr}</span>
    </div>
  );
}

function PositionCard({ position }: { position: import('../../api').GuidedTradePosition }) {
  const pnl = position.unrealizedPnlPercent;
  const holdingMs = Date.now() - new Date(position.createdAt).getTime();
  const holdingMin = Math.floor(holdingMs / 60_000);

  return (
    <div className="ai-position-card">
      <div className="ai-position-header">
        <span className="ai-position-market">{position.market.replace('KRW-', '')}</span>
        <span className={`ai-position-pnl ${pnl >= 0 ? 'positive' : 'negative'}`}>
          {pnl >= 0 ? '+' : ''}{pnl.toFixed(2)}%
        </span>
      </div>
      <div className="ai-position-details">
        <span>진입가</span>
        <span>{position.averageEntryPrice.toLocaleString()}</span>
        <span>현재가</span>
        <span>{position.currentPrice.toLocaleString()}</span>
        <span>보유시간</span>
        <span>{holdingMin}분</span>
        <span>수량</span>
        <span>{position.remainingQuantity}</span>
      </div>
    </div>
  );
}
