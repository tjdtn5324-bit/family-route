// 시연 서버(2판: 계정 → 가족 그룹 → 여행).
// 장소 검색과 도로 경로는 모의 자료다. 계정·가족·여행·사진은 이 PC의 암호화 파일에 보관한다.
// 실행: node tools/preview-phone.mjs 8789  →  http://127.0.0.1:8789 (에뮬레이터는 10.0.2.2:8789)
import { createServer } from '../server/v2/http.mjs';
import { Service } from '../server/v2/service.mjs';
import { openLocalArchive } from '../server/local-persistence.mjs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const port = Number(process.argv[2] || 8790);
const places = [
  { name: '부산역', latitude: 35.1152, longitude: 129.0415, address: '부산 동구 중앙대로 206' },
  { name: '광안리해수욕장', latitude: 35.1532, longitude: 129.1187, address: '부산 수영구 광안해변로 219' },
  { name: '감천문화마을', latitude: 35.0974, longitude: 129.0106, address: '부산 사하구 감천동 1-17' },
  { name: '해운대해수욕장', latitude: 35.1587, longitude: 129.1604, address: '부산 해운대구 우동' },
  { name: '자갈치시장', latitude: 35.0966, longitude: 129.0306, address: '부산 중구 자갈치해안로 52' },
  { name: '경복궁', latitude: 37.5796, longitude: 126.977, address: '서울 종로구 사직로 161' }
];
// 2판 자료는 예전 자료와 다른 폴더에 둔다. 예전 암호화 파일은 지우지 않고 그대로 남는다.
const directory = process.env.FAMILY_V2_ARCHIVE_DIR || join(process.env.LOCALAPPDATA || homedir(), 'AppaEodiga', 'v2-' + port);
const archive = openLocalArchive(directory);
const saved = archive.load();
const service = saved ? Service.fromSnapshot(saved) : new Service();
// 모의 경로: 출발지 → 들르는 곳 → 목적지를 직선으로 잇는다. 실제 도로 경로가 아니다.
const directions = async c => {
  const pts = [c.origin, ...c.waypoints, c.destination].map(p => [p.latitude, p.longitude]);
  let meters = 0; for (let i = 1; i < pts.length; i++) meters += Math.hypot((pts[i][0] - pts[i - 1][0]) * 111000, (pts[i][1] - pts[i - 1][1]) * 88000);
  return { points: pts, distance: Math.round(meters * 1.25), duration: Math.round(meters * 1.25 / 22) };
};
const instance = randomUUID();
const api = createServer({
  service, apiKey: 'preview', persist: archive.save, directions,
  search: async q => { const s = String(q || '').trim(); return places.filter(p => !s || p.name.includes(s) || p.address.includes(s) || (/부산|busan/i.test(s) && p.address.startsWith('부산'))); },
  assets: { '/assets/appa-eodiga.png': [new URL('../server/assets/appa-eodiga.png', import.meta.url), 'image/png'] }
});
const handler = api.listeners('request')[0];
api.removeListener('request', handler);
api.on('request', async (req, res) => {
  if (req.method !== 'GET' || !['/api/preview-instance', '/api/invite-origin'].includes(req.url)) return handler(req, res);
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Cache-Control', 'no-store');
  if (req.url === '/api/preview-instance') return res.end(JSON.stringify({ instance }));
  // 에뮬레이터 전용: 터널을 통해 들어온 요청 대신 바깥으로 요청을 보내지 않는다.
  if (req.headers['cf-ray'] || req.headers['x-forwarded-for']) { res.statusCode = 403; return res.end('{}'); }
  try {
    const read = async url => (await fetch(url, { signal: AbortSignal.timeout(3500), redirect: 'error' })).json();
    const { hostname } = await read('http://127.0.0.1:20241/quicktunnel');
    if (!/^[a-z0-9-]+\.trycloudflare\.com$/.test(hostname)) throw Error();
    const origin = 'https://' + hostname;
    if ((await read(origin + '/api/preview-instance')).instance !== instance) throw Error();
    res.end(JSON.stringify({ origin }));
  } catch { res.end(JSON.stringify({ origin: '' })); }
});
api.listen(port, '127.0.0.1', () => console.log('preview: http://127.0.0.1:' + api.address().port));
