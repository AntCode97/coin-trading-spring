import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  guidedTradingApi,
  type GuidedClosedTradeView,
  type GuidedDailyStats,
} from '../../api';
import {
  AiDayTraderEngine,
  createInitialAiTraderState,
  type AiDayTraderConfig,
  type AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderEngine';
import {
  checkConnection,
  clearConversation,
  clearZaiApiKey,
  getZaiConcurrencyStatus,
  logout,
  setZaiApiKey,
  startLogin,
  subscribeZaiConcurrency,
  type LlmConnectionStatus,
  type ZaiConcurrencyStatus,
} from '../../lib/llmService';
import {
  JOURNAL_PAGE_SIZE,
  PREFERENCE_KEY,
  loadAiDayTraderPreferences,
  normalizePreferredModel,
} from './AiDayTraderScreenConfig';

export interface AiDayTraderHistoryMarketSummary {
  market: string;
  trades: GuidedClosedTradeView[];
  pnlKrw: number;
  wins: number;
  losses: number;
}

export interface AiDayTraderSessionView {
  running: boolean;
  entryEnabled: boolean;
  tradesPerHour: number;
  recentHourPnl: number;
  averageHoldingMinutes: number;
  buyConversionRate: number;
  lastScanLabel: string;
  engineActionLabel: string;
  engineActionClass: 'primary' | 'danger';
}

export interface AiDayTraderProviderView {
  openAiStatus: LlmConnectionStatus;
  zaiStatus: LlmConnectionStatus;
  activeProviderStatus: LlmConnectionStatus;
  providerConnected: boolean;
  providerChecking: boolean;
  providerBusy: boolean;
  zaiApiKeyBusy: boolean;
  zaiApiKeyInput: string;
  providerMessage: string | null;
  zaiConcurrency: ZaiConcurrencyStatus;
}

export interface AiDayTraderJournalView {
  page: number;
  pageCount: number;
  pageEvents: AiTraderState['events'];
  startIndex: number;
  endIndex: number;
  canGoPrevious: boolean;
  canGoNext: boolean;
}

export interface AiDayTraderHistoryView {
  todayStats?: GuidedDailyStats;
  todayTrades: GuidedClosedTradeView[];
  historyMarketSummaries: AiDayTraderHistoryMarketSummary[];
  visibleTrades: GuidedClosedTradeView[];
  selectedHistoryMarket: string;
  isLoading: boolean;
  isFetching: boolean;
}

export interface AiDayTraderScreenViewModel {
  config: AiDayTraderConfig;
  state: AiTraderState;
  session: AiDayTraderSessionView;
  provider: AiDayTraderProviderView;
  journal: AiDayTraderJournalView;
  history: AiDayTraderHistoryView;
  isMonitorOpen: boolean;
  journalScrollRef: React.MutableRefObject<HTMLDivElement | null>;
  updateConfig: (patch: Partial<AiDayTraderConfig>) => void;
  setSelectedHistoryMarket: (market: string) => void;
  setMonitorFocus: (actorId: string | null) => void;
  setZaiApiKeyInput: (value: string) => void;
  refreshConnectionStatus: () => Promise<{ openai: LlmConnectionStatus; zai: LlmConnectionStatus }>;
  handleOpenAiLogin: () => Promise<void>;
  handleOpenAiLogout: () => Promise<void>;
  handleSaveZaiKey: () => Promise<void>;
  handleClearZaiKey: () => Promise<void>;
  handleEngineAction: () => Promise<void>;
  openMonitor: () => void;
  closeMonitor: () => void;
  toggleMonitor: () => void;
  goToLatestJournalPage: () => void;
  goToPreviousJournalPage: () => void;
  goToNextJournalPage: () => void;
}

export function useAiDayTraderScreen(): AiDayTraderScreenViewModel {
  const engineRef = useRef<AiDayTraderEngine | null>(null);
  const journalScrollRef = useRef<HTMLDivElement | null>(null);
  const [config, setConfig] = useState<AiDayTraderConfig>(() => loadAiDayTraderPreferences());
  const [state, setState] = useState<AiTraderState>(() => createInitialAiTraderState());
  const [selectedHistoryMarket, setSelectedHistoryMarket] = useState('ALL');
  const [journalPage, setJournalPage] = useState(1);
  const [isMonitorOpen, setMonitorOpen] = useState(false);
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

  const todayTrades = todayStatsQuery.data?.trades ?? [];
  const historyMarketSummaries = useMemo<AiDayTraderHistoryMarketSummary[]>(() => {
    const grouped = new Map<string, AiDayTraderHistoryMarketSummary>();

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

  const running = state.running;
  const entryEnabled = state.entryEnabled;
  const closedLastHour = state.closedTrades.filter((trade) => Date.now() - new Date(trade.closedAt).getTime() <= 3_600_000);
  const session: AiDayTraderSessionView = {
    running,
    entryEnabled,
    tradesPerHour: closedLastHour.length,
    recentHourPnl: closedLastHour.reduce((sum, trade) => sum + trade.pnlKrw, 0),
    averageHoldingMinutes: state.closedTrades.length
      ? state.closedTrades.reduce((sum, trade) => sum + trade.holdingMinutes, 0) / state.closedTrades.length
      : 0,
    buyConversionRate: state.finalistsReviewed > 0
      ? (state.buyExecutions / state.finalistsReviewed) * 100
      : 0,
    lastScanLabel: state.lastScanAt
      ? new Date(state.lastScanAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      : '미실행',
    engineActionLabel: running
      ? (entryEnabled ? '신규 진입 중지' : '진입 재개')
      : '시작',
    engineActionClass: running && entryEnabled ? 'danger' : 'primary',
  };

  const activeProviderStatus = config.provider === 'zai' ? zaiStatus : openAiStatus;
  const provider: AiDayTraderProviderView = {
    openAiStatus,
    zaiStatus,
    activeProviderStatus,
    providerConnected: activeProviderStatus === 'connected',
    providerChecking: activeProviderStatus === 'checking',
    providerBusy,
    zaiApiKeyBusy,
    zaiApiKeyInput,
    providerMessage,
    zaiConcurrency,
  };

  const history: AiDayTraderHistoryView = {
    todayStats: todayStatsQuery.data,
    todayTrades,
    historyMarketSummaries,
    visibleTrades,
    selectedHistoryMarket,
    isLoading: todayStatsQuery.isLoading,
    isFetching: todayStatsQuery.isFetching,
  };

  const journal: AiDayTraderJournalView = {
    page: journalPage,
    pageCount: journalPageCount,
    pageEvents: journalPageEvents,
    startIndex: journalStartIndex,
    endIndex: journalEndIndex,
    canGoPrevious: journalPage > 1,
    canGoNext: journalPage < journalPageCount,
  };

  const updateConfig = (patch: Partial<AiDayTraderConfig>) => {
    setConfig((current) => ({ ...current, ...patch }));
  };

  const setMonitorFocus = (actorId: string | null) => {
    engineRef.current?.setMonitorFocus(actorId);
  };

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

  const handleEngineAction = async () => {
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

  const goToLatestJournalPage = () => setJournalPage(1);
  const goToPreviousJournalPage = () => setJournalPage((current) => Math.max(1, current - 1));
  const goToNextJournalPage = () => setJournalPage((current) => Math.min(journalPageCount, current + 1));
  const openMonitor = () => setMonitorOpen(true);
  const closeMonitor = () => {
    setMonitorOpen(false);
    setMonitorFocus(null);
  };
  const toggleMonitor = () => {
    setMonitorOpen((current) => {
      const next = !current;
      if (!next) {
        setMonitorFocus(null);
      }
      return next;
    });
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

  return {
    config,
    state,
    session,
    provider,
    journal,
    history,
    isMonitorOpen,
    journalScrollRef,
    updateConfig,
    setSelectedHistoryMarket,
    setMonitorFocus,
    setZaiApiKeyInput,
    refreshConnectionStatus,
    handleOpenAiLogin,
    handleOpenAiLogout,
    handleSaveZaiKey,
    handleClearZaiKey,
    handleEngineAction,
    openMonitor,
    closeMonitor,
    toggleMonitor,
    goToLatestJournalPage,
    goToPreviousJournalPage,
    goToNextJournalPage,
  };
}
