# 아빠어디가 (FamilyRoute)

**가족 각자의 가고 싶은 곳과 방문 이유·한마디를 모아 차량에서 원터치로 출발하고, 경유지 도착 시 한국어 TTS 음성 브리핑과 함께 가족의 이야기를 나누는 PLEOS Connect 차량용 참여형 가족 내비게이션 앱.**

현대자동차그룹(HMG) 차량용 앱 개발 공모전 구현 제출용 프로젝트입니다. 최신 실행 앱은 `ai.familyroute.demo` / `MainActivity`이며, Android Studio의 **Pleos Connect Emulator v2.1.0 (Android Automotive OS, 2560×1440)** 환경에서 실행합니다. 가족 구성원은 차량 화면의 QR 코드를 스캔해 휴대폰 웹에서 각자 계정으로 실시간 참여합니다.

---

## 1. 공모전 제출 규격 필수 5대 항목 요약 (`/guide/submission`)

| 필수 항목 | 내용 요약 |
|---|---|
| **① 앱 소개 및 핵심 기능** | 차량 OS에서 가족방·여행방 직접 생성 및 QR 초대, 휴대폰 사전 코스 준비(`readyForCar`) 연동 및 탑승 즉시 원터치 출발(`quick-start`), 경유지 도착 시 가족 이야기 카드 & 한국어 TTS(`TextToSpeech`) 음성 브리핑, 주행 중 Compact Driving Mode 안전 잠금, 추천 코스 탐색·경유지 편집, 가족 추억 앨범 & 커뮤니티 코스 공유 |
| **② 제출 APK 파일** | [`apk/familyroute_아빠어디가_release.apk`](apk/familyroute_아빠어디가_release.apk) (Release 빌드 및 서명 완료, SHA-256 검증 파일 [`apk/SHA256SUMS`](apk/SHA256SUMS) 포함) |
| **③ 빌드 및 실행 방법** | 시연 서버(`node tools/preview-phone.mjs 8789`) 실행 후 APK 설치(`adb install -r "apk/familyroute_아빠어디가_release.apk"`) 또는 Gradle 빌드(`.\gradlew.bat :app:assembleRelease`) |
| **④ 테스트 환경 및 규격** | **Pleos Connect Emulator v2.1.0** (`2560×1440` 해상도), Android Automotive OS (minSdk 28 / targetSdk 34), Kotlin + Jetpack Compose, Node.js 24+ |
| **⑤ 필요 설정 및 검증** | 별도 유료 API 키 없이 즉시 동작하는 로컬 모의 검색·경로 및 PC 재부팅 시에도 유지되는 AES-256-GCM 암호화 아카이브 영속성 탑재, **서버 자동 테스트 29개 및 Android 단위 테스트 전수 통과** |

---

## 2. 제출 APK

- **다운로드 링크**: [release APK 다운로드](apk/familyroute_아빠어디가_release.apk)
- **파일 경로**: `apk/familyroute_아빠어디가_release.apk`
- **무결성 해시**: [`apk/SHA256SUMS`](apk/SHA256SUMS)
- `familyroute`는 제출 파일의 식별 접두사입니다.
- `assembleRelease` 변형으로 빌드한 뒤 Pleos Connect Emulator에서 즉시 설치·업데이트할 수 있도록 개발 인증서로 서명(`apksigner`)한 실행용 APK입니다.

---

## 3. 주요 기능과 사용자 흐름 (2판: 차량·모바일 양방향 가족 여행 플랫폼)

| 화면 / 영역 | 핵심 사용자 흐름 |
|---|---|
| **차량 OS 온보딩 & QR 초대 (차량)** | 차량에서 로그인 없이 바로 **새 가족방 생성(`POST /api/vehicle/family`)** 및 **새 가족 여행방 생성(`POST /api/vehicle/trips`)** 가능. 차량 화면의 초대 QR 코드를 가족들이 휴대폰 카메라로 스캔하면 즉시 가족방·여행방에 합류 (휴대폰에서 만든 6자리 연결 코드로도 연결 가능) |
| **출발 전 코스 함께 짜기 (휴대폰)** | 가족 각자 장소 검색 → 가고 싶은 이유와 도착 시 보여 줄 한마디를 담아 제안 → 가족 공감(`❤️`) 및 한마디 댓글 → **차량 출발 준비 완료(`readyForCar`)** 버튼으로 공감 순 최적 코스 자동 확정 |
| **탑승 즉시 원터치 출발 (차량)** | 가족이 휴대폰에서 준비 완료한 코스를 차량이 실시간 감지해 자동 표시 → **이 코스로 출발 / 가족 제안 담고 출발(`quick-start`)** 원터치 실행 |
| **경유지 도착 가족 이야기 & 한국어 TTS 브리핑 (차량)** | 경유지 도착 및 정차(P) 시 해당 장소를 제안한 가족의 방문 이유와 메시지가 카드 모달로 열리며 **한국어 TTS(`TextToSpeech`) 음성 브리핑**으로 낭독 |
| **주행 안전 모드 (차량 `ParkingGuard`)** | `CarPropertyManager`의 `PERF_VEHICLE_SPEED`·`GEAR_SELECTION` 연동. 주행(`!parked`) 전환 시 모든 팝업·서브 화면을 즉시 닫고 좌측 카드를 **다음 경유지 1곳 요약 + 주행 안전 안내(Compact Driving Mode)** 로 축소. 주행 중 새 제안 도착 시 화면 조작 없이 **한국어 TTS 음성 안내**만 제공 후 자동 닫힘 |
| **추천 여행 코스 & 경유지 편집 (차량·휴대폰)** | 참고 코스 및 다른 가족이 공개한 커뮤니티 인기·최신 코스를 탐색하고, 원하는 경유지를 빼거나 추가·순서 변경해 단독 길안내 또는 가족 여행방 코스로 즉시 적용 |
| **가족 추억 앨범 & 커뮤니티 공유 (휴대폰)** | 마친 여행이 추억 앨범에 영구 보관되어 방문지·가족 메시지·사진을 다시 감상하고, 우리 가족의 다녀온 여행을 커뮤니티 추천 코스로 공개·좋아요·후기 작성 가능 |

---

## 4. 실행 및 빌드 방법

### 1) 시연 서버 실행 (외부 과금 없음 · 영구 보관 지원)

Node.js 24 이상을 설치하고 저장소 루트에서 실행합니다.

```powershell
npm --prefix server ci
node tools/preview-phone.mjs 8789
```

- PC 브라우저에서 `http://127.0.0.1:8789`를 열거나 차량 화면의 QR 코드로 휴대폰에서 접속합니다.
- 장소 검색과 경로 계산은 내장 모의 자료를 기본 사용하므로 카카오·AWS API 키 없이도 즉시 동작합니다.
- 계정·가족방·여행방·장소 제안·추억 앨범·사진 데이터는 `.runtime/v2-8789/` 아래 **AES-256-GCM 암호화 파일(`state.v2.enc`) 및 자동 백업 아카이브(`state.v2.backup.enc`)** 에 실시간 영구 저장되어 PC를 재부팅해도 그대로 유지됩니다.

### 2) 차량 앱 설치 및 실행

```powershell
adb install -r "apk/familyroute_아빠어디가_release.apk"
adb shell am start -n ai.familyroute.demo/.MainActivity
```

차량 앱은 에뮬레이터 호스트(`10.0.2.2:8789`)의 시연 서버에 자동 연결합니다. 휴대폰 외부 접속 설정은 [휴대폰 접속 안내](docs/PHONE_ACCESS.md)를 참고해 주세요.

### 3) 소스 코드 직접 빌드

```powershell
$env:JAVA_HOME = "C:\dev\tools\jdk-21.0.13+9"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon
```

---

## 5. 심사 시연 순서

1. **차량에서 가족방·QR 생성**: 차량 앱 `가족` 메뉴에서 `가족방 만들기`를 누르면 초대 QR 코드가 즉시 생성됩니다. (또는 휴대폰에서 만든 가족방의 6자리 연결 코드를 입력해도 됩니다.)
2. **휴대폰 QR 합류 및 장소 제안**: 가족들이 휴대폰으로 QR을 스캔해 가입·합류한 뒤, 가고 싶은 장소와 방문 이유·도착 한마디를 제안하고 공감(`❤️`)과 댓글을 남깁니다.
3. **사전 코스 준비 또는 차량 원터치 출발**: 휴대폰에서 `차량 출발 준비 완료`를 누르거나, 차량 화면에서 `가족 제안 담고 출발` 버튼을 눌러 원터치로 길안내를 시작합니다.
4. **주행 안전 모드 확인**: 주행 상태(`!parked`)로 전환하면 열려 있던 서브 창이 모두 닫히고 좌측 카드가 **다음 안내 1곳 요약(Compact Driving Mode)** 으로 전환되며, 주행 중 새 제안은 한국어 TTS 음성으로만 안전하게 안내됩니다.
5. **경유지 도착 및 가족 이야기·TTS 브리핑 확인**: 정차 후 경유지 `도착`을 누르면 제안한 가족의 방문 이유와 한마디 모달이 열리고 한국어 TTS로 자동 낭독됩니다.
6. **여행 마치기 & 추억 앨범·코스 공개**: 차량에서 `여행 마치기`를 누르면 휴대폰 `추억 앨범`에 여행 기록과 사진·메시지가 보관되며, 다른 가족을 위한 추천 코스로 공개할 수 있습니다.

---

## 6. 테스트 환경 및 필요한 설정

| 항목 | 설정 및 규격 |
|---|---|
| **에뮬레이터 버전** | **Pleos Connect Emulator v2.1.0** (Android Studio Pleos Connect AVD, Android Automotive OS) |
| **화면 규격 (해상도)** | **2560×1440** (가로형 차량 와이드 디스플레이, Compose 밀도 스케일링 적용) |
| **Android SDK 규격** | `minSdk 28` / `compileSdk 34` / `targetSdk 34` |
| **언어 · UI · 음성** | Kotlin, Jetpack Compose, Android 표준 `TextToSpeech` (`Locale.KOREAN`) |
| **빌드 도구** | Gradle Wrapper 8.10.2, JDK 21 (Java target 17) |
| **백엔드 서버** | Node.js 24 이상, 자체 HTTP REST API, 모바일 반응형 웹 UI (`server/v2/app.html`) |
| **차량 센서 연동** | `CarPropertyManager` (`PERF_VEHICLE_SPEED`, `GEAR_SELECTION`), `LocationManager` 차량 위치 센서 연동 (권한 미보유 시 서울 시연 좌표 안전 Fallback) |

> 자세한 계정·가족·차량 권한 규칙은 [계정·가족·차량 권한 문서](docs/ACCOUNT_CONNECTION.md)에 정리되어 있습니다.

---

## 7. 카카오 API 연동 범위

| 서비스 | 코드에 구현된 역할 | 시연 서버 기본 동작 |
|---|---|---|
| 카카오 Local REST API | 키워드 장소 검색 (`server/place-search.mjs`) | `KAKAO_REST_API_KEY` 미설정 시 내장 모의 장소 검색 사용 |
| 카카오모빌리티 Directions API | 목적지·최대 5개 경유지 도로 경로 (`server/directions.mjs`) | 키 미설정 시 내장 모의 도로 경로 사용 |

키·비밀번호·인증서 개인키는 저장소와 APK에 포함하지 않으며, 시연 서버는 외부 유료 API 호출 없이 독립 실행됩니다.

---

## 8. 자동 테스트 및 검증

```powershell
npm --prefix server test
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

- **서버 자동 테스트 29개 전수 통과**:
  - `server/v2/p0.test.mjs`: P0 핵심 권한·보안·격리 점검 8개 항목 통과
  - `server/v2/flow.test.mjs`: 3인 가족 여행 생성 → 제안·공감·댓글 → 출발·경유지 도착·사진·앨범·커뮤니티 코스 공개 및 차량 온보딩(`createFamilyFromVehicle`, `createTripFromVehicle`), 사전 코스 준비(`readyForCar`), 원터치 출발(`quick-start`), 영속화 복구까지 검증 완료
  - `server/place-search.test.mjs`, `server/directions.test.mjs`: 장소 검색·경로 모듈 테스트 통과
- **Android 단위 테스트 통과**: `ParkingGuardTest` 주행/정차 안전 잠금 판정 검증 통과

---

## 9. 소스 구조

- `app/`: Android 차량 앱 (`MainActivity.kt` 차량 메인·한국어 TTS·주행 안전 UI, `car/CarUi.kt` 공통 UI 컴포넌트, `phone/CarApi.kt` 서버 API 클라이언트, `phone/VehicleLink.kt` Android Keystore 암호화 보관, `phone/ParkingGuard.kt` 주행 중 조작 잠금)
- `server/v2/`: 2판 서버 (`service.mjs` 계정·가족·여행·제안·추천 코스·추억 앨범 비즈니스 로직, `http.mjs` REST API 라우터, `store.mjs` AES-256-GCM 암호화 영속 저장소, `app.html` 휴대폰 웹 앱)
- `tools/preview-phone.mjs`: 시연 서버 실행기 (자동 백업·복구 및 시드 데이터 포함)
- `apk/`: 공모전 제출용 서명 완료 Release APK (`familyroute_아빠어디가_release.apk`) 및 `SHA256SUMS`
- `docs/`: [계정·가족·차량 권한](docs/ACCOUNT_CONNECTION.md), [휴대폰 접속 설정](docs/PHONE_ACCESS.md), [라이선스 고지](docs/licenses/THIRD_PARTY_NOTICES.md)
