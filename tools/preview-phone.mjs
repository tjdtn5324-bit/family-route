// 휴대폰 화면 미리보기 전용. 카카오·AWS 없이 가짜 검색과 가짜 경로로 서버를 띄운다.
// 실행: node tools/preview-phone.mjs  →  http://127.0.0.1:8790
import { createApp } from '../server/app.mjs';
import { Rooms } from '../server/rooms.mjs';
// 포트를 인자로 바꿀 수 있다. 차량 앱(에뮬레이터)은 8789를 본다: node tools/preview-phone.mjs 8789
const port = Number(process.argv[2] || 8790);
const places = [
  { name: '부산역', latitude: 35.1152, longitude: 129.0415, address: '부산 동구 중앙대로 206' },
  { name: '광안리해수욕장', latitude: 35.1532, longitude: 129.1187, address: '부산 수영구 광안해변로 219' },
  { name: '감천문화마을', latitude: 35.0974, longitude: 129.0106, address: '부산 사하구 감천동 1-17' },
  { name: '해운대해수욕장', latitude: 35.1587, longitude: 129.1604, address: '부산 해운대구 우동' }
];
createApp({
  apiKey: 'preview',
  rooms: new Rooms({ sampleCourses: true }),
  search: async q => places.filter(p => !q || p.name.includes(q) || p.address.includes(q) || q.includes('부산') || /busan/i.test(q)),
  directions: async c => ({ points: [[37.55, 126.97], [35.1152, 129.0415]], distance: 400000, duration: 16000 })
}).listen(port, '127.0.0.1', () => console.log('미리보기: http://127.0.0.1:' + port));
