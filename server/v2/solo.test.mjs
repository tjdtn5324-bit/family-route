// 가족과 연결하지 않은 차량도 장소를 찾고 길안내를 받을 수 있어야 한다.
// 이때 어떤 가족·계정 자료도 보이거나 남으면 안 된다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './http.mjs';
import { Service } from './service.mjs';

async function start(t, { directions } = {}) {
  const service = new Service();
  const server = createServer({
    service,
    search: async q => [{ name: q + ' 역', latitude: 35.1, longitude: 129, address: '부산' }],
    directions: directions || (async c => ({ points: [[c.origin.latitude, c.origin.longitude], ...c.waypoints.map(p => [p.latitude, p.longitude]), [c.destination.latitude, c.destination.longitude]], distance: 1000, duration: 60 }))
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r)); t.after(() => new Promise(r => { server.close(r); server.closeAllConnections(); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (path, data) => { const r = await fetch(base + path, { method: data ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json' }, body: data ? JSON.stringify(data) : undefined }); return { status: r.status, body: await r.json().catch(() => null) }; };
  return { call, service };
}
const busan = { name: '부산역', latitude: 35.1152, longitude: 129.0415, address: '부산' };

test('가족 연결 없이 장소를 찾고 경로를 받는다', async t => {
  const { call, service } = await start(t);
  const found = await call('/api/places?q=' + encodeURIComponent('부산'));
  assert.equal(found.status, 200); assert.equal(found.body.places[0].name, '부산 역');
  const r = await call('/api/drive/route', { destination: busan, waypoints: [{ name: '광안리', latitude: 35.15, longitude: 129.11 }] });
  assert.equal(r.status, 200);
  assert.equal(r.body.route.points.length, 3);
  assert.equal(r.body.destination.name, '부산역');
  // 아무것도 저장하지 않는다.
  assert.deepEqual(Object.values(service.exportSnapshot()).filter(Array.isArray).map(a => a.length).filter(n => n > 0), []);
  // 가족 자료에는 여전히 들어갈 수 없다.
  assert.equal((await call('/api/trips')).status, 401);
  assert.equal((await call('/api/vehicle/home')).status, 401);
});

test('혼자 운전 경로의 입력을 확인하고, 길찾기 실패를 알려 준다', async t => {
  const { call } = await start(t, { directions: async () => { throw new Error('down'); } });
  assert.equal((await call('/api/drive/route', { destination: { name: '', latitude: 1, longitude: 1 } })).status, 400);
  assert.equal((await call('/api/drive/route', { destination: busan, waypoints: Array(6).fill(busan) })).status, 400);
  assert.equal((await call('/api/drive/route', { destination: busan })).status, 502);
});

test('차량 SDK 출발지를 사용하고 잘못된 좌표는 거부한다', async t => {
  const {call} = await start(t);
  const origin = {name:'현재 차량 위치',latitude:36.35,longitude:127.38};
  const result = await call('/api/drive/route',{destination:busan,origin});
  assert.equal(result.status,200);
  assert.deepEqual(result.body.route.points[0],[origin.latitude,origin.longitude]);
  assert.equal((await call('/api/drive/route',{destination:busan,origin:{...origin,latitude:999}})).status,400);
});
