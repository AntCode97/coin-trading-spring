declare global {
  interface McpTool {
    name: string;
    description?: string;
    inputSchema?: {
      type: string;
      properties?: Record<string, { type: string; description?: string }>;
      required?: string[];
    };
  }

  interface McpToolResult {
    content: Array<{ type: string; text?: string }>;
    isError?: boolean;
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
      listTools: () => Promise<{ tools: McpTool[]; error?: string }>;
      callTool: (name: string, args: Record<string, unknown>) => Promise<McpToolResult>;
      status: () => Promise<{ connected: boolean }>;
    };
  }
}

export {};
