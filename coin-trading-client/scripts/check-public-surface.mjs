import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = process.cwd();

function read(relativePath) {
  return readFileSync(resolve(root, relativePath), 'utf8');
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

const appTsx = read('src/App.tsx');
const dashboardTsx = read('src/components/Dashboard.tsx');

assert(
  !appTsx.includes("import ManualTraderWorkspace from './components/ManualTraderWorkspace'"),
  'Public app must not import ManualTraderWorkspace directly.'
);

assert(
  !dashboardTsx.includes('manualClose('),
  'Public Dashboard must not invoke manualClose API.'
);

assert(
  !dashboardTsx.includes('매도하기'),
  'Public Dashboard must not render manual sell CTA.'
);

process.stdout.write('Public surface guard passed.\n');
