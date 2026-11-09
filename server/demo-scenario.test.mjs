import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from './app.mjs';

test('두 가족이 부산 여행 코스를 공유하고 기존 목적지에 경유지를 추가한다', async t => {
  const places = {
    부산역: {name:'부산역', address:'부산', latitude:35.115, longitude:129.042},
    광안리: {name:'광안리', address:'부산', latitude:35.153, longitude:129.118},
    시장: {name:'해산물 시장', address:'부산', latitude:35.097, longitude:129.03},
  };
  const seen = [];
  const server = createApp({
    createSecret: 'demo-test', apiKey:'test',
    search: async q => places[q] ? [places[q]] : [],
    directions: async context => { seen.push(context); return {points:[[37,127],[35.1,129]], distance:100000, duration:3600, guides:[]}; },
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (path, { token, data, create = false } = {}) => {
    const response = await fetch(base + path, {method: data ? 'POST' : 'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(create?{'X-Room-Create-Secret':'demo-test'}:{})},body:data?JSON.stringify(data):undefined});
    const body = await response.json();
    assert.ok(response.ok, `${path}: ${body.error || response.status}`);
    return body;
  };
  const familyA = await call('/api/rooms', {data:{name:'여행을 공유하는 가족'},create:true});
  const member = await call('/api/join', {data:{invite:familyA.invite,name:'엄마'}});
  await call('/api/preferences', {token:member.memberToken,data:{companions:['PARENTS'],styles:['RELAX'],hours:4}});
  const searched = await call('/api/places?q='+encodeURIComponent('시장'), {token:member.memberToken});
  await call('/api/destination', {token:familyA.driverToken,data:{place:searched.places[0]}});
  const stop = await call('/api/proposals', {token:member.memberToken,data:{place:places.광안리,type:'WAYPOINT',message:'함께 바다 보러 가요'}});
  await call(`/api/proposals/${stop.id}/route`, {token:familyA.driverToken,data:{}});
  await call('/api/courses', {token:member.memberToken,data:{title:'가성비 부산 여행',description:'가족과 바다와 시장을 둘러봐요'}});
  const familyB = await call('/api/rooms', {data:{name:'새 가족'},create:true});
  await call('/api/preferences', {token:familyB.driverToken,data:{companions:['PARENTS'],styles:['RELAX'],hours:4}});
  await call('/api/destination', {token:familyB.driverToken,data:{place:places.부산역}});
  const suggestions = await call('/api/recommended-courses?latitude=35.115&longitude=129.042', {token:familyB.driverToken});
  assert.equal(suggestions.courses[0].title, '가성비 부산 여행');
  assert.equal(suggestions.courses[0].matchCount, 3);
  assert.deepEqual(suggestions.courses[0].matchedPreferences.companions, ['PARENTS']);
  await call(`/api/courses/${suggestions.courses[0].id}/use`, {token:familyB.driverToken,data:{}});
  const state = await call('/api/state', {token:familyB.driverToken});
  assert.equal(state.itinerary.destination.name, '부산역');
  assert.deepEqual(state.itinerary.waypoints.map(p => p.name), ['광안리','해산물 시장']);
  assert.deepEqual(seen.at(-1).waypoints.map(p=>p.name), ['광안리','해산물 시장']);
});
