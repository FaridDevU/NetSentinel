import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("electron", {
  checkDepsQuick: () => ipcRenderer.invoke('deps:quick'),
  checkDeps: () => ipcRenderer.invoke('deps:check'),
  getSetupStatus: () => ipcRenderer.invoke('deps:status'),
  runSetup: () => ipcRenderer.invoke('deps:install'),
  startBackend: () => ipcRenderer.invoke('backend:start'),
  getLocalNetworks: () => ipcRenderer.invoke('network:local'),
  saveNvdKey: (key: string) => ipcRenderer.invoke('config:saveNvdKey', key),
  reboot: () => ipcRenderer.invoke('system:reboot'),
  expandWindow: () => ipcRenderer.invoke('window:expand'),
  minimizeWindow: () => ipcRenderer.invoke('window:minimize'),
  closeWindow: () => ipcRenderer.invoke('window:close'),
});
