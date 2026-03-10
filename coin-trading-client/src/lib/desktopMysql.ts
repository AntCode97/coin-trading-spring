export interface DesktopMysqlStatusView {
  connected: boolean;
  hostLabel: string | null;
  database: string | null;
  infoPath?: string | null;
  error?: string;
}

export interface DesktopAiReviewBundleView extends DesktopAiReviewBundle {}

export async function getDesktopMysqlStatus(): Promise<DesktopMysqlStatusView> {
  if (!window.desktopMysql) {
    return {
      connected: false,
      hostLabel: null,
      database: null,
      error: '데스크톱 MySQL IPC를 사용할 수 없습니다.',
    };
  }
  return window.desktopMysql.status();
}

export async function getDesktopAiReviewBundle(options?: {
  strategyCodePrefix?: string;
  targetDate?: string;
  lookbackDays?: number;
  recentLimit?: number;
}): Promise<DesktopAiReviewBundleView> {
  if (!window.desktopMysql) {
    throw new Error('데스크톱 MySQL IPC를 사용할 수 없습니다.');
  }
  const result = await window.desktopMysql.getReviewBundle(options);
  if (!result.ok || !result.bundle) {
    throw new Error(result.error || 'MySQL review bundle 조회 실패');
  }
  return result.bundle;
}
