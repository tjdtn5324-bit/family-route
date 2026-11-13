import test from 'node:test';
import assert from 'node:assert/strict';
import {Service} from './service.mjs';

test('차량 출발지는 가족별 권한을 지키고 경유지와 저장 복원을 유지한다', async () => {
  const directions=async c=>({points:[[c.origin.latitude,c.origin.longitude],...c.waypoints.map(p=>[p.latitude,p.longitude]),[c.destination.latitude,c.destination.longitude]],distance:100,duration:10});
  const service=new Service({directions});
  const signup=await service.signup({email:'sdk@example.com',password:'private-test-1234',name:'가족'});
  const owner=service.userActor(signup.token);
  service.createFamily(owner,{name:'위치 확인 가족'});
  const place=(name,latitude)=>({name,latitude,longitude:129,address:''});
  const trip=await service.createTrip(owner,{title:'가족 여행',destination:place('부산역',35.1),waypoints:[place('광안리',35.15)]});
  const car=service.vehicleActor(service.pairVehicle({code:service.vehicleCode(owner).code,name:'우리 차'}).vehicleToken);
  const origin=place('현재 차량 위치',36.35);
  await assert.rejects(service.setVehicleOrigin(owner,trip.id,origin),e=>e.status===403);
  const updated=await service.setVehicleOrigin(car,trip.id,origin);
  assert.deepEqual(updated.origin,origin);
  assert.equal(updated.stops.length,1);
  assert.deepEqual(updated.route.points[0],[36.35,129]);
  const restored=Service.fromSnapshot(service.exportSnapshot(),{directions});
  assert.deepEqual(restored.tripDetail(restored.userActor(signup.token),trip.id).origin,origin);
  service.startTrip(car,trip.id);
  await assert.rejects(service.setVehicleOrigin(car,trip.id,origin),e=>e.status===409);
});

test('차량 출발지 경로 계산 실패는 기존 출발지와 경로를 보존한다', async () => {
  let broken=false;
  const service=new Service({directions:async c=>{if(broken)throw Error('down');return {points:[[c.origin.latitude,c.origin.longitude],[35,129]],distance:100,duration:10};}});
  const {token}=await service.signup({email:'rollback@example.com',password:'private-test-1234',name:'가족'});
  const owner=service.userActor(token);service.createFamily(owner,{name:'복원 가족'});
  const trip=await service.createTrip(owner,{title:'여행',destination:{name:'목적지',latitude:35,longitude:129}});
  const car=service.vehicleActor(service.pairVehicle({code:service.vehicleCode(owner).code,name:'차량'}).vehicleToken);
  broken=true;
  await assert.rejects(service.setVehicleOrigin(car,trip.id,{name:'차량',latitude:36,longitude:127}),e=>e.status===502);
  const after=service.tripDetail(owner,trip.id);
  assert.deepEqual(after.origin,trip.origin);assert.deepEqual(after.route,trip.route);
});
