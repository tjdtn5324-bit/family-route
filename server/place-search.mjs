// 서버에서만 사용: REST 키를 브라우저나 차량 앱에 전달하지 않는다.
export async function searchPlaces(query, { apiKey, fetchImpl = fetch } = {}) {
  const q = typeof query === 'string' ? query.trim() : '';
  if (!q || [...q].length > 100) throw new Error('검색어는 1~100자로 입력해 주세요');
  if (!apiKey) throw new Error('장소 검색 서비스가 설정되지 않았습니다');
  const url = new URL('https://dapi.kakao.com/v2/local/search/keyword.json');
  url.searchParams.set('query', q);
  url.searchParams.set('size', '10');
  const response = await fetchImpl(url, {
    headers: { Authorization: `KakaoAK ${apiKey}` },
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error('장소 검색에 실패했습니다');
  const data = await response.json();
  if (!Array.isArray(data.documents)) throw new Error('장소 검색 응답을 읽을 수 없습니다');
  return data.documents.flatMap((p) => {
    const latitude = Number(p.y), longitude = Number(p.x);
    if (!p.place_name || !p.x || !p.y || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return [];
    return [{ id: String(p.id), name: p.place_name, address: p.road_address_name || p.address_name || '', latitude, longitude, category: p.category_name || '', url: p.place_url || '' }];
  });
}
