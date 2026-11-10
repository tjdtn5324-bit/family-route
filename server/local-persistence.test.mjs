import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {openLocalArchive} from './local-persistence.mjs';
import {Rooms} from './rooms.mjs';
import {createApp} from './app.mjs';

test('가족 추억과 사진은 암호화되어 재시작 뒤 복원되고 다른 가족방에는 공개되지 않는다',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'family-archive-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 const archive=openLocalArchive(dir),rooms=new Rooms({permanentMemories:true});
 const app=createApp({rooms,persist:archive.save,directions:async()=>({points:[[37,127],[35,129]],distance:1,duration:1})});
 await new Promise(r=>app.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>app.close(r)));
 const base=`http://127.0.0.1:${app.address().port}`;
 const call=async(path,token,data)=>{const r=await fetch(base+path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:data?JSON.stringify(data):undefined});return {status:r.status,data:await r.json()}};
 const room=(await call('/api/rooms',null,{name:'비밀 가족'})).data,member=(await call('/api/join',null,{invite:room.invite,name:'가족'})).data;
 await call('/api/destination',room.driverToken,{place:{name:'부산역',latitude:35.1,longitude:129}});
 assert.equal((await call('/api/trip/finish',room.driverToken,{choice:'MEMORY'})).status,200);
 const jpeg=Buffer.from([255,216,255,217]).toString('base64');
 const photo=await call('/api/photos',member.memberToken,{data:jpeg});assert.equal(photo.status,201);
 const raw=readFileSync(join(dir,'family-archive.enc'));assert.equal(raw.includes(Buffer.from(jpeg)),false);assert.equal(raw.includes(Buffer.from(room.driverToken)),false);
 const restored=Rooms.fromSnapshot(openLocalArchive(dir).load(),{permanentMemories:true});
 assert.equal(restored.state(room.driverToken).archived,true);assert.equal(restored.getPhoto(room.driverToken,photo.data.id).data,jpeg);
 const other=restored.create('다른 방');assert.throws(()=>restored.getPhoto(other.driverToken,photo.data.id));
});
