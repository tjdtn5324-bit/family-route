// 휴대폰 화면 미리보기 전용. 카카오·AWS 없이 가짜 검색과 가짜 경로로 서버를 띄운다.
// 실행: node tools/preview-phone.mjs  →  http://127.0.0.1:8790
import { createApp } from '../server/app.mjs';
import { Rooms } from '../server/rooms.mjs';
import { openLocalArchive } from '../server/local-persistence.mjs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
// 포트를 인자로 바꿀 수 있다. 차량 앱(에뮬레이터)은 8789를 본다: node tools/preview-phone.mjs 8789
const port = Number(process.argv[2] || 8790);
const places = [
  { name: '부산역', latitude: 35.1152, longitude: 129.0415, address: '부산 동구 중앙대로 206' },
  { name: '광안리해수욕장', latitude: 35.1532, longitude: 129.1187, address: '부산 수영구 광안해변로 219' },
  { name: '감천문화마을', latitude: 35.0974, longitude: 129.0106, address: '부산 사하구 감천동 1-17' },
  { name: '해운대해수욕장', latitude: 35.1587, longitude: 129.1604, address: '부산 해운대구 우동' }
];
// 화면 확인용 예시 후기. 실제 사용자가 쓴 글이 아니며 이 미리보기 서버에만 있다.
const directory = process.env.FAMILY_LOCAL_ARCHIVE_DIR || join(process.env.LOCALAPPDATA || homedir(), 'AppaEodiga', 'preview-' + port);
const archive = openLocalArchive(directory);
const saved = archive.load();
const options = { sampleCourses: true, permanentMemories: true };
const rooms = saved ? Rooms.fromSnapshot(saved, options) : new Rooms(options);
if (!saved) {
  rooms.reviewCourse('preview-1', 'guide-busan-market', { rating: 5, text: '(미리보기 예시) 시장 구경이 즐거웠어요.', name: '예시 가족 1' });
  rooms.reviewCourse('preview-2', 'guide-busan-market', { rating: 4, text: '(미리보기 예시) 골목이 가팔라서 편한 신발이 좋아요.', name: '예시 가족 2' });
  archive.save(rooms.exportSnapshot());
}
// Only advertise a quick tunnel that reaches this exact preview process.
const instance = randomUUID();
const api = createApp({
  apiKey: 'preview',
  rooms,
  persist: archive.save,
  search: async q => places.filter(p => !q || p.name.includes(q) || p.address.includes(q) || q.includes('부산') || /busan/i.test(q)),
  directions: async c => ({ points: [[37.55, 126.97], [35.1152, 129.0415]], distance: 400000, duration: 16000 })
});
const handler = api.listeners('request')[0];
api.removeListener('request', handler);
api.on('request', async (req, res) => {
  if (req.method !== 'GET' || !['/api/preview-instance','/api/invite-origin'].includes(req.url)) return handler(req, res);
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Cache-Control', 'no-store');
  if (req.url === '/api/preview-instance') return res.end(JSON.stringify({ instance }));
  // This lookup is for the emulator only. Do not trigger outside requests on behalf of tunnel visitors.
  if (req.headers['cf-ray'] || req.headers['x-forwarded-for']) { res.statusCode = 403; return res.end('{}'); }
  try {
    const read = async url => (await fetch(url, { signal: AbortSignal.timeout(3500), redirect: 'error' })).json();
    const {hostname} = await read('http://127.0.0.1:20241/quicktunnel');
    if (!/^[a-z0-9-]+\.trycloudflare\.com$/.test(hostname)) throw Error();
    const origin = 'https://' + hostname;
    if ((await read(origin + '/api/preview-instance')).instance !== instance) throw Error();
    res.end(JSON.stringify({origin}));
  } catch { res.end(JSON.stringify({origin:''})); }
});
api.listen(port, '127.0.0.1', () => console.log('미리보기: http://127.0.0.1:' + api.address().port));
