import { app, BrowserWindow, nativeImage, Menu, ipcMain } from 'electron';
import { join, delimiter } from 'path';
import { spawn, ChildProcess } from 'child_process';
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'fs';
import { networkInterfaces, homedir } from 'os';
import { randomUUID } from 'crypto';

let mainWindow: BrowserWindow | null = null;
let backendProcess: ChildProcess | null = null;
let sandboxProcess: ChildProcess | null = null;

const CONFIG_DIR = join(homedir(), '.netsentinel');
const CONFIG_FILE = join(CONFIG_DIR, 'config.env');
const HEALTH_URL = 'http://127.0.0.1:8080/api/health';

interface DepResult {
  id: string;
  label: string;
  ok: boolean;
  detail: string;
  technicalDetail?: string;
}

interface LocalNetwork {
  name: string;
  ip: string;
  subnet: string;
}

function backendJarPath(): string {
  return app.isPackaged
    ? join(process.resourcesPath, 'backend.jar')
    : join(__dirname, '..', '..', 'backend', 'target', 'backend-0.1.0.jar');
}

function javaPath(): string {
  return app.isPackaged ? join(process.resourcesPath, 'jre', 'bin', 'java.exe') : 'java';
}

function sandboxPath(): string {
  return app.isPackaged
    ? join(process.resourcesPath, 'sandbox.exe')
    : join(__dirname, '..', '..', 'sandbox', 'target', 'release', 'sandbox.exe');
}

function toolsPath(): string {
  return app.isPackaged ? join(process.resourcesPath, 'tools') : join(__dirname, '..', '..', 'tools');
}

function readConfig(): Record<string, string> {
  const config: Record<string, string> = {};
  try {
    const raw = readFileSync(CONFIG_FILE, 'utf8');
    for (const line of raw.split(/\r?\n/)) {
      const match = line.match(/^([A-Za-z0-9_]+)=(.*)$/);
      if (match) config[match[1]] = match[2];
    }
  } catch {
    return config;
  }
  return config;
}

function writeConfig(config: Record<string, string>): void {
  mkdirSync(CONFIG_DIR, { recursive: true });
  const body = Object.entries(config).map(([k, v]) => `${k}=${v}`).join('\n');
  writeFileSync(CONFIG_FILE, body ? `${body}\n` : '');
}

function setConfigValue(key: string, value: string): void {
  const config = readConfig();
  config[key] = value;
  writeConfig(config);
}

function ensureConfig(): void {
  const config = readConfig();
  if (!config.SANDBOX_AUTH_TOKEN) {
    config.SANDBOX_AUTH_TOKEN = randomUUID().replace(/-/g, '');
    writeConfig(config);
  }
}

function nativeResourcesReady(): boolean {
  if (!app.isPackaged) return true;
  return existsSync(backendJarPath()) && existsSync(javaPath()) && existsSync(sandboxPath());
}

async function backendHealthOk(timeoutMs = 3000): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(HEALTH_URL, { signal: controller.signal });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function waitForBackend(timeoutMs = 30000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await backendHealthOk(2000)) return true;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  return false;
}

async function runQuickCheck(): Promise<{ ready: boolean }> {
  return { ready: nativeResourcesReady() };
}

async function runDepsCheck(): Promise<DepResult[]> {
  const results: DepResult[] = [];

  const jar = backendJarPath();
  const jarOk = existsSync(jar);
  results.push({ id: 'backend', label: 'Application service', ok: jarOk, detail: jarOk ? 'Ready' : 'Pending', technicalDetail: jar });

  const sandbox = sandboxPath();
  const sandboxOk = existsSync(sandbox);
  results.push({ id: 'sandbox', label: 'Secure scan engine', ok: sandboxOk, detail: sandboxOk ? 'Ready' : 'Pending', technicalDetail: sandbox });

  const apiOk = await backendHealthOk();
  results.push({ id: 'api', label: 'Local application', ok: apiOk, detail: apiOk ? 'Ready' : 'Pending', technicalDetail: `GET ${HEALTH_URL}` });

  return results;
}

function netmaskToPrefix(netmask: string): number {
  return netmask.split('.').reduce((acc, octet) => {
    let n = parseInt(octet);
    let count = 0;
    while (n) { count += n & 1; n >>= 1; }
    return acc + count;
  }, 0);
}

function calculateNetworkAddress(ip: string, netmask: string): string {
  const ipParts = ip.split('.').map(Number);
  const maskParts = netmask.split('.').map(Number);
  return ipParts.map((part, i) => part & maskParts[i]).join('.');
}

function getLocalNetworks(): LocalNetwork[] {
  const nets = networkInterfaces();
  const result: LocalNetwork[] = [];
  const skipWords = ['loopback', 'wsl', 'hyper-v', 'virtual', 'tunnel', 'vmware', 'vethernet', 'pseudo'];

  for (const [name, addrs] of Object.entries(nets)) {
    if (!addrs) continue;
    const lower = name.toLowerCase();
    if (skipWords.some(w => lower.includes(w))) continue;

    for (const addr of addrs) {
      if (addr.family !== 'IPv4' || addr.internal) continue;
      if (addr.address.startsWith('169.254')) continue;

      const prefix = netmaskToPrefix(addr.netmask);
      const network = calculateNetworkAddress(addr.address, addr.netmask);
      result.push({ name, ip: addr.address, subnet: `${network}/${prefix}` });
    }
  }

  return result;
}

function setupIpcHandlers(): void {
  ipcMain.handle('deps:quick', () => runQuickCheck());
  ipcMain.handle('deps:check', () => runDepsCheck());

  ipcMain.handle('deps:status', () => {
    const statusFile = join(app.getPath('appData'), 'NetSentinel', 'setup-status.txt');
    try { return readFileSync(statusFile, 'utf8').trim(); } catch { return 'UNKNOWN'; }
  });

  ipcMain.handle('deps:detail', () => {
    const detailFile = join(app.getPath('appData'), 'NetSentinel', 'setup-detail.txt');
    try { return readFileSync(detailFile, 'utf8').trim(); } catch { return ''; }
  });

  ipcMain.handle('deps:install', () => {
    const setupScript = app.isPackaged
      ? join(process.resourcesPath, 'installer', 'setup.ps1')
      : join(__dirname, '..', 'installer', 'setup.ps1');
    const installDir = app.isPackaged
      ? join(process.resourcesPath, '..')
      : join(__dirname, '..', '..');

    const s = setupScript.replace(/\\/g, '\\\\');
    const d = installDir.replace(/\\/g, '\\\\');
    spawn('powershell.exe', [
      '-Command',
      `Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList '-ExecutionPolicy Bypass -File "${s}" "${d}"'`
    ], { detached: true, stdio: 'ignore' }).unref();
  });

  ipcMain.handle('system:reboot', () => {
    spawn('shutdown.exe', ['/r', '/t', '10'], { stdio: 'ignore' });
  });

  ipcMain.handle('window:expand', () => {
    if (!mainWindow) return;
    mainWindow.setResizable(true);
    mainWindow.setMinimumSize(900, 560);
    mainWindow.setSize(1200, 680);
    mainWindow.center();
  });

  ipcMain.handle('window:minimize', () => mainWindow?.minimize());
  ipcMain.handle('window:close', () => mainWindow?.close());

  ipcMain.handle('backend:start', async () => {
    startBackend();
    return waitForBackend();
  });

  ipcMain.handle('network:local', () => getLocalNetworks());

  ipcMain.handle('config:saveNvdKey', (_evt, rawKey: string) => {
    const key = String(rawKey).replace(/[^a-zA-Z0-9\-]/g, '');
    if (!key) return;
    setConfigValue('NVD_API_KEY', key);
  });
}

function startSandbox(token: string): void {
  const bin = sandboxPath();
  if (!existsSync(bin)) return;
  if (sandboxProcess && sandboxProcess.exitCode === null) return;

  const tools = toolsPath();
  const pathValue = existsSync(tools)
    ? `${tools}${delimiter}${process.env.PATH ?? ''}`
    : (process.env.PATH ?? '');

  sandboxProcess = spawn(bin, [], {
    stdio: 'ignore',
    detached: false,
    env: { ...process.env, SANDBOX_AUTH_TOKEN: token, PATH: pathValue },
  });

  sandboxProcess.on('error', () => {});
}

function startBackend(): void {
  const jar = backendJarPath();
  if (!existsSync(jar)) return;

  const config = readConfig();
  const token = config.SANDBOX_AUTH_TOKEN ?? '';

  if (!backendProcess || backendProcess.exitCode !== null) {
    backendProcess = spawn(javaPath(), ['-jar', jar], {
      stdio: 'ignore',
      detached: false,
      env: { ...process.env, ...config },
    });
    backendProcess.on('error', () => {});
  }

  startSandbox(token);
}

function resolveIcon(): Electron.NativeImage {
  const candidates = app.isPackaged
    ? [
        join(process.resourcesPath, 'icon.ico'),
        join(process.resourcesPath, 'icon.png'),
        join(__dirname, '../public/icon.ico'),
        join(__dirname, '../public/icon.png'),
      ]
    : [
        join(__dirname, '../public/icon.ico'),
        join(__dirname, '../public/icon.png'),
      ];
  for (const p of candidates) {
    if (existsSync(p)) return nativeImage.createFromPath(p);
  }
  return nativeImage.createEmpty();
}

async function createWindow(): Promise<void> {
  const icon = resolveIcon();

  Menu.setApplicationMenu(null);

  mainWindow = new BrowserWindow({
    width: 460,
    height: 640,
    minWidth: 460,
    minHeight: 640,
    resizable: false,
    frame: false,
    transparent: false,
    backgroundColor: '#1e1e1e',
    hasShadow: true,
    autoHideMenuBar: true,
    icon,
    show: false,
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
    },
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow?.setMenuBarVisibility(false);
    mainWindow?.show();
  });

  mainWindow.webContents.on('before-input-event', (_event, input) => {
    if (input.type !== 'keyDown') return;
    if (input.key === 'F12') {
      mainWindow?.webContents.openDevTools({ mode: 'detach' });
      return;
    }
    if (!input.control) return;
    const wc = mainWindow?.webContents;
    if (!wc) return;
    if (input.key === '=' || input.key === '+') {
      wc.setZoomFactor(Math.min(wc.getZoomFactor() + 0.1, 2.5));
    } else if (input.key === '-') {
      wc.setZoomFactor(Math.max(wc.getZoomFactor() - 0.1, 0.5));
    } else if (input.key === '0') {
      wc.setZoomFactor(1.0);
    }
  });

  const distIndex = join(__dirname, '../dist/frontend/browser/index.html');
  if (app.isPackaged && existsSync(distIndex)) {
    mainWindow.loadFile(distIndex);
  } else {
    mainWindow.loadURL('http://localhost:4200');
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

setupIpcHandlers();

app.whenReady().then(async () => {
  app.setAppUserModelId('com.netsentinel.app');
  ensureConfig();
  startBackend();
  await createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (backendProcess) {
    backendProcess.kill();
    backendProcess = null;
  }
  if (sandboxProcess) {
    sandboxProcess.kill();
    sandboxProcess = null;
  }
  if (process.platform !== 'darwin') app.quit();
});
