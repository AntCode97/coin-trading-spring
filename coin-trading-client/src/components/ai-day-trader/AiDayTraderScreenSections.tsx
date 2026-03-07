import type { GuidedClosedTradeView, GuidedDailyStats } from '../../api';
import {
  AI_ENTRY_AGGRESSION_OPTIONS,
  type AiDayTraderConfig,
  type AiEntryAggression,
  type AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderEngine';
import {
  type LlmProviderId,
} from '../../lib/llmService';
import {
  ConfigField,
  EmptyState,
  JournalItem,
  KpiCard,
  MiniStat,
  OpportunityCard,
  PanelHeader,
  PositionCard,
  formatDateTime,
  formatExitReason,
  formatHoldingMinutes,
  formatKrw,
  formatPercent,
  providerStatusLabel,
  statusLabel,
  statusToneClass,
} from './AiDayTraderScreenParts';
import {
  MAX_HOLDING_OPTIONS,
  OPENAI_TRADER_MODELS,
  POSITION_CHECK_OPTIONS,
  SCAN_INTERVAL_OPTIONS,
  UNIVERSE_LIMIT_OPTIONS,
  normalizePreferredModel,
} from './AiDayTraderScreenConfig';
import type {
  AiDayTraderHistoryMarketSummary,
  AiDayTraderHistoryView,
  AiDayTraderJournalView,
  AiDayTraderProviderView,
  AiDayTraderSessionView,
} from './useAiDayTraderScreen';
import {
  ZAI_MODELS,
} from '../../lib/llmService';

interface SessionBarProps {
  config: AiDayTraderConfig;
  state: AiTraderState;
  session: AiDayTraderSessionView;
  provider: AiDayTraderProviderView;
  onEngineAction: () => void;
}

interface JournalPanelProps {
  events: AiTraderState['events'];
  journal: AiDayTraderJournalView;
  journalScrollRef: React.MutableRefObject<HTMLDivElement | null>;
  onLatest: () => void;
  onPrevious: () => void;
  onNext: () => void;
}

interface ConfigPanelProps {
  config: AiDayTraderConfig;
  running: boolean;
  provider: AiDayTraderProviderView;
  updateConfig: (patch: Partial<AiDayTraderConfig>) => void;
  onRefreshConnection: () => void;
  onOpenAiLogin: () => void;
  onOpenAiLogout: () => void;
  onSaveZaiKey: () => void;
  onClearZaiKey: () => void;
  onZaiApiKeyChange: (value: string) => void;
}

interface HistoryPanelProps {
  history: AiDayTraderHistoryView;
  onSelectMarket: (market: string) => void;
}

export function AiDayTraderSessionBar({
  config,
  state,
  session,
  provider,
  onEngineAction,
}: SessionBarProps) {
  return (
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
        <div className="ai-scalp-provider-row">
          <span className={`ai-scalp-provider-pill ${provider.providerConnected ? 'connected' : provider.activeProviderStatus}`}>
            {config.provider === 'zai' ? 'z.ai' : 'OpenAI'} · {providerStatusLabel(provider.activeProviderStatus)}
          </span>
          <span className="ai-scalp-provider-model">모델 {config.model}</span>
        </div>
        <div className="ai-scalp-sessionbar__meta">
          <span>상태 {statusLabel(state.status)}</span>
          <span>오픈 포지션 {state.positions.length}/{config.maxConcurrentPositions}</span>
          <span>신규 진입 {session.entryEnabled ? '활성' : '중지'}</span>
          <span>세션 실현 {formatKrw(state.dailyPnl)}</span>
          <span>최근 스캔 {session.lastScanLabel}</span>
          <span>일손실 한도 {formatKrw(config.dailyLossLimitKrw)}</span>
        </div>
        {provider.providerMessage && (
          <div className="ai-scalp-sessionbar__notice">{provider.providerMessage}</div>
        )}
        {state.blockedReason && (
          <div className="ai-scalp-sessionbar__blocked">{state.blockedReason}</div>
        )}
      </div>

      <div className="ai-scalp-sessionbar__kpis">
        <KpiCard label="시간당 거래 수" value={`${session.tradesPerHour}건`} />
        <KpiCard
          label="최근 1시간 실현손익"
          value={formatKrw(session.recentHourPnl)}
          tone={session.recentHourPnl >= 0 ? 'positive' : 'negative'}
        />
        <KpiCard label="평균 보유시간" value={`${session.averageHoldingMinutes.toFixed(1)}분`} />
        <KpiCard label="BUY 전환율" value={`${session.buyConversionRate.toFixed(0)}%`} />
        <KpiCard label="손절 / 익절" value={`${state.losses} / ${state.wins}`} />
        <KpiCard label="대기 후보 수" value={`${state.queue.length}개`} />
      </div>

      <div className="ai-scalp-sessionbar__actions">
        <button
          type="button"
          className={`ai-scalp-action ${session.engineActionClass}`}
          onClick={onEngineAction}
        >
          {session.engineActionLabel}
        </button>
      </div>
    </header>
  );
}

export function AiDayTraderQueuePanel({ opportunities }: { opportunities: AiTraderState['queue'] }) {
  return (
    <aside className="ai-scalp-panel ai-scalp-panel--queue">
      <PanelHeader
        title="기회 큐"
        subtitle="LLM이 지금 바로 보는 후보"
        right={`${opportunities.length}개`}
      />
      <div className="ai-scalp-panel__body ai-scalp-scroll">
        {opportunities.length === 0 ? (
          <EmptyState title="후보 없음" description="엔진을 시작하면 유동성 상위 시장을 압축해서 여기 올립니다." />
        ) : (
          opportunities.map((opportunity, index) => (
            <OpportunityCard key={opportunity.market} opportunity={opportunity} rank={index + 1} />
          ))
        )}
      </div>
    </aside>
  );
}

export function AiDayTraderJournalPanel({
  events,
  journal,
  journalScrollRef,
  onLatest,
  onPrevious,
  onNext,
}: JournalPanelProps) {
  return (
    <main className="ai-scalp-panel ai-scalp-panel--journal">
      <PanelHeader
        title="결정 저널"
        subtitle="SCAN -> BUY/WAIT -> MANAGE -> SELL"
        right={`${events.length} events`}
      />
      <div ref={journalScrollRef} className="ai-scalp-panel__body ai-scalp-scroll ai-scalp-journal">
        {events.length === 0 ? (
          <EmptyState title="저널 비어 있음" description="실시간 스캔과 AI 결정 이벤트가 여기에 시간순으로 쌓입니다." />
        ) : (
          <>
            {journal.pageCount > 1 && (
              <div className="ai-scalp-journal__pager">
                <div className="ai-scalp-journal__pager-meta">
                  <strong>{journal.page} / {journal.pageCount}</strong>
                  <span>{journal.startIndex}-{journal.endIndex} / {events.length}</span>
                </div>
                <div className="ai-scalp-journal__pager-actions">
                  <button
                    type="button"
                    className="ai-scalp-journal__pager-button"
                    onClick={onLatest}
                    disabled={!journal.canGoPrevious}
                  >
                    최신
                  </button>
                  <button
                    type="button"
                    className="ai-scalp-journal__pager-button"
                    onClick={onPrevious}
                    disabled={!journal.canGoPrevious}
                  >
                    이전
                  </button>
                  <button
                    type="button"
                    className="ai-scalp-journal__pager-button"
                    onClick={onNext}
                    disabled={!journal.canGoNext}
                  >
                    다음
                  </button>
                </div>
              </div>
            )}
            {journal.pageEvents.map((event) => (
              <JournalItem key={event.id} event={event} />
            ))}
          </>
        )}
      </div>
    </main>
  );
}

export function AiDayTraderPositionsPanel({
  positions,
  scanCycles,
  finalistsReviewed,
  buyExecutions,
}: {
  positions: AiTraderState['positions'];
  scanCycles: number;
  finalistsReviewed: number;
  buyExecutions: number;
}) {
  return (
    <section className="ai-scalp-panel ai-scalp-panel--positions">
      <PanelHeader
        title="현재 포지션"
        subtitle="AI_SCALP_TRADER prefix만 관리"
        right={`${positions.length}개`}
      />
      <div className="ai-scalp-panel__body ai-scalp-scroll">
        <div className="ai-scalp-side-metrics">
          <MiniStat label="스캔" value={`${scanCycles}`} />
          <MiniStat label="최종검토" value={`${finalistsReviewed}`} />
          <MiniStat label="매수실행" value={`${buyExecutions}`} />
        </div>
        {positions.length === 0 ? (
          <EmptyState title="보유 포지션 없음" description="진입이 실행되면 보유시간, 손익, 보호 가격이 여기에 표시됩니다." />
        ) : (
          positions.map((position) => (
            <PositionCard key={position.tradeId} position={position} />
          ))
        )}
      </div>
    </section>
  );
}

function renderSelectOptions(options: ReadonlyArray<{ value: number; label: string }>) {
  return options.map((option) => (
    <option key={option.value} value={option.value}>{option.label}</option>
  ));
}

export function AiDayTraderConfigPanel({
  config,
  running,
  provider,
  updateConfig,
  onRefreshConnection,
  onOpenAiLogin,
  onOpenAiLogout,
  onSaveZaiKey,
  onClearZaiKey,
  onZaiApiKeyChange,
}: ConfigPanelProps) {
  const modelCatalog = config.provider === 'openai' ? OPENAI_TRADER_MODELS : ZAI_MODELS;

  return (
    <section className="ai-scalp-panel ai-scalp-panel--config">
      <PanelHeader
        title="설정"
        subtitle="새 데스크톱 초단타 전용 로컬 설정"
      />
      <div className="ai-scalp-panel__body ai-scalp-config">
        <div className="ai-scalp-provider-card">
          <div className="ai-scalp-provider-card__header">
            <div>
              <div className="ai-scalp-provider-card__title">LLM 연결</div>
              <div className="ai-scalp-provider-card__subtitle">
                {config.provider === 'zai' ? 'API Key 방식' : 'ChatGPT / Codex 계정 로그인'}
              </div>
            </div>
            <button type="button" className="ai-scalp-link-button" onClick={onRefreshConnection}>
              새로고침
            </button>
          </div>
          <div className="ai-scalp-provider-card__status">
            <span className={`ai-scalp-provider-pill ${provider.providerConnected ? 'connected' : provider.activeProviderStatus}`}>
              {config.provider === 'zai' ? 'z.ai' : 'OpenAI'} · {providerStatusLabel(provider.activeProviderStatus)}
            </span>
            <span className="ai-scalp-provider-card__hint">
              {config.provider === 'zai'
                ? `슬롯 ${provider.zaiConcurrency.active}/${provider.zaiConcurrency.max} · 대기 ${provider.zaiConcurrency.queued}`
                : '로그인 후 초단타 엔진을 시작할 수 있습니다.'}
            </span>
          </div>
          {config.provider === 'openai' ? (
            <div className="ai-scalp-provider-card__actions">
              {provider.providerConnected ? (
                <button type="button" className="ai-scalp-secondary-button" onClick={onOpenAiLogout} disabled={provider.providerBusy}>
                  {provider.providerBusy ? '처리 중...' : '로그아웃'}
                </button>
              ) : (
                <button
                  type="button"
                  className="ai-scalp-secondary-button"
                  onClick={onOpenAiLogin}
                  disabled={provider.providerBusy || provider.providerChecking}
                >
                  {provider.providerBusy || provider.providerChecking ? '연결 중...' : '로그인'}
                </button>
              )}
            </div>
          ) : (
            <div className="ai-scalp-provider-card__key-row">
              <input
                type="password"
                value={provider.zaiApiKeyInput}
                onChange={(event) => onZaiApiKeyChange(event.target.value)}
                placeholder="z.ai API Key"
                disabled={provider.zaiApiKeyBusy}
              />
              <button
                type="button"
                className="ai-scalp-secondary-button"
                onClick={onSaveZaiKey}
                disabled={provider.zaiApiKeyBusy || !provider.zaiApiKeyInput.trim()}
              >
                {provider.zaiApiKeyBusy ? '저장 중...' : '키 저장'}
              </button>
              <button
                type="button"
                className="ai-scalp-secondary-button ghost"
                onClick={onClearZaiKey}
                disabled={provider.zaiApiKeyBusy || provider.zaiStatus !== 'connected'}
              >
                {provider.zaiApiKeyBusy ? '처리 중...' : '키 삭제'}
              </button>
            </div>
          )}
          {config.provider === 'openai' && (
            <div className="ai-scalp-provider-card__hint danger">
              저장된 구버전 `gpt-4` 선택은 자동으로 `gpt-5.4`로 교체됩니다.
            </div>
          )}
        </div>

        <ConfigField label="프로바이더">
          <select
            value={config.provider}
            disabled={running}
            onChange={(event) => updateConfig({
              provider: event.target.value as LlmProviderId,
              model: normalizePreferredModel(event.target.value as LlmProviderId, config.model),
            })}
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
            {modelCatalog.map((model) => (
              <option key={model.id} value={model.id}>{model.label}</option>
            ))}
          </select>
        </ConfigField>

        <ConfigField label="진입 강도">
          <select
            value={config.entryAggression}
            disabled={running}
            onChange={(event) => updateConfig({ entryAggression: event.target.value as AiEntryAggression })}
          >
            {AI_ENTRY_AGGRESSION_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </ConfigField>

        <ConfigField label="1회 금액(KRW)">
          <input
            type="number"
            min={5000}
            step={5000}
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
            {renderSelectOptions(SCAN_INTERVAL_OPTIONS)}
          </select>
        </ConfigField>

        <ConfigField label="포지션 점검">
          <select
            value={config.positionCheckMs}
            disabled={running}
            onChange={(event) => updateConfig({ positionCheckMs: Number(event.target.value) })}
          >
            {renderSelectOptions(POSITION_CHECK_OPTIONS)}
          </select>
        </ConfigField>

        <ConfigField label="유니버스">
          <select
            value={config.universeLimit}
            disabled={running}
            onChange={(event) => updateConfig({ universeLimit: Number(event.target.value) })}
          >
            {renderSelectOptions(UNIVERSE_LIMIT_OPTIONS)}
          </select>
        </ConfigField>

        <ConfigField label="최대 보유시간">
          <select
            value={config.maxHoldingMinutes}
            disabled={running}
            onChange={(event) => updateConfig({ maxHoldingMinutes: Number(event.target.value) })}
          >
            {renderSelectOptions(MAX_HOLDING_OPTIONS)}
          </select>
        </ConfigField>
      </div>
    </section>
  );
}

function HistorySummaryCards({
  todayStats,
}: {
  todayStats?: GuidedDailyStats;
}) {
  return (
    <div className="ai-scalp-history__summary">
      <KpiCard label="오늘 거래 수" value={`${todayStats?.totalTrades ?? 0}건`} />
      <KpiCard label="오늘 승률" value={`${(todayStats?.winRate ?? 0).toFixed(0)}%`} />
      <KpiCard
        label="오늘 실현손익"
        value={formatKrw(todayStats?.totalPnlKrw ?? 0)}
        tone={(todayStats?.totalPnlKrw ?? 0) >= 0 ? 'positive' : 'negative'}
      />
      <KpiCard label="열린 포지션" value={`${todayStats?.openPositionCount ?? 0}개`} />
    </div>
  );
}

function HistoryMarketStrip({
  summaries,
  selectedMarket,
  onSelectMarket,
}: {
  summaries: AiDayTraderHistoryMarketSummary[];
  selectedMarket: string;
  onSelectMarket: (market: string) => void;
}) {
  return (
    <div className="ai-scalp-history__market-strip">
      <button
        type="button"
        className={`ai-scalp-history-chip ${selectedMarket === 'ALL' ? 'active' : ''}`}
        onClick={() => onSelectMarket('ALL')}
      >
        전체
      </button>
      {summaries.map((summary) => (
        <button
          key={summary.market}
          type="button"
          className={`ai-scalp-history-chip ${selectedMarket === summary.market ? 'active' : ''}`}
          onClick={() => onSelectMarket(summary.market)}
        >
          {summary.market.replace('KRW-', '')}
          <span>{summary.trades.length}</span>
        </button>
      ))}
    </div>
  );
}

function HistoryMarketCards({ summaries }: { summaries: AiDayTraderHistoryMarketSummary[] }) {
  return (
    <div className="ai-scalp-history__market-cards">
      {summaries.map((summary) => (
        <article key={summary.market} className="ai-scalp-history-card">
          <div className="ai-scalp-history-card__header">
            <div className="ai-scalp-history-card__market">{summary.market.replace('KRW-', '')}</div>
            <div className={`ai-scalp-history-card__pnl ${summary.pnlKrw >= 0 ? 'positive' : 'negative'}`}>
              {formatKrw(summary.pnlKrw)}
            </div>
          </div>
          <div className="ai-scalp-history-card__meta">
            <span>거래 {summary.trades.length}건</span>
            <span>익절 {summary.wins}</span>
            <span>손절 {summary.losses}</span>
          </div>
        </article>
      ))}
    </div>
  );
}

function HistoryTable({ trades }: { trades: GuidedClosedTradeView[] }) {
  return (
    <div className="ai-scalp-history-table">
      <div className="ai-scalp-history-table__header">
        <span>코인</span>
        <span>진입</span>
        <span>청산</span>
        <span>보유</span>
        <span>손익</span>
        <span>사유</span>
      </div>
      <div className="ai-scalp-history-table__body">
        {trades.map((trade) => (
          <div key={trade.tradeId} className="ai-scalp-history-table__row">
            <span className="ai-scalp-history-table__market">{trade.market.replace('KRW-', '')}</span>
            <span>{formatDateTime(trade.createdAt)}</span>
            <span>{trade.closedAt ? formatDateTime(trade.closedAt) : '-'}</span>
            <span>{formatHoldingMinutes(trade.createdAt, trade.closedAt)}</span>
            <span className={trade.realizedPnl >= 0 ? 'positive' : 'negative'}>
              {formatKrw(trade.realizedPnl)} / {formatPercent(trade.realizedPnlPercent)}
            </span>
            <span title={trade.exitReason ?? '-'}>
              {formatExitReason(trade.exitReason)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function AiDayTraderHistoryPanel({
  history,
  onSelectMarket,
}: HistoryPanelProps) {
  return (
    <section className="ai-scalp-panel ai-scalp-history">
      <PanelHeader
        title="오늘 거래내역"
        subtitle="전체 보기와 코인별 보기 모두 지원"
        right={history.isFetching ? '새로고침 중' : `${history.todayTrades.length} trades`}
      />
      <div className="ai-scalp-panel__body ai-scalp-history__body">
        <HistorySummaryCards todayStats={history.todayStats} />
        <HistoryMarketStrip
          summaries={history.historyMarketSummaries}
          selectedMarket={history.selectedHistoryMarket}
          onSelectMarket={onSelectMarket}
        />

        {history.isLoading ? (
          <EmptyState title="오늘 거래내역 불러오는 중" description="금일 AI 초단타 청산 내역을 조회하고 있습니다." />
        ) : history.todayTrades.length === 0 ? (
          <EmptyState title="오늘 거래 없음" description="AI_SCALP_TRADER prefix 기준으로 오늘 청산된 거래가 아직 없습니다." />
        ) : (
          <>
            <HistoryMarketCards summaries={history.historyMarketSummaries} />
            <HistoryTable trades={history.visibleTrades} />
          </>
        )}
      </div>
    </section>
  );
}
