import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  AiDayTraderEngine,
  DEFAULT_AI_DAY_TRADER_CONFIG,
  createInitialAiTraderState,
  type AiDayTraderConfig,
  type AiRankedOpportunity,
  type AiTraderEvent,
  type AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderEngine';
import {
  CODEX_MODELS,
  ZAI_MODELS,
  type LlmProviderId,
} from '../../lib/llmService';
import './AiDayTraderScreen.css';

const PREFERENCE_KEY = 'ai-scalp-terminal.preferences.v1';

function loadPreferences(): AiDayTraderConfig {
  try {
    const raw = window.localStorage.getItem(PREFERENCE_KEY);
    if (!raw) return DEFAULT_AI_DAY_TRADER_CONFIG;
    const parsed = JSON.parse(raw) as Partial<AiDayTraderConfig>;
    return {
      ...DEFAULT_AI_DAY_TRADER_CONFIG,
      ...parsed,
      strategyCode: DEFAULT_AI_DAY_TRADER_CONFIG.strategyCode,
    };
  } catch {
    return DEFAULT_AI_DAY_TRADER_CONFIG;
  }
}

export default function AiDayTraderScreen() {
  const engineRef = useRef<AiDayTraderEngine | null>(null);
  const [config, setConfig] = useState<AiDayTraderConfig>(() => loadPreferences());
  const [state, setState] = useState<AiTraderState>(() => createInitialAiTraderState());

  useEffect(() => {
    const engine = new AiDayTraderEngine(config);
    engineRef.current = engine;
    const unsubscribe = engine.subscribe(setState);
    return () => {
      unsubscribe();
      engine.stop();
      engineRef.current = null;
    };
  }, []);

  useEffect(() => {
    engineRef.current?.updateConfig(config);
    window.localStorage.setItem(PREFERENCE_KEY, JSON.stringify(config));
  }, [config]);

  const running = state.running;
  const closedLastHour = state.closedTrades.filter((trade) => Date.now() - new Date(trade.closedAt).getTime() <= 3_600_000);
  const tradesPerHour = closedLastHour.length;
  const recentHourPnl = closedLastHour.reduce((sum, trade) => sum + trade.pnlKrw, 0);
  const averageHoldingMinutes = state.closedTrades.length
    ? state.closedTrades.reduce((sum, trade) => sum + trade.holdingMinutes, 0) / state.closedTrades.length
    : 0;
  const buyConversionRate = state.finalistsReviewed > 0
    ? (state.buyExecutions / state.finalistsReviewed) * 100
    : 0;
  const lastScanLabel = state.lastScanAt
    ? new Date(state.lastScanAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : '미실행';

  const updateConfig = (patch: Partial<AiDayTraderConfig>) => {
    setConfig((current) => ({ ...current, ...patch }));
  };

  return (
    <div className="ai-scalp-terminal">
      <header className="ai-scalp-sessionbar">
        <div className="ai-scalp-sessionbar__identity">
          <div className="ai-scalp-sessionbar__title-row">
            <span className={`ai-scalp-status-dot ${statusToneClass(state.status)}`} />
            <div>
              <div className="ai-scalp-sessionbar__title">AI Scalp Trader</div>
              <div className="ai-scalp-sessionbar__subtitle">
                LLM-first · 5-30분 보유 · 전략코드 {config.strategyCode}
              </div>
            </div>
          </div>
          <div className="ai-scalp-sessionbar__meta">
            <span>상태 {statusLabel(state.status)}</span>
            <span>오픈 포지션 {state.positions.length}/{config.maxConcurrentPositions}</span>
            <span>세션 실현 {formatKrw(state.dailyPnl)}</span>
            <span>최근 스캔 {lastScanLabel}</span>
            <span>일손실 한도 {formatKrw(config.dailyLossLimitKrw)}</span>
          </div>
          {state.blockedReason && (
            <div className="ai-scalp-sessionbar__blocked">{state.blockedReason}</div>
          )}
        </div>

        <div className="ai-scalp-sessionbar__kpis">
          <KpiCard label="시간당 거래 수" value={`${tradesPerHour}건`} />
          <KpiCard label="최근 1시간 실현손익" value={formatKrw(recentHourPnl)} tone={recentHourPnl >= 0 ? 'positive' : 'negative'} />
          <KpiCard label="평균 보유시간" value={`${averageHoldingMinutes.toFixed(1)}분`} />
          <KpiCard label="BUY 전환율" value={`${buyConversionRate.toFixed(0)}%`} />
          <KpiCard label="손절 / 익절" value={`${state.losses} / ${state.wins}`} />
          <KpiCard label="대기 후보 수" value={`${state.queue.length}개`} />
        </div>

        <div className="ai-scalp-sessionbar__actions">
          <button
            type="button"
            className={`ai-scalp-action ${running ? 'danger' : 'primary'}`}
            onClick={() => {
              if (running) {
                engineRef.current?.stop();
                return;
              }
              engineRef.current?.updateConfig(config);
              engineRef.current?.start();
            }}
          >
            {running ? '중지' : '시작'}
          </button>
        </div>
      </header>

      <div className="ai-scalp-layout">
        <aside className="ai-scalp-panel ai-scalp-panel--queue">
          <PanelHeader
            title="기회 큐"
            subtitle="LLM이 지금 바로 보는 후보"
            right={`${state.queue.length}개`}
          />
          <div className="ai-scalp-panel__body ai-scalp-scroll">
            {state.queue.length === 0 ? (
              <EmptyState title="후보 없음" description="엔진을 시작하면 유동성 상위 시장을 압축해서 여기 올립니다." />
            ) : (
              state.queue.map((opportunity, index) => (
                <OpportunityCard key={opportunity.market} opportunity={opportunity} rank={index + 1} />
              ))
            )}
          </div>
        </aside>

        <main className="ai-scalp-panel ai-scalp-panel--journal">
          <PanelHeader
            title="결정 저널"
            subtitle="SCAN -> BUY/WAIT -> MANAGE -> SELL"
            right={`${state.events.length} events`}
          />
          <div className="ai-scalp-panel__body ai-scalp-scroll ai-scalp-journal">
            {state.events.length === 0 ? (
              <EmptyState title="저널 비어 있음" description="실시간 스캔과 AI 결정 이벤트가 여기에 시간순으로 쌓입니다." />
            ) : (
              state.events.map((event) => (
                <JournalItem key={event.id} event={event} />
              ))
            )}
          </div>
        </main>

        <aside className="ai-scalp-side">
          <section className="ai-scalp-panel ai-scalp-panel--positions">
            <PanelHeader
              title="현재 포지션"
              subtitle="AI_SCALP_TRADER prefix만 관리"
              right={`${state.positions.length}개`}
            />
            <div className="ai-scalp-panel__body ai-scalp-scroll">
              <div className="ai-scalp-side-metrics">
                <MiniStat label="스캔" value={`${state.scanCycles}`} />
                <MiniStat label="최종검토" value={`${state.finalistsReviewed}`} />
                <MiniStat label="매수실행" value={`${state.buyExecutions}`} />
              </div>
              {state.positions.length === 0 ? (
                <EmptyState title="보유 포지션 없음" description="진입이 실행되면 보유시간, 손익, 보호 가격이 여기에 표시됩니다." />
              ) : (
                state.positions.map((position) => (
                  <PositionCard key={position.tradeId} position={position} />
                ))
              )}
            </div>
          </section>

          <section className="ai-scalp-panel ai-scalp-panel--config">
            <PanelHeader
              title="설정"
              subtitle="새 데스크톱 초단타 전용 로컬 설정"
            />
            <div className="ai-scalp-panel__body ai-scalp-config">
              <ConfigField label="프로바이더">
                <select
                  value={config.provider}
                  disabled={running}
                  onChange={(event) => updateConfig({ provider: event.target.value as LlmProviderId })}
                >
                  <option value="openai">OpenAI</option>
                  <option value="zai">z.ai</option>
                </select>
              </ConfigField>

              <ConfigField label="모델">
                <select
                  value={config.model}
                  disabled={running}
                  onChange={(event) => updateConfig({ model: event.target.value })}
                >
                  {(config.provider === 'openai' ? CODEX_MODELS : ZAI_MODELS).map((model) => (
                    <option key={model.id} value={model.id}>{model.label}</option>
                  ))}
                </select>
              </ConfigField>

              <ConfigField label="1회 금액(KRW)">
                <input
                  type="number"
                  min={5000}
                  step={1000}
                  value={config.amountKrw}
                  disabled={running}
                  onChange={(event) => updateConfig({ amountKrw: Number(event.target.value) })}
                />
              </ConfigField>

              <ConfigField label="동시 포지션">
                <input
                  type="number"
                  min={1}
                  max={5}
                  value={config.maxConcurrentPositions}
                  disabled={running}
                  onChange={(event) => updateConfig({ maxConcurrentPositions: Number(event.target.value) })}
                />
              </ConfigField>

              <ConfigField label="일손실 한도(KRW)">
                <input
                  type="number"
                  step={1000}
                  value={config.dailyLossLimitKrw}
                  disabled={running}
                  onChange={(event) => updateConfig({ dailyLossLimitKrw: Number(event.target.value) })}
                />
              </ConfigField>

              <ConfigField label="스캔 주기">
                <select
                  value={config.scanIntervalMs}
                  disabled={running}
                  onChange={(event) => updateConfig({ scanIntervalMs: Number(event.target.value) })}
                >
                  <option value={10000}>10초</option>
                  <option value={15000}>15초</option>
                  <option value={20000}>20초</option>
                </select>
              </ConfigField>

              <ConfigField label="포지션 점검">
                <select
                  value={config.positionCheckMs}
                  disabled={running}
                  onChange={(event) => updateConfig({ positionCheckMs: Number(event.target.value) })}
                >
                  <option value={5000}>5초</option>
                  <option value={8000}>8초</option>
                  <option value={10000}>10초</option>
                </select>
              </ConfigField>

              <ConfigField label="유니버스">
                <select
                  value={config.universeLimit}
                  disabled={running}
                  onChange={(event) => updateConfig({ universeLimit: Number(event.target.value) })}
                >
                  <option value={24}>24개</option>
                  <option value={36}>36개</option>
                  <option value={48}>48개</option>
                  <option value={60}>60개</option>
                </select>
              </ConfigField>

              <ConfigField label="최대 보유시간">
                <select
                  value={config.maxHoldingMinutes}
                  disabled={running}
                  onChange={(event) => updateConfig({ maxHoldingMinutes: Number(event.target.value) })}
                >
                  <option value={15}>15분</option>
                  <option value={20}>20분</option>
                  <option value={30}>30분</option>
                  <option value={45}>45분</option>
                </select>
              </ConfigField>
            </div>
          </section>
        </aside>
      </div>
    </div>
  );
}

function statusLabel(status: AiTraderState['status']): string {
  const labels: Record<AiTraderState['status'], string> = {
    IDLE: '대기',
    SCANNING: '스캔 중',
    RANKING: '숏리스트 랭킹',
    ANALYZING: '최종 진입 검토',
    EXECUTING: '주문 실행',
    PAUSED: '신규 진입 차단',
    ERROR: '오류',
  };
  return labels[status];
}

function statusToneClass(status: AiTraderState['status']): string {
  if (status === 'EXECUTING') return 'is-live';
  if (status === 'SCANNING' || status === 'RANKING' || status === 'ANALYZING') return 'is-busy';
  if (status === 'PAUSED') return 'is-paused';
  if (status === 'ERROR') return 'is-error';
  return 'is-idle';
}

function formatKrw(value: number): string {
  const sign = value > 0 ? '+' : '';
  return `${sign}${Math.round(value).toLocaleString()}원`;
}

function formatPercent(value: number): string {
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
}

function formatCompactNumber(value: number): string {
  return new Intl.NumberFormat('ko-KR', {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

function formatClock(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function PanelHeader({ title, subtitle, right }: { title: string; subtitle: string; right?: string }) {
  return (
    <div className="ai-scalp-panel__header">
      <div>
        <div className="ai-scalp-panel__title">{title}</div>
        <div className="ai-scalp-panel__subtitle">{subtitle}</div>
      </div>
      {right && <div className="ai-scalp-panel__right">{right}</div>}
    </div>
  );
}

function KpiCard({ label, value, tone }: { label: string; value: string; tone?: 'positive' | 'negative' }) {
  return (
    <div className="ai-scalp-kpi">
      <div className="ai-scalp-kpi__label">{label}</div>
      <div className={`ai-scalp-kpi__value ${tone ?? ''}`}>{value}</div>
    </div>
  );
}

function MiniStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="ai-scalp-mini-stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function ConfigField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="ai-scalp-config__field">
      <span>{label}</span>
      {children}
    </label>
  );
}

function OpportunityCard({ opportunity, rank }: { opportunity: AiRankedOpportunity; rank: number }) {
  return (
    <article className="ai-scalp-opportunity">
      <div className="ai-scalp-opportunity__header">
        <span className="ai-scalp-opportunity__rank">#{rank}</span>
        <div>
          <div className="ai-scalp-opportunity__market">{opportunity.market.replace('KRW-', '')}</div>
          <div className="ai-scalp-opportunity__name">{opportunity.koreanName}</div>
        </div>
        <div className="ai-scalp-opportunity__score">{opportunity.score.toFixed(0)}</div>
      </div>
      <div className="ai-scalp-opportunity__meta">
        <span>가격 {opportunity.tradePrice.toLocaleString()}</span>
        <span className={opportunity.changeRate >= 0 ? 'positive' : 'negative'}>
          변화 {formatPercent(opportunity.changeRate)}
        </span>
        <span>거래대금 {formatCompactNumber(opportunity.turnover)}</span>
      </div>
      <div className="ai-scalp-opportunity__reason">{opportunity.reason}</div>
      {opportunity.crowd && (
        <div className="ai-scalp-opportunity__chips">
          <span>flow {opportunity.crowd.flowScore.toFixed(1)}</span>
          <span>spike {opportunity.crowd.priceSpike10sPercent.toFixed(2)}%</span>
          <span>imb {opportunity.crowd.bidImbalance.toFixed(2)}</span>
          <span>spread {opportunity.crowd.spreadPercent.toFixed(2)}%</span>
        </div>
      )}
    </article>
  );
}

function JournalItem({ event }: { event: AiTraderEvent }) {
  return (
    <article className="ai-scalp-journal__item">
      <div className="ai-scalp-journal__rail" />
      <div className="ai-scalp-journal__content">
        <div className="ai-scalp-journal__header">
          <div className="ai-scalp-journal__headline">
            <span className={`ai-scalp-event-badge ${event.type.toLowerCase()}`}>{event.type}</span>
            <span>{event.message}</span>
          </div>
          <div className="ai-scalp-journal__time">{formatClock(event.timestamp)}</div>
        </div>
        {(event.market || event.confidence !== undefined || event.urgency) && (
          <div className="ai-scalp-journal__meta">
            {event.market && <span>{event.market}</span>}
            {event.confidence !== undefined && <span>confidence {(event.confidence * 100).toFixed(0)}%</span>}
            {event.urgency && <span>urgency {event.urgency}</span>}
          </div>
        )}
        {event.detail && <div className="ai-scalp-journal__detail">{event.detail}</div>}
      </div>
    </article>
  );
}

function PositionCard({ position }: { position: import('../../api').GuidedTradePosition }) {
  const holdingMinutes = Math.max(0, (Date.now() - new Date(position.createdAt).getTime()) / 60_000);
  const pnlClass = position.unrealizedPnlPercent >= 0 ? 'positive' : 'negative';

  return (
    <article className="ai-scalp-position">
      <div className="ai-scalp-position__header">
        <div>
          <div className="ai-scalp-position__market">{position.market.replace('KRW-', '')}</div>
          <div className="ai-scalp-position__strategy">{position.strategyCode ?? 'AI_SCALP_TRADER'}</div>
        </div>
        <div className={`ai-scalp-position__pnl ${pnlClass}`}>
          {formatPercent(position.unrealizedPnlPercent)}
        </div>
      </div>
      <div className="ai-scalp-position__grid">
        <span>보유시간</span>
        <strong>{holdingMinutes.toFixed(1)}분</strong>
        <span>진입가</span>
        <strong>{position.averageEntryPrice.toLocaleString()}</strong>
        <span>현재가</span>
        <strong>{position.currentPrice.toLocaleString()}</strong>
        <span>손절</span>
        <strong>{position.stopLossPrice.toLocaleString()}</strong>
        <span>익절</span>
        <strong>{position.takeProfitPrice.toLocaleString()}</strong>
      </div>
    </article>
  );
}

function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="ai-scalp-empty">
      <div className="ai-scalp-empty__title">{title}</div>
      <div className="ai-scalp-empty__description">{description}</div>
    </div>
  );
}
