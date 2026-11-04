# family-route-demo

Pleos Connect(Android Automotive) 헤드유닛용 가족 경로 데모 앱. 가족이 들를 곳을 제안하면 운전자가 주행 화면에서 정한다. 실제 차량 이동 대신 더미 주행 시뮬레이터를 쓴다(Phase 2부터).

## 현재 상태: Phase 1 + 화면 동작 (차량 이동은 Phase 2)

- Kotlin + Jetpack Compose, 단일 Activity, 상태 기반 화면 전환(내비게이션 라이브러리 없음)
- `TripEngine`이 유일한 상태 주인. 화면은 `StateFlow`를 읽고 `UiAction`만 보낸다. DI 없이 `TripViewModel`에서 수동 주입
- 화면 4개 + 개발자 패널이 모두 동작:
  - **MainScreen**: 지도, 회전 안내, 우측 패널(도착·예산·들를 곳), 가족 바. "들를 곳 N"을 누르면 타임라인
  - **SuggestOverlay**: 주행 중 가족 제안이 운전자 확인 대기가 되면 자동으로 뜬다(한 번에 하나). 10초 자동 "다음 정차 때", 남은 시간 막대, 지도 점선 미리보기
  - **DecideScreen**: 기어 P로 3초 이상 정지한 뒤 "가족 제안 보기". 카드에서 고르고 "이대로 출발"(또는 기어 D로 출발하면 고른 대로 반영하고 닫힘)
  - **TimelineScreen**: 출발~도착 타임라인, "지금" 마커, 사람별 사용 시간(예산 막대와 같은 값), 남은 제안권
  - **개발자 패널**: 하단 "아빠" 아바타 길게 누르기. 기어 P/D, 다음 경유지로 점프, 제안·투표·급한 요청 주입, 리셋, 상태 표시
- 규칙: 예산 30분(우회 시간만 차감), 제안권 1인 2회, 급한 요청은 제안권 없이 가장 가까운 앞쪽 휴게소 자동 확정 + 알림, 예산 초과 → 귀갓길 추천, 목적지 5km 안 → 도착 후 일정 추천
- 입력: 터치 + DPAD. 첫 DPAD 키는 Android가 키 조작 모드 전환에 쓰고, 그때 포커스 테두리가 나타난다. 다음 키부터 좌우 이동, 가운데·Enter로 선택. 뒤로 가기 키는 메인으로
- `FamilyEvent`(제안·투표·급한 요청)는 kotlinx.serialization data class. 동승자 폰을 붙일 때 같은 JSON을 받으면 된다
- 지도는 `MapRenderer` 인터페이스 뒤의 임시 구현(`MockupMapRenderer`, 시안 그림). 차량 위치·회전 안내·도로명은 고정값이고 Phase 2 시뮬레이터에서 실제로 움직인다

### 임시로 둔 것 (Phase 2~3에서 교체)

| 지금 | 바뀔 것 |
|---|---|
| `ManualVehicleSource`(개발자 패널로 기어·위치 조작) | `SimulatedVehicleSource` |
| `DemoCatalog`(장소·거리 하드코딩) | `assets/scenario.json` |
| `DemoStart`(시안 Main 순간을 재현한 시작 상태) | `ScriptedFamilySource`가 주행 거리에 맞춰 이벤트 발생 |
| 도착 시각 = 평균 속도 계산 | 시뮬레이터 남은 거리·구간 속도 기반 |

## 빌드 (Windows, Android Studio 없이)

JDK 21(`C:\dev\tools\jdk-21*`)과 Android SDK(`%LOCALAPPDATA%\Android\Sdk`)를 쓴다.

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 :app:testDebugUnitTest :app:assembleDebug
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n ai.familyroute.demo/.MainActivity
```

## 화면 크기

| 환경 | 화면 | 앱이 받는 영역 |
|---|---|---|
| Pleos Connect v2.1.0 AVD | 2560×1440, 160dpi | **1682×1210dp** (왼쪽 주행 정보·위쪽 상태바·아래쪽 공조 바 제외. `dumpsys`의 mAppBounds `(853,90)-(2535,1300)`) |
| 범용 Automotive AVD `Auto_1920x720` (android-35 automotive) | 1920×720, 160dpi | **1920×624dp** (아래 내비 바 96dp 제외). 위쪽 상태바 76dp가 겹쳐서 콘텐츠는 1920×548dp |
| 시안 | 1600×600 | — |

레이아웃은 우측 패널 폭 470dp, 하단 가족 바 높이 100dp만 고정하고 나머지는 지도가 차지한다.
우측 패널은 높이가 500dp(시안)보다 낮으면 간격을 줄이고, 그래도 안 들어가는 "들를 곳" 줄은 "외 N곳"으로 접는다(주행 화면이라 스크롤 없음).

비교 스크린샷: [docs/phase1/compare_pleos.png](docs/phase1/compare_pleos.png), [docs/phase1/compare_auto_1920x720.png](docs/phase1/compare_auto_1920x720.png)

`Auto_1920x720` AVD는 데이터 파티션이 7.2GB로 고정이라 C 드라이브 여유 공간이 모자라 `D:\avd\`에 두었다. 이 이미지는 디스플레이가 2개라 스크린샷은 `adb exec-out screencap -p -d 4619827259835644672`로 찍는다.

## 폰트

Noto Sans KR 가변 폰트(google/fonts)에서 fonttools로 굵기 400/500/700/900 고정 파일을 뽑고, 한자를 뺀 서브셋(한글·라틴·문장부호·화살표·원문자)을 `res/font`에 넣었다(각 약 2.9MB). 가변 폰트 한 파일에 굵기 설정을 거는 방식은 Pleos AVD에서 900이 700처럼 얇게 나와 쓰지 않는다.

## 시안과 다르게 한 부분

- 하단 "가족 제안 보기"·음성 버튼 높이 64 → **76dp** (사양: 주행 중 누르는 버튼 76dp 이상)
- 음성 버튼을 누르면 "음성 명령(데모)" 표시만 잠깐 나온다(실제 음성 인식 없음)
- Suggest 자동 넘김 6초 → **10초**(사양), 남은 초를 실제로 센다. 음성 힌트 “추가해”는 음성 인식이 없어 뺐다
- Suggest 타일: 사양대로 도착 시각에 체류 시간을 넣어서 "+12분 / 도착이 늦어져요" 대신 "+12분 / 우회 시간", "15:54 / 새 도착 · 50분 머묾 포함"으로 표시(시안 숫자 15:04는 체류를 뺀 값이라 사양과 맞지 않음)
- Decide: "출발하면 이 화면은 요약으로 접혀요" → "출발하면 지도로 돌아가요", "결정 결과는 가족 폰에 바로 알려져요"는 폰 연결이 없어 뺐다. "✓" 문자 → 벡터 아이콘
- 시안에 없는 것: 들를 곳의 "도착 후"·"귀갓길" 칩, 급한 요청 알림 배너(사양 "화면에 알림만"), "가족 제안 보기"는 대기 제안이 없으면 테두리 버튼
