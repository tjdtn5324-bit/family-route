import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Rooms } from './rooms.mjs';
import { createApp } from './app.mjs';

const identity = async token => {
  if (token === 'login-a') return 'account-a';
  if (token === 'login-b') return 'account-b';
  throw Error('invalid account');
};

async function start(t, options = {}) {
  const app = createApp(options);
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => app.close(resolve)));
  const base = `http://127.0.0.1:${app.address().port}`;
  return async (path, {token, method = 'GET', data} = {}) => {
    const response = await fetch(base + path, {
      method, headers: {'Content-Type':'application/json', ...(token ? {Authorization:'Bearer ' + token} : {})},
      body: data === undefined ? undefined : JSON.stringify(data),
    });
    return {status:response.status, body:await response.json()};
  };
}

function fixture() {
  const rooms = new Rooms({permanentMemories:true});
  const a = rooms.create('우리 가족'), b = rooms.create('부모님 여행');
  const memberA = rooms.join(a.invite, '민지'), memberB = rooms.join(b.invite, '민지');
  const peer = rooms.join(a.invite, '친구');
  return {rooms, a, b, memberA, memberB, peer};
}

test('계정별 병렬 가족방은 본인 소속만 반환하고 서버 재시작 뒤 같은 참가자로 복원한다', async t => {
  const f = fixture();
  let saved;
  const call = await start(t, {rooms:f.rooms, profile:{config:{}, verify:identity}, persist:snapshot => { saved = snapshot; }});
  for (const member of [f.memberA, f.memberB]) {
    const result = await call('/api/profile/rooms', {token:'login-a', method:'POST', data:{memberToken:member.memberToken, userId:'account-b'}});
    assert.equal(result.status,200);
    assert.match(result.body.accountKey,/^[0-9a-f]{64}$/);
    assert.equal(result.body.accountKey.includes('account-a'),false);
  }
  const own = await call('/api/profile/rooms', {token:'login-a'});
  assert.deepEqual(own.body.rooms.map(room => room.name), ['우리 가족','부모님 여행']);
  assert.equal(own.body.rooms[0].memberName,'민지');
  assert.equal(own.body.rooms[0].memberCount,2);
  assert.deepEqual((await call('/api/profile/rooms', {token:'login-b'})).body.rooms,[]);
  assert.notEqual((await call('/api/profile/rooms', {token:'login-b'})).body.accountKey,own.body.accountKey);
  const restored = Rooms.fromSnapshot(saved, {permanentMemories:true});
  assert.deepEqual(restored.accountRooms('account-a'), own.body.rooms);
  assert.equal(restored.state(own.body.rooms[0].token).me.id, f.rooms.state(f.memberA.memberToken).me.id);
});

test('다른 계정 및 운전자 세션 연결을 거절하고 같은 계정의 중복 초대는 대표 소속을 유지한다', async t => {
  const f = fixture();
  const call = await start(t, {rooms:f.rooms, profile:{config:{}, verify:identity}});
  const attach = (token, memberToken) => call('/api/profile/rooms', {token, method:'POST',data:{memberToken}});
  const first = await attach('login-a',f.memberA.memberToken);
  assert.equal((await attach('login-b',f.memberA.memberToken)).status,403);
  assert.equal((await attach('login-a',f.a.driverToken)).status,403);
  assert.equal((await attach('login-a','not-a-token')).status,401);
  const duplicate = f.rooms.join(f.a.invite,'민지 재접속');
  const second = await attach('login-a',duplicate.memberToken);
  assert.equal(second.body.room.token,first.body.room.token);
  assert.equal((await call('/api/profile/rooms', {token:'login-a'})).body.rooms.length,1);
  assert.equal((await attach('login-b',duplicate.memberToken)).status,403);
});

test('명시적 탈퇴는 본인의 해당 방 세션만 무효화하고 다른 방과 가족의 사진을 보존한다', async t => {
  const f = fixture();
  const duplicate = f.rooms.join(f.a.invite,'민지 재접속');
  f.rooms.bindAccountRoom('account-a',f.memberA.memberToken);
  f.rooms.bindAccountRoom('account-a',duplicate.memberToken);
  f.rooms.bindAccountRoom('account-a',f.memberB.memberToken);
  f.rooms.bindAccountRoom('account-b',f.peer.memberToken);
  const photo = f.rooms.addPhoto(f.peer.memberToken,Buffer.from([255,216,255,217]).toString('base64'));
  const call = await start(t, {rooms:f.rooms, profile:{config:{}, verify:identity}});
  assert.equal((await call('/api/profile/rooms/' + f.b.roomId, {token:'login-b',method:'DELETE'})).status,404);
  assert.equal((await call('/api/profile/rooms/' + f.a.roomId, {token:'login-a',method:'DELETE'})).status,200);
  for (const memberToken of [f.memberA.memberToken,duplicate.memberToken]) {
    assert.equal((await call('/api/state', {token:memberToken})).status,401);
  }
  assert.equal((await call('/api/state', {token:f.memberB.memberToken})).status,200);
  assert.equal((await call('/api/state', {token:f.peer.memberToken})).status,200);
  assert.equal((await call('/api/state', {token:f.a.driverToken})).status,200);
  assert.equal((await call('/api/photos/' + photo.id, {token:f.peer.memberToken})).status,200);
  const peerState = f.rooms.state(f.peer.memberToken);
  assert.deepEqual(peerState.members.map(member => member.name),['친구']);
  assert.deepEqual(f.rooms.accountRooms('account-a').map(room => room.id),[f.b.roomId]);
  assert.deepEqual(f.rooms.accountRooms('account-b').map(room => room.id),[f.a.roomId]);
});

test('계정 가족방 API는 미설정 서버와 로그인 없는 요청을 명확히 거절한다', async t => {
  const unconfigured = await start(t);
  assert.equal((await unconfigured('/api/profile/rooms')).status,503);
  const call = await start(t,{profile:{config:{},verify:identity}});
  assert.equal((await call('/api/profile/rooms')).status,401);
  assert.equal((await call('/api/profile/rooms', {token:'invalid',method:'POST',data:{memberToken:'anything'}})).status,401);
});
