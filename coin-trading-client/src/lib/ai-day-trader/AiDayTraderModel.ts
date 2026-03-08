import type { GuidedCrowdFeaturePack, GuidedTradePosition } from '../../api';
import {
  DEFAULT_OPENAI_MODEL,
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
export type AiMonitorActorKind = 'CORE' | 'SUBAGENT';
export type AiMonitorActorRole = 'SCAN' | 'RANK' | 'ENTRY' | 'MANAGE' | 'EXECUTION' | 'DELEGATE';
export type AiMonitorActorStatus = 'IDLE' | 'BUSY' | 'SUCCESS' | 'ERROR' | 'WAITING';

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

export interface AiMonitorActor {
  id: string;
  kind: AiMonitorActorKind;
  role: AiMonitorActorRole;
  status: AiMonitorActorStatus;
  label: string;
  market?: string;
  taskSummary?: string;
  parentId?: string;
  toolName?: string;
  lastResult?: string;
  startedAt: number;
  updatedAt: number;
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
  monitorActors: AiMonitorActor[];
  monitorFocusId: string | null;
}

export const AI_ENTRY_AGGRESSION_OPTIONS: Array<{ value: AiEntryAggression; label: string }> = [
  { value: 'CONSERVATIVE', label: '보수적' },
  { value: 'BALANCED', label: '균형' },
  { value: 'AGGRESSIVE', label: '공격적' },
];

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

const CORE_MONITOR_ACTORS: AiMonitorActor[] = [
  { id: 'core-scan', kind: 'CORE', role: 'SCAN', status: 'IDLE', label: 'Scanner', startedAt: 0, updatedAt: 0 },
  { id: 'core-rank', kind: 'CORE', role: 'RANK', status: 'IDLE', label: 'Ranker', startedAt: 0, updatedAt: 0 },
  { id: 'core-entry', kind: 'CORE', role: 'ENTRY', status: 'IDLE', label: 'Entry Agent', startedAt: 0, updatedAt: 0 },
  { id: 'core-manage', kind: 'CORE', role: 'MANAGE', status: 'IDLE', label: 'Position Agent', startedAt: 0, updatedAt: 0 },
  { id: 'core-execution', kind: 'CORE', role: 'EXECUTION', status: 'IDLE', label: 'Execution Bot', startedAt: 0, updatedAt: 0 },
];

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
    monitorActors: CORE_MONITOR_ACTORS.map((actor) => ({ ...actor })),
    monitorFocusId: null,
  };
}
