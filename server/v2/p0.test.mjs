// P0 기본 기능 검증 체크리스트를 HTTP 요청으로 그대로 확인한다.
// 각 test 이름 앞의 번호가 체크리스트 항목 번호다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './http.mjs';
import { Service } from './service.mjs';

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]).toString('base64');

async function start(t) {
  const service = new Service();
  const server = createServer({ service, search: async q => [{ name: q, latitude: 35.1, longitude: 129, address: '부산' }], directions: async c => ({ points: [[37, 127], [c.destination.latitude, c.destination.longitude]], distance: 1000, duration: 60 }) });
  await new Promise(r => server.listen(0, '127.0.0.1', r)); t.after(() => new Promise(r => { server.close(r); server.closeAllConnections(); }));
  return { base: `http://127.0.0.1:${server.address().port}`, service };
}
/** 브라우저 한 개를 흉내 낸다: 쿠키를 기억하고 다음 요청에 보낸다. */
function browser(base) {
  let cookie = '';
  return {
    get cookie() { return cookie; },
    async call(path, { method, data, bearer } = {}) {
      const r = await fetch(base + path, { method: method || (data ? 'POST' : 'GET'), headers: { 'Content-Type': 'application/json', ...(cookie && !bearer ? { Cookie: cookie } : {}), ...(bearer ? { Authorization: 'Bearer ' + bearer } : {}) }, body: data ? JSON.stringify(data) : undefined });
      const set = r.headers.get('set-cookie');
      if (set) { const v = set.split(';')[0]; cookie = v.endsWith('=') ? '' : v; }
      let body = null; try { body = await r.json(); } catch {}
      return { status: r.status, body };
    },
  };
}
const signup = (b, email, name) => b.call('/api/auth/signup', { data: { email, password: 'password-' + name, name } });

test('1. 사용자 A와 B가 각자 독립적으로 가입·로그인할 수 있다', async t => {
  const { base } = await start(t), a = browser(base), b = browser(base);
  assert.equal((await signup(a, 'a@example.com', '엄마')).status, 201);
  assert.equal((await signup(b, 'b@example.com', '아빠')).status, 201);
  assert.equal((await signup(browser(base), 'A@example.com', '사칭')).status, 409); // 같은 이메일 중복 가입 불가
  assert.equal((await a.call('/api/auth/logout', { data: {} })).status, 200);
  assert.equal((await a.call('/api/me')).status, 401);
  assert.equal((await a.call('/api/auth/login', { data: { email: 'a@example.com', password: 'wrong-password' } })).status, 401);
  assert.equal((await a.call('/api/auth/login', { data: { email: 'A@Example.com', password: 'password-엄마' } })).status, 200);
  assert.equal((await a.call('/api/me')).body.user.name, '엄마');
  assert.equal((await b.call('/api/me')).body.user.name, '아빠');
});

test('2. A가 로그인해도 B의 가족·여행·개인 정보가 보이지 않는다', async t => {
  const { base } = await start(t), a = browser(base), b = browser(base);
  await signup(a, 'a@example.com', '엄마'); await signup(b, 'b@example.com', '아빠');
  await b.call('/api/family', { data: { name: 'B네 가족' } });
  const trip = (await b.call('/api/trips', { data: { title: 'B네 부산', destination: { name: '부산역', latitude: 35.11, longitude: 129.04 } } })).body;
  await b.call(`/api/trips/${trip.id}/proposals`, { data: { place: { name: '시장', latitude: 35.1, longitude: 129 }, reason: '비밀 이유' } });
  const photo = (await b.call(`/api/trips/${trip.id}/photos`, { data: { data: JPEG } })).body;
  const me = (await a.call('/api/me')).body;
  assert.equal(me.family, null); assert.equal(JSON.stringify(me).includes('B네'), false);
  assert.equal((await a.call('/api/trips')).status, 404);
  assert.equal((await a.call(`/api/trips/${trip.id}`)).status, 404);
  assert.equal((await a.call(`/api/photos/${photo.id}`)).status, 404);
  assert.equal((await a.call('/api/album')).status, 404);
  // A가 자기 가족을 만들어도 B의 여행은 보이지 않는다.
  await a.call('/api/family', { data: { name: 'A네 가족' } });
  assert.deepEqual((await a.call('/api/trips')).body.trips, []);
  assert.equal((await a.call(`/api/trips/${trip.id}`)).status, 404);
  assert.equal((await a.call(`/api/trips/${trip.id}/proposals`, { data: { place: { name: '끼어들기', latitude: 1, longitude: 1 } } })).status, 404);
  assert.equal((await a.call(`/api/photos/${photo.id}`, { method: 'DELETE' })).status, 404);
});

test('3. 같은 브라우저에서 A 로그아웃 후 B 로그인 시 기존 정보가 남지 않는다', async t => {
  const { base } = await start(t), shared = browser(base);
  await signup(shared, 'a@example.com', '엄마');
  await shared.call('/api/family', { data: { name: 'A네 가족' } });
  const aCookie = shared.cookie;
  await shared.call('/api/auth/logout', { data: {} });
  assert.equal(shared.cookie, '');
  // 로그아웃한 A의 세션 값은 서버에서 지워져 다시 쓸 수 없다.
  const replay = await fetch(base + '/api/me', { headers: { Cookie: aCookie } });
  assert.equal(replay.status, 401);
  await signup(shared, 'b@example.com', '아빠');
  const me = (await shared.call('/api/me')).body;
  assert.equal(me.user.name, '아빠'); assert.equal(me.family, null);
  assert.equal((await shared.call('/api/trips')).status, 404);
});

test('4. A와 B가 동시에 접속해도 서로의 세션이 영향을 받지 않는다', async t => {
  const { base } = await start(t), a = browser(base), b = browser(base), a2 = browser(base);
  await signup(a, 'a@example.com', '엄마'); await signup(b, 'b@example.com', '아빠');
  await a2.call('/api/auth/login', { data: { email: 'a@example.com', password: 'password-엄마' } }); // A의 두 번째 기기
  const results = await Promise.all([a.call('/api/me'), b.call('/api/me'), a2.call('/api/me'), a.call('/api/me'), b.call('/api/me')]);
  assert.deepEqual(results.map(r => r.body.user.name), ['엄마', '아빠', '엄마', '엄마', '아빠']);
  await b.call('/api/auth/logout', { data: {} });
  assert.equal((await a.call('/api/me')).status, 200); assert.equal((await a2.call('/api/me')).status, 200);
  await a.call('/api/auth/logout', { data: {} });
  assert.equal((await a2.call('/api/me')).status, 200); // 한 기기에서 로그아웃해도 다른 기기는 유지
});

test('5. 가족 초대와 가입, 일반 구성원 탈퇴가 정상적으로 작동한다', async t => {
  const { base } = await start(t), a = browser(base), b = browser(base), c = browser(base);
  await signup(a, 'a@example.com', '엄마'); await signup(b, 'b@example.com', '아빠'); await signup(c, 'c@example.com', '할머니');
  await a.call('/api/family', { data: { name: '우리 가족' } });
  const invite = (await a.call('/api/family/invite', { data: {} })).body;
  assert.match(invite.code, /^[A-Z2-9]{8}$/);
  assert.equal((await b.call('/api/family/invite?code=' + invite.code)).body.familyName, '우리 가족');
  assert.equal((await b.call('/api/family/join', { data: { code: 'WRONG123' } })).status, 404);
  assert.equal((await b.call('/api/family/join', { data: { code: invite.code.toLowerCase() } })).status, 200);
  assert.equal((await c.call('/api/family/join', { data: { code: invite.code } })).status, 200);
  assert.deepEqual((await a.call('/api/me')).body.family.members.map(m => [m.name, m.role]), [['엄마', 'ADMIN'], ['아빠', 'MEMBER'], ['할머니', 'MEMBER']]);
  // 한 사람은 한 가족에만: 다른 가족에 들어가려면 먼저 나와야 한다.
  const d = browser(base); await signup(d, 'd@example.com', '삼촌'); await d.call('/api/family', { data: { name: '삼촌네' } });
  const other = (await d.call('/api/family/invite', { data: {} })).body;
  assert.equal((await b.call('/api/family/join', { data: { code: other.code } })).status, 409);
  // 일반 구성원은 언제든 나갈 수 있다.
  assert.equal((await b.call('/api/family/leave', { data: {} })).status, 200);
  assert.equal((await b.call('/api/me')).body.family, null);
  assert.equal((await b.call('/api/family/join', { data: { code: other.code } })).status, 200);
  // 관리자가 구성원을 내보내면 즉시 권한이 없어진다.
  const cMember = (await a.call('/api/me')).body.family.members.find(m => m.name === '할머니');
  assert.equal((await b.call(`/api/family/members/${cMember.id}/remove`, { data: {} })).status, 403);
  assert.equal((await a.call(`/api/family/members/${cMember.id}/remove`, { data: {} })).status, 200);
  assert.equal((await c.call('/api/trips')).status, 404);
});

test('6. 마지막 가족 관리자도 권한 이전 또는 그룹 정리 후 탈퇴할 수 있다', async t => {
  const { base } = await start(t), a = browser(base), b = browser(base);
  await signup(a, 'a@example.com', '엄마'); await signup(b, 'b@example.com', '아빠');
  await a.call('/api/family', { data: { name: '우리 가족' } });
  const code = (await a.call('/api/family/invite', { data: {} })).body.code;
  await b.call('/api/family/join', { data: { code } });
  const bId = (await b.call('/api/me')).body.user.id;
  // 다른 가족이 있으면 관리자를 넘겨야 나갈 수 있다.
  assert.equal((await a.call('/api/family/leave', { data: {} })).status, 409);
  assert.equal((await a.call('/api/family/leave', { data: { transferTo: bId } })).status, 200);
  assert.equal((await b.call('/api/me')).body.family.myRole, 'ADMIN');
  // 마지막 한 사람이 나가면 가족과 그 안의 여행이 정리된다.
  const trip = (await b.call('/api/trips', { data: { title: '마지막 여행' } })).body;
  const r = await b.call('/api/family/leave', { data: {} });
  assert.equal(r.status, 200); assert.equal(r.body.familyDeleted, true);
  assert.equal((await b.call(`/api/trips/${trip.id}`)).status, 404);
  assert.equal((await b.call('/api/family/join', { data: { code } })).status, 404); // 지워진 가족의 초대도 쓸 수 없다
});

test('7. 가족에서 탈퇴한 사용자는 이전 가족의 비공개 데이터에 접근할 수 없다', async t => {
  const { base } = await start(t), a = browser(base), b = browser(base);
  await signup(a, 'a@example.com', '엄마'); await signup(b, 'b@example.com', '아빠');
  await a.call('/api/family', { data: { name: '우리 가족' } });
  await b.call('/api/family/join', { data: { code: (await a.call('/api/family/invite', { data: {} })).body.code } });
  const trip = (await a.call('/api/trips', { data: { title: '부산' } })).body;
  const photo = (await b.call(`/api/trips/${trip.id}/photos`, { data: { data: JPEG } })).body;
  const keep = (await b.call(`/api/trips/${trip.id}/photos`, { data: { data: JPEG } })).body;
  assert.equal((await b.call(`/api/photos/${photo.id}`)).status, 200);
  await b.call('/api/family/leave', { data: { deleteMyPhotos: true } });
  for (const path of [`/api/trips/${trip.id}`, `/api/photos/${photo.id}`, '/api/trips', '/api/album']) assert.equal((await b.call(path)).status, 404, path);
  assert.equal((await b.call(`/api/trips/${trip.id}/proposals`, { data: { place: { name: 'x', latitude: 1, longitude: 1 } } })).status, 404);
  // "내 사진도 지우기"를 고르면 가족 앨범에서도 사라진다.
  assert.equal((await a.call(`/api/photos/${keep.id}`)).status, 404);
  // 차량도 가족에서 지우면 즉시 접근이 끊긴다.
  const code = (await a.call('/api/family/vehicle-code', { data: {} })).body.code;
  const car = (await browser(base).call('/api/vehicle/pair', { data: { code, name: '아이오닉' } })).body;
  assert.equal((await a.call('/api/trips', { bearer: car.vehicleToken })).status, 200);
  const vehicleId = (await a.call('/api/me')).body.family.vehicles[0].id;
  await a.call(`/api/family/vehicles/${vehicleId}`, { method: 'DELETE' });
  assert.equal((await a.call('/api/trips', { bearer: car.vehicleToken })).status, 401);
});

test('8. 여행·장소·사진 삭제 시 다른 가족 그룹의 데이터는 영향을 받지 않는다', async t => {
  const { base } = await start(t), a = browser(base), b = browser(base);
  await signup(a, 'a@example.com', '엄마'); await signup(b, 'b@example.com', '아빠');
  await a.call('/api/family', { data: { name: 'A네' } }); await b.call('/api/family', { data: { name: 'B네' } });
  const make = async who => {
    const trip = (await who.call('/api/trips', { data: { title: '여행', destination: { name: '부산역', latitude: 35.11, longitude: 129.04 } } })).body;
    const proposal = (await who.call(`/api/trips/${trip.id}/proposals`, { data: { place: { name: '시장', latitude: 35.1, longitude: 129 } } })).body.proposals[0];
    const photo = (await who.call(`/api/trips/${trip.id}/photos`, { data: { data: JPEG } })).body;
    return { trip, proposal, photo };
  };
  const A = await make(a), B = await make(b);
  // A가 B의 자료를 지우려 하면 실패하고, B의 자료는 그대로다.
  for (const [path, method] of [[`/api/trips/${B.trip.id}`, 'DELETE'], [`/api/proposals/${B.proposal.id}`, 'DELETE'], [`/api/photos/${B.photo.id}`, 'DELETE']]) assert.equal((await a.call(path, { method })).status, 404);
  // A가 자기 자료를 지워도 B는 영향이 없다.
  await a.call(`/api/photos/${A.photo.id}`, { method: 'DELETE' });
  await a.call(`/api/proposals/${A.proposal.id}`, { method: 'DELETE' });
  await a.call(`/api/trips/${A.trip.id}`, { method: 'DELETE' });
  const bTrip = (await b.call(`/api/trips/${B.trip.id}`)).body;
  assert.equal(bTrip.proposals.length, 1); assert.equal(bTrip.photos.length, 1);
  assert.equal((await b.call(`/api/photos/${B.photo.id}`)).status, 200);
  assert.equal((await a.call('/api/trips')).body.trips.length, 0);
});

test('로그인을 여러 번 틀리면 잠시 막고, 비밀번호는 저장 파일에 그대로 남지 않는다', async t => {
  const { base, service } = await start(t), a = browser(base);
  await signup(a, 'a@example.com', '엄마');
  for (let i = 0; i < 5; i++) await browser(base).call('/api/auth/login', { data: { email: 'a@example.com', password: 'nope-nope' } });
  assert.equal((await browser(base).call('/api/auth/login', { data: { email: 'a@example.com', password: 'password-엄마' } })).status, 429);
  const snapshot = JSON.stringify(service.exportSnapshot());
  assert.equal(snapshot.includes('password-엄마'), false);
  assert.equal(snapshot.includes(a.cookie.split('=')[1]), false); // 세션 값도 해시로만 저장
});

test('차량은 가족이 준 숫자 6자리로만 연결되고, 스스로 연결을 끊을 수 있으며, 가족 관리는 못 한다', async t => {
  const { base } = await start(t), a = browser(base), car = browser(base);
  await signup(a, 'a@example.com', '엄마'); await a.call('/api/family', { data: { name: '우리 가족' } });
  const { code } = (await a.call('/api/family/vehicle-code', { data: {} })).body;
  assert.match(code, /^\d{6}$/);
  assert.equal((await car.call('/api/vehicle/pair', { data: { code: '000000' === code ? '111111' : '000000' } })).status, 404);
  const paired = (await car.call('/api/vehicle/pair', { data: { code, name: '아이오닉 5' } })).body;
  assert.equal((await car.call('/api/vehicle/pair', { data: { code } })).status, 404); // 한 번만
  const token = paired.vehicleToken;
  assert.equal((await car.call('/api/vehicle/home', { bearer: token })).body.family.name, '우리 가족');
  assert.equal((await car.call('/api/family/invite', { data: {}, bearer: token })).status, 401); // 차량 토큰은 사람 계정이 아니다
  assert.equal((await car.call('/api/trips', { data: { title: '차가 만든 여행' }, bearer: token })).status, 403);
  assert.equal((await car.call('/api/vehicle/pair', { method: 'DELETE', bearer: token })).status, 200);
  assert.equal((await car.call('/api/trips', { bearer: token })).status, 401);
  assert.equal((await a.call('/api/me')).body.family.vehicles.length, 0);
});

test('차량 OS에서 가족방과 여행방을 먼저 만들고, 발급된 초대 코드(QR)로 휴대폰 참가자들이 참여할 수 있다', async t => {
  const { base } = await start(t), car = browser(base), dad = browser(base), mom = browser(base);
  const created = (await car.call('/api/vehicle/family', { data: { familyName: '주말 나들이 가족방', vehicleName: '아빠 차' } }));
  assert.equal(created.status, 201);
  const token = created.body.vehicleToken;
  const inviteCode = created.body.family.inviteCode;
  assert.match(inviteCode, /^[A-Z0-9]{8}$/);

  // 차량에서 여행방도 바로 만들 수 있다
  const tripCreated = await car.call('/api/vehicle/trips', {
    data: { title: '강릉 바다 가족여행', destination: { name: '강릉 안목해변', address: '강원 강릉시', latitude: 37.7726, longitude: 128.9472 } },
    bearer: token
  });
  assert.equal(tripCreated.status, 201);
  assert.equal(tripCreated.body.title, '강릉 바다 가족여행');

  // 휴대폰 참가자 1(아빠)이 로그인 후 QR 초대 코드로 미리보기 후 참여 -> 첫 참가자는 자동으로 관리자(ADMIN)가 된다
  await signup(dad, 'dad@example.com', '아빠');
  const preview = (await dad.call(`/api/family/invite?code=${inviteCode}`)).body;
  assert.equal(preview.familyName, '주말 나들이 가족방');
  assert.equal(preview.memberCount, 0);
  const dadJoined = await dad.call('/api/family/join', { data: { code: inviteCode } });
  assert.equal(dadJoined.status, 200);
  assert.equal(dadJoined.body.myRole, 'ADMIN');

  // 휴대폰 참가자 2(엄마)도 같은 QR 초대 코드로 참여 -> 일반 구성원(MEMBER)으로 참여하고 같은 여행방을 본다
  await signup(mom, 'mom@example.com', '엄마');
  const momJoined = await mom.call('/api/family/join', { data: { code: inviteCode } });
  assert.equal(momJoined.status, 200);
  assert.equal(momJoined.body.myRole, 'MEMBER');
  const momTrips = (await mom.call('/api/trips')).body.trips;
  assert.equal(momTrips.length, 1);
  assert.equal(momTrips[0].title, '강릉 바다 가족여행');

  // 차량 홈에서도 참가자 2명과 현재 초대 코드가 보인다
  const home = (await car.call('/api/vehicle/home', { bearer: token })).body;
  assert.equal(home.family.members.length, 2);
  assert.equal(home.family.inviteCode, inviteCode);
});

