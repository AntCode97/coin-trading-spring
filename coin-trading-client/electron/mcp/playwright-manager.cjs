const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

function dedupe(items) {
  return Array.from(new Set(items.filter(Boolean)));
}

function splitPathList(value) {
  if (!value || typeof value !== 'string') return [];
  return value.split(path.delimiter).map((item) => item.trim()).filter(Boolean);
}

function buildLaunchPath() {
  const base = splitPathList(process.env.PATH);
  if (process.platform === 'win32') {
    const windows = [
      process.env.APPDATA && path.join(process.env.APPDATA, 'npm'),
      'C:\\Program Files\\nodejs',
      'C:\\Program Files (x86)\\nodejs',
    ];
    return dedupe([...base, ...windows]).join(path.delimiter);
  }
  const unix = [
    '/opt/homebrew/bin',
    '/usr/local/bin',
    '/usr/bin',
    '/bin',
    '/usr/sbin',
    '/sbin',
  ];
  return dedupe([...base, ...unix]).join(path.delimiter);
}

function existsExecutable(filePath) {
  if (!filePath) return false;
  try {
    fs.accessSync(filePath, fs.constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function resolveCommand(commandName, launchPath) {
  if (!commandName) return null;
  if (path.isAbsolute(commandName)) {
    return existsExecutable(commandName) ? commandName : null;
  }
  const dirs = splitPathList(launchPath);
  for (const dir of dirs) {
    const candidate = path.join(dir, commandName);
    if (existsExecutable(candidate)) {
      return candidate;
    }
  }
  return null;
}

function resolveRunner(launchPath) {
  const npxCandidate = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const npmCandidate = process.platform === 'win32' ? 'npm.cmd' : 'npm';

  const npx = resolveCommand(npxCandidate, launchPath);
  if (npx) {
    return {
      command: npx,
      prefixArgs: ['@playwright/mcp@latest'],
      label: 'npx',
    };
  }

  const npm = resolveCommand(npmCandidate, launchPath);
  if (npm) {
    return {
      command: npm,
      prefixArgs: ['exec', '--yes', '@playwright/mcp@latest', '--'],
      label: 'npm exec',
    };
  }

  return null;
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
    const launchPath = buildLaunchPath();
    const runner = resolveRunner(launchPath);

    if (!runner) {
      this.status = 'error';
      this.lastError = 'npx/npm 실행 파일을 찾지 못했습니다. Node.js 설치 또는 PATH 설정을 확인하세요.';
      return this.getStatus();
    }

    const args = [
      ...runner.prefixArgs,
      '--port',
      String(port),
      '--host',
      host,
      '--cdp-endpoint',
      cdpEndpoint,
    ];

    this.status = 'starting';
    this.lastError = null;

    const child = spawn(runner.command, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        ...process.env,
        PATH: launchPath,
      },
    });

    this.process = child;
    this.port = port;
    this.url = `http://${host}:${port}/mcp`;
    this.cdpEndpoint = cdpEndpoint;

    child.stdout.on('data', () => {
      if (this.status === 'starting') {
        this.status = 'running';
        this.lastError = null;
      }
    });

    child.stderr.on('data', (chunk) => {
      const text = String(chunk || '').trim();
      if (!text) {
        return;
      }
      if (this.status === 'starting' && /listening on http/i.test(text)) {
        this.status = 'running';
        this.lastError = null;
        return;
      }
      if (/(error|enoent|eaddrinuse|failed|exception)/i.test(text)) {
        this.lastError = text;
      }
    });

    child.on('error', (error) => {
      this.status = 'error';
      const detail = error instanceof Error ? error.message : 'playwright-mcp 실행 실패';
      this.lastError = `${runner.label} 실행 실패: ${detail}`;
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
