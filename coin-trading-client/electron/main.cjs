const { app, BrowserWindow, shell, ipcMain } = require('electron');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { runOAuthFlow, refreshAccessToken } = require('./oauth/auth-flow.cjs');
const { saveToken, loadToken, deleteToken, isTokenExpired } = require('./oauth/token-store.cjs');

let staticServer = null;

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
    return { accessToken: token.accessToken };
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
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
