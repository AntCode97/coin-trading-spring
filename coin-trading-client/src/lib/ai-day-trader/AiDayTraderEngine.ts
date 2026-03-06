/**
 * AiDayTraderEngine
 *
 * LLM이 직접 시장을 분석하고 진입/청산 결정을 내리는 자율 단타 엔진.
 * 룰 기반 오토파일럿과 달리 모든 의사결정을 LLM이 수행한다.
 *
 * 루프: 마켓 스캔 -> LLM 분석 -> 진입/관리/청산 결정 -> API 실행 -> 반복
 */
import {
  guidedTradingApi,
  type AutopilotOpportunityView,
  type GuidedAgentContextResponse,
  type GuidedTradePosition,
  type GuidedMarketItem,
} from '../../api';
import {
  requestOneShotTextWithMeta,
  type LlmProviderId,
  type ZaiEndpointMode,
  type DelegationMode,
} from '../llmService';

// ---------- Types ----------

export interface AiDayTraderConfig {
  enabled: boolean;
  provider: LlmProviderId;
  model: string;
  zaiEndpointMode?: ZaiEndpointMode;
  delegationMode?: DelegationMode;
  zaiDelegateModel?: string;
  scanIntervalMs: number;        // 마켓 스캔 주기 (기본 15초)
  positionCheckMs: number;       // 보유 포지션 관리 주기 (기본 8초)
  maxConcurrentPositions: number;
  amountKrw: number;             // 1회 진입 금액
  dailyLossLimitKrw: number;
  universeLimit: number;         // 스캔할 마켓 수
  strategyCode: string;
}

export type AiDecisionType =
  | 'SCAN_ENTRY'      // 시장 스캔 후 진입 결정
  | 'MANAGE_POSITION'  // 보유 포지션 관리
  | 'EXIT_POSITION';   // 청산 결정

export interface AiDecision {
  id: string;
  type: AiDecisionType;
  market: string;
  action: 'BUY' | 'SELL' | 'HOLD' | 'WAIT';
  confidence: number;
  reasoning: string;
  targetPrice?: number;
  stopLoss?: number;
  takeProfit?: number;
  urgency: 'LOW' | 'MEDIUM' | 'HIGH';
  timestamp: number;
}

export interface AiTraderEvent {
  id: string;
  type: 'SCAN' | 'DECISION' | 'EXECUTION' | 'ERROR' | 'STATUS';
  message: string;
  detail?: string;
  market?: string;
  timestamp: number;
}

export type AiTraderStatus = 'IDLE' | 'SCANNING' | 'ANALYZING' | 'EXECUTING' | 'PAUSED' | 'ERROR';

export interface AiTraderState {
  status: AiTraderStatus;
  decisions: AiDecision[];
  events: AiTraderEvent[];
  positions: GuidedTradePosition[];
  dailyPnl: number;
  dailyTradeCount: number;
  totalTokensUsed: number;
  lastScanAt: number | null;
  topCandidates: GuidedMarketItem[];
}

type StateListener = (state: AiTraderState) => void;

// ---------- Prompts ----------

const SCAN_SYSTEM_PROMPT = [
  '너는 암호화폐 전문 AI 데이 트레이더다.',
  '빗썸 거래소에서 초단타(1분~15분 보유) 매매를 수행한다.',
  '',
  '## 역할',
  '- 제공된 시장 데이터를 분석하여 가장 유망한 진입 기회를 찾는다.',
  '- 모멘텀, 거래량 급등, 오더북 불균형, 기술적 패턴을 종합 분석한다.',
  '- 확률이 낮은 기회는 과감히 건너뛴다 (WAIT).',
  '',
  '## 응답 형식',
  '반드시 아래 JSON만 출력한다. 다른 텍스트는 출력하지 않는다.',
  '```json',
  '{',
  '  "action": "BUY" | "WAIT",',
  '  "market": "KRW-XXX",',
  '  "confidence": 0.0~1.0,',
  '  "reasoning": "한줄 근거",',
  '  "stopLoss": number (진입가 대비 손절 가격),',
  '  "takeProfit": number (진입가 대비 익절 가격),',
  '  "urgency": "LOW" | "MEDIUM" | "HIGH"',
  '}',
  '```',
  '',
  '## 원칙',
  '- 승률보다 기대값(expectancy)을 중시한다.',
  '- 확실하지 않으면 WAIT을 선택한다.',
  '- 손절은 반드시 설정한다 (진입가 -0.5% 이내).',
  '- 익절은 손절의 1.5배 이상으로 설정한다.',
].join('\n');

const POSITION_SYSTEM_PROMPT = [
  '너는 암호화폐 포지션 관리 AI다.',
  '현재 보유 중인 포지션을 분석하고 관리 결정을 내린다.',
  '',
  '## 응답 형식',
  '반드시 아래 JSON만 출력한다.',
  '```json',
  '{',
  '  "action": "HOLD" | "SELL",',
  '  "market": "KRW-XXX",',
  '  "confidence": 0.0~1.0,',
  '  "reasoning": "한줄 근거",',
  '  "urgency": "LOW" | "MEDIUM" | "HIGH"',
  '}',
  '```',
  '',
  '## 원칙',
  '- 손절 가격에 도달하면 즉시 SELL (confidence 1.0, urgency HIGH).',
  '- 익절 가격에 도달하면 SELL.',
  '- 보유 10분 초과하면 시장 상황에 따라 판단.',
  '- 추세가 반전되면 조기 청산을 고려.',
].join('\n');

// ---------- Engine ----------

let nextEventId = 0;
function makeEventId(): string {
  return `evt_${Date.now()}_${nextEventId++}`;
}

function makeDecisionId(): string {
  return `dec_${Date.now()}_${nextEventId++}`;
}

export class AiDayTraderEngine {
  private config: AiDayTraderConfig;
  private state: AiTraderState;
  private listeners = new Set<StateListener>();
  private scanTimer: ReturnType<typeof setInterval> | null = null;
  private positionTimer: ReturnType<typeof setInterval> | null = null;
  private running = false;

  constructor(config: AiDayTraderConfig) {
    this.config = config;
    this.state = {
      status: 'IDLE',
      decisions: [],
      events: [],
      positions: [],
      dailyPnl: 0,
      dailyTradeCount: 0,
      totalTokensUsed: 0,
      lastScanAt: null,
      topCandidates: [],
    };
  }

  subscribe(listener: StateListener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  private emit() {
    const snapshot = { ...this.state };
    for (const l of this.listeners) l(snapshot);
  }

  private pushEvent(type: AiTraderEvent['type'], message: string, detail?: string, market?: string) {
    const event: AiTraderEvent = {
      id: makeEventId(),
      type,
      message,
      detail,
      market,
      timestamp: Date.now(),
    };
    this.state.events = [event, ...this.state.events].slice(0, 100);
    this.emit();
  }

  private pushDecision(decision: AiDecision) {
    this.state.decisions = [decision, ...this.state.decisions].slice(0, 50);
    this.emit();
  }

  getState(): AiTraderState {
    return { ...this.state };
  }

  updateConfig(patch: Partial<AiDayTraderConfig>) {
    this.config = { ...this.config, ...patch };
  }

  // ---------- Lifecycle ----------

  start() {
    if (this.running) return;
    this.running = true;
    this.state.status = 'IDLE';
    this.pushEvent('STATUS', 'AI 데이 트레이더 시작');

    // 즉시 첫 스캔
    this.runScanCycle();

    this.scanTimer = setInterval(() => this.runScanCycle(), this.config.scanIntervalMs);
    this.positionTimer = setInterval(() => this.runPositionManagement(), this.config.positionCheckMs);
  }

  stop() {
    this.running = false;
    if (this.scanTimer) { clearInterval(this.scanTimer); this.scanTimer = null; }
    if (this.positionTimer) { clearInterval(this.positionTimer); this.positionTimer = null; }
    this.state.status = 'IDLE';
    this.pushEvent('STATUS', 'AI 데이 트레이더 중지');
    this.emit();
  }

  isRunning(): boolean {
    return this.running;
  }

  // ---------- Scan Cycle ----------

  private async runScanCycle() {
    if (!this.running) return;
    if (this.state.status === 'SCANNING' || this.state.status === 'ANALYZING') return;

    // 일일 손실 한도 체크
    if (this.state.dailyPnl <= this.config.dailyLossLimitKrw) {
      this.pushEvent('STATUS', `일일 손실 한도 도달 (${this.state.dailyPnl.toLocaleString()}원)`);
      return;
    }

    // 동시 포지션 한도 체크
    if (this.state.positions.length >= this.config.maxConcurrentPositions) {
      return;
    }

    try {
      this.state.status = 'SCANNING';
      this.emit();

      // 1) 마켓 데이터 수집
      const markets = await guidedTradingApi.getMarkets('TURNOVER', 'DESC', 'minute1');
      const top = markets.slice(0, this.config.universeLimit);
      this.state.topCandidates = top;
      this.state.lastScanAt = Date.now();

      // 2) 기회 데이터
      const opps = await guidedTradingApi.getAutopilotOpportunities('minute1', 'minute10', undefined, this.config.universeLimit);

      // 3) 상위 후보 에이전트 컨텍스트 (최대 3개)
      const passOrBorderline = opps.opportunities
        .filter(o => o.stage === 'AUTO_PASS' || o.stage === 'BORDERLINE')
        .slice(0, 3);

      if (passOrBorderline.length === 0) {
        this.pushEvent('SCAN', `${top.length}개 마켓 스캔 완료 - 유망 후보 없음`);
        this.state.status = 'IDLE';
        this.emit();
        return;
      }

      this.pushEvent('SCAN', `${passOrBorderline.length}개 유망 후보 발견`, passOrBorderline.map(o => o.market).join(', '));

      // 4) LLM 분석
      this.state.status = 'ANALYZING';
      this.emit();

      for (const opp of passOrBorderline) {
        if (!this.running) break;
        if (this.state.positions.length >= this.config.maxConcurrentPositions) break;

        // 이미 보유 중인 마켓이면 스킵
        if (this.state.positions.some(p => p.market === opp.market)) continue;

        try {
          const ctx = await guidedTradingApi.getAgentContext(opp.market, 'minute1', 60);
          const decision = await this.askLlmForEntry(opp, ctx);

          if (decision && decision.action === 'BUY' && decision.confidence >= 0.6) {
            this.pushDecision(decision);
            await this.executeEntry(decision);
          } else if (decision) {
            this.pushDecision(decision);
            this.pushEvent('DECISION', `${opp.market} WAIT`, decision.reasoning);
          }
        } catch (err) {
          this.pushEvent('ERROR', `${opp.market} 분석 실패`, String(err));
        }
      }

      this.state.status = 'IDLE';
      this.emit();
    } catch (err) {
      this.state.status = 'ERROR';
      this.pushEvent('ERROR', '마켓 스캔 실패', String(err));
      this.emit();
    }
  }

  // ---------- Position Management ----------

  private async runPositionManagement() {
    if (!this.running) return;

    try {
      const positions = await guidedTradingApi.getOpenPositions();
      // 필터: AI 데이트레이더 전략 코드만
      this.state.positions = positions.filter(
        p => p.strategyCode?.startsWith(this.config.strategyCode) ?? false
      );
      this.emit();

      for (const pos of this.state.positions) {
        if (!this.running) break;
        try {
          const ctx = await guidedTradingApi.getAgentContext(pos.market, 'minute1', 30);
          const decision = await this.askLlmForManagement(pos, ctx);
          if (decision) {
            this.pushDecision(decision);
            if (decision.action === 'SELL' && decision.confidence >= 0.5) {
              await this.executeExit(pos, decision);
            }
          }
        } catch (err) {
          this.pushEvent('ERROR', `${pos.market} 포지션 관리 실패`, String(err));
        }
      }
    } catch (err) {
      this.pushEvent('ERROR', '포지션 조회 실패', String(err));
    }
  }

  // ---------- LLM Calls ----------

  private async askLlmForEntry(
    opp: AutopilotOpportunityView,
    ctx: GuidedAgentContextResponse
  ): Promise<AiDecision | null> {
    const slicedCandles = ctx.chart.candles.slice(-30);
    const userPrompt = [
      `## 마켓: ${opp.market} (${opp.koreanName})`,
      `- 기대값: ${(opp.expectancyPct * 100).toFixed(2)}%`,
      `- 스코어: ${opp.score.toFixed(1)}`,
      `- RR: ${opp.riskReward1m.toFixed(2)}`,
      `- 진입괴리: ${(opp.entryGapPct1m * 100).toFixed(2)}%`,
      `- stage: ${opp.stage}`,
      '',
      `## 현재가: ${ctx.chart.recommendation.currentPrice.toLocaleString()}`,
      `## 추천 진입가: ${ctx.chart.recommendation.recommendedEntryPrice.toLocaleString()}`,
      `## 오더북 요약:`,
      `  매수잔량 상위: ${ctx.chart.orderbook?.totalBidSize?.toLocaleString() ?? 'N/A'}`,
      `  매도잔량 상위: ${ctx.chart.orderbook?.totalAskSize?.toLocaleString() ?? 'N/A'}`,
      '',
      `## 최근 ${slicedCandles.length}개 1분봉 (최신 3개):`,
      JSON.stringify(slicedCandles.slice(-3)),
      '',
      '진입할지 판단하라.',
    ].join('\n');

    try {
      const { text, usage } = await requestOneShotTextWithMeta({
        prompt: `${SCAN_SYSTEM_PROMPT}\n\n${userPrompt}`,
        model: this.config.model,
        provider: this.config.provider,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
      });

      this.state.totalTokensUsed += usage.totalTokens;

      const parsed = this.parseDecisionJson(text);
      if (!parsed) return null;

      return {
        id: makeDecisionId(),
        type: 'SCAN_ENTRY',
        market: opp.market,
        action: parsed.action === 'BUY' ? 'BUY' : 'WAIT',
        confidence: Number(parsed.confidence ?? 0),
        reasoning: String(parsed.reasoning ?? ''),
        stopLoss: parsed.stopLoss != null ? Number(parsed.stopLoss) : undefined,
        takeProfit: parsed.takeProfit != null ? Number(parsed.takeProfit) : undefined,
        urgency: (['LOW', 'MEDIUM', 'HIGH'].includes(String(parsed.urgency)) ? String(parsed.urgency) : 'MEDIUM') as AiDecision['urgency'],
        timestamp: Date.now(),
      };
    } catch (err) {
      this.pushEvent('ERROR', `${opp.market} LLM 진입 분석 실패`, String(err));
      return null;
    }
  }

  private async askLlmForManagement(
    pos: GuidedTradePosition,
    ctx: GuidedAgentContextResponse
  ): Promise<AiDecision | null> {
    const holdingMs = Date.now() - new Date(pos.createdAt).getTime();
    const holdingMin = (holdingMs / 60_000).toFixed(1);
    const slicedCandles = ctx.chart.candles.slice(-15);

    const userPrompt = [
      `## 보유 포지션: ${pos.market}`,
      `- 평균진입가: ${pos.averageEntryPrice.toLocaleString()}`,
      `- 현재가: ${pos.currentPrice.toLocaleString()}`,
      `- 미실현 PnL: ${pos.unrealizedPnlPercent.toFixed(2)}%`,
      `- 보유시간: ${holdingMin}분`,
      `- 손절가: ${pos.stopLossPrice.toLocaleString()}`,
      `- 익절가: ${pos.takeProfitPrice.toLocaleString()}`,
      '',
      `## 최근 1분봉 (최신 5개):`,
      JSON.stringify(slicedCandles.slice(-5)),
      '',
      '청산할지 판단하라.',
    ].join('\n');

    try {
      const { text, usage } = await requestOneShotTextWithMeta({
        prompt: `${POSITION_SYSTEM_PROMPT}\n\n${userPrompt}`,
        model: this.config.model,
        provider: this.config.provider,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
      });

      this.state.totalTokensUsed += usage.totalTokens;

      const parsed = this.parseDecisionJson(text);
      if (!parsed) return null;

      return {
        id: makeDecisionId(),
        type: 'MANAGE_POSITION',
        market: pos.market,
        action: parsed.action === 'SELL' ? 'SELL' : 'HOLD',
        confidence: Number(parsed.confidence ?? 0),
        reasoning: String(parsed.reasoning ?? ''),
        urgency: (['LOW', 'MEDIUM', 'HIGH'].includes(String(parsed.urgency)) ? String(parsed.urgency) : 'MEDIUM') as AiDecision['urgency'],
        timestamp: Date.now(),
      };
    } catch (err) {
      this.pushEvent('ERROR', `${pos.market} LLM 관리 분석 실패`, String(err));
      return null;
    }
  }

  // ---------- Execution ----------

  private async executeEntry(decision: AiDecision) {
    try {
      this.state.status = 'EXECUTING';
      this.emit();

      const result = await guidedTradingApi.start({
        market: decision.market,
        amountKrw: this.config.amountKrw,
        orderType: 'MARKET',
        strategyCode: this.config.strategyCode,
        entrySource: 'AUTOPILOT',
        stopLossPrice: decision.stopLoss,
        takeProfitPrice: decision.takeProfit,
      });

      this.state.dailyTradeCount++;
      this.pushEvent(
        'EXECUTION',
        `${decision.market} 매수 체결`,
        `수량: ${result.entryQuantity}, 가격: ${result.averageEntryPrice.toLocaleString()}`,
        decision.market
      );

      this.state.status = 'IDLE';
      this.emit();
    } catch (err) {
      this.pushEvent('ERROR', `${decision.market} 매수 실행 실패`, String(err), decision.market);
      this.state.status = 'IDLE';
      this.emit();
    }
  }

  private async executeExit(pos: GuidedTradePosition, decision: AiDecision) {
    try {
      this.state.status = 'EXECUTING';
      this.emit();

      const result = await guidedTradingApi.stop(pos.market);

      const pnl = result.realizedPnl ?? 0;
      this.state.dailyPnl += pnl;
      this.pushEvent(
        'EXECUTION',
        `${pos.market} 매도 체결`,
        `PnL: ${pnl >= 0 ? '+' : ''}${pnl.toLocaleString()}원 (${decision.reasoning})`,
        pos.market
      );

      this.state.status = 'IDLE';
      this.emit();
    } catch (err) {
      this.pushEvent('ERROR', `${pos.market} 매도 실행 실패`, String(err), pos.market);
      this.state.status = 'IDLE';
      this.emit();
    }
  }

  // ---------- Helpers ----------

  private parseDecisionJson(text: string): Record<string, unknown> | null {
    try {
      // JSON 블록 추출
      const jsonMatch = text.match(/```json\s*([\s\S]*?)```/) || text.match(/\{[\s\S]*\}/);
      if (!jsonMatch) return null;
      const raw = jsonMatch[1] || jsonMatch[0];
      return JSON.parse(raw.trim());
    } catch {
      this.pushEvent('ERROR', 'LLM 응답 파싱 실패', text.slice(0, 200));
      return null;
    }
  }
}

// ---------- Singleton ----------

let instance: AiDayTraderEngine | null = null;

export function getAiDayTraderEngine(config?: AiDayTraderConfig): AiDayTraderEngine {
  if (!instance && config) {
    instance = new AiDayTraderEngine(config);
  }
  if (!instance) {
    throw new Error('AiDayTraderEngine not initialized');
  }
  return instance;
}

export function resetAiDayTraderEngine(): void {
  if (instance) {
    instance.stop();
    instance = null;
  }
}

export const DEFAULT_AI_DAY_TRADER_CONFIG: AiDayTraderConfig = {
  enabled: false,
  provider: 'openai',
  model: 'gpt-4',
  scanIntervalMs: 15_000,
  positionCheckMs: 8_000,
  maxConcurrentPositions: 3,
  amountKrw: 10_000,
  dailyLossLimitKrw: -20_000,
  universeLimit: 15,
  strategyCode: 'AI_DAY_TRADER',
};
