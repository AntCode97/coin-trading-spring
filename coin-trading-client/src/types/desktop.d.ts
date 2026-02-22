declare global {
  interface Window {
    desktopEnv?: {
      platform: string;
      isDesktop: boolean;
    };
    desktopAuth?: {
      startLogin: () => Promise<{ ok: boolean }>;
      getToken: () => Promise<{ accessToken: string } | null>;
      checkStatus: () => Promise<{ status: 'connected' | 'disconnected' | 'expired' }>;
      logout: () => Promise<{ ok: boolean }>;
    };
  }
}

export {};
