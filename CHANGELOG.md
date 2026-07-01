# Changelog

All notable changes to this project are documented here.

## [0.2.0] — Unreleased (pending clean-VM installer validation)

The "native Windows" pivot: NetSentinel no longer depends on WSL2, Kali Linux or PostgreSQL. The app is self-contained and installs on any Windows 10/11 machine with no admin rights and no virtualization.

### Changed
- **Removed WSL2 + Kali Linux.** The Rust sandbox now runs natively as `sandbox.exe` and invokes native Windows tools (`nmap.exe`, `gobuster.exe`).
- **Removed PostgreSQL** in favor of embedded **SQLite** (Hibernate-managed schema, stored under `%USERPROFILE%\.netsentinel`).
- **nmap** runs with `-sT` (TCP connect-scan), so **Npcap and admin rights are no longer required**.
- **Electron** launches the backend on a **bundled JRE** (`java.exe -jar backend.jar`) and the native sandbox directly; the sandbox token and NVD key are stored under `%USERPROFILE%\.netsentinel`.
- **Installer**: `setup.ps1` no longer enables WSL, installs Kali or PostgreSQL; it only verifies the bundled resources. Packaging bundles a trimmed JRE (jlink), the jar, `sandbox.exe` and the tools via electron-builder.

### Added
- **Bilingual diagnostics and agent, English by default.** Backend uses Spring `MessageSource`; the scan language is threaded per request (`ScanRequest.language`), Spanish remains available.

### Docs / cleanup
- Entire codebase, commit history, README and the PDF report template translated to **English**.
- Scan profiles renamed `RAPIDO`/`ESTANDAR`/`COMPLETO` → `QUICK`/`STANDARD`/`FULL` (wire contract unchanged — the API exchanges nmap flag arrays).
- Removed residual WSL/Kali references across backend and frontend.

### Fixed
- Sandbox: UTF-8 panic when building the preview of an oversized argument.

### Pending before release
- Bundle real `nmap.exe`/`gobuster.exe`, run `npm run electron:dist`, and validate the installer on a clean Windows VM. Nikto stays optional (Perl), not bundled.

## [0.1.0] — 2026 (previous architecture, kept for history)

First packaged release. Embedded WSL2 + Kali Linux with the Rust sandbox running inside Kali, PostgreSQL storage, and Spanish-language diagnostics. Included the guided NSIS installer, the Claude agent (tool use + SSE), PDF/JSON/CSV export, scan history and NVD CVE correlation.
