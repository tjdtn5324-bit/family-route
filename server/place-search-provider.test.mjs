import test from 'node:test';
import assert from 'node:assert/strict';
import { createPlaceSearch, loadPlaceSearchKey } from './place-search-provider.mjs';

const key = 'test-key-for-place-search';
const example = { name:'부산역', latitude:35.1152, longitude:129.0415, address:'부산' };

test('실제 검색은 기존 지역 목록과 무관하게 검색어를 전달하고 빈 결과도 유지한다', async () => {
  const calls = [];
  const search = createPlaceSearch({apiKey:key, localSearch:()=>[example], search:async (q, options)=>{calls.push([q,options.apiKey]);return q==='제주공항'?[{name:q,latitude:33.51,longitude:126.49}]:[];}});
  assert.equal((await search(' 제주공항 ')).places[0].name,'제주공항');
  assert.deepEqual(await search('검색결과없는곳'),{places:[],source:'KAKAO'});
  assert.deepEqual(calls,[['제주공항',key],['검색결과없는곳',key]]);
});

test('실제 검색 실패를 제한된 시연 결과로 바꾸지 않는다', async () => {
  const search = createPlaceSearch({apiKey:key,localSearch:()=>[example],search:async()=>{throw Error('upstream denied');}});
  await assert.rejects(search('부산역'), e=>e.status===503 && !e.message.includes(key));
});

test('키가 없을 때 기존 장소는 시연 표시하며 목록 밖은 설정 필요를 알린다', async () => {
  const search = createPlaceSearch({localSearch:q=>q==='부산역'?[example]:[]});
  const result=await search('부산역');
  assert.equal(result.source,'DEMO');assert.match(result.notice,/시연용/);
  await assert.rejects(search('제주공항'),e=>e.status===503 && /지도 API 설정/.test(e.message));
  await assert.rejects(search(' '),e=>e.status===400);
});

test('키는 서버 환경 변수를 우선하며 로컬 설정 오류에서 비밀값을 출력하지 않는다', () => {
  assert.equal(loadPlaceSearchKey({environment:{KAKAO_REST_API_KEY:key},path:'unused',read:()=>{throw Error('unexpected file read');}}),key);
  assert.equal(loadPlaceSearchKey({environment:{},path:'local',read:()=>JSON.stringify({kakaoRestApiKey:key})}),key);
  assert.equal(loadPlaceSearchKey({environment:{},path:'local',read:()=>'{"kakaoRestApiKey":""}'}),'');
  assert.equal(loadPlaceSearchKey({environment:{},path:'local',read:()=>{const e=Error();e.code='ENOENT';throw e;}}),'');
  assert.throws(()=>loadPlaceSearchKey({environment:{},path:'local',read:()=>'{'}),/파일의 형식/);
  assert.throws(()=>loadPlaceSearchKey({environment:{},path:'local',read:()=>JSON.stringify({kakaoRestApiKey:'private\nvalue'})}), e=>!e.message.includes('private'));
});
