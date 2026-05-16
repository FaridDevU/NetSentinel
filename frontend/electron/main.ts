import { app, BrowserWindow } from 'electron';
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

async function createWindow(): Promise<void> {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0d1117',
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
    mainWindow?.show();
  });

  if (process.env['NODE_ENV'] === 'development') {
    mainWindow.loadURL('http://localhost:4200');
  } else {
    mainWindow.loadFile(join(__dirname, '../dist/frontend/browser/index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

app.whenReady().then(async () => {
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
