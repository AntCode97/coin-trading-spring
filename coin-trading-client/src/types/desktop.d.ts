declare global {
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
  }
}

export {};
