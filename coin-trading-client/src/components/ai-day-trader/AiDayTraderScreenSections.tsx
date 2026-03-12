import { useMemo, useState } from 'react';
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
  formatClock,
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
  AiDayTraderDesktopReviewView,
  AiDayTraderHistoryMarketSummary,
  AiDayTraderHistoryView,
  AiDayTraderJournalView,
  AiDayTraderProviderView,
  AiDayTraderSessionView,
  AiDayTraderWatchlistView,
} from './useAiDayTraderScreen';
import {
  ZAI_MODELS,
} from '../../lib/llmService';
import type { AiStrategyReflection } from '../../lib/ai-day-trader/AiDayTraderModel';

interface SessionBarProps {
  config: AiDayTraderConfig;
  state: AiTraderState;
  session: AiDayTraderSessionView;
  provider: AiDayTraderProviderView;
  onEngineAction: () => void;
  onToggleMonitor: () => void;
  isMonitorOpen: boolean;
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

interface WatchlistPanelProps {
  config: AiDayTraderConfig;
  watchlist: AiDayTraderWatchlistView;
  toggleSelectedMarket: (market: string) => void;
  clearSelectedMarkets: () => void;
  setSeedMarket: (market: string) => void;
  setSeedOrderType: (orderType: 'MARKET' | 'LIMIT') => void;
  setSeedLimitPrice: (value: string) => void;
  setSeedStopLossPrice: (value: string) => void;
  setSeedTakeProfitPrice: (value: string) => void;
  setSeedMaxDcaCount: (value: number) => void;
  setSeedDcaStepPercent: (value: number) => void;
  startSeedPosition: () => void;
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
  onToggleMonitor,
  isMonitorOpen,
}: SessionBarProps) {
  const reflection = state.strategyReflection;
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
          <span>감시 {config.selectedMarkets.length > 0 ? `${config.selectedMarkets.length}개 선택` : `상위 ${config.universeLimit}개 자동`}</span>
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
        {(reflection.status === 'ANALYZING' || reflection.updatedAt) && (
          <div className="ai-scalp-sessionbar__notice ai-scalp-sessionbar__notice--review">
            <strong>복기 전략</strong> {reflection.headline}
            <span>{reflection.summary}</span>
          </div>
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
          className="ai-scalp-action secondary"
          onClick={onToggleMonitor}
        >
          {isMonitorOpen ? '모니터 닫기' : '모니터 열기'}
        </button>
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

function renderStrategyAdjustmentChips(reflection: AiStrategyReflection) {
  const chips: string[] = [];
  const adjustments = reflection.adjustments;
  if (adjustments.minBuyConfidenceOffset !== 0) {
    chips.push(`conf ${adjustments.minBuyConfidenceOffset > 0 ? '+' : ''}${Math.round(adjustments.minBuyConfidenceOffset * 100)}%`);
  }
  if (adjustments.expectancyOffsetPct !== 0) {
    chips.push(`exp ${adjustments.expectancyOffsetPct > 0 ? '+' : ''}${adjustments.expectancyOffsetPct.toFixed(2)}%`);
  }
  if (adjustments.minWinRateOffset !== 0) {
    chips.push(`win ${adjustments.minWinRateOffset > 0 ? '+' : ''}${adjustments.minWinRateOffset.toFixed(1)}`);
  }
  if (adjustments.riskRewardOffset !== 0) {
    chips.push(`RR ${adjustments.riskRewardOffset > 0 ? '+' : ''}${adjustments.riskRewardOffset.toFixed(2)}`);
  }
  if (adjustments.spreadMultiplier !== 1) {
    chips.push(`spread x${adjustments.spreadMultiplier.toFixed(2)}`);
  }
  if (adjustments.gapMultiplier !== 1) {
    chips.push(`gap x${adjustments.gapMultiplier.toFixed(2)}`);
  }
  if (adjustments.maxHoldingMinutesOffset !== 0) {
    chips.push(`hold ${adjustments.maxHoldingMinutesOffset > 0 ? '+' : ''}${adjustments.maxHoldingMinutesOffset}m`);
  }
  return chips;
}

export function AiDayTraderStrategyPanel({ reflection }: { reflection: AiStrategyReflection }) {
  const adjustmentChips = renderStrategyAdjustmentChips(reflection);

  return (
    <section className="ai-scalp-panel ai-scalp-panel--strategy">
      <PanelHeader
        title="전략 복기"
        subtitle="오늘 거래를 복기해 진입/청산 기준을 자동 조정"
        right={reflection.updatedAt ? formatClock(reflection.updatedAt) : reflection.status}
      />
      <div className="ai-scalp-panel__body ai-scalp-strategy">
        <div className="ai-scalp-strategy__summary">
          <div className="ai-scalp-strategy__headline-row">
            <strong>{reflection.headline}</strong>
            <span className={`ai-scalp-strategy__pill ${reflection.source.toLowerCase()} ${reflection.status.toLowerCase()}`}>
              {reflection.source === 'LLM' ? 'LLM 강화' : '자동 복기'} · {reflection.status}
            </span>
          </div>
          <p>{reflection.summary}</p>
          <div className="ai-scalp-strategy__meta">
            <span>표본 {reflection.basedOnTradeCount}건</span>
            <span>기준 손익 {formatKrw(reflection.basedOnNetPnlKrw)}</span>
          </div>
        </div>

        <div className="ai-scalp-strategy__blocks">
          <div className="ai-scalp-strategy__block">
            <div className="ai-scalp-strategy__label">집중 시장</div>
            <div className="ai-scalp-strategy__chips">
              {reflection.focusMarkets.length > 0
                ? reflection.focusMarkets.map((market) => <span key={market}>{market.replace('KRW-', '')}</span>)
                : <span className="empty">없음</span>}
            </div>
          </div>
          <div className="ai-scalp-strategy__block">
            <div className="ai-scalp-strategy__label">회피 시장</div>
            <div className="ai-scalp-strategy__chips danger">
              {reflection.avoidMarkets.length > 0
                ? reflection.avoidMarkets.map((market) => <span key={market}>{market.replace('KRW-', '')}</span>)
                : <span className="empty">없음</span>}
            </div>
          </div>
        </div>

        <div className="ai-scalp-strategy__blocks">
          <div className="ai-scalp-strategy__block">
            <div className="ai-scalp-strategy__label">선호 셋업</div>
            <ul className="ai-scalp-strategy__list">
              {reflection.preferredSetups.length > 0
                ? reflection.preferredSetups.map((item) => <li key={item}>{item}</li>)
                : <li>최근 확실한 우위 셋업이 아직 없습니다.</li>}
            </ul>
          </div>
          <div className="ai-scalp-strategy__block">
            <div className="ai-scalp-strategy__label">회피 셋업</div>
            <ul className="ai-scalp-strategy__list danger">
              {reflection.avoidSetups.length > 0
                ? reflection.avoidSetups.map((item) => <li key={item}>{item}</li>)
                : <li>회피 셋업이 아직 지정되지 않았습니다.</li>}
            </ul>
          </div>
        </div>

        <div className="ai-scalp-strategy__block">
          <div className="ai-scalp-strategy__label">현재 적용 조정</div>
          <div className="ai-scalp-strategy__chips">
            {adjustmentChips.length > 0
              ? adjustmentChips.map((chip) => <span key={chip}>{chip}</span>)
              : <span className="empty">조정 없음</span>}
          </div>
        </div>
      </div>
    </section>
  );
}

function reviewStatusLabel(status: AiDayTraderDesktopReviewView['state']['status']): string {
  switch (status) {
    case 'CONNECTING':
      return '연결 중';
    case 'ANALYZING':
      return '분석 중';
    case 'APPLYING':
      return '적용 중';
    case 'READY':
      return '완료';
    case 'SKIPPED':
      return '건너뜀';
    case 'ERROR':
      return '오류';
    default:
      return '대기';
  }
}

export function AiDayTraderReviewAgentPanel({ review }: { review: AiDayTraderDesktopReviewView }) {
  const state = review.state;
  const configPatchChips = state.appliedConfigPatch
    ? Object.entries(state.appliedConfigPatch).map(([key, value]) => `${key}: ${String(value)}`)
    : [];

  return (
    <section className="ai-scalp-panel ai-scalp-panel--desktop-review">
      <PanelHeader
        title="데스크톱 복기 에이전트"
        subtitle="MySQL 직접 복기 + MCP 툴 호출 + 로컬 설정 자동 튜닝"
        right={state.lastRunAt ? formatClock(state.lastRunAt) : reviewStatusLabel(state.status)}
      />
      <div className="ai-scalp-panel__body ai-scalp-desktop-review">
        <div className="ai-scalp-desktop-review__topline">
          <span className={`ai-scalp-desktop-review__pill status-${state.status.toLowerCase()}`}>{reviewStatusLabel(state.status)}</span>
          <span className={`ai-scalp-desktop-review__pill ${state.mysqlConnected ? 'ok' : 'bad'}`}>MySQL {state.mysqlConnected ? '연결' : '오류'}</span>
          <span className={`ai-scalp-desktop-review__pill ${state.mcpConnected ? 'ok' : 'dim'}`}>MCP {state.mcpConnected ? '연결' : '대기'}</span>
          <span className={`ai-scalp-desktop-review__pill ${state.autoEnabled ? 'ok' : 'dim'}`}>야간 자동 {state.autoEnabled ? 'ON' : 'OFF'}</span>
        </div>

        <div className="ai-scalp-desktop-review__actions">
          <button
            type="button"
            className="ai-scalp-action secondary"
            onClick={() => void review.runNow()}
            disabled={state.status === 'CONNECTING' || state.status === 'ANALYZING' || state.status === 'APPLYING'}
          >
            지금 복기
          </button>
          <button
            type="button"
            className="ai-scalp-action subtle"
            onClick={review.toggleAutoEnabled}
          >
            {state.autoEnabled ? '야간 자동 끄기' : '야간 자동 켜기'}
          </button>
        </div>

        <div className="ai-scalp-desktop-review__summary">
          <strong>{state.headline}</strong>
          <p>{state.summary}</p>
          <div className="ai-scalp-desktop-review__meta">
            <span>대상일 {state.lastTargetDate ?? '-'}</span>
            <span>다음 예약 {state.nextRunAt ? formatDateTime(new Date(state.nextRunAt).toISOString()) : '-'}</span>
          </div>
          {state.lastError && (
            <div className="ai-scalp-desktop-review__error">{state.lastError}</div>
          )}
        </div>

        <div className="ai-scalp-desktop-review__blocks">
          <div className="ai-scalp-desktop-review__block">
            <div className="ai-scalp-desktop-review__label">로컬 설정 조정</div>
            <div className="ai-scalp-strategy__chips">
              {configPatchChips.length > 0
                ? configPatchChips.map((chip) => <span key={chip}>{chip}</span>)
                : <span className="empty">이번 실행에서 로컬 설정 변경 없음</span>}
            </div>
          </div>

          <div className="ai-scalp-desktop-review__block">
            <div className="ai-scalp-desktop-review__label">집중 / 회피 시장</div>
            <div className="ai-scalp-strategy__chips">
              {state.focusMarkets.length > 0
                ? state.focusMarkets.map((market: string) => <span key={market}>{market.replace('KRW-', '')}</span>)
                : <span className="empty">집중 시장 없음</span>}
            </div>
            <div className="ai-scalp-strategy__chips danger">
              {state.avoidMarkets.length > 0
                ? state.avoidMarkets.map((market: string) => <span key={market}>{market.replace('KRW-', '')}</span>)
                : <span className="empty">회피 시장 없음</span>}
            </div>
          </div>
        </div>

        <div className="ai-scalp-desktop-review__blocks">
          <div className="ai-scalp-desktop-review__block">
            <div className="ai-scalp-desktop-review__label">서버/MCP 조치</div>
            <ul className="ai-scalp-strategy__list">
              {state.serverActionSummary.length > 0
                ? state.serverActionSummary.map((item: string) => <li key={item}>{item}</li>)
                : <li>실행된 서버 조치 없음</li>}
            </ul>
            {state.slackSummary && (
              <div className="ai-scalp-desktop-review__slack">{state.slackSummary}</div>
            )}
          </div>

          <div className="ai-scalp-desktop-review__block">
            <div className="ai-scalp-desktop-review__label">최근 MCP 도구 호출</div>
            <ul className="ai-scalp-strategy__list">
              {state.toolActions.length > 0
                ? state.toolActions.slice(0, 5).map((action: AiDayTraderDesktopReviewView['state']['toolActions'][number]) => (
                  <li key={action.id}>
                    <strong>{action.toolName}</strong> {action.ok === false ? '실패' : action.result ? '완료' : '호출'}
                  </li>
                ))
                : <li>이번 실행에서 MCP 도구 호출 없음</li>}
            </ul>
          </div>
        </div>
      </div>
    </section>
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

export function AiDayTraderWatchlistPanel({
  config,
  watchlist,
  toggleSelectedMarket,
  clearSelectedMarkets,
  setSeedMarket,
  setSeedOrderType,
  setSeedLimitPrice,
  setSeedStopLossPrice,
  setSeedTakeProfitPrice,
  setSeedMaxDcaCount,
  setSeedDcaStepPercent,
  startSeedPosition,
}: WatchlistPanelProps) {
  const [marketQuery, setMarketQuery] = useState('');
  const normalizedQuery = marketQuery.trim().toUpperCase();
  const selectedSet = useMemo(() => new Set(watchlist.selectedMarkets), [watchlist.selectedMarkets]);
  const filteredMarkets = useMemo(() => {
    const items = watchlist.catalog;
    if (!normalizedQuery) {
      return items.slice(0, 24);
    }
    return items
      .filter((item) => {
        const marketCode = item.market.toUpperCase();
        const symbol = item.symbol.toUpperCase();
        const koreanName = item.koreanName.toUpperCase();
        const englishName = item.englishName?.toUpperCase() ?? '';
        return marketCode.includes(normalizedQuery)
          || symbol.includes(normalizedQuery)
          || koreanName.includes(normalizedQuery)
          || englishName.includes(normalizedQuery);
      })
      .slice(0, 24);
  }, [normalizedQuery, watchlist.catalog]);

  return (
    <section className="ai-scalp-panel ai-scalp-panel--control">
      <PanelHeader
        title="감시 코인 / 시드 진입"
        subtitle="선택한 코인만 감시하고 원하는 가격으로 포지션을 시작"
        right={watchlist.selectedMarkets.length > 0 ? `${watchlist.selectedMarkets.length} selected` : '자동 유니버스'}
      />
      <div className="ai-scalp-panel__body ai-scalp-control">
        <div className="ai-scalp-control__block">
          <div className="ai-scalp-control__label-row">
            <strong>감시 코인</strong>
            <button
              type="button"
              className="ai-scalp-link-button"
              onClick={clearSelectedMarkets}
              disabled={watchlist.selectedMarkets.length === 0}
            >
              전체 해제
            </button>
          </div>
          <p className="ai-scalp-control__hint">
            선택하면 AI가 그 코인들만 숏리스트/진입 후보로 봅니다. 비워두면 기존처럼 유동성 상위 시장을 자동 스캔합니다.
          </p>
          <input
            className="ai-scalp-control__search"
            value={marketQuery}
            onChange={(event) => setMarketQuery(event.target.value)}
            placeholder="BTC, XRP, 비트코인"
          />
          <div className="ai-scalp-control__market-list">
            {watchlist.isCatalogLoading ? (
              <div className="ai-scalp-control__empty">시장 목록 불러오는 중...</div>
            ) : filteredMarkets.length === 0 ? (
              <div className="ai-scalp-control__empty">검색 결과가 없습니다.</div>
            ) : (
              filteredMarkets.map((item) => {
                const selected = selectedSet.has(item.market);
                const isSeed = watchlist.seedMarket === item.market;
                return (
                  <div
                    key={item.market}
                    className={`ai-scalp-control__market-row ${selected ? 'is-selected' : ''} ${isSeed ? 'is-seed' : ''}`}
                  >
                    <button
                      type="button"
                      className="ai-scalp-control__market-main"
                      onClick={() => setSeedMarket(item.market)}
                    >
                      <strong>{item.market.replace('KRW-', '')}</strong>
                      <span>{item.koreanName}</span>
                      <em>{Math.round(item.tradePrice).toLocaleString()}원</em>
                    </button>
                    <button
                      type="button"
                      className={`ai-scalp-control__market-toggle ${selected ? 'active' : ''}`}
                      onClick={() => toggleSelectedMarket(item.market)}
                    >
                      {selected ? '감시 중' : '감시 추가'}
                    </button>
                  </div>
                );
              })
            )}
          </div>
          <div className="ai-scalp-strategy__chips">
            {watchlist.selectedMarkets.length > 0
              ? watchlist.selectedMarkets.map((market) => (
                <button
                  key={market}
                  type="button"
                  className="ai-scalp-control__selected-chip"
                  onClick={() => toggleSelectedMarket(market)}
                >
                  {market.replace('KRW-', '')}
                </button>
              ))
              : <span className="empty">현재는 자동 유니버스를 사용합니다.</span>}
          </div>
        </div>

        <div className="ai-scalp-control__block">
          <div className="ai-scalp-control__label-row">
            <strong>수동 시드 진입</strong>
            <span className="ai-scalp-control__seed-market">
              {watchlist.selectedSeedMarket
                ? `${watchlist.selectedSeedMarket.market.replace('KRW-', '')} · ${watchlist.selectedSeedMarket.koreanName}`
                : '코인을 먼저 고르세요'}
            </span>
          </div>
          <p className="ai-scalp-control__hint">
            지정가나 시장가로 포지션을 먼저 시작한 뒤, 같은 전략코드로 AI가 계속 모니터링하며 익절/손절/DCA를 이어받습니다.
          </p>
          {watchlist.seedRecommendation && (
            <div className="ai-scalp-control__seed-summary">
              <span>추천 진입 {Math.round(watchlist.seedRecommendation.recommendedEntryPrice).toLocaleString()}</span>
              <span>손절 {Math.round(watchlist.seedRecommendation.stopLossPrice).toLocaleString()}</span>
              <span>익절 {Math.round(watchlist.seedRecommendation.takeProfitPrice).toLocaleString()}</span>
              <span>RR {watchlist.seedRecommendation.riskRewardRatio.toFixed(2)}</span>
            </div>
          )}
          <div className="ai-scalp-config ai-scalp-config--seed">
            <ConfigField label="주문 방식">
              <select
                value={watchlist.seedOrderType}
                onChange={(event) => setSeedOrderType(event.target.value as 'MARKET' | 'LIMIT')}
              >
                <option value="LIMIT">지정가 시작</option>
                <option value="MARKET">시장가 시작</option>
              </select>
            </ConfigField>

            <ConfigField label="진입가">
              <input
                type="number"
                step="1"
                value={watchlist.seedLimitPrice}
                disabled={watchlist.seedOrderType !== 'LIMIT'}
                onChange={(event) => setSeedLimitPrice(event.target.value)}
              />
            </ConfigField>

            <ConfigField label="손절가">
              <input
                type="number"
                step="1"
                value={watchlist.seedStopLossPrice}
                onChange={(event) => setSeedStopLossPrice(event.target.value)}
              />
            </ConfigField>

            <ConfigField label="익절가">
              <input
                type="number"
                step="1"
                value={watchlist.seedTakeProfitPrice}
                onChange={(event) => setSeedTakeProfitPrice(event.target.value)}
              />
            </ConfigField>

            <ConfigField label="물타기 횟수">
              <select
                value={watchlist.seedMaxDcaCount}
                onChange={(event) => setSeedMaxDcaCount(Number(event.target.value))}
              >
                <option value={0}>안 함</option>
                <option value={1}>1회</option>
                <option value={2}>2회</option>
                <option value={3}>3회</option>
              </select>
            </ConfigField>

            <ConfigField label="DCA 간격(%)">
              <input
                type="number"
                min={0.5}
                max={15}
                step={0.1}
                value={watchlist.seedDcaStepPercent}
                disabled={watchlist.seedMaxDcaCount === 0}
                onChange={(event) => setSeedDcaStepPercent(Number(event.target.value))}
              />
            </ConfigField>
          </div>

          <div className="ai-scalp-control__seed-footer">
            <span>1회 금액 {formatKrw(config.amountKrw)} · 전략코드 {config.strategyCode}</span>
            <button
              type="button"
              className="ai-scalp-action primary"
              onClick={startSeedPosition}
              disabled={!watchlist.seedMarket || watchlist.isSeedBusy}
            >
              {watchlist.isSeedBusy ? '시작 중...' : '이 코인으로 시작'}
            </button>
          </div>

          {watchlist.seedMessage && (
            <div className="ai-scalp-control__message">{watchlist.seedMessage}</div>
          )}
        </div>
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
