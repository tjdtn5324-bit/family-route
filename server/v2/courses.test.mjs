// 추천 코스: 참고 코스 + 가족이 다녀온 여행을 공개한 코스, 좋아요·후기·요즘 인기.
// 규칙: 공개 코스에는 가족·사람 이름과 사진이 없다. 추천 코스로 다녀온 여행은 다시 공개하지 않는다.
// 자기 가족 코스에는 좋아요·후기를 남길 수 없다. 가족이 사라지면 그 가족의 코스도 사라진다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Service } from './service.mjs';
import { createServer } from './http.mjs';

const place = (name, lat, lng = 129) => ({ name, latitude: lat, longitude: lng, address: '부산' });
const DAY = 86400000;

async function world() {
  let now = Date.UTC(2026, 10, 1);
  const s = new Service({ now: () => now, directions: async c => ({ points: [[37, 127], [c.destination.latitude, c.destination.longitude]], distance: 1000, duration: 60 }) });
  const signup = async (email, name) => s.userActor((await s.signup({ email, password: 'password-1234', name })).token);
  const relog = async email => s.userActor((await s.login({ email, password: 'password-1234' })).token);
  const a1 = await signup('a1@x.com', '에이엄마'); s.createFamily(a1, { name: '에이네 가족' });
  const code = s.createInvite(await relog('a1@x.com')).code;
  const a2 = await signup('a2@x.com', '에이아이'); s.joinFamily(a2, { code });
  const b1 = await signup('b1@x.com', '비아빠'); s.createFamily(b1, { name: '비네 가족' });
  const c1 = await signup('c1@x.com', '씨엄마'); s.createFamily(c1, { name: '씨네 가족' });
  const users = { a1: await relog('a1@x.com'), a2: await relog('a2@x.com'), b1: await relog('b1@x.com'), c1: await relog('c1@x.com') };
  return { s, ...users, relog, tick: ms => { now += ms; } };
}
/** A 가족이 부산 여행을 다녀온다(경유지 2곳 중 1곳만 공개 예정). */
async function doneTrip(s, a1, a2) {
  let t = await s.createTrip(a1, { title: '우리끼리 부산', destination: place('부산역', 35.115), driverUserId: a1.user.id });
  t = s.proposePlace(a2, t.id, { place: place('비밀 맛집', 35.12), reason: '할머니 단골집', message: '엄마 생일' });
  t = s.proposePlace(a2, t.id, { place: place('광안리', 35.153), reason: '야경', message: '' });
  t = await s.decide(a1, t.proposals[0].id, { decision: 'CONFIRM' });
  t = await s.decide(a1, t.proposals[0].id, { decision: 'CONFIRM' });
  s.startTrip(a1, t.id);
  s.addPhoto(a2, t.id, { data: Buffer.from([0xff, 0xd8, 0xff, 1]).toString('base64') });
  return s.finishTrip(a1, t.id);
}

test('참고 코스 8개는 로그인 없이 볼 수 있고, 다녀온 여행을 고른 장소만 코스로 공개한다', async () => {
  const { s, a1, a2, b1 } = await world();
  assert.equal(s.listCourses(null).length, 8);
  const trip = await doneTrip(s, a1, a2);
  // 일반 구성원(만든 사람·운전자 아님)은 공개할 수 없다.
  assert.throws(() => s.publishCourse(a2, { tripId: trip.id, placeIds: [] }), e => e.status === 403);
  const keep = trip.stops.find(p => p.place.name === '광안리').id;
  const course = s.publishCourse(a1, { tripId: trip.id, title: '부산 바다 코스', description: '야경이 좋아요', placeIds: [keep] });
  assert.deepEqual(course.waypoints.map(w => w.name), ['광안리']);
  assert.throws(() => s.publishCourse(a1, { tripId: trip.id, placeIds: [] }), e => e.status === 409); // 한 여행은 한 번만
  // 공개 내용에 가족 이름·사람 이름·제안 이유·숨긴 장소·사진이 없다.
  const seen = JSON.stringify(s.courseDetail(b1, course.id)) + JSON.stringify(s.listCourses(null));
  for (const secret of ['에이네 가족', '에이엄마', '에이아이', '비밀 맛집', '할머니', '엄마 생일', a1.family.id, a1.user.id]) assert.equal(seen.includes(secret), false, secret);
  assert.equal(s.listCourses(null, { sort: 'new' })[0].id, course.id);
  assert.equal(s.listCourses(null, { kind: 'COMMUNITY' }).length, 1);
  assert.equal(s.album(a1)[0].publishedCourseId, course.id);
});

test('다른 가족만 좋아요·후기를 남기고, 이번 주 좋아요가 많으면 요즘 인기가 된다', async () => {
  const { s, a1, a2, b1, c1, tick } = await world();
  const trip = await doneTrip(s, a1, a2);
  const course = s.publishCourse(a1, { tripId: trip.id, placeIds: [] });
  assert.throws(() => s.likeCourse(a2, course.id, {}), e => e.status === 409);
  assert.throws(() => s.reviewCourse(a1, course.id, { rating: 5, text: '최고' }), e => e.status === 409);
  s.likeCourse(b1, course.id, {}); s.likeCourse(b1, course.id, {}); // 두 번 눌러도 한 번
  let d = s.likeCourse(c1, course.id, { liked: true });
  assert.equal(d.likes, 2); assert.equal(d.trending, true);
  assert.equal(s.listCourses(null, { sort: 'trending' })[0].id, course.id);
  d = s.reviewCourse(b1, course.id, { rating: 4, text: '아이들이 좋아했어요' });
  d = s.reviewCourse(b1, course.id, { rating: 5, text: '다시 가도 좋아요' }); // 한 사람 한 후기(고쳐 쓰기)
  assert.equal(d.reviewCount, 1); assert.equal(d.rating, 5); assert.equal(d.reviews[0].mine, true);
  assert.throws(() => s.reviewCourse(c1, course.id, { rating: 6, text: 'x' }), e => e.status === 400);
  assert.throws(() => s.reviewCourse(c1, course.id, { rating: 3, text: '  ' }), e => e.status === 400);
  // 후기에는 쓴 사람 이름이 없다.
  assert.equal(JSON.stringify(s.courseDetail(null, course.id)).includes('비아빠'), false);
  // 8일이 지나면 이번 주 좋아요는 0, 전체 좋아요는 그대로.
  tick(8 * DAY);
  d = s.courseDetail(null, course.id); assert.equal(d.likesThisWeek, 0); assert.equal(d.likes, 2); assert.equal(d.trending, false);
  d = s.likeCourse(c1, course.id, { liked: false }); assert.equal(d.likes, 1);
  d = s.deleteMyReview(b1, course.id); assert.equal(d.reviewCount, 0);
});

test('추천 코스로 만든 여행은 코스 장소를 담아 시작하고, 다녀와도 다시 공개할 수 없다', async () => {
  const { s, b1 } = await world();
  const ref = s.listCourses(null).find(c => c.id === 'guide-busan-sea');
  let t = await s.createTrip(b1, { courseId: ref.id });
  assert.equal(t.title, ref.title); assert.equal(t.destination.name, ref.destination.name);
  assert.deepEqual(t.stops.map(p => p.place.name), ref.waypoints.map(w => w.name));
  assert.ok(t.route); assert.equal(t.course.id, ref.id);
  s.startTrip(b1, t.id); s.finishTrip(b1, t.id);
  assert.throws(() => s.publishCourse(b1, { tripId: t.id, placeIds: [] }), e => e.status === 409 && /다시 공개할 수 없어요/.test(e.message));
  // 대신 좋아요·후기는 남길 수 있고, 다녀온 가족으로 표시된다.
  const d = s.reviewCourse(b1, ref.id, { rating: 5, text: '코스 그대로 좋았어요' });
  assert.equal(d.wentWithFamily, true);
  await assert.rejects(s.createTrip(b1, { courseId: 'no-such-course' }), e => e.status === 404);
});

test('공개 취소 권한, 가족 삭제 시 코스 정리, 저장 후 복원', async () => {
  const { s, a1, a2, b1, relog } = await world();
  const trip = await doneTrip(s, a1, a2);
  const course = s.publishCourse(a1, { tripId: trip.id, placeIds: [] });
  s.likeCourse(b1, course.id, {}); s.reviewCourse(b1, course.id, { rating: 5, text: '좋아요' });
  assert.throws(() => s.unpublishCourse(b1, course.id), e => e.status === 404); // 다른 가족은 내릴 수 없다
  // 저장했다가 다시 열어도 그대로.
  const r = Service.fromSnapshot(structuredClone(s.exportSnapshot()));
  const d = r.courseDetail(null, course.id); assert.equal(d.likes, 1); assert.equal(d.reviewCount, 1);
  // 마지막 구성원까지 나가면 가족이 지워지고 그 가족의 코스·좋아요·후기도 사라진다.
  s.leaveFamily(a2, {}); s.leaveFamily(await relog('a1@x.com'), {});
  assert.throws(() => s.courseDetail(null, course.id), e => e.status === 404);
  assert.equal(s.listCourses(null).length, 8);
});

test('HTTP: 로그인 없이 둘러보고, 좋아요는 로그인해야 하며, 차량은 둘러보기만 한다', async t => {
  const service = new Service();
  const server = createServer({ service });
  await new Promise(r => server.listen(0, '127.0.0.1', r)); t.after(() => new Promise(r => { server.close(r); server.closeAllConnections(); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (path, data, headers = {}) => { const r = await fetch(base + path, { method: data ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...headers }, body: data ? JSON.stringify(data) : undefined }); return { status: r.status, body: await r.json() }; };
  const list = await call('/api/courses?sort=popular');
  assert.equal(list.status, 200); assert.equal(list.body.courses.length, 8);
  assert.equal((await call('/api/courses/guide-busan-sea')).body.waypoints.length, 2);
  assert.equal((await call('/api/courses/guide-busan-sea/like', {})).status, 401);
  assert.equal((await call('/api/courses/guide-busan-sea', undefined, { Authorization: 'Bearer not-a-car' })).status, 200);
});

test('추천 코스로 갈 때 중간에 경유지를 빼거나 새 경유지를 추가할 수 있다(출발 전·이동 중·차량)', async () => {
  const { s, b1 } = await world();
  const ref = s.listCourses(null).find(c => c.id === 'guide-busan-sea');
  // 1) 출발 전 추천 코스의 경유지 중 일부만 골라 여행 시작
  let t = await s.createTrip(b1, { courseId: ref.id, waypoints: [ref.waypoints[0]] });
  assert.deepEqual(t.stops.map(p => p.place.name), [ref.waypoints[0].name]);
  // 2) 차량 연결 후 차량에서 추천 코스 적용 및 중간 경유지 추가·빼기
  const pair = s.pairVehicle({ code: s.vehicleCode(b1).code, name: '아이오닉 5' });
  const car = s.vehicleActor(pair.vehicleToken);
  t = await s.applyCourse(car, t.id, { courseId: ref.id });
  assert.deepEqual(t.stops.map(p => p.place.name), ref.waypoints.map(w => w.name));
  // 출발(ONGOING) 후 중간에 새 경유지 추가 및 기존 추천 여행지 빼기
  s.startTrip(car, t.id);
  t = await s.addStop(car, t.id, { place: place('해운대 카페', 35.16) });
  assert.equal(t.stops.length, 3);
  assert.equal(t.stops[2].place.name, '해운대 카페');
  t = await s.removeProposal(car, t.stops[0].id);
  assert.deepEqual(t.stops.map(p => p.place.name), [ref.waypoints[1].name, '해운대 카페']);
});
