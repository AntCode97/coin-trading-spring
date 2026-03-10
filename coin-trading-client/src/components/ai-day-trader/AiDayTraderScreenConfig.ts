import {
  DEFAULT_AI_DAY_TRADER_CONFIG,
  type AiDayTraderConfig,
  type AiEntryAggression,
} from '../../lib/ai-day-trader/AiDayTraderEngine';
import {
  CODEX_MODELS,
  DEFAULT_OPENAI_MODEL,
  ZAI_MODELS,
  type LlmProviderId,
} from '../../lib/llmService';

export const PREFERENCE_KEY = 'ai-scalp-terminal.preferences.v1';
export const JOURNAL_PAGE_SIZE = 18;

export const OPENAI_TRADER_MODELS = CODEX_MODELS.filter((model) => model.id !== 'gpt-4');

export const SCAN_INTERVAL_OPTIONS = [
  { value: 10_000, label: '10초' },
  { value: 15_000, label: '15초' },
  { value: 20_000, label: '20초' },
  { value: 30_000, label: '30초' },
  { value: 45_000, label: '45초' },
  { value: 60_000, label: '1분' },
  { value: 90_000, label: '1분 30초' },
  { value: 120_000, label: '2분' },
  { value: 180_000, label: '3분' },
] as const;

export const POSITION_CHECK_OPTIONS = [
  { value: 5_000, label: '5초' },
  { value: 8_000, label: '8초' },
  { value: 10_000, label: '10초' },
  { value: 15_000, label: '15초' },
  { value: 20_000, label: '20초' },
  { value: 30_000, label: '30초' },
  { value: 45_000, label: '45초' },
  { value: 60_000, label: '1분' },
  { value: 90_000, label: '1분 30초' },
  { value: 120_000, label: '2분' },
  { value: 180_000, label: '3분' },
] as const;

export const UNIVERSE_LIMIT_OPTIONS = [
  { value: 24, label: '24개' },
  { value: 36, label: '36개' },
  { value: 48, label: '48개' },
  { value: 60, label: '60개' },
] as const;

export const MAX_HOLDING_OPTIONS = [
  { value: 15, label: '15분' },
  { value: 20, label: '20분' },
  { value: 30, label: '30분' },
  { value: 45, label: '45분' },
] as const;

export function normalizeEntryAggression(value?: string): AiEntryAggression {
  if (value === 'CONSERVATIVE' || value === 'AGGRESSIVE') {
    return value;
  }
  return 'BALANCED';
}

export function normalizePreferredModel(provider: LlmProviderId, model?: string): string {
  const catalog = provider === 'zai' ? ZAI_MODELS : OPENAI_TRADER_MODELS;
  const normalized = model?.trim();
  if (provider === 'openai' && normalized === 'gpt-4') {
    return DEFAULT_OPENAI_MODEL;
  }
  return catalog.some((item) => item.id === normalized)
    ? normalized!
    : catalog[0]?.id ?? DEFAULT_AI_DAY_TRADER_CONFIG.model;
}

export function loadAiDayTraderPreferences(): AiDayTraderConfig {
  try {
    const raw = window.localStorage.getItem(PREFERENCE_KEY);
    if (!raw) return DEFAULT_AI_DAY_TRADER_CONFIG;
    const parsed = JSON.parse(raw) as Partial<AiDayTraderConfig>;
    const provider = parsed.provider === 'zai' ? 'zai' : DEFAULT_AI_DAY_TRADER_CONFIG.provider;
    const usesLegacyTimingDefaults =
      (parsed.scanIntervalMs == null || parsed.scanIntervalMs === 10_000)
      && (parsed.positionCheckMs == null || parsed.positionCheckMs === 5_000);
    const usesLegacyEntryAggressionDefault =
      parsed.entryAggression == null
      || (parsed.entryAggression === 'BALANCED' && usesLegacyTimingDefaults);
    const usesLegacyAmountDefault =
      parsed.amountKrw == null
      || (parsed.amountKrw === 10_000 && usesLegacyTimingDefaults && usesLegacyEntryAggressionDefault);
    return {
      ...DEFAULT_AI_DAY_TRADER_CONFIG,
      ...parsed,
      provider,
      entryAggression: usesLegacyEntryAggressionDefault
        ? DEFAULT_AI_DAY_TRADER_CONFIG.entryAggression
        : normalizeEntryAggression(parsed.entryAggression),
      model: normalizePreferredModel(provider, parsed.model),
      scanIntervalMs: usesLegacyTimingDefaults
        ? DEFAULT_AI_DAY_TRADER_CONFIG.scanIntervalMs
        : parsed.scanIntervalMs ?? DEFAULT_AI_DAY_TRADER_CONFIG.scanIntervalMs,
      positionCheckMs: usesLegacyTimingDefaults
        ? DEFAULT_AI_DAY_TRADER_CONFIG.positionCheckMs
        : parsed.positionCheckMs ?? DEFAULT_AI_DAY_TRADER_CONFIG.positionCheckMs,
      amountKrw: usesLegacyAmountDefault
        ? DEFAULT_AI_DAY_TRADER_CONFIG.amountKrw
        : parsed.amountKrw ?? DEFAULT_AI_DAY_TRADER_CONFIG.amountKrw,
      strategyCode: DEFAULT_AI_DAY_TRADER_CONFIG.strategyCode,
    };
  } catch {
    return DEFAULT_AI_DAY_TRADER_CONFIG;
  }
}
