const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const frontendDir = path.resolve(__dirname, '..');
const sandboxDir = path.resolve(frontendDir, '..', 'sandbox');
const builtBinary = path.join(sandboxDir, 'target', 'release', 'sandbox.exe');
const buildDir = path.join(frontendDir, 'build');
const toolsDir = path.join(buildDir, 'tools');
const outputBinary = path.join(buildDir, 'sandbox.exe');

console.log('Compiling Rust sandbox for Windows...');

try {
  execSync('cargo build --release', { cwd: sandboxDir, stdio: 'inherit' });

  if (!fs.existsSync(builtBinary)) {
    throw new Error(`Expected ${builtBinary} after cargo build`);
  }

  fs.mkdirSync(toolsDir, { recursive: true });
  fs.copyFileSync(builtBinary, outputBinary);

  console.log('Sandbox compiled and placed at build/sandbox.exe');
} catch (e) {
  console.error('ERROR: Could not compile sandbox binary.');
  console.error('Make sure the Rust toolchain (cargo) is installed and on PATH.');
  console.error('Or compile manually: cd sandbox && cargo build --release');
  console.error('Then copy: sandbox/target/release/sandbox.exe -> frontend/build/sandbox.exe');
  process.exit(1);
}
