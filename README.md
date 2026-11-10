# family-route-demo

Pleos Connect(Android Automotive) 헤드유닛용 가족 경로 데모 앱. 달리는 차 안에서 가족이 들를 곳을 제안하면 운전자가 주행 화면에서 정한다. 실제 차량 이동 대신 **더미 주행 시뮬레이터**가 서울 → 강릉(가상 경로 192km)을 달린다.

## 상태

| 단계 | 내용 | 상태 |
|---|---|---|
| Phase 1 | 골격, 테마, 폰트, 메인 화면 | 완료 |
| Phase 2 | scenario.json, 시뮬레이터, 움직이는 지도, 회전 안내, ETA | 완료 |
| Phase 3 | 제안 상태머신, SuggestOverlay, 대본 재생, 우회 경로 교체, DPAD | 완료 |
| Phase 4 | DecideScreen, TimelineScreen, 개발자 패널 | 완료 |
| 추가 | 가족 폰 실시간 소식(같은 Wi-Fi), 가까워지면 한 번 더 묻기, 현대차 딥 네이비 색 | 완료 |
| Phase 5 | Pleos Vehicle SDK 연동 | 남음 (아래 "Pleos 연동 메모") |

## 구조

```
assets/scenario.json  (tools/gen_scenario.py로 생성: 경로·장소·우회 폴리라인·대본)
        │
SimulatedVehicleSource ──► TripEngine ◄── FamilyEventSource
 (TripSimulator, 10Hz)       │ 유일한 상태 주인   ├ ScriptedFamilySource (대본, 주행 거리 기준)
                             │                    ├ DevPanelFamilySource (개발자 패널)
                             │                    └ PhoneFamilySource (가족 폰 POST)
                             │ StateFlow → UiMapper → TripUiState
                             ▼
 Compose: MainScreen / SuggestOverlay / DecideScreen / TimelineScreen / DevPanel
 PhoneLinkServer: 상태 변화 → 가족 폰 브라우저(SSE)
```

- `TripEngine`이 경로도 소유한다. 경유지가 확정되면 `ActiveRoute.build`로 우회 폴리라인을 끼운 새 경로를 만들고 시뮬레이터가 따라간다(차 위치는 같은 지점으로 다시 맞춤).
- `MapRenderer` 인터페이스 뒤에 `CanvasMapRenderer`(오프라인 Canvas 지도). MapLibre 등으로 바꿀 때 이것만 새로 구현.
- DI 프레임워크 없이 `TripViewModel`에서 수동 주입.

## 시뮬레이터

- 경로: 주요 지점을 곡선으로 잇고 시드 고정 굴곡을 넣어 200m 간격으로 나눈 가상 좌표(실제 길찾기 아님). 분기점 6곳(팔당, 서종IC, 중앙고속도로, 만종JC, 대관령IC, 경포해변)은 실제로 꺾이게 배치
- 속도: 구간 제한속도(일반도로 60, 올림픽대로 80, 고속도로 100)까지 ±2.5m/s² 가감속, 큰 회전 앞 감속, 경유지 앞에서 멈춤 → 기어 P → 체류 → 재출발
- 배속 기본 ×20(약 2시간 10분 주행이 6분 30초 안팎), 개발자 패널에서 ×1/×5/×20/×60, 일시정지, 세우기, 다음 경유지 직전으로, 처음부터 다시
- 회전 안내: 경로에서 방향이 크게 바뀌는 곳을 뽑고 시나리오의 분기점 문구를 붙인다
- ETA: 남은 경로를 구간 제한속도의 90%로 달린다고 보고 + 남은 경유지 체류 시간(시뮬레이션 시계, 출발 10:30)
- 지도: 차량을 화면 아래 1/3에 고정, 진행 방향이 위. 지나온 길 회색, 남은 길 Active Blue. 배경 도로망·강은 시드 고정 절차 생성

## 대본(사람 손 없이 재생)

출발 전 합의: 엄마 두물머리 카페(확정). 이후 주행 거리에 맞춰 민준 화장실 급한 요청 → 홍천 휴게소 자동 확정, 서연 횡성 한우 식당 제안 + 엄마 찬성, 엄마 대관령 양떼목장(예산 초과), 민준 강릉 중앙시장(도착 후 일정). 응답이 없으면 10초 뒤 "다음 정차 때"로 넘어가고, 홍천 휴게소에 서면 "가족 제안 보기"로 비교 화면을 연다. 미룬 제안은 그 장소 2~6km 앞에서 한 번 더 묻는다.

## 가족 폰 (같은 Wi-Fi)

차량 앱이 8765 포트에 작은 웹 서버를 연다. 폰 브라우저에서 개발자 패널에 나온 주소(`http://<기기 IP>:8765/?k=<접속 코드>`)를 열면 도착 시각·들를 곳·운전자 결정이 실시간으로 뜬다. 외부 인터넷은 쓰지 않는다. `POST /api/event`로 `FamilyEvent` JSON(제안·투표·급한 요청)을 받을 자리도 열어 두었다(동승자 폰 앱은 아직 없음).

에뮬레이터는 NAT 뒤라 폰이 직접 못 들어온다. 노트북에서 관리자 PowerShell로:

```powershell
powershell -ExecutionPolicy Bypass -File tools\phone-link.ps1          # adb forward + 포트 프록시 + 방화벽(개인 네트워크)
powershell -ExecutionPolicy Bypass -File tools\phone-link.ps1 -Remove  # 해제
```

접속 코드 없이는 응답하지 않지만 인증·암호화는 없다. 데모용.

## 빌드 (Windows, Android Studio 없이)

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 :app:testDebugUnitTest :app:assembleDebug
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n ai.familyroute.demo/.MainActivity
```

경로를 바꾸려면 `tools/gen_scenario.py`를 고치고 `python3 tools/gen_scenario.py app/src/main/assets/scenario.json`.

## 화면 크기

| 환경 | 화면 | 앱이 받는 영역 |
|---|---|---|
| Pleos Connect v2.1.0 AVD | 2560×1440, 160dpi | **1682×1210dp** (mAppBounds `(853,90)-(2535,1300)`) |
| 범용 Automotive AVD `Auto_1920x720` (android-35) | 1920×720, 160dpi | 1920×624dp, 상태바를 빼면 콘텐츠 1920×548dp |

우측 패널 470dp, 하단 가족 바 100dp만 고정하고 나머지는 지도. 높이가 낮으면 간격을 줄이고 들를 곳은 "외 N곳"으로 접는다(주행 화면이라 스크롤 없음). `Auto_1920x720` AVD는 C 드라이브 여유 공간 때문에 `D:\avd\`에 있고, 디스플레이가 2개라 스크린샷은 `adb exec-out screencap -p -d 4619827259835644672`.

## 색과 폰트

- 딥 네이비: Hyundai Blue `#002C5F`를 어둡게 뽑은 바탕(`#061528`), Active Blue `#00AAD2` 강조, Light Sand `#F6F3F2` 글자, Sand 계열 경고. 강조색 위 글자는 대비 때문에 흰색 대신 Hyundai Blue. `#002C5F` 외 값은 공식 가이드로 확인하지 않은 알려진 값. 로고는 쓰지 않는다
- 가족 색(아빠·엄마·서연·민준)은 사양 그대로, 아바타에 항상 이름 첫 글자
- Noto Sans KR(OFL) 400/500/700/900 고정 파일(가변 폰트에서 fonttools로 추출, 한자 제외 서브셋)

## 시안과 다르게 한 부분

- 주행 중 버튼 높이 64 → 76dp(사양), Suggest 자동 넘김 6초 → 10초(사양)
- 도착 시각에 체류 시간을 넣는다(사양). 그래서 Suggest 타일은 "+12분 / 우회 시간", "새 도착 · 50분 머묾 포함"
- Decide: "출발하면 지도로 돌아가요", 가족 폰 문구는 연결된 폰이 있을 때 "결정 결과는 가족 폰 N대에 바로 알려져요"
- 음성 버튼은 "음성 명령(데모)" 표시만(실제 인식 없음), 음성 힌트 "추가해"는 뺐다
- 시안에 없는 것: 들를 곳 "도착 후"·"귀갓길" 칩, 급한 요청 알림 배너, 직진 안내 아이콘, 가까워지면 한 번 더 묻기
- 색: 현대차 딥 네이비로 바꿈(배치·크기는 시안 그대로)

## Pleos 연동 메모 (Phase 5)

- 사양의 `ai.pleos.playground:Vehicle:2.3.1`은 Pleos 저장소에 없다. 있는 버전은 **2.0.3, 2.3.2**
- 2.3.2 AAR에서 확인한 것: `Vehicle(context).initialize()`, `getGear().registerGearSelection(GearSelectionListener)`, `getCurrentGearSelectionState(...)`, `VehicleGear.GEAR_PARK/GEAR_DRIVE/...`
- **차량 속도·위치 API는 Vehicle SDK에 없다**(크루즈 목표 속도, 누적 거리만). 속도는 AAOS 표준 `CarPropertyManager`로 읽는 방안(이전 프로젝트에서 에뮬레이터 동작 확인)
- 계획: 위치는 시뮬레이터, 기어(필요하면 속도)는 실제 값을 쓰는 `PleosVehicleSource`. Virtual Console에서 P로 바꾸면 비교 화면 진입
