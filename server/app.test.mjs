import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from './app.mjs';

test('HTTP 가족방에서 검색, 제안, 투표 및 운전자 결정을 동기화한다', async (t) => {
  const server = createApp({ apiKey: 'test', search: async (q) => [{ name: q, latitude: 37.5, longitude: 127 }], directions: async () => ({points:[[37,127],[37.1,127.1]], distance:100, duration:10}) });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (path, { token, data } = {}) => { const r = await fetch(base + path, { method: data ? 'POST' : 'GET', headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' }, body: data ? JSON.stringify(data) : undefined }); return { status: r.status, data: await r.json() }; };
  const room = (await call('/api/rooms', { data: { name: '가족' } })).data;
  const publicCourses = await call('/api/public/courses?q=%EB%B6%80%EC%82%B0');
  assert.equal(publicCourses.status, 200);
  assert.deepEqual(publicCourses.data.courses, []);
  const coursePage = await fetch(base + '/courses');
  assert.equal(coursePage.status, 200);
  assert.match(await coursePage.text(), /함께 떠나고 싶은 곳/);
  const member = (await call('/api/join', { data: { invite: room.invite, name: '서연' } })).data;
  const preferences = { companions: ['PARENTS'], styles: ['RELAX'], hours: 4 };
  assert.equal((await call('/api/preferences', { token: member.memberToken, data: preferences })).status, 200);
  assert.deepEqual((await call('/api/state', { token: room.driverToken })).data.preferences, preferences);
  assert.equal((await call('/api/preferences', { data: preferences })).status, 401);
  const found = await call('/api/places?q=%EC%B9%B4%ED%8E%98', { token: member.memberToken });
  const p = (await call('/api/proposals', { token: member.memberToken, data: { place: found.data.places[0], message: '여기 가요' } })).data;
  assert.equal((await call(`/api/proposals/${p.id}/vote`, { token: member.memberToken, data: { choice: 'YES' } })).status, 200);
  assert.equal((await call(`/api/proposals/${p.id}/route`, { token: member.memberToken, data: {} })).status, 400);
  assert.equal((await call(`/api/proposals/${p.id}/route`, { token: room.driverToken, data: {} })).status, 400);
  assert.equal((await call('/api/destination', { token: room.driverToken, data: {place:{name:'부산역',latitude:35.115,longitude:129.042}} })).status, 200);
  assert.equal((await call(`/api/proposals/${p.id}/route`, { token: room.driverToken, data: {} })).status, 200);
  const itinerary = (await call('/api/state', {token:room.driverToken})).data.itinerary;
  assert.equal((await call('/api/itinerary/waypoints', {token:member.memberToken,data:{version:itinerary.version,ids:[]}})).status,400);
  assert.equal((await call('/api/itinerary/waypoints', {token:room.driverToken,data:{version:itinerary.version-1,ids:[]}})).status,400);
  assert.equal((await call('/api/state', {token:room.driverToken})).data.itinerary.waypoints.length,1);
  assert.equal((await call('/api/itinerary/waypoints', {token:room.driverToken,data:{version:itinerary.version,ids:[]}})).status,200);
  assert.equal((await call('/api/state', {token:room.driverToken})).data.itinerary.waypoints.length,0);
  assert.equal((await call('/api/state', { token: member.memberToken })).data.proposals[0].status, 'REMOVED');
  assert.equal((await call('/api/state')).status, 401);
});

test('가족이 저장 묶음을 제안하면 운전자만 목적지에 반영한다', async t=>{
  const server=createApp({directions:async context=>({points:[[37,127],[35,129]],distance:100,duration:10,waypointNames:context.waypoints.map(p=>p.name)})});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base=`http://127.0.0.1:${server.address().port}`;
  const call=async(path,token,data)=>{const response=await fetch(base+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:data?JSON.stringify(data):undefined});return {status:response.status,data:await response.json()}};
  const room=(await call('/api/rooms',null,{name:'여행'})).data;
  const member=(await call('/api/join',null,{invite:room.invite,name:'민지'})).data;
  const group={title:'부산 여행',places:[{name:'시장',latitude:36,longitude:128},{name:'카페',latitude:37,longitude:127.3}]};
  assert.equal((await call('/api/proposals/saved-group',null,group)).status,401);
  const proposal=await call('/api/proposals/saved-group',member.memberToken,group);assert.equal(proposal.status,201);
  assert.equal((await call(`/api/proposals/${proposal.data.id}/route`,room.driverToken,{})).status,400);
  assert.equal((await call('/api/destination',room.driverToken,{place:{name:'부산역',latitude:35.115,longitude:129.042}})).status,200);
  assert.equal((await call(`/api/proposals/${proposal.data.id}/route`,member.memberToken,{})).status,400);
  assert.equal((await call(`/api/proposals/${proposal.data.id}/route`,room.driverToken,{})).status,200);
  const state=(await call('/api/state',room.driverToken)).data;
  assert.equal(state.itinerary.destination.name,'부산역');assert.deepEqual(state.itinerary.waypoints.map(p=>p.name),['카페','시장']);
});

test('추천 코스를 한 번에 시작하면 목적지와 경유지가 함께 설정된다',async t=>{
 const rooms=new (await import('./rooms.mjs')).Rooms({sampleCourses:true});let sent;
 const server=createApp({rooms,directions:async context=>{sent=context;return {points:[[37,127],[35,129]],distance:100,duration:10}}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>server.close(r)));
 const base=`http://127.0.0.1:${server.address().port}`,room=rooms.create('가족');
 const res=await fetch(base+'/api/courses/guide-busan-market/start',{method:'POST',headers:{Authorization:`Bearer ${room.driverToken}`,'Content-Type':'application/json'},body:'{}'});
 assert.equal(res.status,200);assert.equal(sent.destination.name,'부산역');assert.equal(sent.waypoints.length,2);
 const state=rooms.state(room.driverToken);assert.equal(state.itinerary.destination.name,'부산역');assert.equal(state.itinerary.waypoints.length,2);
});
