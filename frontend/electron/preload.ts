import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("electron", {
  startScan: (target: string) => ipcRenderer.invoke("start-scan", target),
  scanStatus: (scanId: string) => ipcRenderer.invoke("scan-status", scanId),
  scanResults: (scanId: string) => ipcRenderer.invoke("scan-results", scanId),
});
