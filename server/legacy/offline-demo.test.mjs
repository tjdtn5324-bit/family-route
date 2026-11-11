import {test} from 'node:test';
import assert from 'node:assert/strict';
import {demoSearch,demoDirections} from './offline-demo.mjs';

test('키 없는 시연 장소와 경로는 실제 데이터와 구별된다', async () => {
  const places=await demoSearch('부산역');
  assert.equal(places.length,1);
  assert.match(places[0].name,/시연/);
  const route=await demoDirections({origin:{latitude:37.5133,longitude:127.1002},destination:places[0],waypoints:[]});
  assert.equal(route.source,'DEMO');
  assert.equal(route.duration,0);
  assert.equal(route.points.length,2);
  await assert.rejects(demoSearch(''));
});
