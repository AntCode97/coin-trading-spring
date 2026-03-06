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
  entryAggression: AiEntryAggression;
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
export type AiEntryAggression = 'CONSERVATIVE' | 'BALANCED' | 'AGGRESSIVE';
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

type AiTempoProfileKey = 'DEFAULT' | 'DOLLAR_PEG';

interface AiTempoProfile {
  key: AiTempoProfileKey;
  label: string;
  minEntryExpectancyPct: number;
  minEntryWinRate: number;
  minEntryRiskReward: number;
  maxEntryGapPct: number;
  maxEntrySpreadPct: number;
  minEntryCrowdFlow: number;
  earlyHoldMinutes: number;
  earlyExitPnlFloor: number;
  minStopDistancePct: number;
  maxStopDistancePct: number;
  minTakeDistancePct: number;
  maxTakeDistancePct: number;
  minTargetRiskReward: number;
  maxHoldingExtraMinutes: number;
  manageNote: string;
}

interface AiEntryAggressionProfile {
  label: string;
  finalistLimit: number;
  minBuyConfidence: number;
  expectancyOffset: number;
  winRateOffset: number;
  riskRewardOffset: number;
  gapMultiplier: number;
  spreadMultiplier: number;
  crowdFlowOffset: number;
  softPenaltyAllowance: number;
}

type StateListener = (state: AiTraderState) => void;

const SHORTLIST_LIMIT = 12;
const EVENT_HISTORY_LIMIT = 240;
const CLOSED_TRADE_LIMIT = 200;
const TAKE_PROFIT_COOLDOWN_MS = 120_000;
const LLM_EXIT_COOLDOWN_MS = 240_000;
const FORCED_EXIT_COOLDOWN_MS = 300_000;
const STOP_LOSS_COOLDOWN_MS = 480_000;
const CONTEXT_FAILURE_EXIT_THRESHOLD = 3;
const DEFAULT_TEMPO_PROFILE: AiTempoProfile = {
  key: 'DEFAULT',
  label: '기본',
  minEntryExpectancyPct: 0.05,
  minEntryWinRate: 53.5,
  minEntryRiskReward: 1.18,
  maxEntryGapPct: 0.55,
  maxEntrySpreadPct: 0.18,
  minEntryCrowdFlow: 18,
  earlyHoldMinutes: 2,
  earlyExitPnlFloor: -0.45,
  minStopDistancePct: 0.55,
  maxStopDistancePct: 1.10,
  minTakeDistancePct: 0.95,
  maxTakeDistancePct: 2.20,
  minTargetRiskReward: 1.55,
  maxHoldingExtraMinutes: 0,
  manageNote: '일반 알트 템포다. 5~30분 안에 방향성이 선명해야 한다.',
};

const DOLLAR_PEG_TEMPO_PROFILE: AiTempoProfile = {
  key: 'DOLLAR_PEG',
  label: '달러 추종',
  minEntryExpectancyPct: 0.02,
  minEntryWinRate: 55.0,
  minEntryRiskReward: 1.05,
  maxEntryGapPct: 0.24,
  maxEntrySpreadPct: 0.12,
  minEntryCrowdFlow: 8,
  earlyHoldMinutes: 4,
  earlyExitPnlFloor: -0.30,
  minStopDistancePct: 0.32,
  maxStopDistancePct: 0.72,
  minTakeDistancePct: 0.42,
  maxTakeDistancePct: 1.20,
  minTargetRiskReward: 1.28,
  maxHoldingExtraMinutes: 10,
  manageNote: '테더 같은 달러 추종 자산은 다른 코인보다 전개와 체결이 느리다. 작은 흔들림에 과민하게 청산하지 말고 10~40분 템포로 보라.',
};

const DOLLAR_PEG_SYMBOLS = new Set([
  'USDT',
  'USDC',
  'FDUSD',
  'TUSD',
  'USDP',
  'DAI',
  'BUSD',
]);
const BITHUMB_FEE_RATE = 0.0004;

const ENTRY_AGGRESSION_PROFILES: Record<AiEntryAggression, AiEntryAggressionProfile> = {
  CONSERVATIVE: {
    label: '보수적',
    finalistLimit: 4,
    minBuyConfidence: 0.62,
    expectancyOffset: 0.05,
    winRateOffset: 1.5,
    riskRewardOffset: 0.12,
    gapMultiplier: 0.8,
    spreadMultiplier: 0.8,
    crowdFlowOffset: 8,
    softPenaltyAllowance: -0.35,
  },
  BALANCED: {
    label: '균형',
    finalistLimit: 6,
    minBuyConfidence: 0.56,
    expectancyOffset: 0,
    winRateOffset: 0,
    riskRewardOffset: 0,
    gapMultiplier: 1,
    spreadMultiplier: 1,
    crowdFlowOffset: 0,
    softPenaltyAllowance: 0,
  },
  AGGRESSIVE: {
    label: '공격적',
    finalistLimit: 8,
    minBuyConfidence: 0.48,
    expectancyOffset: -0.03,
    winRateOffset: -1.5,
    riskRewardOffset: -0.08,
    gapMultiplier: 1.25,
    spreadMultiplier: 1.25,
    crowdFlowOffset: -8,
    softPenaltyAllowance: 0.45,
  },
};

export const AI_ENTRY_AGGRESSION_OPTIONS: Array<{ value: AiEntryAggression; label: string }> = [
  { value: 'CONSERVATIVE', label: '보수적' },
  { value: 'BALANCED', label: '균형' },
  { value: 'AGGRESSIVE', label: '공격적' },
];

const SHORTLIST_SYSTEM_PROMPT = [
  '너는 빗썸 KRW 현물 시장만 보는 전업 초단타 트레이더다.',
  '목표는 5~30분 안에 끝낼 수 있는 유동성 높은 기회를 많이 잡는 것이다.',
  '과도하게 WAIT만 반복하지는 말되, 얇은 호가나 잡음성 급등처럼 손절이 쉬운 종목은 shortlist에서 낮춰라.',
  '평가 기준: 체결대금, 즉시성 있는 모멘텀, crowd pressure, 순기대값, 스프레드, 진입 괴리, follow-through 가능성.',
  'JSON만 반환한다.',
  '형식: {"shortlist":[{"market":"KRW-BTC","score":82,"reason":"짧은 근거"}]}',
  '최대 12개까지만 반환한다.',
].join('\n');

const ENTRY_SYSTEM_PROMPT = [
  '너는 공격적이지만 규율 있는 KRW 현물 초단타 트레이더다.',
  '보유시간 목표는 5~30분이다.',
  '하드리스크가 아니면 과도하게 WAIT 하지 말고, follow-through 가능성이 있으면 BUY를 우선 검토하라.',
  '순기대값이 명확히 음수이거나, 스프레드가 과도하게 넓거나, 급등 추격이면 WAIT를 선택하라.',
  '깨끗한 추세 지속이나 눌림 재가속만 BUY하고, 잡음성 급등/급락 반대매매는 피하라.',
  'JSON만 반환한다.',
  '형식: {"action":"BUY|WAIT","confidence":0.0,"reasoning":"짧은 근거","stopLoss":0,"takeProfit":0,"urgency":"LOW|MEDIUM|HIGH"}',
  'stopLoss는 현재가보다 낮아야 하고 takeProfit은 현재가보다 높아야 한다.',
].join('\n');

const MANAGE_SYSTEM_PROMPT = [
  '너는 이미 보유한 KRW 현물 초단타 포지션을 관리하는 트레이더다.',
  '보유시간 목표는 5~30분이며, 모멘텀이 약해지거나 downside가 커지면 SELL을 선택한다.',
  '진입 직후 1~2분의 작은 흔들림에는 과민반응하지 말고, 구조 훼손/유동성 붕괴/손절 근접에서만 강하게 SELL 하라.',
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
  entryAggression: 'BALANCED',
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
    const filtered = this.filterAiPositions(positions)
      .map((position) => this.normalizePosition(position))
      .sort((left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
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
      this.state.positions = this.filterAiPositions(scan.positions).map((position) => this.normalizePosition(position));
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

      const aggressionProfile = this.getEntryAggressionProfile();
      const finalists = ranked.slice(0, aggressionProfile.finalistLimit);
      if (finalists.length === 0) return;

      this.setStatus('ANALYZING');
      const activeMarkets = new Set(this.state.positions.map((position) => position.market));
      let activePositionCount = this.state.positions.length;

      for (const finalist of finalists) {
        if (!this.state.running || !this.state.entryEnabled || activePositionCount >= this.config.maxConcurrentPositions) break;
        if (activeMarkets.has(finalist.market)) continue;
        const scanMarket = candidates.find((candidate) => candidate.market === finalist.market);
        if (!scanMarket) continue;
        const tempoProfile = this.getTempoProfile(scanMarket.market, scanMarket.koreanName);

        const entryGateFailure = this.evaluateEntryGate(scanMarket, tempoProfile, aggressionProfile);
        if (entryGateFailure) {
          this.pushEvent(
            'BUY_DECISION',
            `${finalist.market} WAIT`,
            `${aggressionProfile.label} 진입 · ${tempoProfile.label} 템포 · 리스크 필터: ${entryGateFailure}`,
            finalist.market,
            0.99,
            'LOW'
          );
          continue;
        }

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

        const decision = await this.requestEntryDecision(scanMarket, finalist, context, tempoProfile, aggressionProfile);
        if (!decision) continue;

        this.pushEvent(
          'BUY_DECISION',
          `${finalist.market} ${decision.action}`,
          decision.reasoning,
          finalist.market,
          decision.confidence,
          decision.urgency
        );

        if (decision.action !== 'BUY' || decision.confidence < aggressionProfile.minBuyConfidence) {
          continue;
        }

        const protectivePrices = this.resolveProtectivePrices(scanMarket, decision, tempoProfile);

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
        const tempoProfile = this.getTempoProfile(position.market);

        const holdingMinutes = this.getHoldingMinutes(position);
        const effectiveMaxHoldingMinutes = this.getEffectiveMaxHoldingMinutes(tempoProfile);
        if (holdingMinutes >= effectiveMaxHoldingMinutes) {
          await this.executeSell(position, `최대 보유시간 ${effectiveMaxHoldingMinutes}분 도달`, 'FORCED_EXIT');
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

        const decision = await this.requestManageDecision(position, context, tempoProfile, effectiveMaxHoldingMinutes);
        if (!decision) continue;

        this.pushEvent(
          'MANAGE_DECISION',
          `${position.market} ${decision.action}`,
          `${decision.reasoning} · 보유 ${holdingMinutes.toFixed(1)}분 · 미실현 ${this.formatPercent(position.unrealizedPnlPercent)}`,
          position.market,
          decision.confidence,
          decision.urgency
        );

        if (decision.action === 'SELL' && this.shouldDeferEarlySell(position, holdingMinutes, decision, tempoProfile)) {
          this.pushEvent(
            'STATUS',
            `${position.market} 초기 흔들림으로 HOLD 유지`,
            `보유 ${holdingMinutes.toFixed(1)}분 · 미실현 ${this.formatPercent(position.unrealizedPnlPercent)} · ${decision.reasoning}`,
            position.market,
            decision.confidence,
            decision.urgency
          );
          continue;
        }

        if (decision.action === 'SELL' && decision.confidence >= 0.58) {
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
    context: GuidedAgentContextResponse,
    tempoProfile: AiTempoProfile,
    aggressionProfile: AiEntryAggressionProfile
  ): Promise<AiEntryDecision | null> {
    try {
      const response = await requestOneShotTextWithMeta({
        prompt: [
          ENTRY_SYSTEM_PROMPT,
          '',
          `진입 강도: ${aggressionProfile.label}. finalist ${aggressionProfile.finalistLimit}개, BUY confidence 하한 ${Math.round(aggressionProfile.minBuyConfidence * 100)}%.`,
          `템포 프로필: ${tempoProfile.label}. ${tempoProfile.manageNote}`,
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
    context: GuidedAgentContextResponse,
    tempoProfile: AiTempoProfile,
    effectiveMaxHoldingMinutes: number
  ): Promise<AiManageDecision | null> {
    try {
      const response = await requestOneShotTextWithMeta({
        prompt: [
          MANAGE_SYSTEM_PROMPT,
          '',
          `템포 프로필: ${tempoProfile.label}. ${tempoProfile.manageNote}`,
          `포지션 요약: ${JSON.stringify({
            market: position.market,
            averageEntryPrice: position.averageEntryPrice,
            currentPrice: position.currentPrice,
            stopLossPrice: position.stopLossPrice,
            takeProfitPrice: position.takeProfitPrice,
            unrealizedPnlPercent: position.unrealizedPnlPercent,
            createdAt: position.createdAt,
            strategyCode: position.strategyCode,
            effectiveMaxHoldingMinutes,
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

  private resolveProtectivePrices(
    scanMarket: AiScalpScanMarket,
    decision: AiEntryDecision,
    tempoProfile: AiTempoProfile
  ) {
    const currentPrice = scanMarket.tradePrice > 0 ? scanMarket.tradePrice : scanMarket.recommendation.currentPrice;
    const spreadPercent = scanMarket.crowd?.spreadPercent ?? scanMarket.featurePack.spreadPercent ?? 0;
    const entryGapPct = Math.max(0, scanMarket.featurePack.entryGapPct ?? 0);
    const recommendationStopDistance = this.calculateDownsideDistancePercent(currentPrice, scanMarket.recommendation.stopLossPrice);
    const llmStopDistance = this.calculateDownsideDistancePercent(currentPrice, decision.stopLoss);
    const rawStopDistance = Math.max(
      recommendationStopDistance,
      llmStopDistance,
      tempoProfile.minStopDistancePct,
      spreadPercent * 4.5,
      Math.min(entryGapPct, 0.35) * 1.2
    );
    const stopDistancePercent = clamp(rawStopDistance, tempoProfile.minStopDistancePct, tempoProfile.maxStopDistancePct);

    const recommendationTakeDistance = this.calculateUpsideDistancePercent(currentPrice, scanMarket.recommendation.takeProfitPrice);
    const llmTakeDistance = this.calculateUpsideDistancePercent(currentPrice, decision.takeProfit);
    const rawTakeDistance = Math.max(
      recommendationTakeDistance,
      llmTakeDistance,
      tempoProfile.minTakeDistancePct,
      spreadPercent * 8,
      stopDistancePercent * Math.max(scanMarket.recommendation.riskRewardRatio || 0, tempoProfile.minTargetRiskReward)
    );
    const minTakeDistance = Math.max(tempoProfile.minTakeDistancePct, stopDistancePercent * tempoProfile.minTargetRiskReward);
    const takeDistancePercent = clamp(rawTakeDistance, minTakeDistance, tempoProfile.maxTakeDistancePct);

    const stopLoss = currentPrice * (1 - stopDistancePercent / 100);
    const takeProfit = currentPrice * (1 + takeDistancePercent / 100);

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
        const cooldownMs = this.resolveCooldownMs(exitCategory);
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

  private resolveCooldownMs(exitCategory: AiExitCategory): number {
    switch (exitCategory) {
      case 'TAKE_PROFIT':
        return TAKE_PROFIT_COOLDOWN_MS;
      case 'LLM_EXIT':
        return LLM_EXIT_COOLDOWN_MS;
      case 'FORCED_EXIT':
        return FORCED_EXIT_COOLDOWN_MS;
      case 'STOP_LOSS':
      default:
        return STOP_LOSS_COOLDOWN_MS;
    }
  }

  private evaluateEntryGate(
    scanMarket: AiScalpScanMarket,
    tempoProfile: AiTempoProfile,
    aggressionProfile: AiEntryAggressionProfile
  ): string | null {
    const minExpectancyPct = Math.max(-0.01, tempoProfile.minEntryExpectancyPct + aggressionProfile.expectancyOffset);
    const minWinRate = tempoProfile.minEntryWinRate + aggressionProfile.winRateOffset;
    const minRiskReward = Math.max(0.95, tempoProfile.minEntryRiskReward + aggressionProfile.riskRewardOffset);
    const maxSpreadPct = tempoProfile.maxEntrySpreadPct * aggressionProfile.spreadMultiplier;
    const maxEntryGapPct = tempoProfile.maxEntryGapPct * aggressionProfile.gapMultiplier;
    const minCrowdFlow = Math.max(0, tempoProfile.minEntryCrowdFlow + aggressionProfile.crowdFlowOffset);
    const expectancyPct = scanMarket.recommendation.expectancyPct;
    if (expectancyPct < -0.02) {
      return `순기대값 ${expectancyPct.toFixed(2)}% 음수`;
    }

    const recommendedWinRate = scanMarket.recommendation.recommendedEntryWinRate;
    const riskRewardRatio = scanMarket.recommendation.riskRewardRatio;
    if (riskRewardRatio < 0.95) {
      return `RR ${riskRewardRatio.toFixed(2)} 과소`;
    }

    const spreadPercent = scanMarket.crowd?.spreadPercent ?? scanMarket.featurePack.spreadPercent ?? 0;
    if (spreadPercent > maxSpreadPct * 1.8) {
      return `스프레드 ${spreadPercent.toFixed(2)}% 과다`;
    }

    const entryGapPct = Math.max(0, scanMarket.featurePack.entryGapPct ?? 0);
    if (entryGapPct > maxEntryGapPct * 2.0) {
      return `진입 괴리 ${entryGapPct.toFixed(2)}% 과다`;
    }

    const crowdFlow = scanMarket.crowd?.flowScore ?? scanMarket.featurePack.crowdFlowScore ?? 0;
    const priceChange = scanMarket.changeRate;
    if (tempoProfile.key === 'DOLLAR_PEG' && priceChange >= 1.2 && entryGapPct >= 0.08) {
      return `달러 추종 자산 급등 추격 ${priceChange.toFixed(2)}%`;
    }

    if (priceChange >= 4.0 && entryGapPct >= 0.18) {
      return `급등 추격 구간 ${priceChange.toFixed(2)}%`;
    }

    let softPenalty = 0;
    const penaltyReasons: string[] = [];

    if (expectancyPct < minExpectancyPct) {
      softPenalty += expectancyPct < minExpectancyPct * 0.5 ? 1.35 : 0.75;
      penaltyReasons.push(`exp ${expectancyPct.toFixed(2)}%`);
    }

    if (recommendedWinRate < minWinRate) {
      softPenalty += recommendedWinRate < minWinRate - 2.0 ? 1.0 : 0.55;
      penaltyReasons.push(`승률 ${recommendedWinRate.toFixed(1)}%`);
    }

    if (riskRewardRatio < minRiskReward) {
      softPenalty += riskRewardRatio < minRiskReward - 0.15 ? 1.0 : 0.55;
      penaltyReasons.push(`RR ${riskRewardRatio.toFixed(2)}`);
    }

    if (spreadPercent > maxSpreadPct) {
      softPenalty += spreadPercent > maxSpreadPct * 1.35 ? 0.95 : 0.45;
      penaltyReasons.push(`spread ${spreadPercent.toFixed(2)}%`);
    }

    if (entryGapPct > maxEntryGapPct) {
      softPenalty += entryGapPct > maxEntryGapPct * 1.4 ? 0.95 : 0.45;
      penaltyReasons.push(`gap ${entryGapPct.toFixed(2)}%`);
    }

    if (crowdFlow > 0 && crowdFlow < minCrowdFlow) {
      softPenalty += 0.35;
      penaltyReasons.push(`flow ${crowdFlow.toFixed(0)}`);
    }

    const strongOverride =
      expectancyPct >= minExpectancyPct + 0.08 &&
      recommendedWinRate >= minWinRate + 2.5 &&
      riskRewardRatio >= minRiskReward + 0.12;

    const hardSoftPenaltyLimit = 2.4 + aggressionProfile.softPenaltyAllowance;
    const weakSoftPenaltyLimit = 1.6 + aggressionProfile.softPenaltyAllowance * 0.5;

    if (softPenalty >= hardSoftPenaltyLimit && !strongOverride) {
      return `소프트게이트 초과 · ${penaltyReasons.join(' · ')}`;
    }

    if (softPenalty >= weakSoftPenaltyLimit && expectancyPct < minExpectancyPct + 0.03 && !strongOverride) {
      return `진입 근거 약함 · ${penaltyReasons.join(' · ')}`;
    }

    return null;
  }

  private shouldDeferEarlySell(
    position: GuidedTradePosition,
    holdingMinutes: number,
    decision: AiManageDecision,
    tempoProfile: AiTempoProfile
  ): boolean {
    return (
      holdingMinutes < tempoProfile.earlyHoldMinutes &&
      decision.confidence < 0.72 &&
      decision.urgency !== 'HIGH' &&
      position.unrealizedPnlPercent > tempoProfile.earlyExitPnlFloor
    );
  }

  private getEntryAggressionProfile(): AiEntryAggressionProfile {
    return ENTRY_AGGRESSION_PROFILES[this.config.entryAggression] ?? ENTRY_AGGRESSION_PROFILES.BALANCED;
  }

  private getTempoProfile(market: string, koreanName?: string | null): AiTempoProfile {
    const symbol = market.replace(/^KRW-/, '').trim().toUpperCase();
    const name = (koreanName ?? '').trim().toUpperCase();
    if (
      DOLLAR_PEG_SYMBOLS.has(symbol) ||
      name.includes('테더') ||
      name.includes('USD') ||
      name.includes('달러')
    ) {
      return DOLLAR_PEG_TEMPO_PROFILE;
    }
    return DEFAULT_TEMPO_PROFILE;
  }

  private getEffectiveMaxHoldingMinutes(tempoProfile: AiTempoProfile): number {
    if (tempoProfile.key !== 'DOLLAR_PEG') {
      return this.config.maxHoldingMinutes;
    }
    return this.config.maxHoldingMinutes + tempoProfile.maxHoldingExtraMinutes;
  }

  private calculateDownsideDistancePercent(referencePrice: number, candidatePrice?: number): number {
    if (!Number.isFinite(referencePrice) || referencePrice <= 0 || !Number.isFinite(candidatePrice) || (candidatePrice as number) <= 0) {
      return 0;
    }
    return Math.max(0, ((referencePrice - (candidatePrice as number)) / referencePrice) * 100);
  }

  private calculateUpsideDistancePercent(referencePrice: number, candidatePrice?: number): number {
    if (!Number.isFinite(referencePrice) || referencePrice <= 0 || !Number.isFinite(candidatePrice) || (candidatePrice as number) <= 0) {
      return 0;
    }
    return Math.max(0, (((candidatePrice as number) - referencePrice) / referencePrice) * 100);
  }

  private normalizePosition(position: GuidedTradePosition): GuidedTradePosition {
    const currentPrice = position.currentPrice;
    const entryPrice = position.averageEntryPrice;
    if (!this.isPlausiblePositionEntry(entryPrice, currentPrice)) {
      const estimatedEntryPrice = this.estimatePositionEntryPrice(position);
      return {
        ...position,
        averageEntryPrice: estimatedEntryPrice,
        unrealizedPnlPercent: this.calculateFeeAdjustedReturnPercent(estimatedEntryPrice, currentPrice),
      };
    }
    return {
      ...position,
      unrealizedPnlPercent: this.calculateFeeAdjustedReturnPercent(entryPrice, currentPrice),
    };
  }

  private isPlausiblePositionEntry(entryPrice: number, currentPrice: number): boolean {
    if (!Number.isFinite(entryPrice) || entryPrice <= 0 || !Number.isFinite(currentPrice) || currentPrice <= 0) {
      return false;
    }
    return entryPrice >= currentPrice * 0.5 && entryPrice <= currentPrice * 1.5;
  }

  private estimatePositionEntryPrice(position: GuidedTradePosition): number {
    const currentPrice = position.currentPrice;
    if (!Number.isFinite(currentPrice) || currentPrice <= 0) {
      return position.averageEntryPrice;
    }

    const candidates: number[] = [];
    if (
      Number.isFinite(position.stopLossPrice) &&
      Number.isFinite(position.takeProfitPrice) &&
      position.stopLossPrice > 0 &&
      position.takeProfitPrice > position.stopLossPrice
    ) {
      candidates.push((position.stopLossPrice + position.takeProfitPrice) / 2);
    }
    if (Number.isFinite(position.stopLossPrice) && position.stopLossPrice > 0 && position.stopLossPrice < currentPrice) {
      candidates.push((position.stopLossPrice + currentPrice) / 2);
    }
    if (Number.isFinite(position.takeProfitPrice) && position.takeProfitPrice > currentPrice) {
      candidates.push((position.takeProfitPrice + currentPrice) / 2);
    }

    const plausibleCandidate = candidates.find((candidate) => this.isPlausiblePositionEntry(candidate, currentPrice));
    return plausibleCandidate ?? currentPrice;
  }

  private calculateFeeAdjustedReturnPercent(entryPrice: number, currentPrice: number): number {
    if (!Number.isFinite(entryPrice) || entryPrice <= 0 || !Number.isFinite(currentPrice) || currentPrice <= 0) {
      return 0;
    }
    const grossReturn = (currentPrice - entryPrice) / entryPrice;
    const feeReturn = BITHUMB_FEE_RATE * (1 + currentPrice / entryPrice);
    return (grossReturn - feeReturn) * 100;
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
    const normalizedPosition = this.normalizePosition(position);
    const next = [...this.state.positions];
    const index = next.findIndex((item) => item.tradeId === normalizedPosition.tradeId || item.market === normalizedPosition.market);
    if (index >= 0) {
      next[index] = normalizedPosition;
    } else {
      next.unshift(normalizedPosition);
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
