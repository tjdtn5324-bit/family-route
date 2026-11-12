# 제3자 구성 요소 고지

이 저장소와 APK에 포함되거나 실행 중에 불러오는 외부 구성 요소입니다.

| 구성 요소 | 쓰는 곳 | 라이선스 |
|---|---|---|
| Leaflet 1.9 | 차량 지도 화면 (`app/src/main/assets/map/`) | BSD 2-Clause ([전문](../../app/src/main/assets/map/LICENSE)) |
| OpenStreetMap 지도 타일·데이터 | 차량 지도 배경 (실행 중 `tile.openstreetmap.org`에서 불러옴) | © OpenStreetMap contributors, ODbL 1.0 |
| Noto Sans KR | 차량 앱 글꼴 (`app/src/main/res/font/`) | SIL Open Font License 1.1 ([전문](NotoSansKR-OFL.txt)) |
| ZXing core 3.5.3 | 초대 QR 코드 생성 | Apache License 2.0 |
| AndroidX (Compose, Activity, Core, Lifecycle) | 차량 앱 UI | Apache License 2.0 |
| Kotlin 표준 라이브러리, kotlinx.serialization | 차량 앱 | Apache License 2.0 |

카카오 Local·카카오모빌리티 Directions API는 키를 설정했을 때만 호출하며, 저장소와 APK에는 키가 들어 있지 않습니다.

추천 코스의 참고 코스(`server/v2/reference-courses.mjs`)는 공개된 관광 안내 자료를 참고해 장소 이름과 순서만 시연용으로 다시 구성한 것이며, 원문·사진·영상은 포함하지 않습니다.
