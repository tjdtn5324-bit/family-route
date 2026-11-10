import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createApp} from './app.mjs';

test('가족방 사진은 같은 방만 접근하고 공개 코스에는 사진이 없다',async t=>{
 const app=createApp({directions:async()=>({points:[[37,127],[35,129]],distance:1,duration:1})});
 await new Promise(r=>app.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>app.close(r)));
 const base=`http://127.0.0.1:${app.address().port}`;
 async function call(path,{token,data,method}={}){const r=await fetch(base+path,{method:method|| (data?'POST':'GET'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:data?JSON.stringify(data):undefined});return {status:r.status,data:await r.json()}}
 const room=(await call('/api/rooms',{data:{name:'one'}})).data, other=(await call('/api/rooms',{data:{name:'two'}})).data;
 const member=(await call('/api/join',{data:{invite:room.invite,name:'엄마'}})).data, peer=(await call('/api/join',{data:{invite:room.invite,name:'아빠'}})).data, stranger=(await call('/api/join',{data:{invite:other.invite,name:'다른 가족'}})).data;
 const jpeg=Buffer.from([0xff,0xd8,0xff,0xd9]).toString('base64');
 assert.equal((await call('/api/photos',{data:{data:jpeg}})).status,400);
 const photo=await call('/api/photos',{token:member.memberToken,data:{data:jpeg}});assert.equal(photo.status,201);
 assert.equal((await call('/api/photos/'+photo.data.id,{token:peer.memberToken})).data.data,jpeg);
 assert.equal((await call('/api/photos/'+photo.data.id,{token:stranger.memberToken})).status,400);
 assert.equal((await call('/api/photos/'+photo.data.id,{token:peer.memberToken,method:'DELETE'})).status,400);
 const state=(await call('/api/state',{token:peer.memberToken})).data;assert.equal(state.photos.length,1);assert.equal(state.photos[0].data,undefined);
 await call('/api/destination',{token:room.driverToken,data:{place:{name:'부산역',latitude:35.115,longitude:129.042}}});
 const course=await call('/api/courses',{token:member.memberToken,data:{title:'부산 여행',description:'함께 가요'}});assert.equal(course.status,201);
 const publicList=(await call('/api/public/courses')).data;assert.equal(publicList.courses.length,1);assert.equal(JSON.stringify(publicList).includes(jpeg),false);assert.equal(JSON.stringify(publicList).includes(photo.data.id),false);
 assert.equal((await call('/api/photos/'+photo.data.id,{token:member.memberToken,method:'DELETE'})).status,200);
});

test('종료한 경로는 개인 계정에만 저장되고 좋아요는 계정별 한 번만 반영된다',async t=>{
 const records=new Map(),store={saveMemory:async(id,trip)=>{records.set(id,trip);return trip},listMemories:async id=>records.has(id)?[records.get(id)]:[]};
 const profile={config:{},store,verify:async token=>{if(!['a','b'].includes(token))throw Error('bad');return token}};
 const app=createApp({profile,directions:async()=>({points:[[37,127],[35,129]],distance:1,duration:1})});
 await new Promise(r=>app.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>app.close(r)));
 const base=`http://127.0.0.1:${app.address().port}`;
 async function call(path,{token,data,roomToken}={}){const r=await fetch(base+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{ }),...(roomToken?{'X-Room-Token':roomToken}:{})},body:data?JSON.stringify(data):undefined});return {status:r.status,data:await r.json()}}
 const room=(await call('/api/rooms',{data:{}})).data,member=(await call('/api/join',{data:{invite:room.invite,name:'가족'}})).data;
 await call('/api/destination',{token:room.driverToken,data:{place:{name:'부산역',latitude:35.115,longitude:129.042}}});
 assert.equal((await call('/api/trip/finish',{token:member.memberToken,data:{choice:'MEMORY'}})).status,400);
 assert.equal((await call('/api/trip/finish',{token:room.driverToken,data:{choice:'MEMORY'}})).status,200);
 assert.equal((await call('/api/profile/memories',{token:'a',roomToken:member.memberToken,data:{}})).status,201);
 assert.equal((await call('/api/profile/memories',{token:'a'})).data.memories.length,1);assert.equal((await call('/api/profile/memories',{token:'b'})).data.memories.length,0);
 const course=(await call('/api/courses',{token:member.memberToken,data:{title:'여행 코스'}})).data;
 assert.equal((await call('/api/courses/'+course.id+'/like',{data:{liked:true}})).status,401);
 assert.equal((await call('/api/courses/'+course.id+'/like',{token:'a',data:{liked:true}})).data.likes,1);
 assert.equal((await call('/api/courses/'+course.id+'/like',{token:'a',data:{liked:true}})).data.likes,1);
 assert.equal((await call('/api/courses/'+course.id+'/like',{token:'b',data:{liked:true}})).data.likes,2);
 const listing=(await call('/api/public/courses')).data;assert.equal(listing.courses[0].likes,2);assert.equal(JSON.stringify(listing).includes('likedBy'),false);
 assert.equal((await call('/api/courses/'+course.id+'/like',{token:'a',data:{liked:false}})).data.likes,1);
});

test('추억은 본인만 지울 수 있고 로그인 없이는 지울 수 없다',async t=>{
 const records=new Map(),key=(user,id)=>user+':'+id;
 const store={saveMemory:async(user,trip)=>{records.set(key(user,trip.id),trip);return trip},listMemories:async user=>[...records].filter(([k])=>k.startsWith(user+':')).map(([,v])=>v),removeMemory:async(user,id)=>{records.delete(key(user,id))}};
 const profile={config:{},store,verify:async token=>{if(!['a','b'].includes(token))throw Error('bad');return token}};
 const app=createApp({profile,directions:async()=>({points:[[37,127],[35,129]],distance:1,duration:1})});
 await new Promise(r=>app.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>app.close(r)));
 const base=`http://127.0.0.1:${app.address().port}`;
 async function call(path,{token,data,roomToken,method}={}){const r=await fetch(base+path,{method:method||(data?'POST':'GET'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{ }),...(roomToken?{'X-Room-Token':roomToken}:{})},body:data?JSON.stringify(data):undefined});return {status:r.status,data:await r.json()}}
 const room=(await call('/api/rooms',{data:{}})).data,member=(await call('/api/join',{data:{invite:room.invite,name:'가족'}})).data;
 await call('/api/destination',{token:room.driverToken,data:{place:{name:'부산역',latitude:35.115,longitude:129.042}}});
 await call('/api/trip/finish',{token:room.driverToken,data:{choice:'MEMORY'}});
 const saved=(await call('/api/profile/memories',{token:'a',roomToken:member.memberToken,data:{}})).data.memory;
 assert.equal((await call('/api/profile/memories/'+saved.id,{method:'DELETE'})).status,401);
 assert.equal((await call('/api/profile/memories/not-an-id',{token:'a',method:'DELETE'})).status,404);
 assert.equal((await call('/api/profile/memories/'+saved.id,{token:'b',method:'DELETE'})).status,200);
 assert.equal((await call('/api/profile/memories',{token:'a'})).data.memories.length,1);
 assert.equal((await call('/api/profile/memories/'+saved.id,{token:'a',method:'DELETE'})).status,200);
 assert.equal((await call('/api/profile/memories',{token:'a'})).data.memories.length,0);
});
