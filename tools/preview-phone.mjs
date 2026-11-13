// 시연 서버(2판: 계정 → 가족 그룹 → 여행).
// 카카오 키가 있으면 실제 검색·경로를 요청하고, 미설정 시 시연 자료를 사용한다. 계정·가족·여행·사진은 이 PC의 암호화 파일에 보관한다.
// 실행: node tools/preview-phone.mjs 8789  →  http://127.0.0.1:8789 (에뮬레이터는 10.0.2.2:8789)
import { createServer } from '../server/v2/http.mjs';
import { Service } from '../server/v2/service.mjs';
import { REFERENCE_COURSES } from '../server/v2/reference-courses.mjs';
import { openLocalArchive } from '../server/local-persistence.mjs';
import { createPlaceSearch, loadPlaceSearchKey } from '../server/place-search-provider.mjs';
import { getDirections } from '../server/directions.mjs';
import { homedir, networkInterfaces } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';

const port = process.argv[2] !== undefined ? Number(process.argv[2]) : 8789;
const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const basePlaces = [
  { name: '부산역', latitude: 35.1152, longitude: 129.0415, address: '부산 동구 중앙대로 206' },
  { name: '광안리해수욕장', latitude: 35.1532, longitude: 129.1187, address: '부산 수영구 광안해변로 219' },
  { name: '감천문화마을', latitude: 35.0974, longitude: 129.0106, address: '부산 사하구 감천동 1-17' },
  { name: '해운대해수욕장', latitude: 35.1587, longitude: 129.1604, address: '부산 해운대구 우동' },
  { name: '자갈치시장', latitude: 35.0966, longitude: 129.0306, address: '부산 중구 자갈치해안로 52' },
  { name: '태종대', latitude: 35.053, longitude: 129.0872, address: '부산 영도구 전망로 24' },
  { name: '흰여울문화마을', latitude: 35.0789, longitude: 129.0445, address: '부산 영도구 영선동4가' },
  { name: '송도해수욕장', latitude: 35.0759, longitude: 129.017, address: '부산 서구 암남동' },
  { name: '해동용궁사', latitude: 35.1884, longitude: 129.2233, address: '부산 기장군 기장읍 용궁길 86' },
  { name: '광안리 오션뷰 카페', latitude: 35.1541, longitude: 129.1195, address: '부산 수영구 광안해변로 카페거리' },
  { name: '서울역', latitude: 37.5547, longitude: 126.9708, address: '서울 용산구 한강대로 405' },
  { name: '경복궁', latitude: 37.5796, longitude: 126.977, address: '서울 종로구 사직로 161' },
  { name: '북촌한옥마을', latitude: 37.5826, longitude: 126.984, address: '서울 종로구 계동길 37' },
  { name: '남산서울타워', latitude: 37.5512, longitude: 126.9882, address: '서울 용산구 남산공원길 105' },
  { name: '광장시장', latitude: 37.57, longitude: 126.9996, address: '서울 종로구 창경궁로 88' },
  { name: '성수동 카페거리', latitude: 37.5446, longitude: 127.056, address: '서울 성동구 성수이로' },
  { name: '여의도 한강공원', latitude: 37.5284, longitude: 126.9328, address: '서울 영등포구 여의동로 330' },
  { name: '강릉역', latitude: 37.7645, longitude: 128.8996, address: '강원특별자치도 강릉시 용지로 176' },
  { name: '강릉중앙시장', latitude: 37.754, longitude: 128.8986, address: '강원특별자치도 강릉시 금성로 21' },
  { name: '안목해변 커피거리', latitude: 37.7723, longitude: 128.9483, address: '강원특별자치도 강릉시 창해로14번길 51-26' },
  { name: '경포해수욕장', latitude: 37.8034, longitude: 128.9102, address: '강원특별자치도 강릉시 창해로 514' },
  { name: '오죽헌', latitude: 37.7792, longitude: 128.8794, address: '강원특별자치도 강릉시 율곡로3139번길 24' },
  { name: '주문진수산시장', latitude: 37.8891, longitude: 128.8266, address: '강원특별자치도 강릉시 주문진읍 시장길 38' },
  { name: '초당순두부마을', latitude: 37.7912, longitude: 128.9148, address: '강원특별자치도 강릉시 초당순두부길' },
  { name: '첨성대', latitude: 35.8347, longitude: 129.219, address: '경북 경주시 인왕동 839-1' },
  { name: '대릉원', latitude: 35.8382, longitude: 129.2133, address: '경북 경주시 황남동 31-1' },
  { name: '동궁과월지', latitude: 35.8348, longitude: 129.227, address: '경북 경주시 원화로 102' },
  { name: '황리단길 카페거리', latitude: 35.8375, longitude: 129.2097, address: '경북 경주시 포석로 1080' },
  { name: '불국사', latitude: 35.79, longitude: 129.332, address: '경북 경주시 불국로 385' },
  { name: '전주한옥마을', latitude: 35.8148, longitude: 127.1526, address: '전북특별자치도 전주시 완산구 풍남동3가 63-1' },
  { name: '경기전', latitude: 35.8154, longitude: 127.1497, address: '전북특별자치도 전주시 완산구 태조로 44' },
  { name: '전주남부시장 한옥마을야시장', latitude: 35.813, longitude: 127.147, address: '전북특별자치도 전주시 완산구 풍남문2길 49' },
  { name: '속초중앙시장', latitude: 38.2045, longitude: 128.5901, address: '강원특별자치도 속초시 중앙로147번길 12' },
  { name: '여수 이순신광장', latitude: 34.7408, longitude: 127.7364, address: '전남 여수시 중앙동 385-6' },
  { name: '가평 남이섬', latitude: 37.7913, longitude: 127.5255, address: '경기 가평군 가평읍 북한강변로 1024' }
];
const seenPlaces = new Set();
const places = [];
for (const p of [...basePlaces, ...REFERENCE_COURSES.flatMap(c => [...c.waypoints, c.destination])]) {
  if (!seenPlaces.has(p.name)) { seenPlaces.add(p.name); places.push({ name: p.name, latitude: p.latitude, longitude: p.longitude, address: p.address || '' }); }
}
// 2판 자료는 예전 자료와 다른 폴더에 둔다. 예전 암호화 파일은 지우지 않고 그대로 남는다.
const directory = process.env.FAMILY_V2_ARCHIVE_DIR || (port > 0 ? join(projectRoot, '.runtime', 'v2-' + port) : join(process.env.LOCALAPPDATA || homedir(), 'AppaEodiga', 'v2-' + port));
const archive = openLocalArchive(directory);
const saved = archive.load();
const service = saved ? Service.fromSnapshot(saved) : new Service();
const kakaoKey = loadPlaceSearchKey({ path: join(projectRoot, 'place-search.local.json') });
// 도로 경로: 카카오 키가 있으면 실제 도로 경로를 우선 시도하고, 없거나 실패하면 출발지 → 들르는 곳 → 목적지를 잇는 시연 경로로 안내한다.
const mockDirections = async c => {
  const pts = [c.origin, ...c.waypoints, c.destination].map(p => [p.latitude, p.longitude]);
  let meters = 0; for (let i = 1; i < pts.length; i++) meters += Math.hypot((pts[i][0] - pts[i - 1][0]) * 111000, (pts[i][1] - pts[i - 1][1]) * 88000);
  return { points: pts, distance: Math.round(meters * 1.25), duration: Math.round(meters * 1.25 / 22) };
};
const directions = async c => {
  if (kakaoKey) { try { return await getDirections(c, { apiKey: kakaoKey }); } catch {} }
  return mockDirections(c);
};
const localSearch = q => {
  const s = String(q || '').trim();
  const tokens = s.split(/\s+/).filter(Boolean);
  return places.filter(p => !tokens.length || tokens.every(t => p.name.includes(t) || p.address.includes(t) || (/부산|busan/i.test(t) && p.address.startsWith('부산')) || (/강릉/i.test(t) && p.address.includes('강릉')) || (/경주/i.test(t) && p.address.includes('경주')) || (/전주/i.test(t) && p.address.includes('전주')) || (/서울/i.test(t) && p.address.startsWith('서울'))));
};
const instance = randomUUID();
const api = createServer({
  service, apiKey: kakaoKey || 'preview', persist: archive.save, directions,
  search: createPlaceSearch({ apiKey: kakaoKey, localSearch }),
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
  } catch { res.end(JSON.stringify({ origin: lanOrigin() })); }
});
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(sig, () => {
    try { archive.save(service.exportSnapshot()); } catch {}
    process.exit(0);
  });
}
// FAMILY_LAN=1: 터널(cloudflared)이 없는 곳에서 같은 Wi-Fi의 휴대폰이 바로 들어오도록 이 PC의 사설 주소로도 연다.
const lan = process.env.FAMILY_LAN === '1';
function lanOrigin() {
  if (!lan) return '';
  const ips = Object.values(networkInterfaces()).flat().filter(i => i && i.family === 'IPv4' && !i.internal).map(i => i.address)
    .filter(a => /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a));
  const ip = ips.find(a => a.startsWith('192.168.')) || ips[0];
  return ip ? 'http://' + ip + ':' + api.address().port : '';
}
api.listen(port, lan ? '0.0.0.0' : '127.0.0.1', () => console.log('preview: http://127.0.0.1:' + api.address().port + (lanOrigin() ? '  phone: ' + lanOrigin() : '')));
