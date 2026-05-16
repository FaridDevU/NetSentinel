import { app, BrowserWindow, nativeImage, Menu } from 'electron';
import { join } from 'path';
import { spawn, ChildProcess } from 'child_process';
import { existsSync } from 'fs';
import * as http from 'http';

let mainWindow: BrowserWindow | null = null;
let backendProcess: ChildProcess | null = null;

function startBackend(): Promise<void> {
  if (process.env['NODE_ENV'] === 'development') return Promise.resolve();

  const jarPath = join(process.resourcesPath, 'backend.jar');
  if (!existsSync(jarPath)) return Promise.resolve();

  backendProcess = spawn('java', ['-jar', jarPath], {
    stdio: 'ignore',
    detached: false,
  });

  backendProcess.on('error', () => {
    // java not found or failed to start — app continues, user sees offline banner
  });

  return waitForBackend(30);
}

function waitForBackend(retries: number): Promise<void> {
  return new Promise((resolve) => {
    let attempts = 0;

    const check = (): void => {
      if (attempts >= retries) {
        resolve();
        return;
      }
      attempts++;
      const req = http.get('http://127.0.0.1:8080/actuator/health', (res) => {
        if (res.statusCode === 200) {
          resolve();
        } else {
          setTimeout(check, 1000);
        }
      });
      req.on('error', () => setTimeout(check, 1000));
      req.setTimeout(800, () => {
        req.destroy();
        setTimeout(check, 1000);
      });
    };

    check();
  });
}

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

app.whenReady().then(async () => {
  app.setAppUserModelId('com.netsentinel.app');
  await startBackend();
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
