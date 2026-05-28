import { app, BrowserWindow, nativeImage, Menu, ipcMain } from 'electron';
import { join } from 'path';
import { spawn, ChildProcess } from 'child_process';
import { existsSync, readFileSync } from 'fs';
import { networkInterfaces } from 'os';
import { randomUUID } from 'crypto';

let mainWindow: BrowserWindow | null = null;
let backendProcess: ChildProcess | null = null;

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

function spawnOk(cmd: string, args: string[], timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const proc = spawn(cmd, args, { stdio: 'ignore' });
    const t = setTimeout(() => { proc.kill(); resolve(false); }, timeoutMs);
    proc.on('error', () => { clearTimeout(t); resolve(false); });
    proc.on('close', (code) => { clearTimeout(t); resolve(code === 0); });
  });
}

function spawnCapture(cmd: string, args: string[], timeoutMs: number): Promise<Buffer> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    const proc = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'ignore'] });
    const t = setTimeout(() => { proc.kill(); resolve(Buffer.alloc(0)); }, timeoutMs);
    proc.stdout!.on('data', (d: Buffer) => chunks.push(d));
    proc.on('error', () => { clearTimeout(t); resolve(Buffer.alloc(0)); });
    proc.on('close', () => { clearTimeout(t); resolve(Buffer.concat(chunks)); });
  });
}

async function runQuickCheck(): Promise<{ wsl: boolean; kali: boolean }> {
  const wsl = await spawnOk('wsl', ['--status'], 4000);
  if (!wsl) return { wsl: false, kali: false };
  const buf = await spawnCapture('wsl', ['--list', '--quiet'], 4000);
  const kali = buf.toString('utf16le').toLowerCase().includes('kali');
  if (!kali) return { wsl, kali: false };
  const ready = await spawnOk('wsl', [
    '-d', 'kali-linux', '--', 'bash', '-c', 'test -f "$HOME/.netsentinel/start.sh"'
  ], 5000);
  return { wsl, kali: ready };
}

async function backendHealthOk(timeoutMs = 3000): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch('http://localhost:8080/api/health', { signal: controller.signal });
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

async function runDepsCheck(): Promise<DepResult[]> {
  const results: DepResult[] = [];

  const wslOk = await spawnOk('wsl', ['--status'], 5000);
  results.push({ id: 'wsl', label: 'Funciones locales de Windows', ok: wslOk, detail: wslOk ? 'Listo' : 'Pendiente', technicalDetail: 'wsl --status' });
  if (!wslOk) return results;

  const kaliBuf = await spawnCapture('wsl', ['--list', '--quiet'], 5000);
  const kaliOk = kaliBuf.toString('utf16le').toLowerCase().includes('kali');
  results.push({ id: 'kali', label: 'Motor local de analisis', ok: kaliOk, detail: kaliOk ? 'Listo' : 'Pendiente', technicalDetail: 'wsl --list --quiet' });
  if (!kaliOk) return results;

  const jarOk = await spawnOk('wsl', ['-d', 'kali-linux', '--', 'bash', '-c', 'test -f $HOME/.netsentinel/backend.jar'], 10000);
  results.push({ id: 'backend', label: 'Servicio interno', ok: jarOk, detail: jarOk ? 'Listo' : 'Pendiente', technicalDetail: '~/.netsentinel/backend.jar' });

  const sandboxOk = await spawnOk('wsl', ['-d', 'kali-linux', '--', 'bash', '-c', 'test -x $HOME/.netsentinel/sandbox'], 10000);
  results.push({ id: 'sandbox', label: 'Motor seguro de escaneo', ok: sandboxOk, detail: sandboxOk ? 'Listo' : 'Pendiente', technicalDetail: '~/.netsentinel/sandbox' });

  const pgBuf = await spawnCapture('wsl', [
    '-d', 'kali-linux', '--', 'bash', '-c',
    'sudo service postgresql start 2>/dev/null; pg_isready -h 127.0.0.1 -U netsentinel -d netsentinel 2>&1'
  ], 20000);
  const pgOk = pgBuf.toString('utf8').includes('accepting connections');
  results.push({ id: 'postgresql', label: 'Almacenamiento local', ok: pgOk, detail: pgOk ? 'Listo' : 'Pendiente', technicalDetail: pgBuf.toString('utf8').trim() || 'pg_isready' });

  const apiOk = await backendHealthOk();
  results.push({ id: 'api', label: 'Aplicacion local', ok: apiOk, detail: apiOk ? 'Listo' : 'Pendiente', technicalDetail: 'GET http://localhost:8080/api/health' });

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
    return new Promise<void>((resolve) => {
      const proc = spawn('wsl', [
        '-d', 'kali-linux', '--', 'bash', '-c',
        `mkdir -p ~/.netsentinel && touch ~/.netsentinel/config.env && sed -i '/^NVD_API_KEY=/d' ~/.netsentinel/config.env && printf 'NVD_API_KEY=%s\\n' '${key}' >> ~/.netsentinel/config.env`
      ], { stdio: 'ignore' });
      proc.on('close', () => resolve());
      proc.on('error', () => resolve());
    });
  });
}

function ensureSandboxToken(): Promise<void> {
  const token = randomUUID().replace(/-/g, '');
  const script = `mkdir -p ~/.netsentinel && touch ~/.netsentinel/config.env && grep -q '^SANDBOX_AUTH_TOKEN=' ~/.netsentinel/config.env || printf 'SANDBOX_AUTH_TOKEN=%s\\n' '${token}' >> ~/.netsentinel/config.env`;
  return new Promise<void>((resolve) => {
    const proc = spawn('wsl', ['-d', 'kali-linux', '--', 'bash', '-c', script], { stdio: 'ignore' });
    proc.on('close', () => resolve());
    proc.on('error', () => resolve());
  });
}

function startBackend(): void {
  if (!app.isPackaged) return;
  if (backendProcess && backendProcess.exitCode === null) return;

  backendProcess = spawn('wsl', [
    '-d', 'kali-linux',
    '--',
    'bash', '-c', 'bash "$HOME/.netsentinel/start.sh"'
  ], {
    stdio: 'ignore',
    detached: false,
  });

  backendProcess.on('error', () => {});
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
  await ensureSandboxToken();
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
  if (process.platform !== 'darwin') app.quit();
});
