import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  guidedTradingApi,
  type GuidedClosedTradeView,
  type GuidedDailyStats,
  type GuidedMarketItem,
  type GuidedRecommendation,
} from '../../api';
import {
  AiDayTraderEngine,
  createInitialAiTraderState,
  type AiDayTraderConfig,
  type AiTradeBiasMode,
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
import {
  useAiDayTraderDesktopReview,
  type AiDayTraderDesktopReviewView,
} from './useAiDayTraderDesktopReview';
export type { AiDayTraderDesktopReviewView } from './useAiDayTraderDesktopReview';

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

export interface AiDayTraderWatchlistView {
  catalog: GuidedMarketItem[];
  selectedMarkets: string[];
  seedMarket: string;
  seedTradeBias: AiTradeBiasMode;
  seedOrderType: 'MARKET' | 'LIMIT';
  seedLimitPrice: string;
  seedStopLossPrice: string;
  seedTakeProfitPrice: string;
  seedMaxDcaCount: number;
  seedDcaStepPercent: number;
  seedLeverage: number;
  selectedSeedMarket?: GuidedMarketItem;
  seedRecommendation?: GuidedRecommendation;
  isCatalogLoading: boolean;
  isCatalogFetching: boolean;
  isSeedBusy: boolean;
  seedMessage: string | null;
}

export interface AiDayTraderScreenViewModel {
  config: AiDayTraderConfig;
  state: AiTraderState;
  session: AiDayTraderSessionView;
  provider: AiDayTraderProviderView;
  review: AiDayTraderDesktopReviewView;
  journal: AiDayTraderJournalView;
  history: AiDayTraderHistoryView;
  watchlist: AiDayTraderWatchlistView;
  isMonitorOpen: boolean;
  journalScrollRef: React.MutableRefObject<HTMLDivElement | null>;
  updateConfig: (patch: Partial<AiDayTraderConfig>) => void;
  setSelectedHistoryMarket: (market: string) => void;
  setMonitorFocus: (actorId: string | null) => void;
  toggleSelectedMarket: (market: string) => void;
  clearSelectedMarkets: () => void;
  setSeedMarket: (market: string) => void;
  setSeedTradeBias: (tradeBias: AiTradeBiasMode) => void;
  setSeedOrderType: (orderType: 'MARKET' | 'LIMIT') => void;
  setSeedLimitPrice: (value: string) => void;
  setSeedStopLossPrice: (value: string) => void;
  setSeedTakeProfitPrice: (value: string) => void;
  setSeedMaxDcaCount: (value: number) => void;
  setSeedDcaStepPercent: (value: number) => void;
  setSeedLeverage: (value: number) => void;
  startSeedPosition: () => Promise<void>;
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
  const [seedMarket, setSeedMarketState] = useState('');
  const [seedTradeBias, setSeedTradeBiasState] = useState<AiTradeBiasMode>('LONG_ONLY');
  const [seedOrderType, setSeedOrderTypeState] = useState<'MARKET' | 'LIMIT'>('LIMIT');
  const [seedLimitPrice, setSeedLimitPrice] = useState('');
  const [seedStopLossPrice, setSeedStopLossPrice] = useState('');
  const [seedTakeProfitPrice, setSeedTakeProfitPrice] = useState('');
  const [seedMaxDcaCount, setSeedMaxDcaCountState] = useState(1);
  const [seedDcaStepPercent, setSeedDcaStepPercentState] = useState(1.8);
  const [seedLeverage, setSeedLeverageState] = useState(2);
  const [seedBusy, setSeedBusy] = useState(false);
  const [seedMessage, setSeedMessage] = useState<string | null>(null);
  const seedAutofillMarketRef = useRef<string | null>(null);

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

  useEffect(() => {
    setSeedTradeBiasState(config.tradeBias);
  }, [config.tradeBias]);

  const todayStatsQuery = useQuery({
    queryKey: ['ai-scalp-today-stats', config.strategyCode],
    queryFn: () => guidedTradingApi.getTodayStats(config.strategyCode),
    refetchInterval: 15_000,
  });

  const marketsQuery = useQuery({
    queryKey: ['ai-scalp-market-catalog'],
    queryFn: () => guidedTradingApi.getMarkets('TURNOVER', 'DESC', 'minute1', 'SCALP'),
    refetchInterval: 60_000,
  });

  const seedRecommendationQuery = useQuery({
    queryKey: ['ai-scalp-seed-recommendation', seedMarket],
    queryFn: () => guidedTradingApi.getRecommendation(seedMarket, 'minute1', 120, 'SCALP'),
    enabled: seedMarket.trim().length > 0,
    staleTime: 30_000,
  });

  const todayTrades = todayStatsQuery.data?.trades ?? [];
  const marketCatalog = marketsQuery.data ?? [];
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

  useEffect(() => {
    const availableMarkets = new Set(marketCatalog.map((item) => item.market));
    const selectedPreferred = config.selectedMarkets.find((market) => availableMarkets.has(market));
    if (seedMarket && availableMarkets.has(seedMarket)) {
      return;
    }
    const fallbackMarket = selectedPreferred ?? marketCatalog[0]?.market ?? '';
    if (!fallbackMarket) return;
    setSeedMarketState(fallbackMarket);
    seedAutofillMarketRef.current = null;
  }, [config.selectedMarkets, marketCatalog, seedMarket]);

  useEffect(() => {
    if (!seedMarket) return;
    const recommendation = seedRecommendationQuery.data;
    if (!recommendation) return;
    const autofillKey = `${seedMarket}:${seedTradeBias}`;
    if (seedAutofillMarketRef.current === autofillKey) return;
    seedAutofillMarketRef.current = autofillKey;
    const suggestedOrderType = recommendation.suggestedOrderType?.trim().toUpperCase() === 'LIMIT' ? 'LIMIT' : 'MARKET';
    const entryPrice = Math.round(recommendation.recommendedEntryPrice);
    const stopLossPrice = Math.round(recommendation.stopLossPrice);
    const takeProfitPrice = Math.round(recommendation.takeProfitPrice);
    const baseEntryPrice = recommendation.recommendedEntryPrice > 0
      ? recommendation.recommendedEntryPrice
      : recommendation.currentPrice;
    const downsideDistance = baseEntryPrice > 0
      ? Math.max(0.003, (baseEntryPrice - recommendation.stopLossPrice) / baseEntryPrice)
      : 0.006;
    const upsideDistance = baseEntryPrice > 0
      ? Math.max(0.005, (recommendation.takeProfitPrice - baseEntryPrice) / baseEntryPrice)
      : 0.01;
    setSeedOrderTypeState(suggestedOrderType);
    setSeedLimitPrice(String(entryPrice));
    if (seedTradeBias === 'SHORT_ONLY') {
      setSeedStopLossPrice(String(Math.round(baseEntryPrice * (1 + downsideDistance))));
      setSeedTakeProfitPrice(String(Math.round(baseEntryPrice * (1 - upsideDistance))));
    } else {
      setSeedStopLossPrice(String(stopLossPrice));
      setSeedTakeProfitPrice(String(takeProfitPrice));
    }
    setSeedMessage(null);
  }, [seedMarket, seedRecommendationQuery.data, seedTradeBias]);

  useEffect(() => {
    if (seedTradeBias !== 'SHORT_ONLY') {
      setSeedLeverageState(2);
    }
  }, [seedTradeBias]);

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

  const selectedSeedMarket = marketCatalog.find((item) => item.market === seedMarket);
  const watchlist: AiDayTraderWatchlistView = {
    catalog: marketCatalog,
    selectedMarkets: config.selectedMarkets,
    seedMarket,
    seedTradeBias,
    seedOrderType,
    seedLimitPrice,
    seedStopLossPrice,
    seedTakeProfitPrice,
    seedMaxDcaCount,
    seedDcaStepPercent,
    seedLeverage,
    selectedSeedMarket,
    seedRecommendation: seedRecommendationQuery.data,
    isCatalogLoading: marketsQuery.isLoading,
    isCatalogFetching: marketsQuery.isFetching,
    isSeedBusy: seedBusy,
    seedMessage,
  };

  const updateConfig = (patch: Partial<AiDayTraderConfig>) => {
    setConfig((current) => ({ ...current, ...patch }));
  };

  const toggleSelectedMarket = (market: string) => {
    const normalizedMarket = market.trim().toUpperCase();
    if (!normalizedMarket.startsWith('KRW-')) return;
    setConfig((current) => {
      const exists = current.selectedMarkets.includes(normalizedMarket);
      const selectedMarkets = exists
        ? current.selectedMarkets.filter((item) => item !== normalizedMarket)
        : [...current.selectedMarkets, normalizedMarket].slice(0, 24);
      return {
        ...current,
        selectedMarkets,
      };
    });
  };

  const clearSelectedMarkets = () => {
    setConfig((current) => ({
      ...current,
      selectedMarkets: [],
    }));
  };

  const setSeedMarket = (market: string) => {
    setSeedMarketState(market);
    seedAutofillMarketRef.current = null;
  };

  const setSeedTradeBias = (tradeBias: AiTradeBiasMode) => {
    setSeedTradeBiasState(tradeBias);
    seedAutofillMarketRef.current = null;
  };

  const setSeedOrderType = (orderType: 'MARKET' | 'LIMIT') => {
    setSeedOrderTypeState(orderType);
  };

  const setSeedMaxDcaCount = (value: number) => {
    setSeedMaxDcaCountState(Math.min(3, Math.max(0, Math.round(value))));
  };

  const setSeedDcaStepPercent = (value: number) => {
    setSeedDcaStepPercentState(Math.min(15, Math.max(0.5, Number.isFinite(value) ? value : 2)));
  };

  const setSeedLeverage = (value: number) => {
    setSeedLeverageState(Math.min(5, Math.max(1, Math.round(value))));
  };

  const review = useAiDayTraderDesktopReview({
    config,
    provider,
    updateConfig,
  });

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

  const startSeedPosition = async () => {
    if (!seedMarket) {
      setSeedMessage('시작할 코인을 먼저 고르세요.');
      return;
    }

    const parsedLimitPrice = Number(seedLimitPrice);
    const parsedStopLossPrice = Number(seedStopLossPrice);
    const parsedTakeProfitPrice = Number(seedTakeProfitPrice);

    if (seedOrderType === 'LIMIT' && (!Number.isFinite(parsedLimitPrice) || parsedLimitPrice <= 0)) {
      setSeedMessage('지정가 시작에는 유효한 진입 가격이 필요합니다.');
      return;
    }

    setSeedBusy(true);
    setSeedMessage(null);
    try {
      const nextSelectedMarkets = config.selectedMarkets.includes(seedMarket)
        ? config.selectedMarkets
        : [...config.selectedMarkets, seedMarket].filter((market, index, values) => values.indexOf(market) === index).slice(0, 24);
      const nextConfig = {
        ...config,
        selectedMarkets: nextSelectedMarkets,
      };

      await guidedTradingApi.start({
        market: seedMarket,
        amountKrw: nextConfig.amountKrw,
        orderType: seedOrderType,
        limitPrice: seedOrderType === 'LIMIT' ? parsedLimitPrice : undefined,
        stopLossPrice: Number.isFinite(parsedStopLossPrice) && parsedStopLossPrice > 0 ? parsedStopLossPrice : undefined,
        takeProfitPrice: Number.isFinite(parsedTakeProfitPrice) && parsedTakeProfitPrice > 0 ? parsedTakeProfitPrice : undefined,
        maxDcaCount: seedMaxDcaCount,
        dcaStepPercent: seedMaxDcaCount > 0 ? seedDcaStepPercent : undefined,
        interval: 'minute1',
        mode: 'SCALP',
        entrySource: 'AI_SCALP_SEED',
        strategyCode: nextConfig.strategyCode,
        tradeBias: seedTradeBias,
        leverage: seedTradeBias === 'SHORT_ONLY' ? seedLeverage : undefined,
      });

      if (nextSelectedMarkets !== config.selectedMarkets) {
        setConfig(nextConfig);
      }

      await todayStatsQuery.refetch();

      if (!running && provider.providerConnected) {
        engineRef.current?.updateConfig(nextConfig);
        engineRef.current?.start();
        setSeedMessage(`${seedMarket.replace('KRW-', '')} ${seedTradeBias === 'SHORT_ONLY' ? '숏' : '포지션'} 시작 완료 · AI 모니터링도 함께 시작했습니다.`);
      } else if (!running) {
        setSeedMessage(`${seedMarket.replace('KRW-', '')} ${seedTradeBias === 'SHORT_ONLY' ? '숏' : '포지션'} 시작 완료 · 서버 보호 규칙은 적용됐고, AI 모니터링은 로그인 후 시작할 수 있습니다.`);
      } else {
        setSeedMessage(`${seedMarket.replace('KRW-', '')} ${seedTradeBias === 'SHORT_ONLY' ? '숏' : '포지션'} 시작 완료 · 현재 엔진이 이어서 관리합니다.`);
      }
    } catch (error) {
      setSeedMessage(error instanceof Error ? error.message : '시드 포지션 시작 실패');
    } finally {
      setSeedBusy(false);
    }
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
    review,
    journal,
    history,
    watchlist,
    isMonitorOpen,
    journalScrollRef,
    updateConfig,
    setSelectedHistoryMarket,
    setMonitorFocus,
    toggleSelectedMarket,
    clearSelectedMarkets,
    setSeedMarket,
    setSeedTradeBias,
    setSeedOrderType,
    setSeedLimitPrice,
    setSeedStopLossPrice,
    setSeedTakeProfitPrice,
    setSeedMaxDcaCount,
    setSeedDcaStepPercent,
    setSeedLeverage,
    startSeedPosition,
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
