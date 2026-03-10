import { getApiBaseUrl } from '../../api';
import { getDesktopAiReviewBundle, getDesktopMysqlStatus, type DesktopAiReviewBundleView, type DesktopMysqlStatusView } from '../desktopMysql';
import {
  connectMcpServers,
  requestOneShotTextWithMeta,
  type DelegationMode,
  type LlmProviderId,
  type ZaiEndpointMode,
} from '../llmService';
import type {
  AiDayTraderConfig,
  AiEntryAggression,
} from './AiDayTraderModel';

export type AiDesktopReviewStatus = 'IDLE' | 'CONNECTING' | 'ANALYZING' | 'APPLYING' | 'READY' | 'SKIPPED' | 'ERROR';

export interface AiDesktopReviewToolAction {
  id: string;
  toolName: string;
  args: string;
  result?: string;
  ok?: boolean;
  at: number;
}

export interface AiDesktopReviewState {
  status: AiDesktopReviewStatus;
  autoEnabled: boolean;
  mysqlConnected: boolean;
  mcpConnected: boolean;
  lastRunAt: number | null;
  lastRunMode: 'manual' | 'auto' | null;
  lastTargetDate: string | null;
  nextRunAt: number | null;
  headline: string;
  summary: string;
  lastError: string | null;
  appliedConfigPatch: Partial<AiDayTraderConfig> | null;
  focusMarkets: string[];
  avoidMarkets: string[];
  preferredSetups: string[];
  avoidSetups: string[];
  serverActionSummary: string[];
  slackSummary: string | null;
  toolActions: AiDesktopReviewToolAction[];
  mysqlStatus: DesktopMysqlStatusView | null;
}

export interface AiDesktopReviewRunOptions {
  config: AiDayTraderConfig;
  provider: LlmProviderId;
  model: string;
  zaiEndpointMode?: ZaiEndpointMode;
  delegationMode?: DelegationMode;
  zaiDelegateModel?: string;
  autoEnabled: boolean;
  mode: 'manual' | 'auto';
  targetDate: string;
}

export interface AiDesktopReviewRunResult {
  reviewState: AiDesktopReviewState;
  configPatch: Partial<AiDayTraderConfig>;
}

export const AI_DESKTOP_REVIEW_KEY = 'ai-scalp-terminal.desktop-review.v1';

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const AUTO_REVIEW_MINUTE = 10;
const REVIEW_LOOKBACK_DAYS = 14;
const REVIEW_RECENT_LIMIT = 80;
const SCAN_INTERVAL_OPTIONS = [10_000, 15_000, 20_000, 30_000, 45_000, 60_000, 90_000, 120_000, 180_000] as const;
const POSITION_CHECK_OPTIONS = [5_000, 8_000, 10_000, 15_000, 20_000, 30_000, 45_000, 60_000, 90_000, 120_000, 180_000] as const;
const MAX_HOLD_OPTIONS = [15, 20, 30, 45] as const;

const DESKTOP_REVIEW_SYSTEM_PROMPT = [
  '너는 데스크톱 초단타 앱 안에서 직접 거래를 복기하고 다음 날 전략을 조정하는 AI 데스크 매니저다.',
  'MySQL review bundle이 1차 진실이다. MCP 도구는 추가 확인, 서버 설정/프롬프트 조정, Slack 보고에 사용한다.',
  '읽기 도구를 먼저 쓰고, 쓰기 도구는 근거가 강할 때만 최대 2회까지 사용한다.',
  '데스크톱 로컬 설정 변경은 tool이 아니라 desktopConfigPatch JSON으로만 제안한다.',
  'Slack 보고가 필요하면 sendMessageToChannel 또는 sendMessage를 써도 된다. 채널을 모르면 listChannels 후 결정한다.',
  '출력은 반드시 JSON만 한다.',
  '형식:',
  '{"headline":"짧은 제목","summary":"짧은 요약","focusMarkets":["KRW-BTC"],"avoidMarkets":["KRW-WLD"],"preferredSetups":["문장"],"avoidSetups":["문장"],"desktopConfigPatch":{"entryAggression":"CONSERVATIVE","scanIntervalMs":60000,"positionCheckMs":10000,"maxHoldingMinutes":30,"amountKrw":10000,"maxConcurrentPositions":3,"dailyLossLimitKrw":-20000},"serverActionSummary":["한 줄"],"slackSummary":"보냈거나 보낼 내용","confidenceNote":"짧은 주의점"}',
].join('\n');

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

export function getKstDateString(timestamp = Date.now()): string {
  const date = new Date(timestamp + KST_OFFSET_MS);
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

function getKstTimeParts(timestamp = Date.now()): { hour: number; minute: number } {
  const date = new Date(timestamp + KST_OFFSET_MS);
  return { hour: date.getUTCHours(), minute: date.getUTCMinutes() };
}

function getPreviousKstDateString(timestamp = Date.now()): string {
  return getKstDateString(timestamp - 24 * 60 * 60 * 1000);
}

export function getNextDesktopReviewAt(timestamp = Date.now()): number {
  const date = new Date(timestamp + KST_OFFSET_MS);
  const nextUtc = Date.UTC(
    date.getUTCFullYear(),
    date.getUTCMonth(),
    date.getUTCDate() + 1,
    0,
    AUTO_REVIEW_MINUTE,
    0,
    0,
  );
  return nextUtc - KST_OFFSET_MS;
}

export function getPendingAutoReviewTargetDate(lastTargetDate: string | null, timestamp = Date.now()): string | null {
  const { hour, minute } = getKstTimeParts(timestamp);
  if (hour === 0 && minute < AUTO_REVIEW_MINUTE) {
    return null;
  }
  const targetDate = getPreviousKstDateString(timestamp);
  return lastTargetDate === targetDate ? null : targetDate;
}

export function createInitialAiDesktopReviewState(): AiDesktopReviewState {
  return {
    status: 'IDLE',
    autoEnabled: true,
    mysqlConnected: false,
    mcpConnected: false,
    lastRunAt: null,
    lastRunMode: null,
    lastTargetDate: null,
    nextRunAt: getNextDesktopReviewAt(),
    headline: '야간 복기 대기',
    summary: '자정 이후 전일 거래를 MySQL에서 직접 읽고, 필요하면 MCP로 설정·프롬프트·Slack까지 조정합니다.',
    lastError: null,
    appliedConfigPatch: null,
    focusMarkets: [],
    avoidMarkets: [],
    preferredSetups: [],
    avoidSetups: [],
    serverActionSummary: [],
    slackSummary: null,
    toolActions: [],
    mysqlStatus: null,
  };
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

function normalizeMarkets(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => typeof item === 'string' ? item.trim().toUpperCase() : '')
    .filter((item) => item.startsWith('KRW-'))
    .slice(0, 5);
}

function normalizeTextList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => typeof item === 'string' ? item.trim() : '')
    .filter((item) => item.length > 0)
    .slice(0, 6);
}

function toNumber(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function pickClosest<T extends number>(options: readonly T[], candidate: number, fallback: T): T {
  if (!Number.isFinite(candidate)) return fallback;
  let winner = fallback;
  let smallest = Math.abs(candidate - fallback);
  for (const option of options) {
    const distance = Math.abs(candidate - option);
    if (distance < smallest) {
      smallest = distance;
      winner = option;
    }
  }
  return winner;
}

function normalizeAggression(value: unknown, fallback: AiEntryAggression): AiEntryAggression {
  if (value === 'CONSERVATIVE' || value === 'BALANCED' || value === 'AGGRESSIVE') {
    return value;
  }
  return fallback;
}

function clampAndRound(value: number, minValue: number, maxValue: number, step: number): number {
  const clamped = Math.min(maxValue, Math.max(minValue, value));
  return Math.round(clamped / step) * step;
}

function sanitizeDesktopConfigPatch(
  patch: Record<string, unknown> | null,
  currentConfig: AiDayTraderConfig
): Partial<AiDayTraderConfig> {
  if (!patch) return {};
  const next: Partial<AiDayTraderConfig> = {};

  if ('entryAggression' in patch) {
    const normalized = normalizeAggression(patch.entryAggression, currentConfig.entryAggression);
    if (normalized !== currentConfig.entryAggression) next.entryAggression = normalized;
  }

  if ('scanIntervalMs' in patch) {
    const normalized = pickClosest(SCAN_INTERVAL_OPTIONS, toNumber(patch.scanIntervalMs), currentConfig.scanIntervalMs);
    if (normalized !== currentConfig.scanIntervalMs) next.scanIntervalMs = normalized;
  }

  if ('positionCheckMs' in patch) {
    const normalized = pickClosest(POSITION_CHECK_OPTIONS, toNumber(patch.positionCheckMs), currentConfig.positionCheckMs);
    if (normalized !== currentConfig.positionCheckMs) next.positionCheckMs = normalized;
  }

  if ('maxHoldingMinutes' in patch) {
    const normalized = pickClosest(MAX_HOLD_OPTIONS, toNumber(patch.maxHoldingMinutes), currentConfig.maxHoldingMinutes);
    if (normalized !== currentConfig.maxHoldingMinutes) next.maxHoldingMinutes = normalized;
  }

  if ('amountKrw' in patch) {
    const normalized = clampAndRound(toNumber(patch.amountKrw, currentConfig.amountKrw), 5_000, 100_000, 5_000);
    if (normalized !== currentConfig.amountKrw) next.amountKrw = normalized;
  }

  if ('maxConcurrentPositions' in patch) {
    const normalized = Math.min(5, Math.max(1, Math.round(toNumber(patch.maxConcurrentPositions, currentConfig.maxConcurrentPositions))));
    if (normalized !== currentConfig.maxConcurrentPositions) next.maxConcurrentPositions = normalized;
  }

  if ('dailyLossLimitKrw' in patch) {
    const normalized = -Math.abs(clampAndRound(toNumber(patch.dailyLossLimitKrw, currentConfig.dailyLossLimitKrw), 5_000, 100_000, 5_000));
    if (normalized !== currentConfig.dailyLossLimitKrw) next.dailyLossLimitKrw = normalized;
  }

  return next;
}

function buildReviewPrompt(bundle: DesktopAiReviewBundleView, config: AiDayTraderConfig): string {
  const currentConfig = {
    entryAggression: config.entryAggression,
    scanIntervalMs: config.scanIntervalMs,
    positionCheckMs: config.positionCheckMs,
    maxHoldingMinutes: config.maxHoldingMinutes,
    amountKrw: config.amountKrw,
    maxConcurrentPositions: config.maxConcurrentPositions,
    dailyLossLimitKrw: config.dailyLossLimitKrw,
    strategyCode: config.strategyCode,
  };

  return [
    DESKTOP_REVIEW_SYSTEM_PROMPT,
    '',
    `[복기 대상일] ${bundle.targetDate}`,
    `[현재 데스크톱 설정] ${JSON.stringify(currentConfig)}`,
    `[대상일 요약] ${JSON.stringify(bundle.summary)}`,
    `[대상일 거래] ${JSON.stringify(bundle.targetTrades.slice(0, 18))}`,
    `[14일 일별 추이] ${JSON.stringify(bundle.dailyTrend.slice(0, 14))}`,
    `[시장별 성과] ${JSON.stringify(bundle.marketBreakdown.slice(0, 10))}`,
    `[청산 사유별 성과] ${JSON.stringify(bundle.exitReasonBreakdown.slice(0, 10))}`,
    `[열린 포지션] ${JSON.stringify(bundle.openPositions)}`,
    `[최근 KeyValue] ${JSON.stringify(bundle.keyValues.slice(0, 18))}`,
    `[활성/최근 프롬프트] ${JSON.stringify(bundle.prompts.slice(0, 8))}`,
    `[최근 감사 로그] ${JSON.stringify(bundle.auditLogs.slice(0, 10))}`,
    '',
    '원칙:',
    '- 데스크톱 로컬 설정 패치는 작게 조정한다.',
    '- stale exit, 연속 손실, 특정 마켓 편향, 과도한 포지션 수, 너무 빠른 템포를 우선 점검한다.',
    '- 서버 변경이나 Slack 보고가 필요하면 MCP 도구를 사용한다.',
    '- JSON 외 다른 텍스트는 출력하지 않는다.',
  ].join('\n');
}

function buildFallbackReviewState(
  bundle: DesktopAiReviewBundleView,
  base: AiDesktopReviewState,
  mode: 'manual' | 'auto',
  mysqlStatus: DesktopMysqlStatusView
): AiDesktopReviewState {
  const primaryExitReason = bundle.exitReasonBreakdown[0]?.exitReason ?? '손실 패턴 분석 필요';
  return {
    ...base,
    status: bundle.summary.totalTrades > 0 ? 'READY' : 'SKIPPED',
    mysqlConnected: mysqlStatus.connected,
    mcpConnected: false,
    lastRunAt: Date.now(),
    lastRunMode: mode,
    lastTargetDate: bundle.targetDate,
    nextRunAt: getNextDesktopReviewAt(),
    headline: bundle.summary.totalTrades > 0 ? `직접 복기 완료 · ${bundle.summary.totalTrades}건` : `복기 건너뜀 · ${bundle.targetDate}`,
    summary: bundle.summary.totalTrades > 0
      ? `총 ${bundle.summary.totalPnlKrw.toLocaleString('ko-KR')}원 / 승률 ${bundle.summary.winRate.toFixed(1)}% · 주된 청산 패턴 ${primaryExitReason}`
      : '복기 대상일에 닫힌 거래가 없어 조정을 건너뛰었습니다.',
    lastError: null,
    appliedConfigPatch: null,
    focusMarkets: bundle.marketBreakdown.filter((item) => item.totalPnlKrw > 0).slice(0, 3).map((item) => item.market),
    avoidMarkets: bundle.marketBreakdown.filter((item) => item.totalPnlKrw < 0).slice(0, 3).map((item) => item.market),
    preferredSetups: [],
    avoidSetups: [],
    serverActionSummary: [],
    slackSummary: null,
    toolActions: [],
    mysqlStatus,
  };
}

function createToolActionCollector() {
  const actions = new Map<string, AiDesktopReviewToolAction>();
  let sequence = 0;

  return {
    onToolCall(toolName: string, args: string) {
      sequence += 1;
      const id = `${toolName}_${Date.now()}_${sequence}`;
      actions.set(id, { id, toolName, args, at: Date.now() });
    },
    onToolResult(toolName: string, result: string) {
      const pending = [...actions.values()].reverse().find((action) => action.toolName === toolName && action.result == null);
      if (!pending) return;
      pending.result = result;
      pending.ok = !/오류|error/i.test(result);
    },
    list(): AiDesktopReviewToolAction[] {
      return [...actions.values()].sort((left, right) => right.at - left.at);
    },
  };
}

function deriveMcpUrl(): string {
  const apiBase = getApiBaseUrl().replace(/\/$/, '');
  return apiBase.replace(/\/api$/, '/mcp');
}

export async function runAiDayTraderDesktopReview(options: AiDesktopReviewRunOptions): Promise<AiDesktopReviewRunResult> {
  const mysqlStatus = await getDesktopMysqlStatus();
  if (!mysqlStatus.connected) {
    return {
      reviewState: {
        ...createInitialAiDesktopReviewState(),
        autoEnabled: options.autoEnabled,
        status: 'ERROR',
        mysqlConnected: false,
        mcpConnected: false,
        lastRunAt: Date.now(),
        lastRunMode: options.mode,
        lastTargetDate: options.targetDate,
        nextRunAt: getNextDesktopReviewAt(),
        headline: 'MySQL 연결 실패',
        summary: mysqlStatus.error || 'MySQL review bundle을 읽지 못했습니다.',
        lastError: mysqlStatus.error || 'MySQL 연결 실패',
        mysqlStatus,
      },
      configPatch: {},
    };
  }

  const bundle = await getDesktopAiReviewBundle({
    strategyCodePrefix: options.config.strategyCode,
    targetDate: options.targetDate,
    lookbackDays: REVIEW_LOOKBACK_DAYS,
    recentLimit: REVIEW_RECENT_LIMIT,
  });

  const baseState = createInitialAiDesktopReviewState();
  baseState.autoEnabled = options.autoEnabled;
  baseState.mysqlConnected = true;
  baseState.mysqlStatus = mysqlStatus;

  if (bundle.summary.totalTrades === 0 && bundle.historyTrades.length === 0) {
    return {
      reviewState: buildFallbackReviewState(bundle, baseState, options.mode, mysqlStatus),
      configPatch: {},
    };
  }

  const mcpTools = await connectMcpServers([{ serverId: 'trading', url: deriveMcpUrl() }]);
  const collector = createToolActionCollector();
  const response = await requestOneShotTextWithMeta({
    prompt: buildReviewPrompt(bundle, options.config),
    provider: options.provider,
    model: options.model,
    tradingMode: 'SCALP',
    zaiEndpointMode: options.zaiEndpointMode,
    delegationMode: options.delegationMode,
    zaiDelegateModel: options.zaiDelegateModel,
    mcpTools,
    onToolCall: (toolName, args) => collector.onToolCall(toolName, args),
    onToolResult: (toolName, result) => collector.onToolResult(toolName, result),
  });

  const parsed = parseJsonObject(response.text);
  if (!parsed) {
    return {
      reviewState: {
        ...buildFallbackReviewState(bundle, baseState, options.mode, mysqlStatus),
        mcpConnected: mcpTools.length > 0,
        toolActions: collector.list(),
        summary: response.text.trim().slice(0, 320) || baseState.summary,
      },
      configPatch: {},
    };
  }

  const configPatch = sanitizeDesktopConfigPatch(
    typeof parsed.desktopConfigPatch === 'object' && parsed.desktopConfigPatch
      ? parsed.desktopConfigPatch as Record<string, unknown>
      : null,
    options.config,
  );

  const serverActionSummary = normalizeTextList(parsed.serverActionSummary);
  const slackSummary = typeof parsed.slackSummary === 'string' && parsed.slackSummary.trim().length > 0
    ? parsed.slackSummary.trim()
    : null;

  return {
    reviewState: {
      ...baseState,
      status: 'READY',
      mysqlConnected: true,
      mcpConnected: mcpTools.length > 0,
      lastRunAt: Date.now(),
      lastRunMode: options.mode,
      lastTargetDate: bundle.targetDate,
      nextRunAt: getNextDesktopReviewAt(),
      headline: typeof parsed.headline === 'string' && parsed.headline.trim().length > 0
        ? parsed.headline.trim()
        : `데스크톱 복기 완료 · ${bundle.targetDate}`,
      summary: typeof parsed.summary === 'string' && parsed.summary.trim().length > 0
        ? parsed.summary.trim()
        : response.text.trim().slice(0, 320),
      lastError: null,
      appliedConfigPatch: Object.keys(configPatch).length > 0 ? configPatch : null,
      focusMarkets: normalizeMarkets(parsed.focusMarkets),
      avoidMarkets: normalizeMarkets(parsed.avoidMarkets),
      preferredSetups: normalizeTextList(parsed.preferredSetups),
      avoidSetups: normalizeTextList(parsed.avoidSetups),
      serverActionSummary,
      slackSummary,
      toolActions: collector.list(),
      mysqlStatus,
    },
    configPatch,
  };
}
