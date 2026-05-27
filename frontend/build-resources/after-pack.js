const { execFileSync } = require('child_process');
const { existsSync } = require('fs');
const { join } = require('path');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return;

  const exePath = join(context.appOutDir, `${context.packager.appInfo.productFilename}.exe`);
  const iconPath = join(context.packager.projectDir, 'public', 'icon.ico');
  const rceditPath = join(context.packager.projectDir, 'node_modules', 'electron-winstaller', 'vendor', 'rcedit.exe');

  if (!existsSync(exePath) || !existsSync(iconPath) || !existsSync(rceditPath)) return;

  execFileSync(rceditPath, [
    exePath,
    '--set-icon',
    iconPath,
    '--set-version-string',
    'ProductName',
    context.packager.appInfo.productName,
    '--set-version-string',
    'FileDescription',
    context.packager.appInfo.productName,
    '--set-version-string',
    'OriginalFilename',
    `${context.packager.appInfo.productFilename}.exe`,
    '--set-version-string',
    'InternalName',
    context.packager.appInfo.productFilename,
  ], { stdio: 'inherit' });
};
