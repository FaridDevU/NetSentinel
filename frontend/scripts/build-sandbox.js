const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const frontendDir = path.resolve(__dirname, '..');
const installerDir = path.join(frontendDir, 'installer');
const outputBinary = path.join(installerDir, 'netsentinel-sandbox');

console.log('Compiling Rust sandbox for Kali WSL2...');

try {
  if (fs.existsSync(outputBinary)) {
    fs.rmSync(outputBinary);
  }
  const winPath = frontendDir.replace(/\\/g, '/');
  const wslFrontend = execSync(`wsl wslpath -u "${winPath}"`, { encoding: 'utf8' }).trim();
  const wslSandbox = `${wslFrontend}/../sandbox`;
  const wslOutput = `${wslFrontend}/installer/netsentinel-sandbox`;

  execSync(
    `wsl -d kali-linux -- bash -c "cd '${wslSandbox}' && cargo build --release 2>&1 && cp target/release/sandbox '${wslOutput}'"`,
    { stdio: 'inherit' }
  );
  console.log('Sandbox compiled and placed at installer/netsentinel-sandbox');
} catch (e) {
  console.error('ERROR: Could not compile sandbox binary.');
  console.error('Make sure cargo is installed in Kali WSL2: sudo apt-get install -y cargo');
  console.error('Or compile manually: cd sandbox && cargo build --release');
  console.error('Then copy: sandbox/target/release/sandbox -> frontend/installer/netsentinel-sandbox');
  process.exit(1);
}
