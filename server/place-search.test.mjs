import { test } from 'node:test';
import assert from 'node:assert/strict';
import { searchPlaces } from './place-search.mjs';

test('검색어와 서버 키를 전달하고 실제 장소 좌표를 변환한다', async () => {
  const results = await searchPlaces(' 강릉 카페 ', { apiKey: 'test-key', fetchImpl: async (url, options) => {
    assert.equal(url.searchParams.get('query'), '강릉 카페');
    assert.equal(options.headers.Authorization, 'KakaoAK test-key');
    return { ok: true, json: async () => ({ documents: [{ id: '1', place_name: '카페', x: '128.9', y: '37.7', road_address_name: '강릉', place_url: 'https://place.map.kakao.com/1' }, { place_name: '좌표 없음' }] }) };
  }});
  assert.deepEqual(results, [{ id: '1', name: '카페', address: '강릉', latitude: 37.7, longitude: 128.9, category: '', url: 'https://place.map.kakao.com/1' }]);
});

test('설정과 검색어가 없거나 외부 검색이 실패하면 명확히 실패한다', async () => {
  await assert.rejects(searchPlaces('', { apiKey: 'key' }));
  await assert.rejects(searchPlaces('카페', {}));
  await assert.rejects(searchPlaces('카페', { apiKey: 'key', fetchImpl: async () => ({ ok: false }) }));
});
