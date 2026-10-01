#!/usr/bin/env node
/**
 * Headless LSP verification test.
 * Spawns the native launcher binary via stdio and tests the JSON-RPC
 * initialization handshake and capabilities response.
 */

import { spawn } from 'child_process';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..');

const platform = process.platform;
const binaryName = platform === 'win32' ? 'launcher.bat' : 'launcher';
const platformDir = platform === 'win32' ? 'windows' : (platform === 'darwin' ? 'mac' : 'linux');
const launcherPath = path.resolve(rootDir, 'dist', platformDir, 'bin', binaryName);

if (!fs.existsSync(launcherPath)) {
  console.error(`Error: Launcher binary not found at ${launcherPath}`);
  console.error('Please build it first with ./scripts/link_linux.sh (or scripts/build.sh)');
  process.exit(1);
}

console.log(`Starting LSP server from: ${launcherPath}`);
const proc = spawn(launcherPath, [], {
  stdio: ['pipe', 'pipe', 'pipe']
});

let stdoutBuffer = '';
let stderrBuffer = '';

proc.stdout.on('data', (chunk) => {
  stdoutBuffer += chunk.toString();
  checkResponses();
});

proc.stderr.on('data', (chunk) => {
  stderrBuffer += chunk.toString();
});

proc.on('error', (err) => {
  console.error('Failed to spawn LSP process:', err);
  process.exit(1);
});

proc.on('close', (code) => {
  if (code !== 0 && !passed) {
    console.error(`LSP process exited with code ${code}`);
    if (stderrBuffer) {
      console.error('Server stderr:\n', stderrBuffer);
    }
    process.exit(code || 1);
  }
});

let passed = false;

function sendJsonRpc(msgObj) {
  const json = JSON.stringify(msgObj);
  const payload = `Content-Length: ${Buffer.byteLength(json, 'utf8')}\r\n\r\n${json}`;
  proc.stdin.write(payload);
}

// 1. Send Initialize Request
const testWorkspaceUri = `file://${rootDir}`;
sendJsonRpc({
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: {
    rootUri: testWorkspaceUri,
    capabilities: {}
  }
});

function checkResponses() {
  if (!passed && stdoutBuffer.includes('"capabilities"')) {
    try {
      const headerEnd = stdoutBuffer.indexOf('\r\n\r\n');
      if (headerEnd !== -1) {
        const bodyStr = stdoutBuffer.slice(headerEnd + 4);
        const parsed = JSON.parse(bodyStr);
        if (parsed.result && parsed.result.capabilities) {
          const caps = parsed.result.capabilities;
          console.log('✓ LSP handshake successful!');
          console.log('  Capabilities received:', Object.keys(caps).join(', '));
          
          if (!caps.hoverProvider) {
            throw new Error('Expected hoverProvider capability to be true');
          }
          if (!caps.completionProvider) {
            throw new Error('Expected completionProvider capability to be defined');
          }

          console.log('✓ All capability assertions passed.');
          passed = true;
          
          // Clean shutdown
          sendJsonRpc({
            jsonrpc: '2.0',
            id: 2,
            method: 'shutdown',
            params: null
          });

          setTimeout(() => {
            proc.kill();
            process.exit(0);
          }, 500);
        }
      }
    } catch (err) {
      console.error('Error parsing response:', err.message);
      proc.kill();
      process.exit(1);
    }
  }
}

// 10s Timeout
setTimeout(() => {
  if (!passed) {
    console.error('Timed out waiting for LSP response');
    if (stderrBuffer) {
      console.error('Server stderr:\n', stderrBuffer);
    }
    proc.kill();
    process.exit(1);
  }
}, 10000);
