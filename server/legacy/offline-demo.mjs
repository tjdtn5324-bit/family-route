// 심사자의 재현을 위한 예시 데이터. 실제 장소 조회나 도로 길찾기 결과가 아니다.
const places = [
  {name:'부산역 (시연)', address:'시연용 예시 장소 · 부산', latitude:35.115, longitude:129.042},
  {name:'광안리 (시연)', address:'시연용 예시 장소 · 부산', latitude:35.153, longitude:129.118},
  {name:'자갈치시장 (시연)', address:'시연용 예시 장소 · 부산', latitude:35.097, longitude:129.030},
  {name:'해운대 (시연)', address:'시연용 예시 장소 · 부산', latitude:35.159, longitude:129.161},
];
export async function demoSearch(query) {
  const q = typeof query === 'string' ? query.trim() : '';
  if (!q || [...q].length > 100) throw new Error('검색어는 1~100자로 입력해 주세요');
  const term = q.toLowerCase() === 'busan' ? '부산역' : q;
  return places.filter(p => (p.name + ' ' + p.address).includes(term));
}
export async function demoDirections({origin,destination,waypoints=[]}) {
  const points=[origin,...waypoints,destination].map(p=>[p.latitude,p.longitude]);
  if (points.length < 2 || points.some(p => p.some(v=>!Number.isFinite(v)))) throw new Error('경로 좌표를 확인해 주세요');
  return {points,guides:[],distance:0,duration:0,source:'DEMO',positionMode:'DEMO'};
}
