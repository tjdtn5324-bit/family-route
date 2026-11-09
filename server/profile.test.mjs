import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, SignJWT } from 'jose';
import { createIdentityVerifier, createSavedPlaceStore } from './profile.mjs';
import { createApp } from './app.mjs';

test('Cognito 서명·발급자·클라이언트·토큰 종류를 검증한다', async () => {
  const {publicKey,privateKey}=await generateKeyPair('RS256');
  const issuer='https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_example';
  const verify=createIdentityVerifier({region:'ap-southeast-2',userPoolId:'ap-southeast-2_example',clientId:'client',keySet:publicKey});
  const signed=async claims => new SignJWT(claims).setProtectedHeader({alg:'RS256'}).setIssuer(issuer).setSubject('family-user').setIssuedAt().setExpirationTime('5m').sign(privateKey);
  assert.equal(await verify(await signed({token_use:'access',client_id:'client'})),'family-user');
  await assert.rejects(verify(await signed({token_use:'id',client_id:'client'})));
  await assert.rejects(verify(await signed({token_use:'access',client_id:'other'})));
  await assert.rejects(verify('invalid'));
});

test('사용자별 저장과 삭제는 검증된 계정의 키만 사용한다', async () => {
  const rows=new Map();
  const client={send:async command=>{
    const input=command.input, name=command.constructor.name;
    if(name==='PutCommand'){rows.set(input.Item.pk+'|'+input.Item.sk,input.Item);return {}}
    if(name==='QueryCommand')return {Items:[...rows.values()].filter(x=>x.pk===input.ExpressionAttributeValues[':pk'])};
    if(name==='DeleteCommand'){rows.delete(input.Key.pk+'|'+input.Key.sk);return {}}
    throw Error(name);
  }};
  const store=createSavedPlaceStore({region:'ap-southeast-2',tableName:'test',client});
  const place=await store.save('a',{name:'광안리',latitude:35.15,longitude:129.11});
  assert.equal((await store.list('a')).length,1);assert.equal((await store.list('b')).length,0);
  await store.remove('b',place.id);assert.equal((await store.list('a')).length,1);
  await store.remove('a',place.id);assert.equal((await store.list('a')).length,0);
  await assert.rejects(store.save('a',{name:'bad',latitude:999,longitude:0}));
});

test('로그인 없는 저장 요청을 거절하고 본인 계정의 저장 장소만 반환한다', async t => {
  const data=new Map(), store={list:async id=>data.get(id)||[],save:async(id,p)=>{data.set(id,[p]);return p},remove:async id=>data.delete(id)};
  const app=createApp({profile:{config:{clientId:'test',domain:'https://test.example',region:'ap-southeast-2'},verify:async token=>{if(token==='a'||token==='b')return token;throw Error('invalid')},store}});
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>app.close(resolve)));
  const base=`http://127.0.0.1:${app.address().port}`;
  const request=(token,method='GET',data)=>fetch(base+'/api/profile/places',{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:data?JSON.stringify(data):undefined});
  assert.equal((await request()).status,401);
  assert.equal((await request('a','POST',{place:{name:'광안리'}})).status,201);
  assert.deepEqual((await (await request('b')).json()).places,[]);
  assert.equal((await (await request('a')).json()).places[0].name,'광안리');
});

test('계정 장소 검색과 휴대폰에서 만든 여행방의 차량 연결', async t => {
  const profile={config:{},verify:async token=>{if(token!=='account')throw Error('invalid');return 'user'},store:{}};
  const app=createApp({profile,createSecret:'vehicle',search:async q=>[{name:q,latitude:35,longitude:129}]});
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>app.close(resolve)));
  const base=`http://127.0.0.1:${app.address().port}`;
  const request=(path,{token,data,secret}={})=>fetch(base+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{ }),...(secret?{'X-Room-Create-Secret':secret}:{})},body:data?JSON.stringify(data):undefined});
  assert.equal((await request('/api/profile/search?q=busan')).status,401);
  assert.equal((await (await request('/api/profile/search?q=busan',{token:'account'})).json()).places[0].name,'busan');
  assert.equal((await request('/api/rooms/prepare',{data:{memberName:'엄마'}})).status,401);
  const room=await (await request('/api/rooms/prepare',{token:'account',data:{memberName:'엄마'}})).json();
  assert.equal(room.driverToken,undefined);assert.ok(room.memberToken);assert.ok(room.code);
  assert.equal((await request('/api/rooms/claim',{data:{code:room.code}})).status,403);
  const vehicle=await (await request('/api/rooms/claim',{secret:'vehicle',data:{code:room.code}})).json();
  assert.ok(vehicle.driverToken);assert.equal((await request('/api/state',{token:vehicle.driverToken})).status,200);
  assert.equal((await request('/api/rooms/claim',{secret:'vehicle',data:{code:room.code}})).status,400);
});
