import {
  guidedTradingApi,
  type AiScalpScanMarket,
  type GuidedAgentContextResponse,
  type GuidedCrowdFeaturePack,
  type GuidedTradePosition,
} from '../../api';
import {
  DEFAULT_OPENAI_MODEL,
  requestOneShotTextWithMeta,
  type DelegationMode,
  type LlmProviderId,
  type ZaiEndpointMode,
} from '../llmService';

export interface AiDayTraderConfig {
  enabled: boolean;
  provider: LlmProviderId;
  model: string;
  zaiEndpointMode?: ZaiEndpointMode;
  delegationMode?: DelegationMode;
  zaiDelegateModel?: string;
  scanIntervalMs: number;
  positionCheckMs: number;
  maxConcurrentPositions: number;
  amountKrw: number;
  dailyLossLimitKrw: number;
  universeLimit: number;
  maxHoldingMinutes: number;
  strategyCode: string;
}

export type AiTraderStatus = 'IDLE' | 'SCANNING' | 'RANKING' | 'ANALYZING' | 'EXECUTING' | 'PAUSED' | 'ERROR';
export type AiTraderEventType =
  | 'SCAN'
  | 'RANK'
  | 'BUY_DECISION'
  | 'BUY_EXECUTION'
  | 'MANAGE_DECISION'
  | 'SELL_EXECUTION'
  | 'ERROR'
  | 'STATUS';
export type AiUrgency = 'LOW' | 'MEDIUM' | 'HIGH';
export type AiExitCategory = 'TAKE_PROFIT' | 'STOP_LOSS' | 'LLM_EXIT' | 'FORCED_EXIT';

export interface AiTraderEvent {
  id: string;
  type: AiTraderEventType;
  message: string;
  detail?: string;
  market?: string;
  confidence?: number;
  urgency?: AiUrgency;
  timestamp: number;
}

export interface AiRankedOpportunity {
  market: string;
  koreanName: string;
  score: number;
  reason: string;
  tradePrice: number;
  changeRate: number;
  turnover: number;
  crowd?: GuidedCrowdFeaturePack | null;
}

export interface AiClosedTrade {
  market: string;
  pnlKrw: number;
  pnlPercent: number;
  holdingMinutes: number;
  exitCategory: AiExitCategory;
  closedAt: string;
}

export interface AiTraderState {
  running: boolean;
  entryEnabled: boolean;
  status: AiTraderStatus;
  events: AiTraderEvent[];
  positions: GuidedTradePosition[];
  queue: AiRankedOpportunity[];
  closedTrades: AiClosedTrade[];
  dailyPnl: number;
  wins: number;
  losses: number;
  scanCycles: number;
  finalistsReviewed: number;
  buyExecutions: number;
  lastScanAt: number | null;
  blockedReason: string | null;
}

interface AiEntryDecision {
  action: 'BUY' | 'WAIT';
  confidence: number;
  reasoning: string;
  stopLoss?: number;
  takeProfit?: number;
  urgency: AiUrgency;
}

interface AiManageDecision {
  action: 'HOLD' | 'SELL';
  confidence: number;
  reasoning: string;
  urgency: AiUrgency;
}

type StateListener = (state: AiTraderState) => void;

const SHORTLIST_LIMIT = 12;
const FINALIST_LIMIT = 4;
const EVENT_HISTORY_LIMIT = 240;
const CLOSED_TRADE_LIMIT = 200;
const SHORT_COOLDOWN_MS = 60_000;
const LONG_COOLDOWN_MS = 120_000;
const CONTEXT_FAILURE_EXIT_THRESHOLD = 3;

const SHORTLIST_SYSTEM_PROMPT = [
  '너는 빗썸 KRW 현물 시장만 보는 전업 초단타 트레이더다.',
  '목표는 5~30분 안에 끝낼 수 있는 유동성 높은 기회를 많이 잡는 것이다.',
  '과도하게 WAIT만 반복하지 말고, 유동성/스프레드/모멘텀/기대값이 괜찮으면 여러 종목을 shortlist에 올려라.',
  '평가 기준: 체결대금, 즉시성 있는 모멘텀, crowd pressure, 기대값, 스프레드, 진입 괴리.',
  'JSON만 반환한다.',
  '형식: {"shortlist":[{"market":"KRW-BTC","score":82,"reason":"짧은 근거"}]}',
  '최대 12개까지만 반환한다.',
].join('\n');

const ENTRY_SYSTEM_PROMPT = [
  '너는 공격적이지만 규율 있는 KRW 현물 초단타 트레이더다.',
  '보유시간 목표는 5~30분이다.',
  '조금만 괜찮아도 무조건 WAIT 하지 말고, 유동성과 payoff가 충분하면 BUY를 선택하라.',
  'JSON만 반환한다.',
  '형식: {"action":"BUY|WAIT","confidence":0.0,"reasoning":"짧은 근거","stopLoss":0,"takeProfit":0,"urgency":"LOW|MEDIUM|HIGH"}',
  'stopLoss는 현재가보다 낮아야 하고 takeProfit은 현재가보다 높아야 한다.',
].join('\n');

const MANAGE_SYSTEM_PROMPT = [
  '너는 이미 보유한 KRW 현물 초단타 포지션을 관리하는 트레이더다.',
  '보유시간 목표는 5~30분이며, 모멘텀이 약해지거나 downside가 커지면 SELL을 선택한다.',
  'JSON만 반환한다.',
  '형식: {"action":"HOLD|SELL","confidence":0.0,"reasoning":"짧은 근거","urgency":"LOW|MEDIUM|HIGH"}',
].join('\n');

let nextId = 0;

function makeId(prefix: string): string {
  nextId += 1;
  return `${prefix}_${Date.now()}_${nextId}`;
}

function clamp(value: number, minValue: number, maxValue: number): number {
  return Math.min(maxValue, Math.max(minValue, value));
}

function toFiniteNumber(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function parseJsonObject(text: string): Record<string, unknown> | null {
  try {
    const fenced = text.match(/```json\s*([\s\S]*?)```/i);
    const raw = fenced?.[1] ?? text.match(/\{[\s\S]*\}/)?.[0];
    if (!raw) return null;
    return JSON.parse(raw.trim()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function normalizeUrgency(value: unknown): AiUrgency {
  const normalized = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return normalized === 'LOW' || normalized === 'HIGH' ? normalized : 'MEDIUM';
}

function normalizeMarket(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim().toUpperCase();
  return normalized.startsWith('KRW-') ? normalized : null;
}

function normalizeStrategyPrefix(value: string): string {
  const normalized = value.trim().toUpperCase();
  return normalized || 'AI_SCALP_TRADER';
}

function cloneState(state: AiTraderState): AiTraderState {
  return {
    ...state,
    events: [...state.events],
    positions: [...state.positions],
    queue: [...state.queue],
    closedTrades: [...state.closedTrades],
  };
}

export function createInitialAiTraderState(): AiTraderState {
  return {
    running: false,
    entryEnabled: false,
    status: 'IDLE',
    events: [],
    positions: [],
    queue: [],
    closedTrades: [],
    dailyPnl: 0,
    wins: 0,
    losses: 0,
    scanCycles: 0,
    finalistsReviewed: 0,
    buyExecutions: 0,
    lastScanAt: null,
    blockedReason: null,
  };
}

export const DEFAULT_AI_DAY_TRADER_CONFIG: AiDayTraderConfig = {
  enabled: false,
  provider: 'openai',
  model: DEFAULT_OPENAI_MODEL,
  zaiEndpointMode: 'coding',
  delegationMode: 'AUTO_AND_MANUAL',
  zaiDelegateModel: 'glm-5',
  scanIntervalMs: 10_000,
  positionCheckMs: 5_000,
  maxConcurrentPositions: 5,
  amountKrw: 10_000,
  dailyLossLimitKrw: -20_000,
  universeLimit: 36,
  maxHoldingMinutes: 30,
  strategyCode: 'AI_SCALP_TRADER',
};

export class AiDayTraderEngine {
  private config: AiDayTraderConfig;
  private state: AiTraderState;
  private listeners = new Set<StateListener>();
  private scanTimer: number | null = null;
  private manageTimer: number | null = null;
  private scanInFlight = false;
  private manageInFlight = false;
  private cooldownUntilByMarket = new Map<string, number>();
  private contextFailureCountByMarket = new Map<string, number>();

  constructor(config: AiDayTraderConfig) {
    this.config = { ...config };
    this.state = createInitialAiTraderState();
  }

  subscribe(listener: StateListener): () => void {
    this.listeners.add(listener);
    listener(cloneState(this.state));
    return () => this.listeners.delete(listener);
  }

  getState(): AiTraderState {
    return cloneState(this.state);
  }

  updateConfig(patch: Partial<AiDayTraderConfig>) {
    this.config = { ...this.config, ...patch };
  }

  isRunning(): boolean {
    return this.state.running;
  }

  start() {
    if (this.state.running) {
      if (!this.state.entryEnabled) {
        this.state.entryEnabled = true;
        this.setBlockedReason(null);
        this.setStatus('IDLE');
        this.emit();
        this.pushEvent('STATUS', '신규 진입 재개');
        if (this.scanTimer === null) {
          this.scanTimer = window.setInterval(() => {
            void this.runScanCycle();
          }, this.config.scanIntervalMs);
        }
        if (this.manageTimer === null) {
          this.manageTimer = window.setInterval(() => {
            void this.runPositionManagement();
          }, this.config.positionCheckMs);
        }
        void this.runScanCycle();
      }
      return;
    }
    this.state.running = true;
    this.state.entryEnabled = true;
    this.state.status = 'IDLE';
    this.emit();
    this.pushEvent('STATUS', 'LLM 초단타 터미널 시작');
    void this.syncOpenPositions();
    void this.runScanCycle();
    void this.runPositionManagement();
    this.scanTimer = window.setInterval(() => {
      void this.runScanCycle();
    }, this.config.scanIntervalMs);
    this.manageTimer = window.setInterval(() => {
      void this.runPositionManagement();
    }, this.config.positionCheckMs);
  }

  stop() {
    if (!this.state.running) return;
    if (this.scanTimer !== null) {
      window.clearInterval(this.scanTimer);
      this.scanTimer = null;
    }
    this.state.entryEnabled = false;
    this.state.queue = [];
    if (this.state.positions.length === 0) {
      if (this.manageTimer !== null) {
        window.clearInterval(this.manageTimer);
        this.manageTimer = null;
      }
      this.state.running = false;
      this.state.status = 'IDLE';
      this.setBlockedReason(null);
      this.emit();
      this.pushEvent('STATUS', 'LLM 초단타 터미널 중지');
      return;
    }
    this.setBlockedReason('신규 진입 중지 · 보유 포지션만 자동 관리');
    this.setStatus('PAUSED');
    this.emit();
    this.pushEvent('STATUS', '신규 진입 중지 · 보유 포지션 관리 계속');
  }

  private emit() {
    const snapshot = cloneState(this.state);
    for (const listener of this.listeners) {
      listener(snapshot);
    }
  }

  private pushEvent(
    type: AiTraderEventType,
    message: string,
    detail?: string,
    market?: string,
    confidence?: number,
    urgency?: AiUrgency
  ) {
    const event: AiTraderEvent = {
      id: makeId('evt'),
      type,
      message,
      detail,
      market,
      confidence,
      urgency,
      timestamp: Date.now(),
    };
    this.state.events = [event, ...this.state.events].slice(0, EVENT_HISTORY_LIMIT);
    this.emit();
  }

  private setStatus(status: AiTraderStatus) {
    if (this.state.status === status) return;
    this.state.status = status;
    this.emit();
  }

  private setBlockedReason(reason: string | null) {
    if (this.state.blockedReason === reason) return;
    const previous = this.state.blockedReason;
    this.state.blockedReason = reason;
    this.emit();
    if (reason) {
      this.pushEvent('STATUS', reason);
      return;
    }
    if (previous) {
      this.pushEvent('STATUS', '진입 차단 해제');
    }
  }

  private setIdleIfReady() {
    if (!this.state.running) return;
    if (this.scanInFlight || this.manageInFlight) return;
    if (!this.state.entryEnabled) {
      this.setStatus('PAUSED');
      return;
    }
    if (this.state.blockedReason) {
      this.setStatus('PAUSED');
      return;
    }
    this.setStatus('IDLE');
  }

  private async syncOpenPositions(): Promise<GuidedTradePosition[]> {
    const positions = await guidedTradingApi.getOpenPositions();
    const filtered = this.filterAiPositions(positions).sort(
      (left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime()
    );
    this.state.positions = filtered;
    this.emit();
    return filtered;
  }

  private filterAiPositions(positions: GuidedTradePosition[]): GuidedTradePosition[] {
    const strategyPrefix = normalizeStrategyPrefix(this.config.strategyCode);
    return positions.filter((position) => {
      const strategyCode = position.strategyCode?.trim().toUpperCase();
      return Boolean(strategyCode?.startsWith(strategyPrefix));
    });
  }

  private pruneTransientState() {
    const now = Date.now();
    for (const [market, until] of this.cooldownUntilByMarket.entries()) {
      if (until <= now) {
        this.cooldownUntilByMarket.delete(market);
      }
    }
    if (this.state.events.length > EVENT_HISTORY_LIMIT) {
      this.state.events = this.state.events.slice(0, EVENT_HISTORY_LIMIT);
    }
    if (this.state.closedTrades.length > CLOSED_TRADE_LIMIT) {
      this.state.closedTrades = this.state.closedTrades.slice(0, CLOSED_TRADE_LIMIT);
    }
  }

  private async runScanCycle() {
    if (!this.state.running || !this.state.entryEnabled || this.scanInFlight) return;
    this.scanInFlight = true;
    this.pruneTransientState();

    try {
      const positions = await this.syncOpenPositions();
      if (!this.state.running || !this.state.entryEnabled) return;

      if (this.state.dailyPnl <= this.config.dailyLossLimitKrw) {
        this.setBlockedReason(
          `일손실 차단 활성화 · 실현 ${this.formatKrw(this.state.dailyPnl)} / 한도 ${this.formatKrw(this.config.dailyLossLimitKrw)}`
        );
        this.setStatus('PAUSED');
        return;
      }

      if (positions.length >= this.config.maxConcurrentPositions) {
        this.setBlockedReason(
          `동시 포지션 한도 도달 · ${positions.length}/${this.config.maxConcurrentPositions}`
        );
        this.setStatus('PAUSED');
        return;
      }

      this.setBlockedReason(null);
      this.state.lastScanAt = Date.now();
      this.setStatus('SCANNING');
      this.pushEvent('SCAN', `유동성 상위 ${this.config.universeLimit}개 시장 스캔`);

      const scan = await guidedTradingApi.getAiScalpScan('minute1', this.config.universeLimit, this.config.strategyCode);
      if (!this.state.running) return;

      this.state.scanCycles += 1;
      this.state.lastScanAt = Date.now();
      this.state.positions = this.filterAiPositions(scan.positions);
      this.emit();

      const openMarkets = new Set(this.state.positions.map((position) => position.market));
      const candidates = scan.markets.filter((market) => !openMarkets.has(market.market) && !this.isCoolingDown(market.market));
      if (candidates.length === 0) {
        this.state.queue = [];
        this.emit();
        this.pushEvent('STATUS', '현재 진입 가능한 후보가 없음');
        return;
      }

      this.setStatus('RANKING');
      const ranked = await this.rankMarkets(candidates);
      this.state.queue = ranked;
      this.emit();
      this.pushEvent(
        'RANK',
        `LLM shortlist ${ranked.length}개`,
        ranked
          .slice(0, 3)
          .map((item) => `${item.market.replace('KRW-', '')} ${item.score.toFixed(0)}`)
          .join(' · ')
      );

      const finalists = ranked.slice(0, FINALIST_LIMIT);
      if (finalists.length === 0) return;

      this.setStatus('ANALYZING');
      const activeMarkets = new Set(this.state.positions.map((position) => position.market));
      let activePositionCount = this.state.positions.length;

      for (const finalist of finalists) {
        if (!this.state.running || !this.state.entryEnabled || activePositionCount >= this.config.maxConcurrentPositions) break;
        if (activeMarkets.has(finalist.market)) continue;
        const scanMarket = candidates.find((candidate) => candidate.market === finalist.market);
        if (!scanMarket) continue;

        this.state.finalistsReviewed += 1;
        this.emit();

        let context: GuidedAgentContextResponse;
        try {
          context = await guidedTradingApi.getAgentContext(
            finalist.market,
            'minute1',
            60,
            20,
            'SCALP',
            'CROWD_PRESSURE'
          );
        } catch (error) {
          this.pushEvent('ERROR', `${finalist.market} 진입 컨텍스트 조회 실패`, this.toErrorText(error), finalist.market);
          continue;
        }

        const decision = await this.requestEntryDecision(scanMarket, finalist, context);
        if (!decision) continue;

        this.pushEvent(
          'BUY_DECISION',
          `${finalist.market} ${decision.action}`,
          decision.reasoning,
          finalist.market,
          decision.confidence,
          decision.urgency
        );

        if (decision.action !== 'BUY' || decision.confidence < 0.55) {
          continue;
        }

        const protectivePrices = this.resolveProtectivePrices(scanMarket, decision);

        try {
          this.setStatus('EXECUTING');
          const position = await guidedTradingApi.start({
            market: finalist.market,
            amountKrw: this.config.amountKrw,
            orderType: 'MARKET',
            interval: 'minute1',
            mode: 'SCALP',
            entrySource: 'AI_SCALP',
            strategyCode: normalizeStrategyPrefix(this.config.strategyCode),
            stopLossPrice: protectivePrices.stopLoss,
            takeProfitPrice: protectivePrices.takeProfit,
            maxDcaCount: 0,
          });

          this.state.buyExecutions += 1;
          activePositionCount += 1;
          activeMarkets.add(position.market);
          this.upsertPosition(position);
          this.contextFailureCountByMarket.set(position.market, 0);
          this.pushEvent(
            'BUY_EXECUTION',
            `${position.market} 매수 실행`,
            `stop ${this.formatPrice(protectivePrices.stopLoss)} · take ${this.formatPrice(protectivePrices.takeProfit)} · ${decision.reasoning}`,
            position.market,
            decision.confidence,
            decision.urgency
          );
        } catch (error) {
          this.pushEvent('ERROR', `${finalist.market} 매수 실패`, this.toErrorText(error), finalist.market);
        }
      }
    } catch (error) {
      this.pushEvent('ERROR', '스캔 루프 실패', this.toErrorText(error));
    } finally {
      this.scanInFlight = false;
      this.setIdleIfReady();
    }
  }

  private async runPositionManagement() {
    if (!this.state.running || this.manageInFlight) return;
    this.manageInFlight = true;
    this.pruneTransientState();

    try {
      const positions = await this.syncOpenPositions();
      if (!this.state.running) return;
      if (positions.length === 0) {
        this.completeStopWhenFlat();
        return;
      }

      for (const position of positions) {
        if (!this.state.running) break;

        const holdingMinutes = this.getHoldingMinutes(position);
        if (holdingMinutes >= this.config.maxHoldingMinutes) {
          await this.executeSell(position, `최대 보유시간 ${this.config.maxHoldingMinutes}분 도달`, 'FORCED_EXIT');
          continue;
        }

        let context: GuidedAgentContextResponse;
        try {
          context = await guidedTradingApi.getAgentContext(
            position.market,
            'minute1',
            30,
            20,
            'SCALP',
            'CROWD_PRESSURE'
          );
          this.contextFailureCountByMarket.set(position.market, 0);
        } catch (error) {
          const failureCount = (this.contextFailureCountByMarket.get(position.market) ?? 0) + 1;
          this.contextFailureCountByMarket.set(position.market, failureCount);
          this.pushEvent(
            'ERROR',
            `${position.market} 관리 컨텍스트 실패 (${failureCount}/${CONTEXT_FAILURE_EXIT_THRESHOLD})`,
            this.toErrorText(error),
            position.market
          );
          if (failureCount >= CONTEXT_FAILURE_EXIT_THRESHOLD) {
            await this.executeSell(position, '컨텍스트 조회 실패 누적', 'FORCED_EXIT');
          }
          continue;
        }

        const decision = await this.requestManageDecision(position, context);
        if (!decision) continue;

        this.pushEvent(
          'MANAGE_DECISION',
          `${position.market} ${decision.action}`,
          `${decision.reasoning} · 보유 ${holdingMinutes.toFixed(1)}분 · 미실현 ${this.formatPercent(position.unrealizedPnlPercent)}`,
          position.market,
          decision.confidence,
          decision.urgency
        );

        if (decision.action === 'SELL' && decision.confidence >= 0.5) {
          await this.executeSell(position, decision.reasoning, undefined, decision);
        }
      }

      await this.syncOpenPositions();
      this.completeStopWhenFlat();
    } catch (error) {
      this.pushEvent('ERROR', '포지션 관리 루프 실패', this.toErrorText(error));
    } finally {
      this.manageInFlight = false;
      this.setIdleIfReady();
    }
  }

  private async rankMarkets(markets: AiScalpScanMarket[]): Promise<AiRankedOpportunity[]> {
    const normalizedMarkets = markets.map((market) => ({
      market: market.market,
      koreanName: market.koreanName,
      tradePrice: market.tradePrice,
      changeRate: market.changeRate,
      turnover: market.turnover,
      liquidityRank: market.liquidityRank,
      expectancyPct: market.recommendation.expectancyPct,
      recommendedEntryWinRate: market.recommendation.recommendedEntryWinRate,
      marketEntryWinRate: market.recommendation.marketEntryWinRate,
      riskRewardRatio: market.recommendation.riskRewardRatio,
      entryGapPct: market.featurePack.entryGapPct,
      confidence: market.featurePack.confidence,
      flowScore: market.crowd?.flowScore ?? market.featurePack.crowdFlowScore ?? 0,
      spreadPercent: market.crowd?.spreadPercent ?? market.featurePack.spreadPercent ?? 0,
      bidImbalance: market.crowd?.bidImbalance ?? market.featurePack.bidImbalance ?? 0,
      notionalSpikeRatio: market.crowd?.notionalSpikeRatio ?? market.featurePack.notionalSpikeRatio ?? 0,
    }));
    const deterministic = this.buildDeterministicShortlist(markets);

    try {
      const response = await requestOneShotTextWithMeta({
        prompt: [
          SHORTLIST_SYSTEM_PROMPT,
          '',
          `후보 시장 요약(JSON): ${JSON.stringify(normalizedMarkets)}`,
        ].join('\n'),
        provider: this.config.provider,
        model: this.config.model,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
      });

      const parsed = parseJsonObject(response.text);
      const rawShortlist = Array.isArray(parsed?.shortlist) ? parsed.shortlist : [];
      const marketMap = new Map(markets.map((market) => [market.market, market]));
      const dedupe = new Set<string>();
      const shortlist: AiRankedOpportunity[] = [];

      for (const item of rawShortlist) {
        if (typeof item !== 'object' || item === null) continue;
        const shortlistItem = item as Record<string, unknown>;
        const market = normalizeMarket(shortlistItem.market);
        if (!market || dedupe.has(market)) continue;
        const scanMarket = marketMap.get(market);
        if (!scanMarket) continue;

        dedupe.add(market);
        shortlist.push({
          market,
          koreanName: scanMarket.koreanName,
          score: clamp(toFiniteNumber(shortlistItem.score, 65), 0, 100),
          reason:
            typeof shortlistItem.reason === 'string' &&
            shortlistItem.reason.trim().length > 0
              ? shortlistItem.reason.trim()
              : this.buildDeterministicReason(scanMarket),
          tradePrice: scanMarket.tradePrice,
          changeRate: scanMarket.changeRate,
          turnover: scanMarket.turnover,
          crowd: scanMarket.crowd,
        });
        if (shortlist.length >= Math.min(SHORTLIST_LIMIT, markets.length)) {
          break;
        }
      }

      if (shortlist.length > 0) {
        return shortlist;
      }
    } catch (error) {
      this.pushEvent('ERROR', 'LLM shortlist 실패, 정량 정렬로 폴백', this.toErrorText(error));
    }

    return deterministic;
  }

  private async requestEntryDecision(
    scanMarket: AiScalpScanMarket,
    finalist: AiRankedOpportunity,
    context: GuidedAgentContextResponse
  ): Promise<AiEntryDecision | null> {
    try {
      const response = await requestOneShotTextWithMeta({
        prompt: [
          ENTRY_SYSTEM_PROMPT,
          '',
          `후보 요약: ${JSON.stringify({
            market: scanMarket.market,
            koreanName: scanMarket.koreanName,
            score: finalist.score,
            reason: finalist.reason,
            tradePrice: scanMarket.tradePrice,
            changeRate: scanMarket.changeRate,
            turnover: scanMarket.turnover,
            recommendation: scanMarket.recommendation,
            featurePack: scanMarket.featurePack,
            crowd: scanMarket.crowd,
          })}`,
        ].join('\n'),
        provider: this.config.provider,
        model: this.config.model,
        context,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
      });

      const parsed = parseJsonObject(response.text);
      if (!parsed) {
        this.pushEvent('ERROR', `${scanMarket.market} 진입 응답 파싱 실패`, response.text.slice(0, 200), scanMarket.market);
        return null;
      }

      const action = typeof parsed.action === 'string' ? parsed.action.trim().toUpperCase() : 'WAIT';
      const normalizedAction: AiEntryDecision['action'] = action === 'BUY' ? 'BUY' : 'WAIT';
      return {
        action: normalizedAction,
        confidence: clamp(toFiniteNumber(parsed.confidence, 0), 0, 1),
        reasoning:
          typeof parsed.reasoning === 'string' && parsed.reasoning.trim().length > 0
            ? parsed.reasoning.trim()
            : finalist.reason,
        stopLoss: toFiniteNumber(parsed.stopLoss, NaN),
        takeProfit: toFiniteNumber(parsed.takeProfit, NaN),
        urgency: normalizeUrgency(parsed.urgency),
      };
    } catch (error) {
      this.pushEvent('ERROR', `${scanMarket.market} 진입 LLM 실패`, this.toErrorText(error), scanMarket.market);
      return null;
    }
  }

  private async requestManageDecision(
    position: GuidedTradePosition,
    context: GuidedAgentContextResponse
  ): Promise<AiManageDecision | null> {
    try {
      const response = await requestOneShotTextWithMeta({
        prompt: [
          MANAGE_SYSTEM_PROMPT,
          '',
          `포지션 요약: ${JSON.stringify({
            market: position.market,
            averageEntryPrice: position.averageEntryPrice,
            currentPrice: position.currentPrice,
            stopLossPrice: position.stopLossPrice,
            takeProfitPrice: position.takeProfitPrice,
            unrealizedPnlPercent: position.unrealizedPnlPercent,
            createdAt: position.createdAt,
            strategyCode: position.strategyCode,
          })}`,
        ].join('\n'),
        provider: this.config.provider,
        model: this.config.model,
        context,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
      });

      const parsed = parseJsonObject(response.text);
      if (!parsed) {
        this.pushEvent('ERROR', `${position.market} 관리 응답 파싱 실패`, response.text.slice(0, 200), position.market);
        return null;
      }

      const action = typeof parsed.action === 'string' ? parsed.action.trim().toUpperCase() : 'HOLD';
      return {
        action: action === 'SELL' ? 'SELL' : 'HOLD',
        confidence: clamp(toFiniteNumber(parsed.confidence, 0), 0, 1),
        reasoning:
          typeof parsed.reasoning === 'string' && parsed.reasoning.trim().length > 0
            ? parsed.reasoning.trim()
            : '시장 흐름 유지',
        urgency: normalizeUrgency(parsed.urgency),
      };
    } catch (error) {
      this.pushEvent('ERROR', `${position.market} 관리 LLM 실패`, this.toErrorText(error), position.market);
      return null;
    }
  }

  private resolveProtectivePrices(scanMarket: AiScalpScanMarket, decision: AiEntryDecision) {
    const currentPrice = scanMarket.tradePrice > 0 ? scanMarket.tradePrice : scanMarket.recommendation.currentPrice;
    let stopLoss = Number.isFinite(decision.stopLoss) ? (decision.stopLoss as number) : scanMarket.recommendation.stopLossPrice;
    let takeProfit = Number.isFinite(decision.takeProfit) ? (decision.takeProfit as number) : scanMarket.recommendation.takeProfitPrice;

    if (!Number.isFinite(stopLoss) || stopLoss <= 0 || stopLoss >= currentPrice) {
      stopLoss = currentPrice * 0.996;
    }
    if (!Number.isFinite(takeProfit) || takeProfit <= currentPrice) {
      takeProfit = currentPrice * 1.006;
    }

    return {
      stopLoss,
      takeProfit,
    };
  }

  private buildDeterministicShortlist(markets: AiScalpScanMarket[]): AiRankedOpportunity[] {
    return [...markets]
      .sort((left, right) => {
        const expectancyGap = right.recommendation.expectancyPct - left.recommendation.expectancyPct;
        if (Math.abs(expectancyGap) > 1e-9) return expectancyGap;
        const flowGap =
          (right.crowd?.flowScore ?? right.featurePack.crowdFlowScore ?? -Infinity) -
          (left.crowd?.flowScore ?? left.featurePack.crowdFlowScore ?? -Infinity);
        if (Math.abs(flowGap) > 1e-9) return flowGap;
        return right.turnover - left.turnover;
      })
      .slice(0, Math.min(SHORTLIST_LIMIT, markets.length))
      .map((market, index) => {
        const crowdFlow = market.crowd?.flowScore ?? market.featurePack.crowdFlowScore ?? 0;
        const score = clamp(
          62 +
            market.recommendation.expectancyPct * 120 +
            crowdFlow * 0.18 +
            market.recommendation.riskRewardRatio * 4 -
            market.featurePack.entryGapPct * 3 -
            index * 1.5,
          0,
          100
        );
        return {
          market: market.market,
          koreanName: market.koreanName,
          score,
          reason: this.buildDeterministicReason(market),
          tradePrice: market.tradePrice,
          changeRate: market.changeRate,
          turnover: market.turnover,
          crowd: market.crowd,
        };
      });
  }

  private buildDeterministicReason(market: AiScalpScanMarket): string {
    const parts = [
      `exp ${market.recommendation.expectancyPct.toFixed(2)}%`,
      `RR ${market.recommendation.riskRewardRatio.toFixed(2)}`,
      `spread ${(market.crowd?.spreadPercent ?? market.featurePack.spreadPercent ?? 0).toFixed(2)}%`,
    ];
    const flowScore = market.crowd?.flowScore ?? market.featurePack.crowdFlowScore;
    if (typeof flowScore === 'number' && Number.isFinite(flowScore)) {
      parts.push(`flow ${flowScore.toFixed(1)}`);
    }
    return parts.join(' · ');
  }

  private async executeSell(
    position: GuidedTradePosition,
    reason: string,
    forcedCategory?: AiExitCategory,
    decision?: Pick<AiManageDecision, 'confidence' | 'urgency'>
  ) {
    try {
      this.setStatus('EXECUTING');
      const result = await guidedTradingApi.stop(position.market);
      const closed =
        result.status === 'CLOSED' ||
        Boolean(result.closedAt) ||
        (typeof result.remainingQuantity === 'number' && result.remainingQuantity <= 0);
      const pnlKrw = toFiniteNumber(result.realizedPnl, 0);
      const pnlPercent = toFiniteNumber(result.realizedPnlPercent, 0);
      const exitCategory = this.resolveExitCategory(reason, pnlKrw, pnlPercent, forcedCategory);

      this.pushEvent(
        'SELL_EXECUTION',
        `${position.market} 매도 실행`,
        `${reason} · 실현 ${this.formatKrw(pnlKrw)} / ${this.formatPercent(pnlPercent)}`,
        position.market,
        decision?.confidence,
        decision?.urgency
      );

      if (closed) {
        this.removePosition(position.market);
        this.registerClosedTrade({
          market: position.market,
          pnlKrw,
          pnlPercent,
          holdingMinutes: this.getHoldingMinutes(result),
          exitCategory,
          closedAt: result.closedAt ?? new Date().toISOString(),
        });
        const cooldownMs = exitCategory === 'TAKE_PROFIT' ? SHORT_COOLDOWN_MS : LONG_COOLDOWN_MS;
        this.cooldownUntilByMarket.set(position.market, Date.now() + cooldownMs);
        this.contextFailureCountByMarket.delete(position.market);
      } else {
        this.upsertPosition(result);
      }
    } catch (error) {
      this.pushEvent('ERROR', `${position.market} 매도 실패`, this.toErrorText(error), position.market);
    }
  }

  private resolveExitCategory(
    reason: string,
    pnlKrw: number,
    pnlPercent: number,
    forcedCategory?: AiExitCategory
  ): AiExitCategory {
    if (forcedCategory) return forcedCategory;
    if (/보유시간|컨텍스트|실패|max hold|failure/i.test(reason)) return 'FORCED_EXIT';
    if (pnlKrw > 0 || pnlPercent > 0) return 'TAKE_PROFIT';
    if (pnlKrw < 0 || pnlPercent < 0) return 'STOP_LOSS';
    return 'LLM_EXIT';
  }

  private registerClosedTrade(trade: AiClosedTrade) {
    this.state.closedTrades = [trade, ...this.state.closedTrades].slice(0, CLOSED_TRADE_LIMIT);
    this.recalculateClosedTradeStats();
    this.emit();
  }

  private recalculateClosedTradeStats() {
    this.state.dailyPnl = this.state.closedTrades.reduce((sum, trade) => sum + trade.pnlKrw, 0);
    this.state.wins = this.state.closedTrades.filter((trade) => trade.pnlKrw > 0 || trade.pnlPercent > 0).length;
    this.state.losses = this.state.closedTrades.filter((trade) => trade.pnlKrw < 0 || trade.pnlPercent < 0).length;
  }

  private upsertPosition(position: GuidedTradePosition) {
    const next = [...this.state.positions];
    const index = next.findIndex((item) => item.tradeId === position.tradeId || item.market === position.market);
    if (index >= 0) {
      next[index] = position;
    } else {
      next.unshift(position);
    }
    this.state.positions = next;
    this.emit();
  }

  private removePosition(market: string) {
    this.state.positions = this.state.positions.filter((position) => position.market !== market);
    this.emit();
  }

  private completeStopWhenFlat(): boolean {
    if (!this.state.running || this.state.entryEnabled || this.state.positions.length > 0) {
      return false;
    }
    if (this.scanTimer !== null) {
      window.clearInterval(this.scanTimer);
      this.scanTimer = null;
    }
    if (this.manageTimer !== null) {
      window.clearInterval(this.manageTimer);
      this.manageTimer = null;
    }
    this.state.running = false;
    this.state.status = 'IDLE';
    this.state.blockedReason = null;
    this.emit();
    this.pushEvent('STATUS', '보유 포지션 정리 완료 · 엔진 완전 중지');
    return true;
  }

  private isCoolingDown(market: string): boolean {
    const until = this.cooldownUntilByMarket.get(market);
    return typeof until === 'number' && until > Date.now();
  }

  private getHoldingMinutes(position: Pick<GuidedTradePosition, 'createdAt'>): number {
    const createdAt = new Date(position.createdAt).getTime();
    if (!Number.isFinite(createdAt)) return 0;
    return Math.max(0, (Date.now() - createdAt) / 60_000);
  }

  private formatKrw(value: number): string {
    const sign = value > 0 ? '+' : '';
    return `${sign}${Math.round(value).toLocaleString()}원`;
  }

  private formatPercent(value: number): string {
    const sign = value > 0 ? '+' : '';
    return `${sign}${value.toFixed(2)}%`;
  }

  private formatPrice(value: number): string {
    if (!Number.isFinite(value)) return '-';
    return value.toLocaleString('ko-KR', { maximumFractionDigits: 4 });
  }

  private toErrorText(error: unknown): string {
    if (error instanceof Error && error.message) return error.message;
    return String(error);
  }
}
