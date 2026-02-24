const { spawn } = require('node:child_process');

function resolveNpxCommand() {
  return process.platform === 'win32' ? 'npx.cmd' : 'npx';
}

class PlaywrightMcpManager {
  constructor() {
    this.process = null;
    this.status = 'stopped';
    this.port = null;
    this.url = null;
    this.cdpEndpoint = null;
    this.lastError = null;
  }

  async start(config = {}) {
    if (this.process && this.status === 'running') {
      return this.getStatus();
    }

    const port = Number(config.port) > 0 ? Number(config.port) : 8931;
    const host = typeof config.host === 'string' && config.host.trim() ? config.host.trim() : '127.0.0.1';
    const cdpEndpoint = typeof config.cdpEndpoint === 'string' && config.cdpEndpoint.trim()
      ? config.cdpEndpoint.trim()
      : 'http://127.0.0.1:9333';

    const npx = resolveNpxCommand();
    const args = [
      '@playwright/mcp@latest',
      '--port',
      String(port),
      '--host',
      host,
      '--cdp-endpoint',
      cdpEndpoint,
    ];

    this.status = 'starting';
    this.lastError = null;

    const child = spawn(npx, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
    });

    this.process = child;
    this.port = port;
    this.url = `http://${host}:${port}/mcp`;
    this.cdpEndpoint = cdpEndpoint;

    child.stdout.on('data', () => {
      if (this.status === 'starting') {
        this.status = 'running';
      }
    });

    child.stderr.on('data', (chunk) => {
      const text = String(chunk || '').trim();
      if (text) {
        this.lastError = text;
      }
    });

    child.on('error', (error) => {
      this.status = 'error';
      this.lastError = error instanceof Error ? error.message : 'playwright-mcp 실행 실패';
    });

    child.on('exit', (code) => {
      if (this.status !== 'stopped') {
        this.status = code === 0 ? 'stopped' : 'error';
      }
      this.process = null;
    });

    return this.getStatus();
  }

  async stop() {
    if (!this.process) {
      this.status = 'stopped';
      return this.getStatus();
    }
    this.status = 'stopped';
    this.process.kill('SIGTERM');
    this.process = null;
    return this.getStatus();
  }

  getStatus() {
    return {
      status: this.status,
      running: this.status === 'running' || this.status === 'starting',
      port: this.port,
      url: this.url,
      cdpEndpoint: this.cdpEndpoint,
      lastError: this.lastError,
    };
  }
}

module.exports = { PlaywrightMcpManager };
