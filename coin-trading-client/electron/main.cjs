const { app, BrowserWindow, shell, ipcMain } = require('electron');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { runOAuthFlow, refreshAccessToken } = require('./oauth/auth-flow.cjs');
const { saveToken, loadToken, deleteToken, isTokenExpired } = require('./oauth/token-store.cjs');
const { saveZaiApiKey, loadZaiApiKey, clearZaiApiKey } = require('./llm/zai-store.cjs');
const { McpHub } = require('./mcp/mcp-hub.cjs');
const { PlaywrightMcpManager } = require('./mcp/playwright-manager.cjs');

let staticServer = null;
const mcpHub = new McpHub();
const playwrightManager = new PlaywrightMcpManager();
const ELECTRON_CDP_PORT = Number(process.env.ELECTRON_CDP_PORT || 9333);
const ELECTRON_CDP_HOST = '127.0.0.1';
const ZAI_CHAT_ENDPOINTS = {
  coding: 'https://api.z.ai/api/coding/paas/v4/chat/completions',
  general: 'https://api.z.ai/api/paas/v4/chat/completions',
};

// Playwright MCP가 Electron 화면을 직접 자동화할 수 있도록 CDP 포트를 연다.
app.commandLine.appendSwitch('remote-debugging-port', String(ELECTRON_CDP_PORT));
app.commandLine.appendSwitch('remote-debugging-address', ELECTRON_CDP_HOST);

function registerOAuthIpc() {
  ipcMain.handle('oauth:start-login', async () => {
    const tokenData = await runOAuthFlow((url) => shell.openExternal(url));
    saveToken(tokenData);
    return { ok: true };
  });

  ipcMain.handle('oauth:get-token', async () => {
    let token = loadToken();
    if (!token) return null;
    if (isTokenExpired(token)) {
      if (!token.refreshToken) return null;
      try {
        const refreshed = await refreshAccessToken(token.refreshToken);
        token = saveToken(refreshed);
      } catch {
        return null;
      }
    }
    return { accessToken: token.accessToken, accountId: token.accountId || null };
  });

  ipcMain.handle('oauth:check-status', async () => {
    const token = loadToken();
    if (!token) return { status: 'disconnected' };
    if (isTokenExpired(token)) return { status: 'expired' };
    return { status: 'connected' };
  });

  ipcMain.handle('oauth:logout', async () => {
    deleteToken();
    return { ok: true };
  });
}

function registerMcpIpc() {
  ipcMain.handle('mcp:connect', async (_event, mcpUrl) => {
    try {
      const result = await mcpHub.connectMany([{ serverId: 'trading', url: mcpUrl }]);
      return { ok: true, tools: result.tools || [] };
    } catch (e) {
      return { ok: false, error: e.message, tools: [] };
    }
  });

  ipcMain.handle('mcp:connect-many', async (_event, servers) => {
    try {
      const result = await mcpHub.connectMany(Array.isArray(servers) ? servers : []);
      return result;
    } catch (e) {
      return { ok: false, tools: [], connectedServers: [], error: e.message };
    }
  });

  ipcMain.handle('mcp:list-tools', async () => {
    try {
      return mcpHub.listTools();
    } catch (e) {
      return { tools: [], error: e.message };
    }
  });

  ipcMain.handle('mcp:call-tool', async (_event, name, args, serverId) => {
    try {
      const result = await mcpHub.callTool(name, args, serverId ?? null);
      return result;
    } catch (e) {
      return { content: [{ type: 'text', text: e.message }], isError: true };
    }
  });

  ipcMain.handle('mcp:status', async () => {
    return mcpHub.status();
  });

  ipcMain.handle('playwright:start', async (_event, config) => {
    const cdpEndpoint = `http://${ELECTRON_CDP_HOST}:${ELECTRON_CDP_PORT}`;
    return playwrightManager.start({
      ...(config || {}),
      cdpEndpoint,
    });
  });

  ipcMain.handle('playwright:stop', async () => {
    return playwrightManager.stop();
  });

  ipcMain.handle('playwright:status', async () => {
    return playwrightManager.getStatus();
  });
}

function extractTextFromZaiContent(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .map((item) => {
      if (typeof item === 'string') return item;
      if (item && typeof item.text === 'string') return item.text;
      return '';
    })
    .filter((item) => item.trim().length > 0)
    .join('\n');
}

function normalizeZaiToolCalls(toolCalls) {
  if (!Array.isArray(toolCalls)) return [];
  return toolCalls
    .map((toolCall) => {
      const id = typeof toolCall?.id === 'string' ? toolCall.id : '';
      const name = typeof toolCall?.function?.name === 'string' ? toolCall.function.name : '';
      const args =
        typeof toolCall?.function?.arguments === 'string'
          ? toolCall.function.arguments
          : JSON.stringify(toolCall?.function?.arguments || {});
      if (!name) return null;
      return { id, name, args };
    })
    .filter(Boolean);
}

function normalizeZaiUsage(usage) {
  if (!usage || typeof usage !== 'object') return undefined;
  const promptTokens = Number(usage.prompt_tokens);
  const completionTokens = Number(usage.completion_tokens);
  const totalTokens = Number(usage.total_tokens);
  return {
    promptTokens: Number.isFinite(promptTokens) ? promptTokens : 0,
    completionTokens: Number.isFinite(completionTokens) ? completionTokens : 0,
    totalTokens: Number.isFinite(totalTokens) ? totalTokens : 0,
  };
}

function registerZaiIpc() {
  ipcMain.handle('zai:set-api-key', async (_event, apiKey) => {
    try {
      return saveZaiApiKey(apiKey);
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'z.ai API Key 저장 실패' };
    }
  });

  ipcMain.handle('zai:clear-api-key', async () => {
    return clearZaiApiKey();
  });

  ipcMain.handle('zai:check-status', async () => {
    try {
      const stored = loadZaiApiKey();
      if (!stored?.apiKey) {
        return { status: 'disconnected' };
      }
      return { status: 'connected' };
    } catch {
      return { status: 'error' };
    }
  });

  ipcMain.handle('zai:chat-completions', async (_event, payload) => {
    try {
      const stored = loadZaiApiKey();
      if (!stored?.apiKey) {
        return { ok: false, error: 'z.ai API Key가 등록되지 않았습니다.' };
      }

      const endpointMode = payload?.endpointMode === 'general' ? 'general' : 'coding';
      const endpoint = ZAI_CHAT_ENDPOINTS[endpointMode];
      const messages = Array.isArray(payload?.messages) ? payload.messages : [];

      if (!payload?.model || typeof payload.model !== 'string') {
        return { ok: false, error: 'z.ai model이 비어 있습니다.' };
      }
      if (messages.length === 0) {
        return { ok: false, error: 'z.ai messages가 비어 있습니다.' };
      }

      const body = {
        model: payload.model,
        messages,
        tools: Array.isArray(payload?.tools) ? payload.tools : undefined,
        tool_choice: payload?.toolChoice || undefined,
        stream: false,
      };

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${stored.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      });

      const rawText = await response.text();
      let parsed;
      try {
        parsed = JSON.parse(rawText);
      } catch {
        parsed = null;
      }

      if (!response.ok) {
        const detail =
          parsed?.error?.message
          || parsed?.message
          || rawText
          || '알 수 없는 오류';
        return {
          ok: false,
          error: `z.ai API 오류 (${response.status}): ${detail}`,
        };
      }

      const message = parsed?.choices?.[0]?.message;
      const text = extractTextFromZaiContent(message?.content).trim();
      const toolCalls = normalizeZaiToolCalls(message?.tool_calls);
      const usage = normalizeZaiUsage(parsed?.usage);

      return {
        ok: true,
        text,
        toolCalls,
        usage,
      };
    } catch (error) {
      return {
        ok: false,
        error: error instanceof Error ? error.message : 'z.ai chat 호출 실패',
      };
    }
  });
}

function contentType(filePath) {
  if (filePath.endsWith('.html')) return 'text/html; charset=utf-8';
  if (filePath.endsWith('.js')) return 'application/javascript; charset=utf-8';
  if (filePath.endsWith('.css')) return 'text/css; charset=utf-8';
  if (filePath.endsWith('.svg')) return 'image/svg+xml';
  if (filePath.endsWith('.json')) return 'application/json; charset=utf-8';
  if (filePath.endsWith('.png')) return 'image/png';
  if (filePath.endsWith('.jpg') || filePath.endsWith('.jpeg')) return 'image/jpeg';
  if (filePath.endsWith('.woff2')) return 'font/woff2';
  if (filePath.endsWith('.woff')) return 'font/woff';
  return 'application/octet-stream';
}

function startStaticServer() {
  const distDir = path.join(__dirname, '..', 'dist');

  staticServer = http.createServer((req, res) => {
    const rawUrl = req.url || '/';
    const cleanUrl = rawUrl.split('?')[0];
    const relativePath = cleanUrl === '/' ? '/index.html' : cleanUrl;
    const targetPath = path.join(distDir, relativePath);

    const safePath = targetPath.startsWith(distDir) ? targetPath : path.join(distDir, 'index.html');
    const finalPath = fs.existsSync(safePath) && fs.statSync(safePath).isFile()
      ? safePath
      : path.join(distDir, 'index.html');

    const fileBuffer = fs.readFileSync(finalPath);
    res.writeHead(200, {
      'Content-Type': contentType(finalPath),
      'Cache-Control': 'no-cache',
    });
    res.end(fileBuffer);
  });

  return new Promise((resolve, reject) => {
    staticServer.once('error', reject);
    staticServer.listen(0, '127.0.0.1', () => {
      const address = staticServer.address();
      if (!address || typeof address !== 'object') {
        reject(new Error('Failed to bind local desktop static server'));
        return;
      }
      resolve(`http://127.0.0.1:${address.port}`);
    });
  });
}

async function createWindow() {
  const win = new BrowserWindow({
    width: 1680,
    height: 1020,
    minWidth: 1320,
    minHeight: 860,
    title: 'Coin Trading Workspace (Mac)',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false,
    },
  });

  const startUrl = process.env.ELECTRON_START_URL;
  if (startUrl && startUrl.startsWith('http')) {
    win.loadURL(startUrl);
  } else {
    const localUrl = await startStaticServer();
    await win.loadURL(localUrl);
  }

  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });
}

app.whenReady().then(() => {
  registerOAuthIpc();
  registerZaiIpc();
  registerMcpIpc();
  void createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      void createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (staticServer) {
    staticServer.close();
    staticServer = null;
  }
  void playwrightManager.stop();
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
