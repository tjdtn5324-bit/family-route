import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Rooms } from './rooms.mjs';
const setDestination = (rooms, token) => {
  const c = rooms.destinationContext(token, {name:'부산역',latitude:35.115,longitude:129.042});
  rooms.applyDestination(token, c.version, c.destination, {points:[[37,127],[35.115,129.042]]});
};

test('시연 예시 코스는 명시적으로 켰을 때만 표시되고 실제 공유 코스와 구분된다', () => {
  const normal = new Rooms(), n = normal.create('여행');
  assert.equal(normal.recommendedCourses(n.driverToken,35.115,129.042).length,0);
  const demo = new Rooms({sampleCourses:true}), room = demo.create('여행');
  const list = demo.recommendedCourses(room.driverToken,35.115,129.042);
  assert.ok(list.length>=4);
  assert.ok(list.every(c=>c.sample === true && c.description.includes('시연')));
  assert.ok(demo.recommendedCourses(room.driverToken,37.56,126.98).some(c => c.title.includes('서울')));
});

test('목적지를 정하기 전에는 경유지 추가를 거절하고 제안을 보관한다', () => {
  const rooms = new Rooms(), room = rooms.create('여행'), member = rooms.join(room.invite, '가족');
  const p = rooms.propose(member.memberToken, {name:'광안리',latitude:35.153,longitude:129.118});
  assert.throws(() => rooms.routeContext(room.driverToken, p.id), /먼저 목적지/);
  assert.equal(rooms.state(member.memberToken).proposals[0].status, 'PENDING');
  setDestination(rooms, room.driverToken);
  assert.equal(rooms.routeContext(room.driverToken, p.id).destination.name, '부산역');
});

test('운전자는 경유지 순서를 바꾸고 삭제할 수 있으며 실패한 계산은 기존 경로를 보존한다', () => {
  const rooms = new Rooms(), room = rooms.create('여행'), member = rooms.join(room.invite, '가족');
  setDestination(rooms, room.driverToken);
  const add = (name, lat) => { const p=rooms.propose(member.memberToken,{name,latitude:lat,longitude:129}); const c=rooms.routeContext(room.driverToken,p.id); rooms.applyRoute(room.driverToken,p.id,c.version,{points:[[37,127],[lat,129]]}); return p.id; };
  const first=add('광안리',35.15), second=add('시장',35.1), third=add('카페',35.12);
  assert.throws(()=>rooms.itineraryContext(member.memberToken,[first]));
  assert.throws(()=>rooms.itineraryContext(room.driverToken,[first,first]));
  assert.throws(()=>rooms.itineraryContext(room.driverToken,['unknown']));
  const draft=rooms.itineraryContext(room.driverToken,[third,first]);
  assert.deepEqual(draft.waypoints.map(p=>p.name),['카페','광안리']);
  assert.deepEqual(rooms.state(room.driverToken).itinerary.waypoints.map(p=>p.name),['광안리','시장','카페']);
  assert.throws(()=>rooms.applyItinerary(room.driverToken,draft.version-1,[third,first],{points:[[1,2],[3,4]]}));
  rooms.applyItinerary(room.driverToken,draft.version,[third,first],{points:[[37,127],[35,129]]});
  assert.deepEqual(rooms.state(room.driverToken).itinerary.waypoints.map(p=>p.name),['카페','광안리']);
  assert.equal(rooms.state(member.memberToken).proposals.find(p=>p.id===second).status,'REMOVED');
  assert.deepEqual(rooms.publishCourse(room.driverToken,'여행','').waypoints.map(p=>p.name),['카페','광안리']);
});

test('가족 취향은 방별로 분리되고 게시자가 고른 태그와 일치하는 코스를 우선한다', () => {
  const rooms = new Rooms(), relax = rooms.create('부모님 여행'), active = rooms.create('활동 여행'), viewer = rooms.create('우리 여행');
  const trip = token => {
    const context = rooms.destinationContext(token, {name:'부산역',latitude:35.115,longitude:129.042});
    rooms.applyDestination(token, context.version, context.destination, {points:[[37,127],[35.115,129.042]]});
  };
  trip(relax.driverToken); trip(active.driverToken);
  rooms.setPreferences(relax.driverToken, {companions:['PARENTS'],styles:['RELAX'],hours:4});
  const one = rooms.publishCourse(relax.driverToken, '부모님과 천천히', '');
  rooms.setPreferences(active.driverToken, {companions:['CHILD'],styles:['ACTIVITY'],hours:8});
  const two = rooms.publishCourse(active.driverToken, '신나는 하루', '');
  const person = rooms.join(viewer.invite, '가족');
  rooms.setPreferences(person.memberToken, {companions:['PARENTS'],styles:['RELAX'],hours:4});
  const list = rooms.recommendedCourses(viewer.driverToken,35.115,129.042);
  assert.equal(list[0].id, one.id); assert.equal(list[0].matchCount,3); assert.equal(list[1].id,two.id);
  assert.deepEqual(list[0].matchedPreferences, {companions:['PARENTS'],styles:['RELAX'],hours:4});
  assert.deepEqual(rooms.state(active.driverToken).preferences,{companions:['CHILD'],styles:['ACTIVITY'],hours:8});
  rooms.setPreferences(relax.driverToken, {companions:[],styles:[],hours:null});
  assert.equal(rooms.courses(viewer.driverToken).find(c=>c.id===one.id).preferences.styles[0], 'RELAX');
  assert.throws(()=>rooms.setPreferences('bad', {companions:[],styles:[],hours:null}));
  assert.throws(()=>rooms.setPreferences(person.memberToken, {companions:['CHILD','DOG'],styles:[],hours:null}));
  assert.throws(()=>rooms.setPreferences(person.memberToken, {companions:[],styles:[],hours:3}));
  const restored = Rooms.fromSnapshot(JSON.parse(JSON.stringify(rooms.exportSnapshot())));
  assert.equal(restored.recommendedCourses(viewer.driverToken,35.115,129.042)[0].id,one.id);
});

test('복원한 가족방에서도 멤버와 공유 코스를 유지하고 다른 방의 접근은 차단한다', () => {
  const original = new Rooms(), first = original.create('첫 가족'), second = original.create('둘째 가족');
  const member = original.join(first.invite, '가족');
  const destination = original.destinationContext(first.driverToken, {name:'부산역',latitude:35.115,longitude:129.042});
  original.applyDestination(first.driverToken, destination.version, destination.destination, {points:[[37,127],[35.115,129.042]]});
  const course = original.publishCourse(member.memberToken, '부산 여행', '');
  const restored = Rooms.fromSnapshot(JSON.parse(JSON.stringify(original.exportSnapshot())));
  assert.equal(restored.state(member.memberToken).room.name, '첫 가족');
  assert.equal(restored.courses(second.driverToken)[0].id, course.id);
  const proposal = restored.propose(member.memberToken, {name:'광안리',latitude:35.153,longitude:129.118});
  assert.throws(() => restored.routeContext(second.driverToken, proposal.id));
  assert.throws(() => Rooms.fromSnapshot({version:2}));
});

test('서로 다른 가족의 장소 제안과 투표가 운전자에게 보이며 운전자만 결정한다', () => {
  const rooms = new Rooms(), room = rooms.create('여행');
  setDestination(rooms, room.driverToken);
  const a = rooms.join(room.invite, '엄마'), b = rooms.join(room.invite, '민준');
  let updates = 0; const stop = rooms.subscribe(room.driverToken, () => updates++);
  const proposal = rooms.propose(a.memberToken, { name: '실제 장소', latitude: 37.5, longitude: 127.1 }, '들러요');
  rooms.vote(b.memberToken, proposal.id, 'YES');
  assert.equal(rooms.state(room.driverToken).proposals[0].votes[rooms.session(b.memberToken).id], 'YES');
  assert.throws(() => rooms.decide(a.memberToken, proposal.id, 'DECLINED'));
  const context = rooms.routeContext(room.driverToken, proposal.id);
  rooms.applyRoute(room.driverToken, proposal.id, context.version, {points:[[37,127],[37.1,127.1]]});
  assert.equal(rooms.state(b.memberToken).proposals[0].status, 'ACCEPTED');
  assert.equal(updates, 3); stop();
});

test('다른 가족방, 잘못된 초대 및 잘못된 좌표를 차단한다', () => {
  const rooms = new Rooms(), one = rooms.create('하나'), two = rooms.create('둘');
  assert.throws(() => rooms.join('invalid', '가족'));
  const member = rooms.join(one.invite, '가족');
  assert.throws(() => rooms.propose(member.memberToken, { name: '장소', latitude: 999, longitude: 127 }));
  const p = rooms.propose(member.memberToken, { name: '장소', latitude: 37, longitude: 127 });
  assert.throws(() => rooms.decide(two.driverToken, p.id, 'ACCEPTED'));
  assert.equal(rooms.state(two.driverToken).proposals.length, 0);
});

test('실제 경로 반영은 운전자만 할 수 있고 오래된 경로 결과는 막는다', () => {
  const rooms = new Rooms(), room = rooms.create('시험'), member = rooms.join(room.invite, '가족');
  setDestination(rooms, room.driverToken);
  const p = rooms.propose(member.memberToken, { name: '장소', latitude: 37, longitude: 127 });
  assert.throws(() => rooms.routeContext(member.memberToken, p.id));
  assert.throws(() => rooms.applyRoute(room.driverToken, p.id, 99, {}));
  const c = rooms.routeContext(room.driverToken, p.id);
  rooms.applyRoute(room.driverToken, p.id, c.version, {points:[[37,127],[37.1,127.1]]});
  assert.equal(rooms.state(member.memberToken).proposals[0].status, 'ACCEPTED');
});

test('가족이 제안한 목적지를 승인한 다음 경유지를 추가한다', () => {
  const rooms = new Rooms(), room = rooms.create('여행'), member = rooms.join(room.invite, '가족');
  const destination = rooms.propose(member.memberToken, { name:'목적지', latitude:37.7, longitude:128.8 }, '', 'DESTINATION');
  const first = rooms.routeContext(room.driverToken, destination.id);
  assert.equal(first.destination.name, '목적지'); assert.equal(first.waypoints.length, 0);
  rooms.applyRoute(room.driverToken, destination.id, first.version, {points:[[37,127],[37.7,128.8]]});
  const waypoint = rooms.propose(member.memberToken, { name:'경유지', latitude:37.5, longitude:127.5 });
  const second = rooms.routeContext(room.driverToken, waypoint.id);
  assert.equal(second.destination.name, '목적지'); assert.equal(second.waypoints[0].name, '경유지');
});

test('공유 코스를 검색해 다른 가족이 제안하고 운전자가 순서대로 적용한다', () => {
  const rooms = new Rooms(), a = rooms.create('첫 가족'), b = rooms.create('둘째 가족');
  setDestination(rooms, a.driverToken);
  const first = rooms.join(a.invite, '첫째'), second = rooms.join(b.invite, '둘째');
  const stop = rooms.propose(first.memberToken, {name:'대전', latitude:36.35, longitude:127.38});
  const c1 = rooms.routeContext(a.driverToken, stop.id);
  rooms.applyRoute(a.driverToken, stop.id, c1.version, {points:[[37,127],[36.35,127.38]]});
  const course = rooms.publishCourse(first.memberToken, '서울 부산 여행', '즐거운 여행');
  assert.equal(rooms.courses(second.memberToken, '부산')[0].id, course.id);
  setDestination(rooms, b.driverToken);
  const proposal = rooms.proposeCourse(second.memberToken, course.id);
  assert.throws(() => rooms.routeContext(second.memberToken, proposal.id));
  const context = rooms.routeContext(b.driverToken, proposal.id);
  assert.equal(context.waypoints[0].name, '대전');
  rooms.applyRoute(b.driverToken, proposal.id, context.version, {points:[[37,127],[36.35,127.38]]});
  assert.equal(rooms.state(b.driverToken).itinerary.waypoints[0].name, '대전');
  assert.equal(rooms.state(b.driverToken).itinerary.destination.name, course.destination.name);
});

test('운전자가 목적지를 직접 설정하고 목적지 주변 코스를 추천받는다', () => {
  const rooms = new Rooms(), a = rooms.create('여행'), member = rooms.join(a.invite, '가족');
  const destination = {name:'부산역', latitude:35.115, longitude:129.042};
  assert.throws(() => rooms.destinationContext(member.memberToken, destination));
  const context = rooms.destinationContext(a.driverToken, destination);
  rooms.applyDestination(a.driverToken, context.version, context.destination, {points:[[37,127],[35.115,129.042]]});
  const course = rooms.publishCourse(member.memberToken, '부산 여행', '');
  const b = rooms.create('다른 가족');
  assert.equal(rooms.recommendedCourses(b.driverToken, 35.12, 129.04)[0].id, course.id);
  assert.equal(rooms.recommendedCourses(b.driverToken, 37.56, 126.98).length, 0);
  assert.throws(() => rooms.applyDestination(a.driverToken, context.version, context.destination, {}));
});

test('공유 코스를 경유지로 추가할 때 기존 목적지를 유지하고 장소 순서를 보존한다', () => {
  const rooms = new Rooms(), a = rooms.create('코스 작성'), b = rooms.create('여행');
  const member = rooms.join(a.invite, '작성자');
  const market = rooms.destinationContext(a.driverToken, {name:'해산물 시장',latitude:35.097,longitude:129.03});
  // 새 목적지 설정 뒤 경유지를 추가하는 일반 흐름
  rooms.applyDestination(a.driverToken, market.version, market.destination, {points:[[37,127],[35,129]]});
  const stop = rooms.propose(member.memberToken, {name:'광안리',latitude:35.153,longitude:129.118});
  const second = rooms.routeContext(a.driverToken, stop.id); rooms.applyRoute(a.driverToken, stop.id, second.version, {points:[[37,127],[35,129]]});
  const course = rooms.publishCourse(member.memberToken, '가성비 부산 여행', '');
  const busan = rooms.destinationContext(b.driverToken, {name:'부산역',latitude:35.115,longitude:129.042});
  rooms.applyDestination(b.driverToken, busan.version, busan.destination, {points:[[37,127],[35,129]]});
  const p = rooms.proposeCourse(b.driverToken, course.id, true); p.course.mode='ADD';
  const result = rooms.routeContext(b.driverToken, p.id);
  assert.equal(result.destination.name, '부산역');
  assert.deepEqual(result.waypoints.map(x=>x.name), ['광안리', '해산물 시장']);
  rooms.applyRoute(b.driverToken, p.id, result.version, {points:[[37,127],[35,129]]});
  assert.equal(rooms.state(b.driverToken).itinerary.destination.name, '부산역');
  assert.deepEqual(rooms.state(b.driverToken).itinerary.waypoints.map(x=>x.name), ['광안리', '해산물 시장']);
});

test('참고 여행 코스는 지역별 검색과 목적지 주변 추천에 나타난다', () => {
  const rooms=new Rooms({sampleCourses:true}),r=rooms.create('여행');
  for(const [query,lat,lon] of [['부산',35.1152,129.0415],['강릉',37.7645,128.8996],['경주',35.8348,129.2270],['전주',35.8148,127.1526]]) {
    const publicCourses=rooms.publicCourses(query).filter(c=>c.curated);
    assert.ok(publicCourses.length>0,query+' 검색');
    assert.ok(publicCourses.every(c=>c.sample && c.sourceUrl.startsWith('https://')),query+' 출처·시연 표시');
    assert.ok(rooms.recommendedCourses(r.driverToken,lat,lon).some(c=>c.curated),query+' 목적지 추천');
  }
});


test('저장 묶음을 가족이 제안하면 기존 목적지와 경유지를 유지하고 선택 장소를 직선거리 순서로 추가한다', () => {
  const rooms=new Rooms(), room=rooms.create('여행'), member=rooms.join(room.invite,'엄마');
  setDestination(rooms,room.driverToken);
  const places=[{name:'남쪽',latitude:36,longitude:128},{name:'북쪽',latitude:37,longitude:127.3}];
  const proposal=rooms.proposeSavedGroup(member.memberToken,'가족 여행',places);
  assert.equal(proposal.member.name,'엄마'); assert.equal(proposal.type,'COURSE');
  assert.equal(proposal.place.name,'가족 여행');
  const context=rooms.routeContext(room.driverToken,proposal.id);
  assert.equal(context.destination.name,'부산역');
  assert.deepEqual(context.waypoints.map(p=>p.name),['북쪽','남쪽']);
  rooms.applyRoute(room.driverToken,proposal.id,context.version,{points:[[37,127],[35.115,129.042]]});
  assert.deepEqual(rooms.state(room.driverToken).itinerary.waypoints.map(p=>p.name),['북쪽','남쪽']);
  assert.throws(()=>rooms.proposeSavedGroup(member.memberToken,'나쁜 묶음',[{name:'오류',latitude:900,longitude:1}]),/장소와 좌표/);
  assert.throws(()=>rooms.proposeSavedGroup(member.memberToken,'너무 많음',Array(6).fill(places[0])),/1~5곳/);
  const other=rooms.create('다른 방');assert.equal(rooms.state(other.driverToken).proposals.length,0);
});
