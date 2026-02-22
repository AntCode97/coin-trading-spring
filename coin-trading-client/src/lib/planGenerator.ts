import type { AgentAction } from './llmService';
import type { PlanExecution, PlanStep, PlanStepType } from './planTypes';
import type { GuidedTradePosition, GuidedRecommendation } from '../api';

let planIdCounter = 0;

function stepId(): string {
  return `step-${Date.now()}-${++planIdCounter}`;
}

function step(type: PlanStepType, label: string, params: Record<string, unknown> = {}): PlanStep {
  return { id: stepId(), type, label, status: 'pending', params };
}

export function generatePlan(
  action: AgentAction,
  market: string,
  activePosition: GuidedTradePosition | null | undefined,
  recommendation: GuidedRecommendation | null | undefined,
  amountKrw: number,
): PlanExecution {
  const actionType = action.type.toUpperCase();
  const steps: PlanStep[] = [];

  switch (actionType) {
    case 'WAIT_RETEST': {
      const targetPrice = action.targetPrice ?? recommendation?.recommendedEntryPrice;
      if (activePosition?.status === 'PENDING_ENTRY') {
        steps.push(step('CANCEL_PENDING_ORDER', `기존 미체결 주문 취소`, { market }));
      }
      steps.push(
        step('MONITOR_SUPPORT', `${targetPrice ? formatPrice(targetPrice) + ' ' : ''}지지선 모니터링`, {
          market,
          targetPrice,
          timeoutMs: 5 * 60 * 1000,
        }),
      );
      steps.push(
        step('CONFIRM_BOUNCE', `반등 확인 (+0.3%)`, {
          market,
          targetPrice,
          bouncePercent: 0.3,
          timeoutMs: 2 * 60 * 1000,
        }),
      );
      steps.push(
        step('PLACE_ORDER', `지정가 매수 ${targetPrice ? formatPrice(targetPrice) : ''}`, {
          market,
          amountKrw,
          targetPrice,
          stopLossPrice: recommendation?.stopLossPrice,
          takeProfitPrice: recommendation?.takeProfitPrice,
        }),
      );
      break;
    }

    case 'ADD': {
      if (activePosition?.status === 'PENDING_ENTRY') {
        steps.push(step('CANCEL_PENDING_ORDER', `기존 미체결 주문 취소`, { market }));
      }
      const targetPrice = action.targetPrice ?? recommendation?.recommendedEntryPrice;
      steps.push(
        step('PLACE_ORDER', `${targetPrice ? '지정가' : '시장가'} 매수 ${targetPrice ? formatPrice(targetPrice) : ''}`, {
          market,
          amountKrw,
          targetPrice,
          stopLossPrice: recommendation?.stopLossPrice,
          takeProfitPrice: recommendation?.takeProfitPrice,
        }),
      );
      break;
    }

    case 'FULL_EXIT': {
      steps.push(step('SELL_MARKET', '전체 시장가 매도', { market }));
      break;
    }

    case 'PARTIAL_TP': {
      const ratio = typeof action.sizePercent === 'number' ? action.sizePercent : 50;
      steps.push(
        step('PARTIAL_SELL', `${ratio}% 시장가 매도`, { market, ratio }),
      );
      break;
    }

    case 'HOLD':
    default: {
      steps.push(step('WAIT', '관망 (즉시 완료)', {}));
      break;
    }
  }

  return {
    id: `plan-${Date.now()}`,
    actionType,
    actionTitle: action.title,
    market,
    steps,
    status: 'running',
    currentStepIndex: 0,
    createdAt: Date.now(),
  };
}

function formatPrice(price: number): string {
  return price.toLocaleString('ko-KR');
}
