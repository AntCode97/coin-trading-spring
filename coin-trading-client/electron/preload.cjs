const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktopEnv', {
  platform: process.platform,
  isDesktop: true,
});

contextBridge.exposeInMainWorld('desktopAuth', {
  startLogin: () => ipcRenderer.invoke('oauth:start-login'),
  getToken: () => ipcRenderer.invoke('oauth:get-token'),
  checkStatus: () => ipcRenderer.invoke('oauth:check-status'),
  logout: () => ipcRenderer.invoke('oauth:logout'),
});

contextBridge.exposeInMainWorld('desktopMcp', {
  connect: (mcpUrl) => ipcRenderer.invoke('mcp:connect', mcpUrl),
  connectMany: (servers) => ipcRenderer.invoke('mcp:connect-many', servers),
  listTools: () => ipcRenderer.invoke('mcp:list-tools'),
  callTool: (name, args, serverId) => ipcRenderer.invoke('mcp:call-tool', name, args, serverId),
  status: () => ipcRenderer.invoke('mcp:status'),
  playwrightStart: (config) => ipcRenderer.invoke('playwright:start', config),
  playwrightStop: () => ipcRenderer.invoke('playwright:stop'),
  playwrightStatus: () => ipcRenderer.invoke('playwright:status'),
});

contextBridge.exposeInMainWorld('desktopZai', {
  setApiKey: (apiKey) => ipcRenderer.invoke('zai:set-api-key', apiKey),
  clearApiKey: () => ipcRenderer.invoke('zai:clear-api-key'),
  checkStatus: () => ipcRenderer.invoke('zai:check-status'),
  chatCompletions: (payload) => ipcRenderer.invoke('zai:chat-completions', payload),
});
