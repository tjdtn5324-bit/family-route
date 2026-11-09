const coordinate = p => p && Number.isFinite(p.longitude) && Math.abs(p.longitude) <= 180 && Number.isFinite(p.latitude) && Math.abs(p.latitude) <= 90;

// 실제 도로 경로를 서버에서 조회한다. 실시간 차량 위치는 별도로 받아야 한다.
export async function getDirections({ origin, destination, waypoints = [] }, { apiKey, fetchImpl = fetch } = {}) {
  if (!coordinate(origin) || !coordinate(destination) || !Array.isArray(waypoints) || waypoints.length > 5 || waypoints.some(p => !coordinate(p))) throw new Error('경로 좌표를 확인해 주세요');
  if (!apiKey) throw new Error('길찾기 서비스가 설정되지 않았습니다');
  const url = new URL('https://apis-navi.kakaomobility.com/v1/directions');
  const point = p => `${p.longitude},${p.latitude}`;
  url.searchParams.set('origin', point(origin));
  url.searchParams.set('destination', point(destination));
  if (waypoints.length) url.searchParams.set('waypoints', waypoints.map(point).join('|'));
  url.searchParams.set('summary', 'false');
  const result = await fetchImpl(url, { headers: { Authorization: `KakaoAK ${apiKey}` }, signal: AbortSignal.timeout(12000) });
  if (!result.ok) throw new Error('실제 경로를 가져오지 못했습니다');
  const body = await result.json(), route = body.routes?.[0];
  if (!route || route.result_code !== 0 || !Array.isArray(route.sections)) throw new Error('경로를 찾지 못했습니다');
  const points = [];
  const guides = [];
  for (const section of route.sections) {
    for (const road of section.roads || []) {
      const v = road.vertexes || [];
      for (let i = 0; i + 1 < v.length; i += 2) if (Number.isFinite(v[i]) && Number.isFinite(v[i + 1])) points.push([v[i + 1], v[i]]);
    }
    for (const guide of section.guides || []) if (guide.guidance) guides.push({ text: guide.guidance, distance: guide.distance, latitude: guide.y, longitude: guide.x });
  }
  if (points.length < 2) throw new Error('경로 좌표를 가져오지 못했습니다');
  return { points, guides, distance: route.summary.distance, duration: route.summary.duration, source: 'KAKAO', positionMode: 'DEMO' };
}
