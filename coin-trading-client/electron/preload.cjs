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
  listTools: () => ipcRenderer.invoke('mcp:list-tools'),
  callTool: (name, args) => ipcRenderer.invoke('mcp:call-tool', name, args),
  status: () => ipcRenderer.invoke('mcp:status'),
});
