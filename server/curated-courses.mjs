// 장소 좌표는 카카오 장소 검색으로 확인. 동선은 원본 게시물의 복제가 아닌 앱 시연용 재구성이다.
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
const prefs=styles=>({companions:[],styles,hours:null});
export const curatedCourses=[
  {id:'guide-seoul-palace',title:'서울 궁궐과 한옥 산책',destination:p.seoul,waypoints:[p.gyeongbok,p.bukchon],preferences:prefs(['RELAX']),sourceName:'서울 관광 안내',sourceUrl:'https://korean.visitseoul.net/'},
  {id:'guide-seoul-market',title:'서울 시장과 남산 풍경',destination:p.seoul,waypoints:[p.gwangjang,p.namsan],preferences:prefs(['FOOD','RELAX']),sourceName:'서울 관광 안내',sourceUrl:'https://korean.visitseoul.net/'},
  {id:'guide-busan-market',title:'부산 골목과 시장 둘러보기',destination:p.busan,waypoints:[p.gamcheon,p.jagalchi],preferences:prefs(['FOOD']),sourceName:'한국관광공사 부산 여행 안내',sourceUrl:'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=b167f992-9942-4f71-8ea6-94079a108650'},
  {id:'guide-busan-sea',title:'부산 시장과 바다',destination:p.busan,waypoints:[p.jagalchi,p.gwangalli],preferences:prefs(['FOOD','RELAX']),sourceName:'한국관광공사 시장 여행 안내',sourceUrl:'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=0d5f1981-f9a0-4771-a084-21ee06eee77b'},
  {id:'guide-gangneung-market',title:'강릉 시장과 커피 바다',destination:p.gangneung,waypoints:[p.market,p.anmok],preferences:prefs(['FOOD','RELAX']),sourceName:'강릉 당일 여행 영상',sourceUrl:'https://www.youtube.com/watch?v=atIUbjHTxqA'},
  {id:'guide-gangneung-sea',title:'강릉 바다 천천히 보기',destination:p.gangneung,waypoints:[p.anmok,p.gyeongpo],preferences:prefs(['RELAX']),sourceName:'강릉 당일 여행 영상',sourceUrl:'https://www.youtube.com/watch?v=atIUbjHTxqA'},
  {id:'guide-gyeongju-history',title:'경주 천년의 풍경',destination:p.donggung,waypoints:[p.daereungwon,p.cheomseongdae],preferences:prefs(['RELAX']),sourceName:'한국관광공사 경주 여행 안내',sourceUrl:'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=90c35e84-e7cf-4316-b122-ed2e08adb277'},
  {id:'guide-jeonju-hanok',title:'전주 한옥과 시장 구경',destination:p.hanok,waypoints:[p.gyeonggijeon,p.jeonjuMarket],preferences:prefs(['FOOD']),sourceName:'국립전주박물관 전주 여행 영상',sourceUrl:'https://www.youtube.com/watch?v=yPtVLSN6xao'},
].map(c=>({...c,sourceKind:c.sourceUrl.includes('youtube.com')?'video':'tour',description:'시연용으로 관광 안내 자료를 참고해 만든 코스예요. 방문 전 운영 시간과 주차를 확인해 주세요.',sample:true,curated:true,createdAt:0}));
