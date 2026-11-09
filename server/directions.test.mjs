import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getDirections } from './directions.mjs';
test('경유지 좌표를 전달하고 실제 도로 경로를 표시 가능한 좌표로 변환', async () => {
  const p = { latitude: 37, longitude: 127 };
  const route = await getDirections({origin:p,destination:p,waypoints:[p]}, {apiKey:'test', fetchImpl:async (url,options)=>{
    assert.equal(url.searchParams.get('waypoints'),'127,37'); assert.equal(options.headers.Authorization,'KakaoAK test');
    return {ok:true,json:async()=>({routes:[{result_code:0,summary:{distance:100,duration:10},sections:[{roads:[{vertexes:[127,37,127.1,37.1]}],guides:[{guidance:'우회전',distance:100,x:127,y:37}]}]}]})};
  }});
  assert.deepEqual(route.points, [[37,127],[37.1,127.1]]); assert.equal(route.guides[0].text,'우회전');
});
test('길찾기 실패는 성공으로 처리하지 않는다', async () => {
  const p = {latitude:37,longitude:127};
  await assert.rejects(getDirections({origin:p,destination:p},{apiKey:'test',fetchImpl:async()=>({ok:true,json:async()=>({routes:[{result_code:1}]})})}));
});
