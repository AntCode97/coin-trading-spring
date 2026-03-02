import type {
  AutopilotOpportunityView,
  GuidedAgentContextResponse,
  GuidedAgentFeaturePack,
} from '../../api';
import {
  type LlmProviderId,
  requestOneShotTextWithMeta,
  type OneShotUsageMeta,
  type TradingMode,
} from '../llmService';

export type FineGrainedStage = 'AUTO_PASS' | 'BORDERLINE' | 'RULE_FAIL';
export type FineAgentMode = 'LITE' | 'FULL';

export interface SpecialistAgentOutput {
  agent: 'TECHNICAL' | 'MICROSTRUCTURE' | 'EXECUTION_RISK';
  score: number;
  confidence: number;
  reason: string;
}

export interface SynthAgentOutput {
  score: number;
  confidence: number;
  reason: string;
}

export interface PmAgentOutput {
  approve: boolean;
  stage: FineGrainedStage;
  score: number;
  confidence: number;
  reason: string;
  orderPlan: {
    orderType: 'MARKET' | 'LIMIT';
    cooldownSec: number;
  };
}

export interface FineGrainedDecisionResult {
  stage: FineGrainedStage;
  approve: boolean;
  score: number;
  confidence: number;
  reason: string;
  specialists: SpecialistAgentOutput[];
  synth: SynthAgentOutput;
  pm: PmAgentOutput;
  usage: {
    calls: number;
    estimatedInputTokens: number;
    estimatedOutputTokens: number;
    totalTokens: number;
  };
}

export interface FineGrainedPipelineOptions {
  market: string;
  tradingMode: TradingMode;
  provider: LlmProviderId;
  model: string;
  minLlmConfidence: number;
  mode?: FineAgentMode;
  candidate: AutopilotOpportunityView;
  context: GuidedAgentContextResponse;
}

function parseJsonObject(text: string): Record<string, unknown> | null {
  const codeBlockMatch = text.match(/```json\s*([\s\S]*?)```/i);
  const candidate = codeBlockMatch ? codeBlockMatch[1] : text;
  const objectMatch = candidate.match(/\{[\s\S]*\}/);
  const raw = objectMatch ? objectMatch[0] : candidate;
  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
    return null;
  } catch {
    return null;
  }
}

function toNumber(value: unknown, fallback: number): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string') {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function createUsageAccumulator(): {
  calls: number;
  estimatedInputTokens: number;
  estimatedOutputTokens: number;
  totalTokens: number;
} {
  return {
    calls: 0,
    estimatedInputTokens: 0,
    estimatedOutputTokens: 0,
    totalTokens: 0,
  };
}

function mergeUsage(
  acc: ReturnType<typeof createUsageAccumulator>,
  usage: OneShotUsageMeta
): void {
  acc.calls += 1;
  acc.estimatedInputTokens += usage.estimatedInputTokens;
  acc.estimatedOutputTokens += usage.estimatedOutputTokens;
  acc.totalTokens += usage.totalTokens;
}

function parseStage(value: unknown): FineGrainedStage {
  if (typeof value !== 'string') return 'RULE_FAIL';
  const normalized = value.trim().toUpperCase();
  if (normalized === 'AUTO_PASS') return 'AUTO_PASS';
  if (normalized === 'BORDERLINE') return 'BORDERLINE';
  return 'RULE_FAIL';
}

function pickFeaturePack(context: GuidedAgentContextResponse): GuidedAgentFeaturePack | null {
  return context.featurePack ?? null;
}

function toPercentLike(value: number): number {
  if (!Number.isFinite(value)) return 0;
  if (value <= 1.0) return clamp(value * 100, 0, 100);
  return clamp(value, 0, 100);
}

function fallbackFeaturePack(context: GuidedAgentContextResponse): GuidedAgentFeaturePack {
  const recommendation = context.chart.recommendation;
  const current = recommendation.currentPrice;
  const entry = recommendation.recommendedEntryPrice;
  const entryGapPct = entry > 0 ? ((current - entry) / entry) * 100 : 0;
  return {
    generatedAt: context.generatedAt,
    technical: {
      trendScore: recommendation.winRateBreakdown.trend,
      pullbackScore: recommendation.winRateBreakdown.pullback,
      volatilityScore: recommendation.winRateBreakdown.volatility,
      riskRewardScore: recommendation.winRateBreakdown.riskReward,
      riskRewardRatio: recommendation.riskRewardRatio,
      recommendedEntryWinRate: recommendation.recommendedEntryWinRate,
      marketEntryWinRate: recommendation.marketEntryWinRate,
      atrPercent: 0,
      momentum6: 0,
      momentum24: 0,
      entryGapPct,
    },
    microstructure: {
      spreadPercent: context.chart.orderbook?.spreadPercent ?? 0,
      totalBidSize: context.chart.orderbook?.totalBidSize ?? 0,
      totalAskSize: context.chart.orderbook?.totalAskSize ?? 0,
      bidAskImbalance: 0,
      top5BidAskImbalance: 0,
      orderbookTimestamp: context.chart.orderbook?.timestamp ?? null,
    },
    executionRisk: {
      suggestedOrderType: recommendation.suggestedOrderType === 'LIMIT' ? 'LIMIT' : 'MARKET',
      chasingRiskScore: clamp(Math.max(0, entryGapPct) * 8, 0, 100),
      pendingFillRiskScore: clamp((context.chart.orderbook?.spreadPercent ?? 0) * 10, 0, 100),
      stopDistancePercent: entry > 0 ? Math.max(0, ((entry - recommendation.stopLossPrice) / entry) * 100) : 0,
      takeProfitDistancePercent: entry > 0 ? Math.max(0, ((recommendation.takeProfitPrice - entry) / entry) * 100) : 0,
      confidence: recommendation.confidence,
    },
  };
}

function fallbackDecision(
  candidate: AutopilotOpportunityView,
  featurePack: GuidedAgentFeaturePack,
  minLlmConfidence: number,
): FineGrainedDecisionResult {
  const tech = featurePack.technical;
  const micro = featurePack.microstructure;
  const exec = featurePack.executionRisk;
  const techScore = clamp(
    tech.trendScore * 100 * 0.35 +
      tech.pullbackScore * 100 * 0.2 +
      tech.volatilityScore * 100 * 0.15 +
      tech.riskRewardScore * 100 * 0.3,
    0,
    100
  );
  const microScore = clamp(
    65 - micro.spreadPercent * 6 + micro.bidAskImbalance * 18 + micro.top5BidAskImbalance * 14,
    0,
    100
  );
  const execScore = clamp(100 - exec.chasingRiskScore * 0.55 - exec.pendingFillRiskScore * 0.45, 0, 100);
  const synthScore = clamp(
    techScore * 0.45 + microScore * 0.3 + execScore * 0.25 + candidate.score * 0.25,
    0,
    100
  );
  const confidence = clamp((candidate.recommendedEntryWinRate1m ?? 50) - 30, 20, 90);

  const stage: FineGrainedStage = (() => {
    if (exec.chasingRiskScore >= 70 || featurePack.technical.entryGapPct > 1.8) return 'RULE_FAIL';
    if (synthScore >= 68 && confidence >= minLlmConfidence) return 'AUTO_PASS';
    if (synthScore >= 56) return 'BORDERLINE';
    return 'RULE_FAIL';
  })();
  const approve = stage !== 'RULE_FAIL';
  const reason = `fallback score ${synthScore.toFixed(1)}, confidence ${confidence.toFixed(0)}, chaseRisk ${exec.chasingRiskScore.toFixed(1)}`;

  const specialists: SpecialistAgentOutput[] = [
    { agent: 'TECHNICAL', score: techScore, confidence, reason: `RR ${tech.riskRewardRatio.toFixed(2)}, momentum6 ${tech.momentum6.toFixed(2)}%` },
    { agent: 'MICROSTRUCTURE', score: microScore, confidence, reason: `spread ${micro.spreadPercent.toFixed(3)}%, imbalance ${micro.bidAskImbalance.toFixed(3)}` },
    { agent: 'EXECUTION_RISK', score: execScore, confidence, reason: `chasing ${exec.chasingRiskScore.toFixed(1)}, pending ${exec.pendingFillRiskScore.toFixed(1)}` },
  ];
  const synth: SynthAgentOutput = { score: synthScore, confidence, reason };
  const pm: PmAgentOutput = {
    approve,
    stage,
    score: synthScore,
    confidence,
    reason,
    orderPlan: {
      orderType: exec.suggestedOrderType === 'LIMIT' ? 'LIMIT' : 'MARKET',
      cooldownSec: stage === 'RULE_FAIL' ? 120 : stage === 'BORDERLINE' ? 60 : 30,
    },
  };
  return {
    stage,
    approve,
    score: synthScore,
    confidence,
    reason,
    specialists,
    synth,
    pm,
    usage: createUsageAccumulator(),
  };
}

function buildDeterministicSpecialists(
  candidate: AutopilotOpportunityView,
  featurePack: GuidedAgentFeaturePack
): SpecialistAgentOutput[] {
  const technical = featurePack.technical;
  const micro = featurePack.microstructure;
  const execution = featurePack.executionRisk;
  const trend = toPercentLike(technical.trendScore);
  const pullback = toPercentLike(technical.pullbackScore);
  const volatility = toPercentLike(technical.volatilityScore);
  const rrScore = toPercentLike(technical.riskRewardScore);
  const technicalScore = clamp(
    trend * 0.35 + pullback * 0.2 + volatility * 0.15 + rrScore * 0.3,
    0,
    100
  );
  const microScore = clamp(
    65 - micro.spreadPercent * 6 + micro.bidAskImbalance * 18 + micro.top5BidAskImbalance * 14,
    0,
    100
  );
  const executionScore = clamp(
    100 - execution.chasingRiskScore * 0.55 - execution.pendingFillRiskScore * 0.45,
    0,
    100
  );
  const confidenceBase = clamp((candidate.recommendedEntryWinRate1m ?? 50) - 30, 20, 90);
  return [
    {
      agent: 'TECHNICAL',
      score: technicalScore,
      confidence: confidenceBase,
      reason: `trend ${trend.toFixed(1)} pullback ${pullback.toFixed(1)} rr ${technical.riskRewardRatio.toFixed(2)}`,
    },
    {
      agent: 'MICROSTRUCTURE',
      score: microScore,
      confidence: confidenceBase,
      reason: `spread ${micro.spreadPercent.toFixed(3)} imbalance ${micro.bidAskImbalance.toFixed(3)}`,
    },
    {
      agent: 'EXECUTION_RISK',
      score: executionScore,
      confidence: confidenceBase,
      reason: `chasing ${execution.chasingRiskScore.toFixed(1)} pending ${execution.pendingFillRiskScore.toFixed(1)}`,
    },
  ];
}

async function runSpecialistAgent(
  agent: SpecialistAgentOutput['agent'],
  options: FineGrainedPipelineOptions,
  featurePack: GuidedAgentFeaturePack,
): Promise<{ output: SpecialistAgentOutput; usage: OneShotUsageMeta }> {
  const prompt = [
    `Market: ${options.market}`,
    `Agent: ${agent}`,
    'Return JSON only.',
    'Format: {"score":0-100,"confidence":0-100,"reason":"<=80 chars"}',
    `Candidate: ${JSON.stringify({
      score: options.candidate.score,
      expectancyPct: options.candidate.expectancyPct,
      riskReward1m: options.candidate.riskReward1m,
      stage: options.candidate.stage,
    })}`,
    `FeaturePack: ${JSON.stringify(featurePack)}`,
    agent === 'TECHNICAL'
      ? 'Focus only on technical section and ignore microstructure/execution.'
      : agent === 'MICROSTRUCTURE'
        ? 'Focus only on microstructure section and ignore technical/fundamental.'
        : 'Focus only on execution-risk section and score fill/chasing risk.',
  ].join('\n');

  const response = await requestOneShotTextWithMeta({
    provider: options.provider,
    prompt,
    model: options.model,
    context: options.context,
    tradingMode: options.tradingMode,
  });
  const parsed = parseJsonObject(response.text);
  const score = clamp(toNumber(parsed?.score, 50), 0, 100);
  const confidence = clamp(toNumber(parsed?.confidence, 50), 0, 100);
  const reason = typeof parsed?.reason === 'string' ? parsed.reason.slice(0, 160) : 'parse fallback';
  return {
    output: { agent, score, confidence, reason },
    usage: response.usage,
  };
}

async function runSynthAgent(
  options: FineGrainedPipelineOptions,
  featurePack: GuidedAgentFeaturePack,
  specialists: SpecialistAgentOutput[],
): Promise<{ output: SynthAgentOutput; usage: OneShotUsageMeta }> {
  const prompt = [
    `Market: ${options.market}`,
    'Role: SYNTHESIZER',
    'Return JSON only.',
    'Format: {"score":0-100,"confidence":0-100,"reason":"<=120 chars"}',
    `Specialists: ${JSON.stringify(specialists)}`,
    `FeaturePack: ${JSON.stringify(featurePack)}`,
    `Candidate: ${JSON.stringify(options.candidate)}`,
    'Synthesize specialist conflicts. Penalize over-chasing and thin liquidity.',
  ].join('\n');

  const response = await requestOneShotTextWithMeta({
    provider: options.provider,
    prompt,
    model: options.model,
    context: options.context,
    tradingMode: options.tradingMode,
  });
  const parsed = parseJsonObject(response.text);
  const score = clamp(toNumber(parsed?.score, 50), 0, 100);
  const confidence = clamp(toNumber(parsed?.confidence, 50), 0, 100);
  const reason = typeof parsed?.reason === 'string' ? parsed.reason.slice(0, 220) : 'parse fallback';
  return {
    output: { score, confidence, reason },
    usage: response.usage,
  };
}

async function runPmAgent(
  options: FineGrainedPipelineOptions,
  specialists: SpecialistAgentOutput[],
  synth: SynthAgentOutput,
): Promise<{ output: PmAgentOutput; usage: OneShotUsageMeta }> {
  const prompt = [
    `Market: ${options.market}`,
    'Role: PM',
    'Return JSON only.',
    'Format: {"approve":true|false,"stage":"AUTO_PASS|BORDERLINE|RULE_FAIL","score":0-100,"confidence":0-100,"cooldownSec":30-300,"orderType":"MARKET|LIMIT","reason":"<=120 chars"}',
    `Min confidence gate: ${options.minLlmConfidence}`,
    `Specialists: ${JSON.stringify(specialists)}`,
    `Synth: ${JSON.stringify(synth)}`,
    `Candidate: ${JSON.stringify(options.candidate)}`,
  ].join('\n');
  const response = await requestOneShotTextWithMeta({
    provider: options.provider,
    prompt,
    model: options.model,
    context: options.context,
    tradingMode: options.tradingMode,
  });
  const parsed = parseJsonObject(response.text);
  const stage = parseStage(parsed?.stage);
  const score = clamp(toNumber(parsed?.score, synth.score), 0, 100);
  const confidence = clamp(toNumber(parsed?.confidence, synth.confidence), 0, 100);
  const approveByField = Boolean(parsed?.approve);
  const approve = approveByField && stage !== 'RULE_FAIL' && confidence >= options.minLlmConfidence;
  const reason = typeof parsed?.reason === 'string' ? parsed.reason.slice(0, 240) : 'parse fallback';
  const orderTypeRaw = typeof parsed?.orderType === 'string' ? parsed.orderType.toUpperCase() : 'MARKET';
  const orderType: 'MARKET' | 'LIMIT' = orderTypeRaw === 'LIMIT' ? 'LIMIT' : 'MARKET';
  const cooldownSec = clamp(Math.round(toNumber(parsed?.cooldownSec, stage === 'RULE_FAIL' ? 120 : 45)), 30, 300);
  return {
    output: {
      approve,
      stage: approve ? stage : 'RULE_FAIL',
      score,
      confidence,
      reason,
      orderPlan: {
        orderType,
        cooldownSec,
      },
    },
    usage: response.usage,
  };
}

export async function runFineGrainedAgentPipeline(
  options: FineGrainedPipelineOptions
): Promise<FineGrainedDecisionResult> {
  const featurePack = pickFeaturePack(options.context) ?? fallbackFeaturePack(options.context);
  const mode: FineAgentMode = options.mode === 'FULL' ? 'FULL' : 'LITE';
  const usage = createUsageAccumulator();
  try {
    let specialists: SpecialistAgentOutput[];
    if (mode === 'FULL') {
      specialists = [];
      const technical = await runSpecialistAgent('TECHNICAL', options, featurePack);
      specialists.push(technical.output);
      mergeUsage(usage, technical.usage);
      const micro = await runSpecialistAgent('MICROSTRUCTURE', options, featurePack);
      specialists.push(micro.output);
      mergeUsage(usage, micro.usage);
      const execution = await runSpecialistAgent('EXECUTION_RISK', options, featurePack);
      specialists.push(execution.output);
      mergeUsage(usage, execution.usage);
    } else {
      specialists = buildDeterministicSpecialists(options.candidate, featurePack);
    }
    const synthResult = await runSynthAgent(options, featurePack, specialists);
    const synth = synthResult.output;
    mergeUsage(usage, synthResult.usage);
    const pmResult = await runPmAgent(options, specialists, synth);
    const pm = pmResult.output;
    mergeUsage(usage, pmResult.usage);

    const stage: FineGrainedStage = pm.approve ? pm.stage : 'RULE_FAIL';
    const approve = stage !== 'RULE_FAIL';
    const reason = pm.reason || synth.reason;

    return {
      stage,
      approve,
      score: pm.score,
      confidence: pm.confidence,
      reason,
      specialists,
      synth,
      pm,
      usage,
    };
  } catch {
    return fallbackDecision(options.candidate, featurePack, options.minLlmConfidence);
  }
}
