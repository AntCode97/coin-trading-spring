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
