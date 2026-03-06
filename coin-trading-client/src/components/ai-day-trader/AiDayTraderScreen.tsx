import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  guidedTradingApi,
  type GuidedClosedTradeView,
} from '../../api';
import {
  AI_ENTRY_AGGRESSION_OPTIONS,
  AiDayTraderEngine,
  DEFAULT_AI_DAY_TRADER_CONFIG,
  createInitialAiTraderState,
  type AiDayTraderConfig,
  type AiEntryAggression,
  type AiRankedOpportunity,
  type AiTraderEvent,
  type AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderEngine';
import {
  CODEX_MODELS,
  DEFAULT_OPENAI_MODEL,
  ZAI_MODELS,
  checkConnection,
  clearConversation,
  clearZaiApiKey,
  getZaiConcurrencyStatus,
  logout,
  setZaiApiKey,
  startLogin,
  subscribeZaiConcurrency,
  type LlmConnectionStatus,
  type LlmProviderId,
  type ZaiConcurrencyStatus,
} from '../../lib/llmService';
import './AiDayTraderScreen.css';

const PREFERENCE_KEY = 'ai-scalp-terminal.preferences.v1';
const OPENAI_TRADER_MODELS = CODEX_MODELS.filter((model) => model.id !== 'gpt-4');
const JOURNAL_PAGE_SIZE = 18;

function normalizeEntryAggression(value?: string): AiEntryAggression {
  if (value === 'CONSERVATIVE' || value === 'AGGRESSIVE') {
    return value;
  }
  return 'BALANCED';
}

function normalizePreferredModel(provider: LlmProviderId, model?: string): string {
  const catalog = provider === 'zai' ? ZAI_MODELS : OPENAI_TRADER_MODELS;
  const normalized = model?.trim();
  if (provider === 'openai' && normalized === 'gpt-4') {
    return DEFAULT_OPENAI_MODEL;
  }
  return catalog.some((item) => item.id === normalized)
    ? normalized!
    : catalog[0]?.id ?? DEFAULT_AI_DAY_TRADER_CONFIG.model;
}

function loadPreferences(): AiDayTraderConfig {
  try {
    const raw = window.localStorage.getItem(PREFERENCE_KEY);
    if (!raw) return DEFAULT_AI_DAY_TRADER_CONFIG;
    const parsed = JSON.parse(raw) as Partial<AiDayTraderConfig>;
    const provider = parsed.provider === 'zai' ? 'zai' : DEFAULT_AI_DAY_TRADER_CONFIG.provider;
    return {
      ...DEFAULT_AI_DAY_TRADER_CONFIG,
      ...parsed,
      provider,
      entryAggression: normalizeEntryAggression(parsed.entryAggression),
      model: normalizePreferredModel(provider, parsed.model),
      strategyCode: DEFAULT_AI_DAY_TRADER_CONFIG.strategyCode,
    };
  } catch {
    return DEFAULT_AI_DAY_TRADER_CONFIG;
  }
}

export default function AiDayTraderScreen() {
  const engineRef = useRef<AiDayTraderEngine | null>(null);
  const journalScrollRef = useRef<HTMLDivElement | null>(null);
  const [config, setConfig] = useState<AiDayTraderConfig>(() => loadPreferences());
  const [state, setState] = useState<AiTraderState>(() => createInitialAiTraderState());
  const [selectedHistoryMarket, setSelectedHistoryMarket] = useState<string>('ALL');
  const [journalPage, setJournalPage] = useState(1);
  const [openAiStatus, setOpenAiStatus] = useState<LlmConnectionStatus>('checking');
  const [zaiStatus, setZaiStatus] = useState<LlmConnectionStatus>('checking');
  const [providerBusy, setProviderBusy] = useState(false);
  const [zaiApiKeyBusy, setZaiApiKeyBusy] = useState(false);
  const [zaiApiKeyInput, setZaiApiKeyInput] = useState('');
  const [providerMessage, setProviderMessage] = useState<string | null>(null);
  const [zaiConcurrency, setZaiConcurrency] = useState<ZaiConcurrencyStatus>(getZaiConcurrencyStatus());

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

  useEffect(() => {
    const unsubscribe = subscribeZaiConcurrency(setZaiConcurrency);
    return unsubscribe;
  }, []);

  useEffect(() => {
    let cancelled = false;
    const refresh = async () => {
      try {
        const [openai, zai] = await Promise.all([
          checkConnection('openai'),
          checkConnection('zai'),
        ]);
        if (cancelled) return;
        setOpenAiStatus(openai);
        setZaiStatus(zai);
      } catch {
        if (cancelled) return;
        setOpenAiStatus('error');
        setZaiStatus('error');
      }
    };
    void refresh();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    setConfig((current) => {
      const normalizedModel = normalizePreferredModel(current.provider, current.model);
      if (normalizedModel === current.model) {
        return current;
      }
      return {
        ...current,
        model: normalizedModel,
      };
    });
  }, [config.provider]);

  const todayStatsQuery = useQuery({
    queryKey: ['ai-scalp-today-stats', config.strategyCode],
    queryFn: () => guidedTradingApi.getTodayStats(config.strategyCode),
    refetchInterval: 15_000,
  });

  const running = state.running;
  const entryEnabled = state.entryEnabled;
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
  const todayTrades = todayStatsQuery.data?.trades ?? [];
  const historyMarketSummaries = useMemo(() => {
    const grouped = new Map<string, {
      market: string;
      trades: GuidedClosedTradeView[];
      pnlKrw: number;
      wins: number;
      losses: number;
    }>();

    for (const trade of todayTrades) {
      const current = grouped.get(trade.market) ?? {
        market: trade.market,
        trades: [],
        pnlKrw: 0,
        wins: 0,
        losses: 0,
      };
      current.trades.push(trade);
      current.pnlKrw += trade.realizedPnl;
      if (trade.realizedPnl >= 0) {
        current.wins += 1;
      } else {
        current.losses += 1;
      }
      grouped.set(trade.market, current);
    }

    return [...grouped.values()].sort((left, right) => {
      if (Math.abs(right.pnlKrw - left.pnlKrw) > 1e-9) {
        return right.pnlKrw - left.pnlKrw;
      }
      return right.trades.length - left.trades.length;
    });
  }, [todayTrades]);
  const visibleTrades = useMemo(() => {
    if (selectedHistoryMarket === 'ALL') return todayTrades;
    return todayTrades.filter((trade) => trade.market === selectedHistoryMarket);
  }, [selectedHistoryMarket, todayTrades]);
  const journalPageCount = Math.max(1, Math.ceil(state.events.length / JOURNAL_PAGE_SIZE));
  const journalPageEvents = useMemo(() => {
    const start = (journalPage - 1) * JOURNAL_PAGE_SIZE;
    return state.events.slice(start, start + JOURNAL_PAGE_SIZE);
  }, [journalPage, state.events]);
  const journalStartIndex = state.events.length === 0 ? 0 : (journalPage - 1) * JOURNAL_PAGE_SIZE + 1;
  const journalEndIndex = Math.min(journalPage * JOURNAL_PAGE_SIZE, state.events.length);

  const updateConfig = (patch: Partial<AiDayTraderConfig>) => {
    setConfig((current) => ({ ...current, ...patch }));
  };
  const activeProviderStatus = config.provider === 'zai' ? zaiStatus : openAiStatus;
  const providerConnected = activeProviderStatus === 'connected';
  const providerChecking = activeProviderStatus === 'checking';
  const engineActionLabel = running
    ? (entryEnabled ? '신규 진입 중지' : '진입 재개')
    : '시작';
  const engineActionClass = running && entryEnabled ? 'danger' : 'primary';

  const refreshConnectionStatus = async (): Promise<{ openai: LlmConnectionStatus; zai: LlmConnectionStatus }> => {
    try {
      const [openai, zai] = await Promise.all([
        checkConnection('openai'),
        checkConnection('zai'),
      ]);
      setOpenAiStatus(openai);
      setZaiStatus(zai);
      return { openai, zai };
    } catch {
      setOpenAiStatus('error');
      setZaiStatus('error');
      return { openai: 'error', zai: 'error' };
    }
  };

  const handleOpenAiLogin = async () => {
    setProviderBusy(true);
    setProviderMessage(null);
    setOpenAiStatus('checking');
    try {
      await startLogin('openai');
      clearConversation('openai');
      await refreshConnectionStatus();
      setProviderMessage('OpenAI 로그인 완료');
    } catch (error) {
      setOpenAiStatus('error');
      setProviderMessage(error instanceof Error ? error.message : 'OpenAI 로그인 실패');
    } finally {
      setProviderBusy(false);
    }
  };

  const handleOpenAiLogout = async () => {
    setProviderBusy(true);
    setProviderMessage(null);
    try {
      await logout('openai');
      clearConversation('openai');
      setOpenAiStatus('disconnected');
      setProviderMessage('OpenAI 로그아웃 완료');
    } catch (error) {
      setProviderMessage(error instanceof Error ? error.message : 'OpenAI 로그아웃 실패');
    } finally {
      setProviderBusy(false);
    }
  };

  const handleSaveZaiKey = async () => {
    const trimmed = zaiApiKeyInput.trim();
    if (!trimmed) {
      setProviderMessage('z.ai API Key를 입력하세요.');
      return;
    }
    setZaiApiKeyBusy(true);
    setProviderMessage(null);
    try {
      await setZaiApiKey(trimmed);
      clearConversation('zai');
      setZaiApiKeyInput('');
      await refreshConnectionStatus();
      setProviderMessage('z.ai API Key 저장 완료');
    } catch (error) {
      setZaiStatus('error');
      setProviderMessage(error instanceof Error ? error.message : 'z.ai API Key 저장 실패');
    } finally {
      setZaiApiKeyBusy(false);
    }
  };

  const handleClearZaiKey = async () => {
    setZaiApiKeyBusy(true);
    setProviderMessage(null);
    try {
      await clearZaiApiKey();
      clearConversation('zai');
      setZaiStatus('disconnected');
      setProviderMessage('z.ai API Key 삭제 완료');
    } catch (error) {
      setProviderMessage(error instanceof Error ? error.message : 'z.ai API Key 삭제 실패');
    } finally {
      setZaiApiKeyBusy(false);
    }
  };

  const handleStart = async () => {
    if (running && entryEnabled) {
      engineRef.current?.stop();
      return;
    }
    const latestStatus = await refreshConnectionStatus();
    const connected = config.provider === 'zai'
      ? latestStatus.zai === 'connected'
      : latestStatus.openai === 'connected';
    if (!connected) {
      setProviderMessage(
        config.provider === 'zai'
          ? 'z.ai API Key를 먼저 연결해야 시작할 수 있습니다.'
          : 'OpenAI 로그인이 필요합니다.'
      );
      return;
    }
    engineRef.current?.updateConfig(config);
    engineRef.current?.start();
  };

  useEffect(() => {
    if (selectedHistoryMarket === 'ALL') return;
    if (historyMarketSummaries.some((summary) => summary.market === selectedHistoryMarket)) return;
    setSelectedHistoryMarket('ALL');
  }, [historyMarketSummaries, selectedHistoryMarket]);

  useEffect(() => {
    setJournalPage((current) => Math.min(current, journalPageCount));
  }, [journalPageCount]);

  useEffect(() => {
    journalScrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
  }, [journalPage]);

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
          <div className="ai-scalp-provider-row">
            <span className={`ai-scalp-provider-pill ${providerConnected ? 'connected' : activeProviderStatus}`}>
              {config.provider === 'zai' ? 'z.ai' : 'OpenAI'} · {providerStatusLabel(activeProviderStatus)}
            </span>
            <span className="ai-scalp-provider-model">모델 {config.model}</span>
          </div>
          <div className="ai-scalp-sessionbar__meta">
            <span>상태 {statusLabel(state.status)}</span>
            <span>오픈 포지션 {state.positions.length}/{config.maxConcurrentPositions}</span>
            <span>신규 진입 {entryEnabled ? '활성' : '중지'}</span>
            <span>세션 실현 {formatKrw(state.dailyPnl)}</span>
            <span>최근 스캔 {lastScanLabel}</span>
            <span>일손실 한도 {formatKrw(config.dailyLossLimitKrw)}</span>
          </div>
          {providerMessage && (
            <div className="ai-scalp-sessionbar__notice">{providerMessage}</div>
          )}
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
            className={`ai-scalp-action ${engineActionClass}`}
            onClick={() => void handleStart()}
          >
            {engineActionLabel}
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
          <div ref={journalScrollRef} className="ai-scalp-panel__body ai-scalp-scroll ai-scalp-journal">
            {state.events.length === 0 ? (
              <EmptyState title="저널 비어 있음" description="실시간 스캔과 AI 결정 이벤트가 여기에 시간순으로 쌓입니다." />
            ) : (
              <>
                {journalPageCount > 1 && (
                  <div className="ai-scalp-journal__pager">
                    <div className="ai-scalp-journal__pager-meta">
                      <strong>{journalPage} / {journalPageCount}</strong>
                      <span>{journalStartIndex}-{journalEndIndex} / {state.events.length}</span>
                    </div>
                    <div className="ai-scalp-journal__pager-actions">
                      <button
                        type="button"
                        className="ai-scalp-journal__pager-button"
                        onClick={() => setJournalPage(1)}
                        disabled={journalPage === 1}
                      >
                        최신
                      </button>
                      <button
                        type="button"
                        className="ai-scalp-journal__pager-button"
                        onClick={() => setJournalPage((current) => Math.max(1, current - 1))}
                        disabled={journalPage === 1}
                      >
                        이전
                      </button>
                      <button
                        type="button"
                        className="ai-scalp-journal__pager-button"
                        onClick={() => setJournalPage((current) => Math.min(journalPageCount, current + 1))}
                        disabled={journalPage === journalPageCount}
                      >
                        다음
                      </button>
                    </div>
                  </div>
                )}
                {journalPageEvents.map((event) => (
                  <JournalItem key={event.id} event={event} />
                ))}
              </>
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
              <div className="ai-scalp-provider-card">
                <div className="ai-scalp-provider-card__header">
                  <div>
                    <div className="ai-scalp-provider-card__title">LLM 연결</div>
                    <div className="ai-scalp-provider-card__subtitle">
                      {config.provider === 'zai' ? 'API Key 방식' : 'ChatGPT / Codex 계정 로그인'}
                    </div>
                  </div>
                  <button type="button" className="ai-scalp-link-button" onClick={() => void refreshConnectionStatus()}>
                    새로고침
                  </button>
                </div>
                <div className="ai-scalp-provider-card__status">
                  <span className={`ai-scalp-provider-pill ${providerConnected ? 'connected' : activeProviderStatus}`}>
                    {config.provider === 'zai' ? 'z.ai' : 'OpenAI'} · {providerStatusLabel(activeProviderStatus)}
                  </span>
                  <span className="ai-scalp-provider-card__hint">
                    {config.provider === 'zai'
                      ? `슬롯 ${zaiConcurrency.active}/${zaiConcurrency.max} · 대기 ${zaiConcurrency.queued}`
                      : '로그인 후 초단타 엔진을 시작할 수 있습니다.'}
                  </span>
                </div>
                {config.provider === 'openai' ? (
                  <div className="ai-scalp-provider-card__actions">
                    {providerConnected ? (
                      <button type="button" className="ai-scalp-secondary-button" onClick={() => void handleOpenAiLogout()} disabled={providerBusy}>
                        {providerBusy ? '처리 중...' : '로그아웃'}
                      </button>
                    ) : (
                      <button type="button" className="ai-scalp-secondary-button" onClick={() => void handleOpenAiLogin()} disabled={providerBusy || providerChecking}>
                        {providerBusy || providerChecking ? '연결 중...' : '로그인'}
                      </button>
                    )}
                  </div>
                ) : (
                  <div className="ai-scalp-provider-card__key-row">
                    <input
                      type="password"
                      value={zaiApiKeyInput}
                      onChange={(event) => setZaiApiKeyInput(event.target.value)}
                      placeholder="z.ai API Key"
                      disabled={zaiApiKeyBusy}
                    />
                    <button type="button" className="ai-scalp-secondary-button" onClick={() => void handleSaveZaiKey()} disabled={zaiApiKeyBusy || !zaiApiKeyInput.trim()}>
                      {zaiApiKeyBusy ? '저장 중...' : '키 저장'}
                    </button>
                    <button type="button" className="ai-scalp-secondary-button ghost" onClick={() => void handleClearZaiKey()} disabled={zaiApiKeyBusy || zaiStatus !== 'connected'}>
                      {zaiApiKeyBusy ? '처리 중...' : '키 삭제'}
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
                  {(config.provider === 'openai' ? OPENAI_TRADER_MODELS : ZAI_MODELS).map((model) => (
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
                  <option value={10000}>10초</option>
                  <option value={15000}>15초</option>
                  <option value={20000}>20초</option>
                  <option value={30000}>30초</option>
                  <option value={45000}>45초</option>
                  <option value={60000}>1분</option>
                  <option value={90000}>1분 30초</option>
                  <option value={120000}>2분</option>
                  <option value={180000}>3분</option>
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
                  <option value={15000}>15초</option>
                  <option value={20000}>20초</option>
                  <option value={30000}>30초</option>
                  <option value={45000}>45초</option>
                  <option value={60000}>1분</option>
                  <option value={90000}>1분 30초</option>
                  <option value={120000}>2분</option>
                  <option value={180000}>3분</option>
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

      <section className="ai-scalp-panel ai-scalp-history">
        <PanelHeader
          title="오늘 거래내역"
          subtitle="전체 보기와 코인별 보기 모두 지원"
          right={todayStatsQuery.isFetching ? '새로고침 중' : `${todayTrades.length} trades`}
        />
        <div className="ai-scalp-panel__body ai-scalp-history__body">
          <div className="ai-scalp-history__summary">
            <KpiCard label="오늘 거래 수" value={`${todayStatsQuery.data?.totalTrades ?? 0}건`} />
            <KpiCard label="오늘 승률" value={`${(todayStatsQuery.data?.winRate ?? 0).toFixed(0)}%`} />
            <KpiCard
              label="오늘 실현손익"
              value={formatKrw(todayStatsQuery.data?.totalPnlKrw ?? 0)}
              tone={(todayStatsQuery.data?.totalPnlKrw ?? 0) >= 0 ? 'positive' : 'negative'}
            />
            <KpiCard label="열린 포지션" value={`${todayStatsQuery.data?.openPositionCount ?? 0}개`} />
          </div>

          <div className="ai-scalp-history__market-strip">
            <button
              type="button"
              className={`ai-scalp-history-chip ${selectedHistoryMarket === 'ALL' ? 'active' : ''}`}
              onClick={() => setSelectedHistoryMarket('ALL')}
            >
              전체
            </button>
            {historyMarketSummaries.map((summary) => (
              <button
                key={summary.market}
                type="button"
                className={`ai-scalp-history-chip ${selectedHistoryMarket === summary.market ? 'active' : ''}`}
                onClick={() => setSelectedHistoryMarket(summary.market)}
              >
                {summary.market.replace('KRW-', '')}
                <span>{summary.trades.length}</span>
              </button>
            ))}
          </div>

          {todayStatsQuery.isLoading ? (
            <EmptyState title="오늘 거래내역 불러오는 중" description="금일 AI 초단타 청산 내역을 조회하고 있습니다." />
          ) : todayTrades.length === 0 ? (
            <EmptyState title="오늘 거래 없음" description="AI_SCALP_TRADER prefix 기준으로 오늘 청산된 거래가 아직 없습니다." />
          ) : (
            <>
              <div className="ai-scalp-history__market-cards">
                {historyMarketSummaries.map((summary) => (
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
                  {visibleTrades.map((trade) => (
                    <div key={trade.tradeId} className="ai-scalp-history-table__row">
                      <span className="ai-scalp-history-table__market">{trade.market.replace('KRW-', '')}</span>
                      <span>{formatDateTime(trade.createdAt)}</span>
                      <span>{trade.closedAt ? formatDateTime(trade.closedAt) : '-'}</span>
                      <span>{formatHoldingMinutes(trade.createdAt, trade.closedAt)}</span>
                      <span className={trade.realizedPnl >= 0 ? 'positive' : 'negative'}>
                        {formatKrw(trade.realizedPnl)} / {formatPercent(trade.realizedPnlPercent)}
                      </span>
                      <span>{trade.exitReason ?? '-'}</span>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>
      </section>
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

function providerStatusLabel(status: LlmConnectionStatus): string {
  if (status === 'connected') return '연결됨';
  if (status === 'checking') return '확인 중';
  if (status === 'expired') return '만료';
  if (status === 'error') return '오류';
  return '미연결';
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

function formatDateTime(value: string): string {
  return new Date(value).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function formatHoldingMinutes(createdAt: string, closedAt?: string | null): string {
  const start = new Date(createdAt).getTime();
  const end = new Date(closedAt ?? createdAt).getTime();
  const minutes = Math.max(0, (end - start) / 60_000);
  return `${minutes.toFixed(1)}분`;
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
