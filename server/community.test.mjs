import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from './app.mjs';
import { Rooms, regionOf } from './rooms.mjs';

test('주소에서 여행 지역 이름을 뽑는다', () => {
  assert.equal(regionOf({ address: '부산 동구 중앙대로 206' }), '부산');
  assert.equal(regionOf({ address: '부산광역시' }), '부산');
  assert.equal(regionOf({ address: '강원특별자치도 강릉시 용지로 176' }), '강릉');
  assert.equal(regionOf({ address: '경북 경주시 인왕동 839-1' }), '경주');
  assert.equal(regionOf({ name: '어딘가', address: '' }), '어딘가');
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
  const second = (await call('/api/courses', { token: member.memberToken, data: { title: '둘째 코스' } })).data;

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
  const restored = Rooms.fromSnapshot(JSON.parse(JSON.stringify(rooms.exportSnapshot())));
  const course = restored.publicCourses('', { kind: 'official' }).find(c => c.id === 'guide-busan-market');
  assert.equal(course.reviewCount, 1); assert.equal(course.rating, 5); assert.ok(course.trendScore > 0);
});
