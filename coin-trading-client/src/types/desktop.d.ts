declare global {
  type ZaiEndpointMode = 'coding' | 'general';

  interface DesktopMcpServerConfig {
    serverId: string;
    url: string;
  }

  interface DesktopMcpServerStatus {
    serverId: string;
    url: string;
    connected: boolean;
  }

  interface DesktopMcpConnectServerResult {
    serverId: string;
    url: string;
    ok: boolean;
    error?: string;
  }

  interface PlaywrightMcpStatus {
    status: 'stopped' | 'starting' | 'running' | 'error';
    running: boolean;
    port: number | null;
    url: string | null;
    cdpEndpoint: string | null;
    lastError: string | null;
  }

  interface McpTool {
    name: string;
    description?: string;
    serverId?: string;
    qualifiedName?: string;
    originName?: string;
    inputSchema?: {
      type: string;
      properties?: Record<string, { type: string; description?: string }>;
      required?: string[];
    };
  }

  interface McpToolResult {
    content: Array<{
      type: string;
      text?: string;
      data?: string;
      mimeType?: string;
      url?: string;
    }>;
    isError?: boolean;
  }

  interface DesktopZaiTool {
    type: 'function';
    function: {
      name: string;
      description?: string;
      parameters?: Record<string, unknown>;
    };
  }

  interface DesktopZaiChatPayload {
    endpointMode?: ZaiEndpointMode;
    model: string;
    messages: Array<{
      role: 'system' | 'user' | 'assistant';
      content: string;
    }>;
    tools?: DesktopZaiTool[];
    toolChoice?: 'none' | 'auto' | { type: 'function'; function: { name: string } };
  }

  interface DesktopZaiChatResponse {
    ok: boolean;
    text?: string;
    toolCalls?: Array<{
      id: string;
      name: string;
      args: string;
    }>;
    usage?: {
      promptTokens: number;
      completionTokens: number;
      totalTokens: number;
    };
    error?: string;
  }

  interface DesktopMysqlStatus {
    connected: boolean;
    hostLabel: string | null;
    database: string | null;
    infoPath?: string | null;
    error?: string;
  }

  interface DesktopAiReviewTrade {
    tradeId: number;
    market: string;
    status: string;
    averageEntryPrice: number;
    averageExitPrice: number;
    entryQuantity: number;
    targetAmountKrw: number;
    realizedPnl: number;
    realizedPnlPercent: number;
    recommendationReason?: string | null;
    exitReason?: string | null;
    strategyCode?: string | null;
    createdAt: string | null;
    closedAt?: string | null;
    holdingMinutes: number;
    tradeDateKst: string;
  }

  interface DesktopAiReviewBundle {
    generatedAt: string;
    targetDate: string;
    strategyCodePrefix: string;
    summary: {
      totalTrades: number;
      wins: number;
      losses: number;
      totalPnlKrw: number;
      avgPnlPercent: number;
      winRate: number;
      avgHoldingMinutes: number;
    };
    targetTrades: DesktopAiReviewTrade[];
    historyTrades: DesktopAiReviewTrade[];
    openPositions: Array<{
      tradeId: number;
      market: string;
      status: string;
      averageEntryPrice: number;
      remainingQuantity: number;
      stopLossPrice: number;
      takeProfitPrice: number;
      createdAt: string | null;
    }>;
    dailyTrend: Array<{
      tradeDate: string;
      totalTrades: number;
      wins: number;
      losses: number;
      totalPnlKrw: number;
      avgPnlPercent: number;
      winRate: number;
      avgHoldingMinutes: number;
    }>;
    marketBreakdown: Array<{
      market: string;
      totalTrades: number;
      wins: number;
      losses: number;
      totalPnlKrw: number;
      avgPnlPercent: number;
      winRate: number;
      avgHoldingMinutes: number;
    }>;
    exitReasonBreakdown: Array<{
      exitReason: string;
      totalTrades: number;
      wins: number;
      losses: number;
      totalPnlKrw: number;
      avgPnlPercent: number;
      winRate: number;
      avgHoldingMinutes: number;
    }>;
    keyValues: Array<{
      key: string;
      value: string;
      category?: string | null;
      updatedAt?: string | null;
    }>;
    prompts: Array<{
      promptName: string;
      promptType: string;
      version: number;
      isActive: boolean;
      createdBy: string;
      performanceScore?: number | null;
      usageCount: number;
      updatedAt?: string | null;
    }>;
    auditLogs: Array<{
      eventType: string;
      action: string;
      triggeredBy?: string | null;
      reason?: string | null;
      outputPreview?: string | null;
      createdAt?: string | null;
    }>;
  }

  interface Window {
    desktopEnv?: {
      platform: string;
      isDesktop: boolean;
    };
    desktopAuth?: {
      startLogin: () => Promise<{ ok: boolean }>;
      getToken: () => Promise<{ accessToken: string; accountId?: string | null } | null>;
      checkStatus: () => Promise<{ status: 'connected' | 'disconnected' | 'expired' }>;
      logout: () => Promise<{ ok: boolean }>;
    };
    desktopMcp?: {
      connect: (mcpUrl: string) => Promise<{ ok: boolean; tools: McpTool[]; error?: string }>;
      connectMany: (
        servers: DesktopMcpServerConfig[]
      ) => Promise<{ ok: boolean; tools: McpTool[]; connectedServers: DesktopMcpConnectServerResult[]; error?: string }>;
      listTools: () => Promise<{ tools: McpTool[]; error?: string }>;
      callTool: (name: string, args: Record<string, unknown>, serverId?: string | null) => Promise<McpToolResult>;
      status: () => Promise<{ connected: boolean; servers: DesktopMcpServerStatus[] }>;
      playwrightStart: (config?: { port?: number; host?: string; cdpEndpoint?: string }) => Promise<PlaywrightMcpStatus>;
      playwrightStop: () => Promise<PlaywrightMcpStatus>;
      playwrightStatus: () => Promise<PlaywrightMcpStatus>;
    };
    desktopMysql?: {
      status: () => Promise<DesktopMysqlStatus>;
      getReviewBundle: (
        options?: {
          strategyCodePrefix?: string;
          targetDate?: string;
          lookbackDays?: number;
          recentLimit?: number;
        }
      ) => Promise<{ ok: boolean; bundle?: DesktopAiReviewBundle; error?: string }>;
    };
    desktopZai?: {
      setApiKey: (apiKey: string) => Promise<{ ok: boolean; error?: string }>;
      clearApiKey: () => Promise<{ ok: boolean }>;
      checkStatus: () => Promise<{ status: 'connected' | 'disconnected' | 'error' }>;
      chatCompletions: (payload: DesktopZaiChatPayload) => Promise<DesktopZaiChatResponse>;
    };
  }
}

export {};
