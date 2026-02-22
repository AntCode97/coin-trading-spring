const { contextBridge } = require('electron');

contextBridge.exposeInMainWorld('desktopEnv', {
  platform: process.platform,
  isDesktop: true,
});
