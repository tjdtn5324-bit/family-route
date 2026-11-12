// 가족 3명이 각자 기기로 로그인해 여행 하나를 함께 만들고, 장소를 제안하고, 다녀온 뒤 기록을 다시 보는 흐름.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Service } from './service.mjs';

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 9]).toString('base64');
const place = (name, lat, lng = 129) => ({ name, latitude: lat, longitude: lng, address: '부산' });

async function family() {
  const calls = [];
  const s = new Service({ directions: async c => { calls.push(c); return { points: [[37, 127], [c.destination.latitude, c.destination.longitude]], distance: 400000, duration: 16000 }; } });
  const user = async (email, name) => s.userActor((await s.signup({ email, password: 'password-1234', name })).token);
  let dad = await user('dad@x.com', '아빠'), mom = await user('mom@x.com', '엄마'), kid = await user('kid@x.com', '민지');
  s.createFamily(dad, { name: '우리 가족' });
  dad = s.userActor((await s.login({ email: 'dad@x.com', password: 'password-1234' })).token);
  const code = s.createInvite(dad).code;
  s.joinFamily(mom, { code }); s.joinFamily(kid, { code });
  const refresh = async email => s.userActor((await s.login({ email, password: 'password-1234' })).token);
  return { s, calls, dad: await refresh('dad@x.com'), mom: await refresh('mom@x.com'), kid: await refresh('kid@x.com') };
}

test('여행 만들기 → 가족 제안 → 운전자 확정 → 출발 → 정차 후 이야기 → 끝 → 앨범', async () => {
  const { s, calls, dad, mom, kid } = await family();
  // ② 가족 여행 만들기: 날짜, 목적지, 운전자
  let trip = await s.createTrip(dad, { title: '부산 가족 여행', date: '2026-12-24', destination: place('부산역', 35.115), driverUserId: dad.user.id });
  assert.equal(trip.status, 'PLANNING'); assert.equal(trip.driver.name, '아빠'); assert.ok(trip.route);
  // ③ 가족 모두 참여: 장소, 이유, 메시지
  trip = s.proposePlace(mom, trip.id, { place: place('광안리 카페', 35.153), reason: '바다 보며 커피', message: '여보 여기 꼭 가요' });
  trip = s.proposePlace(kid, trip.id, { place: place('감천문화마을', 35.097), reason: '그림 같은 골목', message: '사진 찍고 싶어!' });
  trip = s.vote(dad, trip.proposals[1].id, { choice: 'YES' });
  assert.equal(trip.proposals.length, 2); assert.equal(trip.proposals[1].yes, 1);
  // 일반 구성원은 경유지를 확정할 수 없다(만든 사람·운전자·관리자만).
  await assert.rejects(s.decide(kid, trip.proposals[0].id, { decision: 'CONFIRM' }), e => e.status === 403);
  trip = await s.decide(dad, trip.proposals[0].id, { decision: 'CONFIRM' });
  trip = await s.decide(dad, trip.proposals[0].id, { decision: 'CONFIRM' });
  assert.deepEqual(trip.stops.map(p => p.place.name), ['광안리 카페', '감천문화마을']);
  assert.deepEqual(calls.at(-1).waypoints.map(p => p.name), ['광안리 카페', '감천문화마을']);
  // 순서 바꾸기
  trip = await s.reorderStops(dad, trip.id, { ids: [trip.stops[1].id, trip.stops[0].id] });
  assert.deepEqual(trip.stops.map(p => p.place.name), ['감천문화마을', '광안리 카페']);
  // ④ 함께 여행하기: 차량이 출발·도착을 기록하고 이야기를 보여 준다.
  const pair = s.pairVehicle({ code: s.vehicleCode(mom).code, name: '아이오닉 5' });
  const car = s.vehicleActor(pair.vehicleToken);
  assert.equal(s.listTrips(car)[0].title, '부산 가족 여행');
  assert.throws(() => s.proposePlace(car, trip.id, { place: place('x', 1) }), e => e.status === 403);
  s.startTrip(car, trip.id);
  const arrived = s.arriveAt(car, trip.stops[0].id);
  assert.deepEqual(arrived.stories, [{ by: '민지', reason: '그림 같은 골목', message: '사진 찍고 싶어!' }]);
  s.addPhoto(kid, trip.id, { data: JPEG, proposalId: trip.stops[0].id });
  // ⑤ 추억으로 보관
  s.finishTrip(car, trip.id);
  const album = s.album(mom);
  assert.equal(album.length, 1); assert.equal(album[0].visitedCount, 1); assert.equal(album[0].photoCount, 1); assert.equal(album[0].storyCount, 2);
  const detail = s.tripDetail(mom, trip.id);
  assert.equal(detail.status, 'DONE'); assert.equal(detail.stops[0].status, 'VISITED'); assert.equal(detail.stops[1].status, 'CONFIRMED');
  await assert.rejects(Promise.resolve().then(() => s.proposePlace(kid, trip.id, { place: place('늦은 제안', 35) })), e => e.status === 409);
});

test('경로를 못 찾으면 들르는 곳과 경로를 바꾸지 않는다', async () => {
  const { s, dad, mom } = await family();
  let trip = await s.createTrip(dad, { title: '여행', destination: place('부산역', 35.115) });
  trip = s.proposePlace(mom, trip.id, { place: place('시장', 35.1) });
  const before = s.tripDetail(dad, trip.id);
  s.directions = async () => { throw new Error('down'); };
  await assert.rejects(s.decide(dad, trip.proposals[0].id, { decision: 'CONFIRM' }), e => e.status === 502);
  const after = s.tripDetail(dad, trip.id);
  assert.equal(after.stops.length, 0); assert.equal(after.proposals[0].status, 'PROPOSED'); assert.deepEqual(after.route, before.route);
});

test('운전자로 정한 가족과 여행을 만든 가족은 정할 수 있고, 제안한 사람은 자기 제안을 지울 수 있다', async () => {
  const { s, dad, mom, kid } = await family();
  let trip = await s.createTrip(mom, { title: '엄마가 만든 여행', destination: place('부산역', 35.115), driverUserId: kid.user.id });
  trip = s.proposePlace(dad, trip.id, { place: place('시장', 35.1) });
  trip = await s.decide(kid, trip.proposals[0].id, { decision: 'DECLINE' }); // 운전자
  assert.equal(trip.proposals[0].status, 'DECLINED');
  trip = s.proposePlace(kid, trip.id, { place: place('공원', 35.2) });
  const mine = trip.proposals.find(p => p.place.name === '공원');
  assert.equal(mine.canDelete, true);
  trip = await s.removeProposal(kid, mine.id);
  assert.equal(trip.proposals.some(p => p.place.name === '공원'), false);
});

test('저장했다가 다시 불러와도 계정·가족·여행·세션이 그대로다', async () => {
  const { s, dad } = await family();
  const token = (await s.login({ email: 'dad@x.com', password: 'password-1234' })).token;
  await s.createTrip(dad, { title: '저장 확인' });
  const restored = Service.fromSnapshot(JSON.parse(JSON.stringify(s.exportSnapshot())));
  const actor = restored.userActor(token);
  assert.equal(actor.family.name, '우리 가족'); assert.equal(restored.listTrips(actor)[0].title, '저장 확인');
  assert.equal((await restored.login({ email: 'dad@x.com', password: 'password-1234' })).user.name, '아빠');
});

test('출발 전 가족이 인스타 스토리형 제안·리액션·한마디를 남기고, 차에 타자마자 원터치로 코스를 확정해 출발할 수 있다', async () => {
  const { s, dad, mom, kid } = await family();
  let trip = await s.createTrip(dad, { title: '강릉 가족 바다 여행', destination: place('강릉 안목해변', 37.772) });
  // 엄마와 민지가 설렘 테마(mood)와 이유·타임캡슐 메시지를 남긴다
  trip = s.proposePlace(mom, trip.id, { place: place('강릉 중앙시장', 37.754), reason: '닭강정이랑 오징어순대 먹어요', message: '시장 입구에서 인증샷 찍기!', mood: '🍦 꼭 먹고 싶은 간식·맛집' });
  trip = s.proposePlace(kid, trip.id, { place: place('대관령 양떼목장', 37.688), reason: '양들한테 건초 주고 싶어요', message: '아빠 운전 고마워요 ❤️', mood: '🎈 아이가 손꼽아 기다린 곳' });

  // 가족들이 인스타 스토리처럼 이모지 리액션과 한마디 댓글을 남긴다
  const kidPropId = trip.proposals[1].id;
  trip = s.reactProposal(dad, kidPropId, { emoji: '❤️' });
  trip = s.reactProposal(mom, kidPropId, { emoji: '🤩' });
  trip = s.commentProposal(mom, kidPropId, { text: '우리 민지 소원 꼭 들어주자!' });
  const kidProp = trip.proposals.find(p => p.id === kidPropId);
  assert.equal(kidProp.totalReactions, 2);
  assert.equal(kidProp.comments.length, 1);
  assert.equal(kidProp.by.avatar, '👧');

  // 일반 구성원(엄마)도 출발 전에 '차량 탑승 준비 완료(setTripReady)'를 눌러 제안을 모두 코스에 반영할 수 있다
  trip = await s.setTripReady(mom, trip.id, { ready: true, autoConfirmPending: true });
  assert.equal(trip.readyForCar, true);
  assert.equal(trip.stops.length, 2);
  // 리액션이 더 많은 민지의 양떼목장이 1번 경유지로 자동 배치된다
  assert.equal(trip.stops[0].place.name, '대관령 양떼목장');

  // PC 재부팅 스냅샷 복원 후에도 readyForCar, 리액션, 코멘트, 스토리가 그대로 유지되고 차량에서 바로 출발 가능
  const pair = s.pairVehicle({ code: s.vehicleCode(dad).code, name: '가족 카니발' });
  const restored = Service.fromSnapshot(JSON.parse(JSON.stringify(s.exportSnapshot())));
  const car = restored.vehicleActor(pair.vehicleToken);
  const started = await restored.quickStartTrip(car, trip.id);
  assert.equal(started.status, 'ONGOING');
  const arrived = restored.arriveAt(car, started.stops[0].id);
  assert.equal(arrived.storyCards[0].mood, '🎈 아이가 손꼽아 기다린 곳');
  assert.equal(arrived.storyCards[0].comments[0].text, '우리 민지 소원 꼭 들어주자!');
});


test('출발 취소는 준비 중으로 되돌리고, 코스 비우기는 한마디가 있는 곳만 제안으로 남긴다', async () => {
  const { s, dad, mom, kid } = await family();
  let trip = await s.createTrip(dad, { title: '취소 연습', destination: place('부산역', 35.115), driverUserId: dad.user.id });
  trip = s.proposePlace(mom, trip.id, { place: place('광안리 카페', 35.153), reason: '바다 보며 커피', message: '여보 여기 꼭 가요' });
  trip = await s.decide(dad, trip.proposals[0].id, { decision: 'CONFIRM' });
  trip = await s.addStop(dad, trip.id, { place: place('휴게소', 36.2) });
  const car = s.vehicleActor(s.pairVehicle({ code: s.vehicleCode(dad).code, name: '아빠 차' }).vehicleToken);
  assert.throws(() => s.cancelStart(car, trip.id), e => e.status === 409);
  s.startTrip(car, trip.id);
  s.arriveAt(car, trip.stops[0].id);
  assert.throws(() => s.cancelStart(kid, trip.id), e => e.status === 403);
  await assert.rejects(s.clearCourse(car, trip.id), e => e.status === 409);
  trip = s.cancelStart(car, trip.id);
  assert.equal(trip.status, 'PLANNING'); assert.equal(trip.startedAt, null);
  assert.deepEqual(trip.stops.map(p => p.status), ['CONFIRMED', 'CONFIRMED']);
  trip = s.startTrip(car, trip.id); trip = s.cancelStart(car, trip.id);
  trip = await s.clearCourse(car, trip.id);
  assert.equal(trip.destination, null); assert.equal(trip.stops.length, 0); assert.equal(trip.route, null); assert.equal(trip.readyForCar, false);
  assert.deepEqual(trip.proposals.map(p => [p.place.name, p.status]), [['광안리 카페', 'PROPOSED']]);
});
