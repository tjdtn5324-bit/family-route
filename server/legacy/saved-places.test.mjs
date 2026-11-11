import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
const html = readFileSync(new URL('./phone.html', import.meta.url), 'utf8');
const source = html.match(/function distanceToRouteKm\(place, points\) \{[\s\S]*?\n\}/)?.[0];
assert.ok(source, '거리 계산 함수를 찾을 수 있어야 한다');
const distanceToRouteKm = runInNewContext(`(${source})`);

test('저장 장소와 경로 선분의 거리를 계산한다', () => {
  const route = [[37, 127], [37, 127.1]];
  assert.ok(distanceToRouteKm({ latitude: 37, longitude: 127.05 }, route) < 0.001);
  assert.ok(distanceToRouteKm({ latitude: 37.1, longitude: 127.05 }, route) > 10);
  assert.equal(distanceToRouteKm({ latitude: 37, longitude: 127 }, []), null);
});
