export type PlanStepStatus = 'pending' | 'active' | 'done' | 'failed' | 'skipped';

export type PlanStepType =
  | 'CANCEL_PENDING_ORDER'
  | 'MONITOR_SUPPORT'
  | 'CONFIRM_BOUNCE'
  | 'PLACE_ORDER'
  | 'SELL_MARKET'
  | 'PARTIAL_SELL'
  | 'WAIT';

export interface PlanStep {
  id: string;
  type: PlanStepType;
  label: string;
  status: PlanStepStatus;
  detail?: string;
  startedAt?: number;
  completedAt?: number;
  params: Record<string, unknown>;
}

export interface PlanExecution {
  id: string;
  actionType: string;
  actionTitle: string;
  market: string;
  steps: PlanStep[];
  status: 'running' | 'completed' | 'failed' | 'cancelled';
  currentStepIndex: number;
  createdAt: number;
}
