'use strict';
/**
 * Ensure self-signed TLS certs exist (Windows-friendly, no openssl required).
 */
const fs = require('fs');
const path = require('path');
const selfsigned = require('selfsigned');

async function ensureTls(certDir, commonName) {
  const keyPath = path.join(certDir, 'key.pem');
  const certPath = path.join(certDir, 'cert.pem');
  if (fs.existsSync(certPath) && fs.existsSync(keyPath)) {
    return {
      key: fs.readFileSync(keyPath),
      cert: fs.readFileSync(certPath),
    };
  }
  fs.mkdirSync(certDir, { recursive: true, mode: 0o700 });
  const attrs = [{ name: 'commonName', value: commonName || 'cursor-bridge' }];
  const opts = {
    days: 3650,
    keySize: 2048,
    algorithm: 'sha256',
    extensions: [{ name: 'basicConstraints', cA: true }],
  };
  let pems;
  if (typeof selfsigned.generate === 'function') {
    const out = selfsigned.generate(attrs, opts);
    pems = out && typeof out.then === 'function' ? await out : out;
  } else {
    throw new Error('selfsigned.generate unavailable');
  }
  fs.writeFileSync(keyPath, pems.private, { mode: 0o600 });
  fs.writeFileSync(certPath, pems.cert, { mode: 0o644 });
  try { fs.chmodSync(certDir, 0o700); } catch {}
  try { fs.chmodSync(keyPath, 0o600); } catch {}
  return { key: Buffer.from(pems.private), cert: Buffer.from(pems.cert) };
}

module.exports = { ensureTls };
