import { useCallback, useEffect, useRef, useState } from 'react';
import type { AgentAction } from './llmService';
import type { PlanExecution, PlanStep } from './planTypes';
import { generatePlan } from './planGenerator';
import { guidedTradingApi, type GuidedTradePosition, type GuidedRecommendation } from '../api';

interface UsePlanExecutionOptions {
  market: string;
  activePosition: GuidedTradePosition | null | undefined;
  recommendation: GuidedRecommendation | null | undefined;
  amountKrw: number;
  customStopLoss?: number | null;
  customTakeProfit?: number | null;
  onComplete?: () => void;
  onStepDone?: (step: PlanStep) => void;
}

export function usePlanExecution(options: UsePlanExecutionOptions) {
  const {
    market,
    activePosition,
    recommendation,
    amountKrw,
    customStopLoss,
    customTakeProfit,
    onComplete,
    onStepDone,
  } = options;

  const [plan, setPlan] = useState<PlanExecution | null>(null);
  const [currentPrice, setCurrentPrice] = useState<number | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const cancelledRef = useRef(false);

  const cleanup = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  const updateStep = useCallback(
    (stepIndex: number, update: Partial<PlanStep>) => {
      setPlan((prev) => {
        if (!prev) return prev;
        const steps = [...prev.steps];
        steps[stepIndex] = { ...steps[stepIndex], ...update };
        return { ...prev, steps };
      });
    },
    [],
  );

  const completePlan = useCallback(
    (status: 'completed' | 'failed' | 'cancelled') => {
      cleanup();
      setPlan((prev) => (prev ? { ...prev, status } : prev));
      if (status === 'completed') onComplete?.();
    },
    [cleanup, onComplete],
  );

  const advanceStep = useCallback(
    (nextIndex: number) => {
      setPlan((prev) => (prev ? { ...prev, currentStepIndex: nextIndex } : prev));
    },
    [],
  );

  // 3초 폴링 루프: 현재 active step 평가
  const runLoop = useCallback(
    (planSnapshot: PlanExecution) => {
      cleanup();
      cancelledRef.current = false;

      const tick = async () => {
        if (cancelledRef.current) return;

        // 최신 plan 상태를 위한 getter
        let latestPlan = planSnapshot;
        setPlan((prev) => {
          if (prev) latestPlan = prev;
          return prev;
        });

        if (latestPlan.status !== 'running') return;

        const idx = latestPlan.currentStepIndex;
        if (idx >= latestPlan.steps.length) {
          completePlan('completed');
          return;
        }

        const step = latestPlan.steps[idx];

        // active로 전환
        if (step.status === 'pending') {
          updateStep(idx, { status: 'active', startedAt: Date.now() });
        }

        // ticker 폴링
        try {
          const ticker = await guidedTradingApi.getRealtimeTicker(latestPlan.market);
          if (ticker) setCurrentPrice(ticker.tradePrice);
        } catch {
          // ticker 실패 무시
        }

        try {
          const result = await executeStep(step, latestPlan.market, {
            amountKrw,
            customStopLoss,
            customTakeProfit,
            recommendation,
          });

          if (result === 'done') {
            updateStep(idx, { status: 'done', completedAt: Date.now() });
            onStepDone?.(step);
            const nextIdx = idx + 1;
            if (nextIdx >= latestPlan.steps.length) {
              completePlan('completed');
            } else {
              advanceStep(nextIdx);
            }
          } else if (result === 'timeout') {
            updateStep(idx, {
              status: 'failed',
              completedAt: Date.now(),
              detail: '시간 초과',
            });
            completePlan('cancelled');
          }
          // 'waiting' → 다음 tick에서 재시도
        } catch (e) {
          const errMsg = e instanceof Error ? e.message : '알 수 없는 오류';
          updateStep(idx, { status: 'failed', completedAt: Date.now(), detail: errMsg });
          completePlan('failed');
        }
      };

      // 즉시 1회 실행 후 3초 간격
      void tick();
      intervalRef.current = setInterval(() => void tick(), 3000);
    },
    [cleanup, completePlan, advanceStep, updateStep, amountKrw, customStopLoss, customTakeProfit, recommendation, onStepDone],
  );

  const startPlan = useCallback(
    (action: AgentAction) => {
      cleanup();
      const newPlan = generatePlan(action, market, activePosition, recommendation, amountKrw);
      setPlan(newPlan);
      setCurrentPrice(null);
      runLoop(newPlan);
    },
    [market, activePosition, recommendation, amountKrw, cleanup, runLoop],
  );

  const cancelPlan = useCallback(() => {
    cancelledRef.current = true;
    cleanup();
    setPlan((prev) => {
      if (!prev || prev.status !== 'running') return prev;
      const steps = prev.steps.map((s) =>
        s.status === 'active' ? { ...s, status: 'skipped' as const, completedAt: Date.now(), detail: '사용자 취소' } : s,
      );
      return { ...prev, steps, status: 'cancelled' };
    });
  }, [cleanup]);

  const dismissPlan = useCallback(() => {
    cleanup();
    setPlan(null);
  }, [cleanup]);

  // market 변경 시 실행 중인 플랜 취소
  const prevMarketRef = useRef(market);
  useEffect(() => {
    if (prevMarketRef.current !== market && plan?.status === 'running') {
      const ok = window.confirm('마켓 변경 시 실행 중인 플랜이 취소됩니다. 계속하시겠습니까?');
      if (ok) {
        cancelPlan();
      }
    }
    prevMarketRef.current = market;
  }, [market, plan?.status, cancelPlan]);

  // cleanup on unmount
  useEffect(() => cleanup, [cleanup]);

  return {
    plan,
    currentPrice,
    startPlan,
    cancelPlan,
    dismissPlan,
    isRunning: plan?.status === 'running',
  };
}

// ------- step 실행 로직 -------

type StepResult = 'done' | 'waiting' | 'timeout';

interface StepContext {
  amountKrw: number;
  customStopLoss?: number | null;
  customTakeProfit?: number | null;
  recommendation: GuidedRecommendation | null | undefined;
}

async function executeStep(
  step: PlanStep,
  market: string,
  ctx: StepContext,
): Promise<StepResult> {
  switch (step.type) {
    case 'CANCEL_PENDING_ORDER': {
      try {
        await guidedTradingApi.cancelPending(market);
      } catch {
        // 취소 실패(404, 미체결 없음 등)는 non-critical — 다음 단계 진행
      }
      return 'done';
    }

    case 'MONITOR_SUPPORT': {
      const targetPrice = step.params.targetPrice as number | undefined;
      const timeoutMs = (step.params.timeoutMs as number) ?? 5 * 60 * 1000;
      if (!targetPrice) return 'done'; // 목표가 없으면 스킵

      if (isTimedOut(step, timeoutMs)) return 'timeout';

      const ticker = await guidedTradingApi.getRealtimeTicker(market);
      if (!ticker) return 'waiting';

      // 현재가가 목표가의 0.3% 이내에 도달하면 done
      if (ticker.tradePrice <= targetPrice * 1.003) return 'done';
      return 'waiting';
    }

    case 'CONFIRM_BOUNCE': {
      const targetPrice = step.params.targetPrice as number | undefined;
      const timeoutMs = (step.params.timeoutMs as number) ?? 2 * 60 * 1000;
      if (!targetPrice) return 'done';

      if (isTimedOut(step, timeoutMs)) return 'timeout';

      const ticker = await guidedTradingApi.getRealtimeTicker(market);
      if (!ticker) return 'waiting';

      // 목표가 대비 0.3% 이상 반등하면 done
      if (ticker.tradePrice >= targetPrice * 1.003) return 'done';
      return 'waiting';
    }

    case 'PLACE_ORDER': {
      const targetPrice = step.params.targetPrice as number | undefined;
      const payload = {
        market,
        amountKrw: (step.params.amountKrw as number) ?? ctx.amountKrw,
        orderType: targetPrice ? 'LIMIT' : 'MARKET',
        limitPrice: targetPrice,
        stopLossPrice: ctx.customStopLoss ?? (step.params.stopLossPrice as number | undefined) ?? ctx.recommendation?.stopLossPrice,
        takeProfitPrice: ctx.customTakeProfit ?? (step.params.takeProfitPrice as number | undefined) ?? ctx.recommendation?.takeProfitPrice,
      };
      await guidedTradingApi.start(payload);
      return 'done';
    }

    case 'SELL_MARKET': {
      await guidedTradingApi.stop(market);
      return 'done';
    }

    case 'PARTIAL_SELL': {
      const ratio = ((step.params.ratio as number) ?? 50) / 100;
      const bounded = Math.max(0.1, Math.min(0.9, ratio));
      await guidedTradingApi.partialTakeProfit(market, bounded);
      return 'done';
    }

    case 'WAIT': {
      return 'done';
    }

    default:
      return 'done';
  }
}

function isTimedOut(step: PlanStep, timeoutMs: number): boolean {
  if (!step.startedAt) return false;
  return Date.now() - step.startedAt > timeoutMs;
}
