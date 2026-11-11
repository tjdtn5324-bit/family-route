import test from 'node:test';
import assert from 'node:assert/strict';
import { Rooms } from './rooms.mjs';
test('가족 초대 코드는 가족 권한만 주며 만료·잘못된 코드를 거절한다',()=>{
 const rooms=new Rooms(),a=rooms.create('A'),b=rooms.create('B');
 assert.throws(()=>rooms.inviteCode('invalid'));
 const c=rooms.inviteCode(a.driverToken);
 assert.equal(rooms.inviteCode(a.driverToken).code,c.code);
 const member=rooms.joinWithCode(c.code.toLowerCase().slice(0,5)+' '+c.code.slice(5),'엄마');
 assert.equal(member.roomId,a.roomId);
 assert.equal(rooms.session(member.memberToken).role,'member');
 assert.notEqual(rooms.inviteCode(b.driverToken).code,c.code);
 assert.throws(()=>rooms.claimPairing(c.code));
 assert.throws(()=>rooms.joinWithCode('00000-INVALID','가족'));
 const restored=Rooms.fromSnapshot(rooms.exportSnapshot());
 assert.throws(()=>restored.joinWithCode(c.code,'가족'));
 const now=Date.now;try{Date.now=()=>c.expiresAt+1;assert.throws(()=>rooms.joinWithCode(c.code,'가족'));}finally{Date.now=now;}
});
