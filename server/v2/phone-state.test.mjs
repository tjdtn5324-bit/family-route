import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const html = readFileSync(new URL('./app.html', import.meta.url), 'utf8');
const viewerSource = html.slice(html.indexOf('let viewerEntry ='), html.indexOf('\nfunction openEditTrip()', html.indexOf('let viewerEntry =')));
function viewerFixture(photoSrc = async () => 'data:image/jpeg;base64,test') {
  const listeners = new Map();
  const elements = new Map(['viewer','viewerImg','viewerWho','viewerDel','viewerClose'].map(id => [id, {
    open:false, src:'', textContent:'', handlers:new Map(),
    removeAttribute(name) { this[name] = ''; },
    showModal() { this.open = true; },
    close() { this.open = false; this.handlers.get('close')?.({}); },
    addEventListener(name, handler) { this.handlers.set(name, handler); }
  }]));
  const states = [{route:'trip'}];
  const history = {get state() {return states.at(-1);},pushState(state) {states.push(state);},back() {states.pop(); listeners.get('popstate')?.();}};
  const context = { $:id => elements.get(id), history, window:{addEventListener:(name, handler)=>listeners.set(name,handler)}, location:{href:'https://family.example/'}, photoSrc, fmtDay:()=> '오늘', api:async()=>({}), photoCache:new Map(), reload:async()=>{}, toast:()=>{} };
  runInNewContext(viewerSource + '\nglobalThis.openPhoto = openViewer;', context);
  return {context, elements, history, states};
}
const photo = {id:'test-photo', by:{name:'가족'},createdAt:0,canDelete:false};

test('사진을 본 뒤 브라우저 뒤로가기는 사진만 닫고 여행 경로를 보존한다', async () => {
  const f = viewerFixture();
  f.context.openPhoto(photo);
  await Promise.resolve();
  assert.equal(f.elements.get('viewer').open,true);
  assert.equal(f.states.length,2);
  f.history.back();
  assert.equal(f.elements.get('viewer').open,false);
  assert.equal(f.elements.get('viewerImg').src,'');
  assert.deepEqual(f.history.state,{route:'trip'});
});

test('사진 닫기와 Esc도 동일한 뒤로가기 항목을 소비한다', () => {
  const f = viewerFixture();
  f.context.openPhoto(photo);
  f.elements.get('viewerClose').onclick();
  assert.equal(f.states.length,1);
  f.context.openPhoto(photo);
  let prevented = false;
  f.elements.get('viewer').handlers.get('cancel')({preventDefault(){prevented=true;}});
  assert.equal(prevented,true);
  assert.equal(f.elements.get('viewer').open,false);
  assert.equal(f.states.length,1);
});

test('닫은 사진의 늦은 응답이 다음 사진을 덮어쓰지 않는다', async () => {
  let finish;
  const old = new Promise(resolve => {finish=resolve;});
  const f = viewerFixture(id=>id==='test-photo'?old:Promise.resolve('new-photo'));
  f.context.openPhoto(photo);
  f.elements.get('viewerClose').onclick();
  f.context.openPhoto({...photo,id:'new'});
  await Promise.resolve();
  assert.equal(f.elements.get('viewerImg').src,'new-photo');
  finish('old-photo');
  await Promise.resolve();
  assert.equal(f.elements.get('viewerImg').src,'new-photo');
});

test('가족방 전환 전 요청은 늦게 도착해도 새 가족방에 전달되지 않는다', async () => {
  const start = html.indexOf('let familyEpoch =');
  const end = html.indexOf('// ───────── 상태와 화면 전환',start);
  let finish;
  const pending = new Promise(resolve=>{finish=resolve;});
  const context = {fetch:()=>pending,AbortSignal,resetToLogin:()=>{}};
  runInNewContext(html.slice(start,end)+'\nglobalThis.call=api;globalThis.switchFamily=()=>familyEpoch++;',context);
  const request = context.call('/api/trips');
  context.switchFamily();
  finish({status:200,ok:true,json:async()=>({photos:['previous-family']})});
  await assert.rejects(request,error=>error.message==='');
});

function quickProposalFixture(trips) {
  const calls = [], buttons = [];
  const source = html.slice(html.indexOf('async function openQuickProposal('), html.indexOf('function tripCard(', html.indexOf('async function openQuickProposal(')));
  const context = {
    S:{me:{family:{id:'family'}},trips},
    showTrip:async id=>{calls.push(['trip',id]);},
    openPropose:options=>calls.push(['propose',options.kind]),
    openCreateTrip:options=>calls.push(['create',options.proposalKind]),
    showNoFamily:()=>calls.push(['family']),
    h:()=>({}),
    btn:(label,run)=>{buttons.push({label,run});return {};},
    openSheet:(title,render)=>{calls.push(['choose']);render({append(){}});}
  };
  runInNewContext(source+'\nglobalThis.quick=openQuickProposal;',context);
  return {calls,buttons,quick:context.quick};
}
test('여행 목록에서 목적지와 경유지 제안을 바로 열고 완료 여행은 선택하지 않는다',async()=>{
  const one=quickProposalFixture([{id:'done',status:'DONE'},{id:'active',status:'ONGOING'}]);
  await one.quick('DESTINATION');
  assert.deepEqual(one.calls,[['trip','active'],['propose','DESTINATION']]);
  const none=quickProposalFixture([{id:'done',status:'DONE'}]);
  await none.quick('STOP');
  assert.deepEqual(none.calls,[['create','STOP']]);
});
test('여행이 여러 개면 사용자가 고르기 전까지 어느 여행에도 제안하지 않는다',async()=>{
  const f=quickProposalFixture([{id:'a',title:'A',status:'PLANNING'},{id:'b',title:'B',status:'ONGOING'}]);
  await f.quick('STOP');
  assert.deepEqual(f.calls,[['choose']]);
  await f.buttons.find(button=>button.label==='B').run();
  assert.deepEqual(f.calls,[['choose'],['trip','b'],['propose','STOP']]);
});

test('여행 목록 삭제는 확인 후에만 요청하고 다른 여행 선택을 보존한다',async()=>{
  const source=html.slice(html.indexOf('async function deleteListedTrip('),html.indexOf('const placeSearch',html.indexOf('async function deleteListedTrip(')));
  const calls=[];
  const context={S:{trip:{id:'keep'},view:'trip'},confirmSheet:async()=>false,api:async(...args)=>calls.push(args),photoCache:new Map([['old','photo']]),toast:()=>{},go:async tab=>calls.push(['go',tab])};
  runInNewContext(source+'\nglobalThis.remove=deleteListedTrip;',context);
  await context.remove({id:'remove',title:'삭제할 여행'});
  assert.equal(calls.length,0);
  context.confirmSheet=async()=>true;
  await context.remove({id:'remove',title:'삭제할 여행'},'album');
  assert.equal(calls[0][0],'/api/trips/remove');
  assert.equal(calls[0][1].method,'DELETE');
  assert.deepEqual(calls[1],['go','album']);
  assert.equal(context.S.trip.id,'keep');
  assert.equal(context.photoCache.size,0);
});
