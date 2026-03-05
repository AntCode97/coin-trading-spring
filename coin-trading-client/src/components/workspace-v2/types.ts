export type WorkspaceDensity = 'COMFORT' | 'COMPACT';
export type ExecutionArmState = 'DISARMED' | 'ARMED';

export interface CommandBarKpis {
  todayPnlKrw: number;
  exposurePercent: number;
  activeEngineCount: number;
  warningCount: number;
}
