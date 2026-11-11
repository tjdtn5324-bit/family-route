import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Restart the actual preview entry point. Production rooms and the Windows login task stay untouched.
test('preview restores the same invitation, photo and courses after a process restart', { timeout: 30000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'family-preview-test-'));
  let child;
  async function stop() {
    if (child && child.exitCode === null && child.signalCode === null) {
      const done = once(child, 'exit');
      child.kill();
      await done;
    }
  }
  t.after(async () => { await stop(); await rm(directory, { recursive: true, force: true }); });
  async function start() {
    child = spawn(process.execPath, [fileURLToPath(new URL('../tools/preview-phone.mjs', import.meta.url)), '0'], {
      env: { ...process.env, LOCALAPPDATA: directory, FAMILY_ACCOUNT_CONFIG: '', FAMILY_LOCAL_ARCHIVE_DIR: directory }, windowsHide: true
    });
    const [line] = await once(child.stdout, 'data');
    return line.toString().match(/http:\/\/127\.0\.0\.1:\d+/)[0];
  }
  let base = await start();
  async function call(path, body, token) {
    const r = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify(body) });
    assert.ok(r.ok, `${path} (${r.status})`);
    return r.json();
  }
  const room = await call('/api/rooms', { name: 'Restart test' });
  const member = await call('/api/join', { invite: room.invite, name: 'Tester' });
  const { code } = await call('/api/rooms/invite-code', {}, room.driverToken);
  const jpeg = Buffer.from([255,216,255,217]).toString('base64');
  const photo = await call('/api/photos', { data: jpeg }, member.memberToken);
  const before = await call('/api/public/courses?sort=popular');
  assert.ok(before.courses.length > 0);
  await stop();
  base = await start();
  assert.deepEqual((await call('/api/public/courses?sort=popular')).courses.map(c => [c.id,c.likes]), before.courses.map(c => [c.id,c.likes]));
  assert.equal((await call('/api/photos/' + photo.id, undefined, member.memberToken)).data, jpeg);
  assert.equal((await call('/api/join-code', { code, name: 'After restart' })).roomId, member.roomId);
});
