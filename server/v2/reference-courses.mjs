// 앱이 처음부터 보여 주는 '지자체 추천' 코스. 블로그를 따로 찾지 않아도 되도록,
// 각 지자체가 공식 관광 누리집에서 안내하는 명소를 묶어 시연용으로 구성했다(출처는 코스마다 표시).
// 장소 좌표는 카카오 장소 검색으로 확인했다. 원문·사진을 옮긴 것이 아니며, 실제 서비스에서는 지자체·한국관광공사 공개 자료와 연동한다.
const place=(name,latitude,longitude,address)=>({name,latitude,longitude,address});
const p={
  seoul:place('서울역',37.554722,126.970833,'서울 용산구 한강대로 405'),
  gyeongbok:place('경복궁',37.579617,126.977041,'서울 종로구 사직로 161'),
  bukchon:place('북촌한옥마을',37.582604,126.983969,'서울 종로구 계동길 37'),
  namsan:place('남산서울타워',37.551169,126.988227,'서울 용산구 남산공원길 105'),
  gwangjang:place('광장시장',37.570034,126.999609,'서울 종로구 창경궁로 88'),
  busan:place('부산역',35.11520340622514,129.04154985192403,'부산 동구 중앙대로 206'),
  gamcheon:place('감천문화마을',35.09740872250286,129.01056080474402,'부산 사하구 감천동 1-17'),
  jagalchi:place('자갈치시장',35.09661988021089,129.03057978534403,'부산 중구 자갈치해안로 52'),
  gwangalli:place('광안리해수욕장',35.1531932736837,129.118976093583,'부산 수영구 광안해변로 219'),
  gangneung:place('강릉역',37.7645235587621,128.899627553491,'강원특별자치도 강릉시 용지로 176'),
  market:place('강릉중앙시장',37.754025117657804,128.8986237900098,'강원특별자치도 강릉시 금성로 21'),
  anmok:place('안목해변',37.7722617595376,128.948275405137,'강원특별자치도 강릉시 창해로14번길 51-26'),
  gyeongpo:place('경포해수욕장',37.8034055083125,128.910210247605,'강원특별자치도 강릉시 창해로 514'),
  cheomseongdae:place('첨성대',35.83471481233599,129.21900018259583,'경북 경주시 인왕동 839-1'),
  daereungwon:place('대릉원',35.83819105876201,129.21333399231364,'경북 경주시 황남동 31-1'),
  donggung:place('동궁과월지',35.8347762643542,129.226975518857,'경북 경주시 원화로 102'),
  hanok:place('전주한옥마을',35.81477744329797,127.15255700142177,'전북특별자치도 전주시 완산구 풍남동3가 63-1'),
  gyeonggijeon:place('경기전',35.81537944320413,127.1497232503336,'전북특별자치도 전주시 완산구 태조로 44'),
  jeonjuMarket:place('전주남부시장 한옥마을야시장',35.8129818304138,127.147012284091,'전북특별자치도 전주시 완산구 풍남문2길 49'),
};
export const REFERENCE_COURSES=[
  {id:'guide-seoul-palace',title:'서울 궁궐과 한옥 산책',destination:p.seoul,waypoints:[p.gyeongbok,p.bukchon],sourceName:'서울특별시 공식 관광 안내 비짓서울',sourceUrl:'https://korean.visitseoul.net/mvp'},
  {id:'guide-seoul-market',title:'서울 시장과 남산 풍경',destination:p.seoul,waypoints:[p.gwangjang,p.namsan],sourceName:'서울특별시 공식 관광 안내 비짓서울',sourceUrl:'https://korean.visitseoul.net/mvp'},
  {id:'guide-busan-market',title:'부산 골목과 시장 둘러보기',destination:p.busan,waypoints:[p.gamcheon,p.jagalchi],sourceName:'부산광역시 공식 관광 안내 비짓부산',sourceUrl:'https://www.visitbusan.net/index.do?menuCd=DOM_000000202012000000'},
  {id:'guide-busan-sea',title:'부산 시장과 바다',destination:p.busan,waypoints:[p.jagalchi,p.gwangalli],sourceName:'부산광역시 공식 관광 안내 비짓부산',sourceUrl:'https://www.visitbusan.net/index.do?menuCd=DOM_000000202012000000'},
  {id:'guide-gangneung-market',title:'강릉 시장과 커피 바다',destination:p.gangneung,waypoints:[p.market,p.anmok],sourceName:'강릉시 공식 관광 안내',sourceUrl:'https://visitgangneung.net/pub/tourtheme.do'},
  {id:'guide-gangneung-sea',title:'강릉 바다 천천히 보기',destination:p.gangneung,waypoints:[p.anmok,p.gyeongpo],sourceName:'강릉시 공식 관광 안내',sourceUrl:'https://visitgangneung.net/pub/tourtheme.do'},
  {id:'guide-gyeongju-history',title:'경주 천년의 풍경',destination:p.donggung,waypoints:[p.daereungwon,p.cheomseongdae],sourceName:'경주시 문화관광 · 경주야경산책 코스',sourceUrl:'https://www.gyeongju.go.kr/tour/page.do?mnu_uid=4724&'},
  {id:'guide-jeonju-hanok',title:'전주 한옥과 시장 구경',destination:p.hanok,waypoints:[p.gyeonggijeon,p.jeonjuMarket],sourceName:'전주시 문화관광 추천여행',sourceUrl:'https://tour.jeonju.go.kr/index.jeonju?menuCd=DOM_000000112000000000'},
].map(c=>Object.freeze({...c,kind:'REFERENCE',description:'지자체 공식 관광 안내에 소개된 곳을 묶은 코스예요. 방문 전 운영 시간과 주차를 확인해 주세요.',createdAt:0}));
