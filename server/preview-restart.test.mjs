import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 실제 시연 서버(tools/preview-phone.mjs)를 껐다 켜도 계정·가족·여행·사진·차량 연결이 남는지 확인한다.
// 임시 폴더만 쓰고, 이 PC의 실제 보관 파일은 건드리지 않는다.
test('시연 서버를 다시 켜도 계정, 가족, 여행, 사진, 차량 연결이 그대로 남는다', { timeout: 30000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'family-preview-test-'));
  let child;
  async function stop() {
    if (child && child.exitCode === null && child.signalCode === null) { const done = once(child, 'exit'); child.kill(); await done; }
  }
  t.after(async () => { await stop(); await rm(directory, { recursive: true, force: true }); });
  async function start() {
    child = spawn(process.execPath, [fileURLToPath(new URL('../tools/preview-phone.mjs', import.meta.url)), '0'], {
      env: { ...process.env, FAMILY_V2_ARCHIVE_DIR: directory }, windowsHide: true
    });
    const [line] = await once(child.stdout, 'data');
    return line.toString().match(/http:\/\/127\.0\.0\.1:\d+/)[0];
  }
  let base = await start();
  let cookie = '';
  async function call(path, data, bearer) {
    const r = await fetch(base + path, { method: data ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(bearer ? { Authorization: 'Bearer ' + bearer } : cookie ? { Cookie: cookie } : {}) }, body: data ? JSON.stringify(data) : undefined });
    const set = r.headers.get('set-cookie'); if (set) cookie = set.split(';')[0];
    assert.ok(r.ok, `${path} (${r.status})`);
    return r.json();
  }
  await call('/api/auth/signup', { email: 'restart@example.com', password: 'password-restart', name: '시험' });
  await call('/api/family', { name: '다시 켜기 가족' });
  const trip = await call('/api/trips', { title: '부산 여행', destination: { name: '부산역', latitude: 35.1152, longitude: 129.0415, address: '부산' } });
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2]).toString('base64');
  const photo = await call(`/api/trips/${trip.id}/photos`, { data: jpeg });
  const { code } = await call('/api/family/vehicle-code', {});
  const { vehicleToken } = await call('/api/vehicle/pair', { code, name: '시험 차' });
  await stop();
  base = await start();
  cookie = '';
  // 세션은 다시 로그인해야 하지만(쿠키는 그대로 써도 된다), 자료는 남아 있어야 한다.
  await call('/api/auth/login', { email: 'restart@example.com', password: 'password-restart' });
  assert.equal((await call('/api/me')).family.name, '다시 켜기 가족');
  assert.equal((await call('/api/trips')).trips[0].title, '부산 여행');
  assert.equal((await call('/api/photos/' + photo.id)).data, jpeg);
  assert.equal((await call('/api/vehicle/home', undefined, vehicleToken)).family.name, '다시 켜기 가족');
});
