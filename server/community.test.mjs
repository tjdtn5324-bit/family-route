import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from './app.mjs';
import { Rooms, regionOf } from './rooms.mjs';

test('주소에서 여행 지역 이름을 뽑는다', () => {
  assert.equal(regionOf({ address: '부산 동구 중앙대로 206' }), '부산');
  assert.equal(regionOf({ address: '부산광역시' }), '부산');
  assert.equal(regionOf({ address: '강원특별자치도 강릉시 용지로 176' }), '강릉');
  assert.equal(regionOf({ address: '경북 경주시 인왕동 839-1' }), '경주');
  // 알려진 시·도 주소가 아니면 지역 이름을 내지 않는다(아무 글이나 지역으로 올라가지 않게).
  assert.equal(regionOf({ name: '어딘가', address: '' }), '');
  assert.equal(regionOf({ address: '무료 쿠폰 받기 광고' }), '');
  assert.equal(regionOf({ address: '경기 광주시 오포로 1' }), '경기 광주');
  assert.equal(regionOf({ address: '광주 동구 금남로 1' }), '광주');
});

test('후기는 로그인한 본인만 쓰고 지우며, 별점 평균과 요즘 인기 순서에 반영된다', async t => {
  const rooms = new Rooms({ sampleCourses: true });
  const profile = { config: {}, store: {}, verify: async token => { if (!['a', 'b'].includes(token)) throw Error('bad'); return 'user-' + token; } };
  const app = createApp({ rooms, profile, directions: async () => ({ points: [[37, 127], [35, 129]], distance: 1, duration: 1 }) });
  await new Promise(r => app.listen(0, '127.0.0.1', r)); t.after(() => new Promise(r => app.close(r)));
  const base = `http://127.0.0.1:${app.address().port}`;
  const call = async (path, { token, data, method } = {}) => { const r = await fetch(base + path, { method: method || (data ? 'POST' : 'GET'), headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: data ? JSON.stringify(data) : undefined }); return { status: r.status, data: await r.json() }; };

  // 가족이 공유한 코스 두 개를 만든다.
  const room = (await call('/api/rooms', { data: {} })).data, member = (await call('/api/join', { data: { invite: room.invite, name: '가족' } })).data;
  await call('/api/destination', { token: room.driverToken, data: { place: { name: '부산역', latitude: 35.115, longitude: 129.042, address: '부산 동구' } } });
  const first = (await call('/api/courses', { token: member.memberToken, data: { title: '첫 코스' } })).data;
  // 같은 장소로 다시 올리면 거절되고, 다른 경로면 올라간다.
  assert.equal((await call('/api/courses', { token: member.memberToken, data: { title: '같은 코스 또 올리기' } })).status, 400);
  await call('/api/destination', { token: room.driverToken, data: { place: { name: '해운대', latitude: 35.158, longitude: 129.16, address: '부산 해운대구' } } });
  const second = (await call('/api/courses', { token: member.memberToken, data: { title: '둘째 코스' } })).data;
  assert.equal(second.mine, true); assert.equal('ownerRoomId' in second, false);

  assert.equal((await call(`/api/courses/${second.id}/reviews`, { data: { rating: 5, text: '좋았어요' } })).status, 401);
  assert.equal((await call(`/api/courses/${second.id}/reviews`, { token: 'a', data: { rating: 6 } })).status, 400);
  assert.equal((await call('/api/courses/sample-busan-sea/reviews', { token: 'a', data: { rating: 5 } })).status, 400);
  assert.equal((await call(`/api/courses/${second.id}/reviews`, { token: 'a', data: { rating: 5, text: '아이와 가기 좋았어요', name: '민지 엄마' } })).status, 200);
  assert.equal((await call(`/api/courses/${second.id}/reviews`, { token: 'a', data: { rating: 4, text: '다시 써요' } })).data.count, 1);
  const afterB = (await call(`/api/courses/${second.id}/reviews`, { token: 'b', data: { rating: 2, text: '주차가 어려웠어요' } })).data;
  assert.equal(afterB.count, 2); assert.equal(afterB.rating, 3);
  assert.equal(afterB.reviews.filter(r => r.mine).length, 1);

  // 읽기는 누구나 할 수 있고 계정 정보는 나가지 않는다.
  const open = await call(`/api/courses/${second.id}/reviews`);
  assert.equal(open.status, 200); assert.equal(open.data.reviews.some(r => r.mine), false);
  assert.equal(JSON.stringify(open.data).includes('user-'), false);

  // 좋아요순(기본)은 그대로이고, 요즘 인기는 최근 활동이 많은 코스가 먼저다.
  await call(`/api/courses/${first.id}/like`, { token: 'a', data: { liked: true } });
  const trending = (await call('/api/public/courses?sort=trending')).data.courses;
  assert.equal(trending[0].id, second.id); assert.equal(trending[0].reviewCount, 2); assert.ok(trending[0].trendScore > trending[1].trendScore);
  assert.equal((await call('/api/public/courses')).data.courses[0].id, first.id);
  assert.equal((await call('/api/public/courses?kind=official')).data.courses.every(c => c.official), true);
  assert.equal((await call('/api/public/courses?kind=community')).data.courses.length, 2);

  const hot = (await call('/api/public/trending')).data;
  assert.equal(hot.regions[0].region, '부산'); assert.equal(hot.courses[0].id, second.id);

  // 다른 사람의 후기는 지워지지 않는다.
  assert.equal((await call(`/api/courses/${second.id}/reviews`, { token: 'a', method: 'DELETE' })).data.count, 1);
  assert.equal((await call(`/api/courses/${second.id}/reviews`)).data.reviews[0].text, '주차가 어려웠어요');
});

test('좋아요와 후기는 저장했다가 다시 불러와도 남는다', () => {
  const rooms = new Rooms({ sampleCourses: true });
  rooms.reviewCourse('u1', 'guide-busan-market', { rating: 5, text: '시장 구경이 즐거웠어요' });
  const restored = Rooms.fromSnapshot(JSON.parse(JSON.stringify(rooms.exportSnapshot())), { sampleCourses: true });
  const course = restored.publicCourses('', { kind: 'official' }).find(c => c.id === 'guide-busan-market');
  assert.equal(course.reviewCount, 1); assert.equal(course.rating, 5); assert.ok(course.trendScore > 0);
});

test('인기 점수는 한 사람이 반복해도 오르지 않고, 취소하면 빠진다', () => {
  const rooms = new Rooms({ sampleCourses: true }), room = rooms.create('가족'), member = rooms.join(room.invite, '가족');
  const context = rooms.destinationContext(room.driverToken, { name: '부산역', latitude: 35.115, longitude: 129.042, address: '부산 동구' });
  rooms.applyDestination(room.driverToken, context.version, context.destination, { points: [[37, 127], [35, 129]] });
  const course = rooms.publishCourse(member.memberToken, '우리 코스', '');
  const score = () => rooms.publicCourses('', { kind: 'community' })[0].trendScore;
  for (let i = 0; i < 5; i++) { rooms.setCourseLike('u1', course.id, true); rooms.setCourseLike('u1', course.id, false); }
  assert.equal(score(), 0);
  rooms.setCourseLike('u1', course.id, true); const once = score();
  rooms.setCourseLike('u1', course.id, true); assert.equal(score(), once);
  for (let i = 0; i < 3; i++) { rooms.reviewCourse('u1', course.id, { rating: 5 }); rooms.deleteReview('u1', course.id); }
  assert.equal(score(), once);
  // 좋아요 한 번으로는 '요즘 인기' 표시가 붙지 않는다.
  assert.equal(rooms.publicCourses('', { kind: 'community' })[0].trending, false);
  // 운영자를 사칭하는 이름은 쓸 수 없다.
  assert.equal(rooms.reviewCourse('u2', course.id, { rating: 4, name: '가족경로 운영자' }).reviews[0].name, '여행자');
});

test('올린 가족방만 코스를 내릴 수 있고, 세 명이 신고하면 목록에서 숨는다', () => {
  const rooms = new Rooms(), mine = rooms.create('우리'), other = rooms.create('남');
  const go = (token, name, lat) => { const c = rooms.destinationContext(token, { name, latitude: lat, longitude: 129, address: '부산 동구' }); rooms.applyDestination(token, c.version, c.destination, { points: [[37, 127], [lat, 129]] }); };
  go(mine.driverToken, '부산역', 35.1); const course = rooms.publishCourse(mine.driverToken, '우리 코스', '');
  assert.throws(() => rooms.unpublishCourse(other.driverToken, course.id));
  // 한 방에서 3개까지만 올릴 수 있다.
  go(mine.driverToken, '둘', 35.2); rooms.publishCourse(mine.driverToken, '둘', '');
  go(mine.driverToken, '셋', 35.3); rooms.publishCourse(mine.driverToken, '셋', '');
  go(mine.driverToken, '넷', 35.4); assert.throws(() => rooms.publishCourse(mine.driverToken, '넷', ''));
  rooms.reportCourse('a', course.id); rooms.reportCourse('a', course.id); rooms.reportCourse('b', course.id);
  assert.equal(rooms.publicCourses().some(c => c.id === course.id), true);
  assert.equal(rooms.reportCourse('c', course.id).hidden, true);
  assert.equal(rooms.publicCourses().some(c => c.id === course.id), false);
  // 올린 방에는 계속 보여서 직접 내릴 수 있다.
  assert.equal(rooms.publicCourses('', { roomId: rooms.roomIdOf(mine.driverToken) }).some(c => c.id === course.id), true);
  rooms.unpublishCourse(mine.driverToken, course.id);
  assert.equal(rooms.publicCourses('', { roomId: rooms.roomIdOf(mine.driverToken) }).some(c => c.id === course.id), false);
});

test('공개할 때 뺀 장소는 나가지 않고, 끝난 여행의 들르는 곳은 다음 여행에 붙지 않는다', () => {
  const rooms = new Rooms(), room = rooms.create('가족'), member = rooms.join(room.invite, '가족');
  const c = rooms.destinationContext(room.driverToken, { name: '우리 집', latitude: 35.1, longitude: 129, address: '부산 동구 어딘가 1' });
  rooms.applyDestination(room.driverToken, c.version, c.destination, { points: [[37, 127], [35.1, 129]] });
  const stop = rooms.propose(member.memberToken, { name: '시장', latitude: 35.2, longitude: 129.1, address: '부산 중구' }, '', 'WAYPOINT');
  // 약한 신호에서 같은 제안을 다시 보내도 하나만 남는다.
  assert.equal(rooms.propose(member.memberToken, { name: '시장', latitude: 35.2, longitude: 129.1 }, '', 'WAYPOINT').id, stop.id);
  const route = rooms.routeContext(room.driverToken, stop.id); rooms.applyRoute(room.driverToken, stop.id, route.version, { points: [[37, 127], [35.1, 129]] });
  assert.throws(() => rooms.publishCourse(member.memberToken, '빈 코스', '', 'current', [0, 1]));
  const shared = rooms.publishCourse(member.memberToken, '시장 구경', '', 'current', [1]);
  assert.equal(shared.destination.name, '시장'); assert.equal(JSON.stringify(shared).includes('우리 집'), false);
  rooms.finishTrip(room.driverToken, 'MEMORY');
  // 남기지 않고 종료해도 앞서 남긴 여행 기록은 지워지지 않는다.
  const again = rooms.destinationContext(room.driverToken, { name: '경주', latitude: 35.8, longitude: 129.2 });
  rooms.applyDestination(room.driverToken, again.version, again.destination, { points: [[37, 127], [35.8, 129.2]] });
  rooms.finishTrip(room.driverToken, 'SKIP');
  assert.equal(rooms.state(member.memberToken).completedTrip.destination.name, '우리 집');
  const next = rooms.propose(member.memberToken, { name: '전주', latitude: 35.8, longitude: 127.1 }, '', 'DESTINATION');
  assert.equal(rooms.routeContext(room.driverToken, next.id).waypoints.length, 0);
});
