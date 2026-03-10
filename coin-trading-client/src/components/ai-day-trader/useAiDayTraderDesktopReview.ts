import { useCallback, useEffect, useRef, useState } from 'react';
import type { AiDayTraderConfig } from '../../lib/ai-day-trader/AiDayTraderEngine';
import {
  AI_DESKTOP_REVIEW_KEY,
  createInitialAiDesktopReviewState,
  getKstDateString,
  getPendingAutoReviewTargetDate,
  runAiDayTraderDesktopReview,
  type AiDesktopReviewState,
} from '../../lib/ai-day-trader/AiDayTraderDesktopReviewAgent';

interface ReviewProviderState {
  providerConnected: boolean;
}

export interface AiDayTraderDesktopReviewView {
  state: AiDesktopReviewState;
  runNow: () => Promise<void>;
  toggleAutoEnabled: () => void;
  setAutoEnabled: (enabled: boolean) => void;
}

function loadDesktopReviewState(): AiDesktopReviewState {
  try {
    const raw = window.localStorage.getItem(AI_DESKTOP_REVIEW_KEY);
    if (!raw) return createInitialAiDesktopReviewState();
    return {
      ...createInitialAiDesktopReviewState(),
      ...(JSON.parse(raw) as Partial<AiDesktopReviewState>),
    };
  } catch {
    return createInitialAiDesktopReviewState();
  }
}

interface UseAiDayTraderDesktopReviewOptions {
  config: AiDayTraderConfig;
  provider: ReviewProviderState;
  updateConfig: (patch: Partial<AiDayTraderConfig>) => void;
}

export function useAiDayTraderDesktopReview({
  config,
  provider,
  updateConfig,
}: UseAiDayTraderDesktopReviewOptions): AiDayTraderDesktopReviewView {
  const [state, setState] = useState<AiDesktopReviewState>(() => loadDesktopReviewState());
  const runningRef = useRef(false);

  useEffect(() => {
    window.localStorage.setItem(AI_DESKTOP_REVIEW_KEY, JSON.stringify(state));
  }, [state]);

  const runReview = useCallback(async (mode: 'manual' | 'auto', targetDate: string) => {
    if (runningRef.current) return;
    if (!provider.providerConnected) {
      setState((current) => ({
        ...current,
        status: 'ERROR',
        lastRunAt: Date.now(),
        lastRunMode: mode,
        lastTargetDate: targetDate,
        lastError: `${config.provider === 'zai' ? 'z.ai' : 'OpenAI'} 연결이 필요합니다.`,
        headline: '복기 대기',
        summary: `${config.provider === 'zai' ? 'z.ai API Key' : 'OpenAI 로그인'}를 먼저 연결해야 복기를 실행할 수 있습니다.`,
      }));
      return;
    }

    runningRef.current = true;
    setState((current) => ({
      ...current,
      status: 'CONNECTING',
      lastError: null,
    }));

    try {
      const result = await runAiDayTraderDesktopReview({
        config,
        provider: config.provider,
        model: config.model,
        zaiEndpointMode: config.zaiEndpointMode,
        delegationMode: config.delegationMode,
        zaiDelegateModel: config.zaiDelegateModel,
        autoEnabled: state.autoEnabled,
        mode,
        targetDate,
      });
      if (Object.keys(result.configPatch).length > 0) {
        updateConfig(result.configPatch);
      }
      setState(result.reviewState);
    } catch (error) {
      setState((current) => ({
        ...current,
        status: 'ERROR',
        lastRunAt: Date.now(),
        lastRunMode: mode,
        lastTargetDate: targetDate,
        lastError: error instanceof Error ? error.message : '데스크톱 복기 실패',
        headline: '복기 실패',
        summary: error instanceof Error ? error.message : '데스크톱 복기 실패',
      }));
    } finally {
      runningRef.current = false;
    }
  }, [config, provider.providerConnected, state.autoEnabled, updateConfig]);

  const runNow = useCallback(async () => {
    await runReview('manual', getKstDateString());
  }, [runReview]);

  const setAutoEnabled = useCallback((enabled: boolean) => {
    setState((current) => ({
      ...current,
      autoEnabled: enabled,
    }));
  }, []);

  const toggleAutoEnabled = useCallback(() => {
    setState((current) => ({
      ...current,
      autoEnabled: !current.autoEnabled,
    }));
  }, []);

  useEffect(() => {
    const maybeRun = () => {
      if (!state.autoEnabled) return;
      const targetDate = getPendingAutoReviewTargetDate(state.lastTargetDate);
      if (!targetDate) return;
      if (!provider.providerConnected) return;
      void runReview('auto', targetDate);
    };

    maybeRun();
    const timer = window.setInterval(maybeRun, 60_000);
    return () => window.clearInterval(timer);
  }, [provider.providerConnected, runReview, state.autoEnabled, state.lastTargetDate]);

  return {
    state,
    runNow,
    toggleAutoEnabled,
    setAutoEnabled,
  };
}
