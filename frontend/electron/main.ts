import { app, BrowserWindow, ipcMain } from "electron";
import { join } from "path";
import { spawn } from "child_process";

let mainWindow: BrowserWindow | null = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    show: false,
    webPreferences: {
      preload: join(__dirname, "preload.js"),
      nodeIntegration: false,
      contextIsolation: true,
    },
  });

  mainWindow.once("ready-to-show", () => {
    mainWindow?.show();
  });

  if (process.env["NODE_ENV"] === "development") {
    mainWindow.loadURL("http://localhost:4200");
    mainWindow.webContents.openDevTools({ mode: "detach" });
  } else {
    mainWindow.loadFile(join(__dirname, "../dist/frontend/browser/index.html"));
  }

  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

// IPC: iniciar escaneo via Spring Boot backend
ipcMain.handle("start-scan", async (_, target: string) => {
  try {
    const response = await fetch("http://localhost:8080/api/scan/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ target }),
    });
    return await response.json();
  } catch (error) {
    return { error: "No se pudo conectar al backend. Asegurate de que NetSentinel este corriendo." };
  }
});

// IPC: obtener estado del escaneo
ipcMain.handle("scan-status", async (_, scanId: string) => {
  try {
    const response = await fetch(`http://localhost:8080/api/scan/${scanId}/status`);
    return await response.json();
  } catch (error) {
    return { error: "Error al obtener estado del escaneo." };
  }
});

// IPC: obtener resultados del escaneo
ipcMain.handle("scan-results", async (_, scanId: string) => {
  try {
    const response = await fetch(`http://localhost:8080/api/scan/${scanId}/results`);
    return await response.json();
  } catch (error) {
    return { error: "Error al obtener resultados." };
  }
});
