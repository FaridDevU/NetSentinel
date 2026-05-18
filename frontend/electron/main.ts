import { app, BrowserWindow, nativeImage, Menu, ipcMain } from 'electron';
import { join } from 'path';
import { spawn, ChildProcess } from 'child_process';
import { existsSync, readFileSync } from 'fs';
import * as http from 'http';

let mainWindow: BrowserWindow | null = null;
let backendProcess: ChildProcess | null = null;

interface DepResult {
  id: string;
  label: string;
  ok: boolean;
  detail: string;
}

// -- Async helpers --

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

// -- Quick check: WSL + Kali only (fast, ~1-3s) --

async function runQuickCheck(): Promise<{ wsl: boolean; kali: boolean }> {
  const wsl = await spawnOk('wsl', ['--status'], 4000);
  if (!wsl) return { wsl: false, kali: false };
  const buf = await spawnCapture('wsl', ['--list', '--quiet'], 4000);
  const kali = buf.toString('utf16le').toLowerCase().includes('kali');
  return { wsl, kali };
}

// -- Full dep check: all components (~10-20s) --

async function runDepsCheck(): Promise<DepResult[]> {
  const results: DepResult[] = [];

  const wslOk = await spawnOk('wsl', ['--status'], 5000);
  results.push({ id: 'wsl', label: 'WSL2', ok: wslOk, detail: wslOk ? 'Disponible' : 'No instalado' });
  if (!wslOk) return results;

  const kaliBuf = await spawnCapture('wsl', ['--list', '--quiet'], 5000);
  const kaliOk = kaliBuf.toString('utf16le').toLowerCase().includes('kali');
  results.push({ id: 'kali', label: 'Kali Linux', ok: kaliOk, detail: kaliOk ? 'Instalado' : 'No instalado' });
  if (!kaliOk) return results;

  const jarOk = await spawnOk('wsl', ['-d', 'kali-linux', '--', 'bash', '-c', 'test -f $HOME/.netsentinel/backend.jar'], 10000);
  results.push({ id: 'backend', label: 'Backend', ok: jarOk, detail: jarOk ? 'Instalado' : 'No encontrado' });

  const sandboxOk = await spawnOk('wsl', ['-d', 'kali-linux', '--', 'bash', '-c', 'test -x $HOME/.netsentinel/sandbox'], 10000);
  results.push({ id: 'sandbox', label: 'Sandbox (nmap, gobuster, nikto)', ok: sandboxOk, detail: sandboxOk ? 'Instalado' : 'No encontrado' });

  const pgBuf = await spawnCapture('wsl', [
    '-d', 'kali-linux', '--', 'bash', '-c',
    'sudo service postgresql start 2>/dev/null; pg_isready -h 127.0.0.1 -U netsentinel -d netsentinel 2>&1'
  ], 20000);
  const pgOk = pgBuf.toString('utf8').includes('accepting connections');
  results.push({ id: 'postgresql', label: 'Base de datos (PostgreSQL)', ok: pgOk, detail: pgOk ? 'Configurada' : 'Sin configurar' });

  return results;
}

// -- IPC handlers --

function setupIpcHandlers(): void {
  ipcMain.handle('deps:quick', () => runQuickCheck());
  ipcMain.handle('deps:check', () => runDepsCheck());

  ipcMain.handle('deps:status', () => {
    const statusFile = join(app.getPath('appData'), 'NetSentinel', 'setup-status.txt');
    try { return readFileSync(statusFile, 'utf8').trim(); } catch { return 'UNKNOWN'; }
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
      `Start-Process powershell -Verb RunAs -ArgumentList '-NoExit -ExecutionPolicy Bypass -File "${s}" "${d}"'`
    ], { detached: true, stdio: 'ignore' }).unref();
  });

  ipcMain.handle('backend:start', () => {
    startBackend();
  });
}

// -- Backend launch --

function startBackend(): void {
  if (process.env['NODE_ENV'] === 'development') return;
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

// -- Window --

function resolveIcon(): Electron.NativeImage {
  const icoPath =
    process.env['NODE_ENV'] === 'development'
      ? join(__dirname, '../public/icon.ico')
      : join(process.resourcesPath, 'icon.ico');
  if (existsSync(icoPath)) return nativeImage.createFromPath(icoPath);

  const pngPath =
    process.env['NODE_ENV'] === 'development'
      ? join(__dirname, '../public/icon.png')
      : join(process.resourcesPath, 'icon.png');
  return nativeImage.createFromPath(pngPath);
}

async function createWindow(): Promise<void> {
  const icon = resolveIcon();

  Menu.setApplicationMenu(null);

  mainWindow = new BrowserWindow({
    width: 1200,
    height: 680,
    minWidth: 900,
    minHeight: 560,
    backgroundColor: '#1e1e1e',
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

  const distIndex = join(__dirname, '../dist/frontend/browser/index.html');
  if (existsSync(distIndex)) {
    mainWindow.loadFile(distIndex);
  } else {
    mainWindow.loadURL('http://localhost:4200');
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// -- Bootstrap --

setupIpcHandlers();

app.whenReady().then(async () => {
  app.setAppUserModelId('com.netsentinel.app');
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
