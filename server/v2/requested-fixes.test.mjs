import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Service } from './service.mjs';
import { createServer } from './http.mjs';
import { openLocalArchive } from '../local-persistence.mjs';

const place = (name, latitude = 35.1) => ({name, latitude, longitude:129, address:'부산'});
const JPEG = Buffer.from([255,216,255,224,9]).toString('base64');
const password = '고유한-password-1234';
const route = async c => ({points:[[37,127],[c.destination.latitude,c.destination.longitude]],distance:100,duration:10});
async function addUser(service, email, name) {
  const {token} = await service.signup({email, password, name});
  return {token, actor:service.userActor(token)};
}
async function serve(t, options) {
  const server = createServer(options);
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => new Promise(resolve => {server.close(resolve);server.closeAllConnections();}));
  const base = `http://127.0.0.1:${server.address().port}`;
  return async (path,{data,cookie,method}={}) => {
    const response = await fetch(base+path,{method:method || (data?'POST':'GET'),headers:{'Content-Type':'application/json',...(cookie?{Cookie:cookie}:{})},body:data?JSON.stringify(data):undefined});
    return {status:response.status,body:await response.json(),cookie:response.headers.get('set-cookie')?.split(';')[0]};
  };
}

test('같은 계정의 두 세션은 가족방을 각각 선택하고 사진은 선택한 방에서만 읽는다', async () => {
  const service = new Service({directions:route});
  const {token,actor} = await addUser(service,'one@example.com','나');
  const familyA = service.createFamily(actor,{name:'가족 A'});
  const firstTrip = await service.createTrip(actor,{title:'A 여행',destination:place('도착지')});
  const photo = service.addPhoto(actor,firstTrip.id,{data:JPEG});
  const session2 = (await service.login({email:'one@example.com',password})).token;
  const second = service.userActor(session2);
  const familyB = service.createFamily(actor,{name:'가족 B'});
  assert.equal(service.me(service.userActor(token)).family.id,familyB.id);
  assert.equal(service.me(service.userActor(session2)).family.id,familyA.id);
  assert.equal(service.me(service.userActor(token)).families.length,2);
  assert.throws(() => service.photo(service.userActor(token),photo.id),e=>e.status===404);
  assert.equal(service.photo(service.userActor(session2),photo.id).data,JPEG);
  const outsider = await addUser(service,'outsider@example.com','다른 사람');
  assert.throws(() => service.selectFamily(outsider.actor,{familyId:familyA.id}),e=>e.status===404);
  assert.throws(() => service.photo(outsider.actor,photo.id),e=>e.status===404);
  const restored = Service.fromSnapshot(JSON.parse(JSON.stringify(service.exportSnapshot())));
  assert.equal(restored.me(restored.userActor(token)).family.id,familyB.id);
  assert.equal(restored.me(restored.userActor(session2)).family.id,familyA.id);
  restored.leaveFamily(restored.userActor(session2));
  assert.deepEqual(restored.me(restored.userActor(token)).families.map(f=>f.id),[familyB.id]);
  assert.equal(service.invitePreview(second,service.createInvite(actor).code).familyId,familyB.id);
});

test('관리자 제안도 운전자 확인을 기다리고 일반 준비나 투표는 승인하지 않으며 차량은 일괄 확인한다', async () => {
  const service = new Service({directions:route});
  const admin = await addUser(service,'admin@example.com','관리자');
  service.createFamily(admin.actor,{name:'우리 가족'});
  const driver = await addUser(service,'driver@example.com','운전자');
  service.joinFamily(driver.actor,{code:service.createInvite(admin.actor).code});
  let trip = await service.createTrip(admin.actor,{title:'여행',destination:place('부산역'),driverUserId:driver.actor.user.id});
  trip = service.proposePlace(admin.actor,trip.id,{place:place('관리자의 제안',35.2)});
  const id = trip.proposals[0].id;
  assert.equal(trip.canDecide,false);
  assert.equal(trip.proposals[0].canDecide,false);
  await assert.rejects(service.decide(admin.actor,id,{decision:'CONFIRM'}),e=>e.status===403);
  service.vote(driver.actor,id,{choice:'YES'});
  trip = await service.setTripReady(admin.actor,trip.id,{ready:true,autoConfirmPending:true});
  assert.equal(trip.stops.length,0);
  assert.equal(trip.proposals[0].status,'PROPOSED');
  await assert.rejects(service.quickStartTrip(admin.actor,trip.id),e=>e.status===403);
  const car = service.vehicleActor(service.pairVehicle({code:service.vehicleCode(admin.actor).code,name:'우리 차'}).vehicleToken);
  const driverView = service.tripDetail(driver.actor,trip.id);
  assert.equal(driverView.canDecide,true);
  assert.equal(driverView.proposals[0].canDecide,true);
  trip = await service.quickStartTrip(car,trip.id);
  assert.equal(trip.stops.length,1);
  assert.equal(trip.stops[0].status,'CONFIRMED');
});

test('가입 저장 실패는 쿠키나 성공을 발급하지 않고 동일 이메일 재시도와 계정 복원이 가능하다', async t => {
  const directory = mkdtempSync(join(tmpdir(),'family-v2-account-regression-'));
  t.after(()=>rmSync(directory,{recursive:true,force:true}));
  const archive = openLocalArchive(directory);
  let failNext = true;
  const service = new Service();
  const call = await serve(t,{service,persist:snapshot=>{if(failNext){failNext=false;throw Error('disk unavailable');}archive.save(snapshot);}});
  const credentials = {email:'returning@example.com',password,name:'다시 온 가족'};
  const responses = await Promise.all([
    call('/api/auth/signup',{data:credentials}),
    call('/api/auth/signup',{data:credentials})
  ]);
  const failed = responses.find(r => r.status === 503), success = responses.find(r => r.status === 201);
  assert.ok(failed); assert.ok(success);
  assert.equal(failed.status,503);
  assert.equal(failed.cookie,undefined);
  assert.equal(success.status,201);
  assert.ok(success.cookie);
  assert.equal(service.exportSnapshot().users.length,1);
  const restored = Service.fromSnapshot(archive.load());
  assert.equal(restored.userActor(decodeURIComponent(success.cookie.split('=')[1])).user.email,credentials.email);
  assert.equal((await restored.login(credentials)).user.email,credentials.email);
  failNext=true;
  const failedLogin = await call('/api/auth/login',{data:credentials});
  assert.equal(failedLogin.status,503);
  assert.equal(failedLogin.cookie,undefined);
  assert.equal((await call('/api/me',{cookie:success.cookie})).status,200);
  assert.equal((await call('/api/auth/login',{data:credentials})).status,200);
});

test('여행 목록의 삭제 권한과 실제 삭제 권한이 일치하고 다른 가족 여행은 보존한다', async () => {
  const service = new Service({directions:route});
  const owner = await addUser(service,'delete-owner@example.com','관리자');
  service.createFamily(owner.actor,{name:'여행 가족'});
  const member = await addUser(service,'delete-member@example.com','가족');
  service.joinFamily(member.actor,{code:service.createInvite(owner.actor).code});
  const outsider = await addUser(service,'delete-other@example.com','다른 가족');
  service.createFamily(outsider.actor,{name:'다른 가족'});
  const trip = await service.createTrip(owner.actor,{title:'삭제 시험 여행',destination:place('도착지')});
  const other = await service.createTrip(outsider.actor,{title:'보존 여행',destination:place('다른 도착지')});
  const photo = service.addPhoto(owner.actor,trip.id,{data:JPEG});
  assert.equal(service.listTrips(owner.actor)[0].canManage,true);
  assert.equal(service.listTrips(member.actor)[0].canManage,false);
  assert.throws(()=>service.deleteTrip(member.actor,trip.id),e=>e.status===403);
  assert.throws(()=>service.deleteTrip(outsider.actor,trip.id),e=>e.status===404);
  const paired = service.pairVehicle({code:service.vehicleCode(owner.actor).code,name:'확인 차량'});
  const car = service.vehicleActor(paired.vehicleToken);
  assert.equal(service.listTrips(car)[0].canManage,true);
  service.deleteTrip(car,trip.id);
  assert.equal(service.listTrips(owner.actor).length,0);
  assert.throws(()=>service.photo(owner.actor,photo.id),e=>e.status===404);
  assert.equal(service.listTrips(outsider.actor)[0].id,other.id);
});

test('도착지 변경은 경유지를 유지하고 새 경로와 저장 상태에 반영된다',async()=>{
  let unavailable=false;
  const service=new Service({directions:async c=>{
    if(unavailable)throw Error('route unavailable');
    return {points:[[37,127],...[...c.waypoints,c.destination].map(p=>[p.latitude,p.longitude])],distance:100,duration:10};
  }});
  const owner=await addUser(service,'destination-edit@example.com','운전자');
  service.createFamily(owner.actor,{name:'도착지 변경 확인'});
  let trip=await service.createTrip(owner.actor,{title:'여행',destination:place('이전 도착지',35.1)});
  trip=await service.addStop(owner.actor,trip.id,{place:place('경유지',35.2)});
  const stopId=trip.stops[0].id;
  trip=await service.updateTrip(owner.actor,trip.id,{destination:place('새 도착지',35.3)});
  assert.equal(trip.destination.name,'새 도착지');
  assert.equal(trip.stops[0].id,stopId);
  assert.deepEqual(trip.route.points.at(-1),[35.3,129]);
  const restored=Service.fromSnapshot(service.exportSnapshot());
  assert.equal(restored.tripDetail(restored.userActor(owner.token),trip.id).destination.name,'새 도착지');
  unavailable=true;
  await assert.rejects(service.updateTrip(owner.actor,trip.id,{destination:place('실패한 도착지',35.4)}));
  assert.equal(service.tripDetail(owner.actor,trip.id).destination.name,'새 도착지');
});
