# 아빠어디가

**가족 각자의 가고 싶은 곳을 모아 차량에서 함께 여행 경로를 정하고, 가족 추억 앨범으로 여행을 다시 보는 PLEOS 차량용 앱.**

현대자동차그룹 차량용 앱 개발 공모전 구현 제출용 프로젝트입니다. 이전 이름은 가족경로이며, 최신 실행 앱은 `ai.familyroute.demo` / `MainActivity`입니다. Android Studio의 **Pleos Connect VM(Android Automotive)** 에서 실행합니다. 가족 구성원은 휴대폰 웹에서 각자 계정으로 참여합니다.

## 제출 APK

- [release APK 다운로드](apk/familyroute_아빠어디가_release.apk)
- 파일 경로: `apk/familyroute_아빠어디가_release.apk`
- `familyroute`는 기존 제출 파일의 식별 접두사입니다. 정식 팀명/개인 성명은 접수양식 작성 시 확정해야 합니다.
- release 변형으로 빌드한 실행 APK입니다. 현재 VM의 앱과 데이터를 유지하며 업데이트할 수 있도록 기존 개발 인증서로 서명합니다. 스토어 배포용 서명키가 아닙니다.
- 기존 `가족경로` APK가 아닌 위 최신 APK를 사용해 주세요. 직접 `assembleRelease`로 빌드하면 unsigned APK가 생성되므로 본인 인증서로 별도 서명해야 설치할 수 있습니다.

## 구현 기능과 사용 순서 (2판: 계정 → 가족 → 여행)

| 화면 | 사용자 흐름 |
|---|---|
| 로그인·회원가입 (휴대폰) | 이메일·이름·비밀번호로 가입 → 로그인. 계정마다 자료가 완전히 분리됩니다 |
| 우리 가족 (휴대폰) | 가족 만들기 또는 초대 코드로 참여 → 초대 링크 보내기, 관리자 넘기기, 내보내기, 나가기, 차량 연결 코드 받기 |
| 가족 여행방 (휴대폰) | 새 여행 만들기(제목·날짜·운전자·목적지) → 여행별로 제안·경로·사진이 묶임 |
| 장소 제안 (휴대폰) | 장소 검색 → 가고 싶은 이유와 가족에게 한마디 → 다른 가족이 좋아요/글쎄요 → 관리자·운전자·차량이 확정 |
| 내비게이션 (차량) | 가족 연결 없이도 `목적지`에서 찾아 바로 길안내. 가족과 연결하면: 가족 여행 고르기 → 제안 확정·순서 변경·목적지 지정 → 출발 → 들르는 곳 도착 시 제안한 가족의 이야기 → 여행 마치기 |
| 가족 추억 앨범 (휴대폰) | 마친 여행이 앨범으로 이동 → 들른 곳·이야기·사진 다시 보기, 사진 삭제 |

차량은 주차(P) 상태에서만 조작할 수 있습니다. 주행 중에는 지도와 한 줄 안내만 보이고, 새 제안은 "정차 후 확인"으로만 알립니다.

## 실행 방법

### 1. 비용 없는 시연 서버 실행

Node.js 24 이상을 설치하고 저장소 루트에서 실행합니다.

```powershell
npm --prefix server ci
node tools/preview-phone.mjs 8789
```

PC 브라우저에서 `http://127.0.0.1:8789`를 엽니다. 장소 검색과 경로는 모의 자료(부산 예시 장소, 직선 경로)이고 카카오·AWS로 요청을 보내지 않습니다. 계정·가족·여행·사진은 이 PC의 암호화 파일에 저장됩니다.

### 2. 차량 앱 실행

```powershell
adb install -r "apk/familyroute_아빠어디가_release.apk"
adb shell am start -n ai.familyroute.demo/.MainActivity
```

차량 앱은 에뮬레이터 호스트(`10.0.2.2:8789`)의 시연 서버에 연결합니다. 휴대폰 참여에는 같은 서버를 가리키는 HTTPS 주소가 필요합니다. 시연 PC의 실행 스크립트가 임시 터널을 열고, 차량 `가족` 메뉴가 그 주소의 QR을 보여 줍니다. [접속 설정](docs/PHONE_ACCESS.md)

```powershell
# JDK 17/21 및 Android SDK 경로 설정 후 저장소 루트에서 실행
.\gradlew.bat :app:assembleDebug -PfamilyServerUrl=http://10.0.2.2:8789
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 3. 심사 시연 순서

1. 휴대폰(또는 PC 브라우저)에서 가입하고 `가족 만들기`를 합니다. `가족 초대하기`로 다른 가족에게 링크를 보내고, 각자 자기 계정으로 가입해 참여합니다.
2. `새 여행 만들기`로 여행을 만들고 목적지(예: 부산역)를 정합니다.
3. 가족이 각자 `가고 싶은 곳 제안하기`로 장소와 이유를 남기고 서로 좋아요를 누릅니다.
4. PLEOS VM은 연결 없이도 `목적지`에서 바로 길안내를 쓸 수 있습니다. 가족 여행을 보려면 휴대폰 `우리 가족 → 차량 연결 코드 받기`의 숫자 6자리를 차량 `가족` 메뉴에 입력합니다.
5. 차량 `여행` 메뉴에서 제안을 경로에 담고 순서를 바꾼 뒤 `출발하기`를 누릅니다.
6. 들르는 곳에서 `도착`을 누르면 그곳을 제안한 가족의 이야기가 나옵니다. 휴대폰에서 그 장소에 사진을 올립니다.
7. 차량에서 `여행 마치기` → 휴대폰 `추억 앨범`에서 여행 기록을 다시 봅니다.

## 계정과 가족

- 앱 자체 계정(이메일·비밀번호)을 씁니다. Cognito 로그인은 2판에서 쓰지 않습니다.
- 한 사람은 한 번에 한 가족에만 속합니다. 다른 가족에 들어가려면 먼저 나가야 합니다.
- 관리자는 나가기 전에 관리자를 넘겨야 하고, 마지막 구성원이 나가면 가족과 모든 여행·사진이 지워집니다. 내보낸 구성원은 즉시 접근이 막힙니다.
- 휴대폰에는 가족 자료를 저장하지 않습니다. 같은 브라우저에서 A가 로그아웃하고 B가 로그인해도 A의 자료가 남지 않습니다.
- 자세한 권한 규칙은 [계정·가족·차량 권한](docs/ACCOUNT_CONNECTION.md)에 있습니다.

## 테스트 환경 및 필요한 설정

| 항목 | 설정 |
|---|---|
| 실행 환경 | Windows, Android Studio Pleos Connect AVD, Android Automotive |
| 확인한 VM 해상도 | 2560×1440 |
| Android | minSdk 28 / compileSdk·targetSdk 34 |
| 언어·UI | Kotlin, Jetpack Compose |
| 빌드 | Gradle wrapper 8.10.2, JDK 21에서 확인(Java target 17) |
| 서버 | Node.js 24 이상, HTTP API, 휴대폰 웹 UI |
| 차량 상태 | Android Automotive CarPropertyManager의 기어 상태 |
| 위치·주행 | 서울 시연 출발 위치. 들르는 곳 도착은 차량에서 직접 누름(GPS 자동 판단 아님) |

PLEOS 기본 내비게이션에 경유지를 보내는 앱이 아니라 자체 지도/경로 UI를 사용합니다. Vehicle/FusedLocation/NaviHelper SDK 전체 연동을 완료했다고 주장하지 않습니다.

## 카카오 연동 범위

| 서비스 | 코드에 구현된 역할 | 현재 시연과의 차이 |
|---|---|---|
| 카카오 Local REST API | 키워드 장소 검색 (`server/place-search.mjs`) | 서버의 `KAKAO_REST_API_KEY` 필요. 시연 서버는 모의 검색 |
| 카카오모빌리티 Directions API | 목적지·최대 5개 경유지 도로 경로 (`server/directions.mjs`) | 같은 키 필요. 시연 서버는 직선 모의 경로 |

키·비밀번호·인증서 개인키는 저장소/APK에 포함하지 않습니다. 시연 서버는 외부 유료 서비스를 호출하지 않습니다.

## 검증

```powershell
npm --prefix server test
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

서버 자동 테스트 21개 통과(2판 기준, 가족 연결 없는 길안내 2개 포함). 그중 P0 점검 8개 항목은 `server/v2/p0.test.mjs`에 같은 번호로 들어 있습니다. 3명 가족이 여행을 만들고 제안·확정·출발·도착·사진·마치기·앨범까지 가는 흐름은 `server/v2/flow.test.mjs`에서 확인합니다. PLEOS VM에서는 시험 계정으로 차량 연결 → 실시간 제안 알림 → 경로 담기·순서 변경 → 출발 → 주행 중 잠금 → 도착 이야기 → 마치기 → 휴대폰에서 차량 연결 끊기를 확인했습니다. 실제 차량 주행·운전자 주의 분산 인증 시험은 하지 않았습니다.

## 소스 구조

- `app/`: Android 차량 앱. `MainActivity`(차량 화면), `phone/CarApi.kt`(서버 연결), `phone/VehicleLink.kt`(차량 연결 정보 암호화 보관), `phone/ParkingGuard.kt`(주행 중 잠금)
- `server/v2/`: 2판 서버. `service.mjs`(계정·가족·여행 권한), `http.mjs`(API), `app.html`(휴대폰 웹)
- `tools/preview-phone.mjs`: 모의 장소·경로를 쓰는 시연 서버
- `server/legacy/`, `tools/legacy/`: 1판(가족방·Cognito) 코드. 참고용이며 실행하지 않음
- `apk/`: 공모전 제출 release APK
- `docs/licenses/`: 포함 폰트 등 라이선스 자료

저장소에는 초기 시뮬레이터 코드도 남아 있습니다. 최신 진입점은 `MainActivity`이며 이전 phase 문서보다 이 README를 우선합니다. 앱 아이콘은 제공된 가족 자동차 이미지에서 제작한 시연 자산이며 현대자동차 공식 앱을 의미하지 않습니다. 커뮤니티(공개 코스·좋아요·후기)는 1판에만 있고 2판에는 아직 옮기지 않았습니다.

## 제출 안내

[공식 GitHub 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission#github)와 [개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)에 맞춰 앱 소개·실행 방법·환경·APK 경로를 정리했습니다. 비공개 저장소이므로 제출자는 공지된 심사용 계정의 접근 권한을 별도로 확인해야 합니다. GitHub 업데이트가 참가 신청 이메일 제출을 대신하지 않습니다.
