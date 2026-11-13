# 여기들렀다가 (FamilyRoute)

**가족 각자의 가고 싶은 곳과 이야기를 모아 차량에서 함께 코스를 정하고, 여행이 끝나면 가족만의 사진과 추억으로 이어가는 차량용 여행 앱입니다.**

현대자동차그룹 차량용 앱 개발 공모전 제출 프로젝트입니다. **팀명: 아빠어디가 / 앱 이름: 여기들렀다가**. 차량은 Pleos Connect의 Android 앱, 가족의 휴대폰은 별도 설치 없이 접속하는 웹 앱을 사용합니다.

## 제출 정보

| 공식 README 요구 항목 | 내용 |
| --- | --- |
| 앱 이름 및 한 줄 소개 | **여기들렀다가** — 가족의 장소 제안·이야기·여행 사진을 차량과 휴대폰으로 연결하는 가족 여행 서비스 |
| 실행 방법 | 아래 실행 방법의 서버 실행 → APK 설치 → 앱 실행 |
| 테스트 환경 및 필요한 설정 | Node.js 24 이상, JDK 21, Android SDK 34, Android Studio. 카카오 키 없이 시연 가능하며 실제 장소 검색·도로 경로는 서버에 키 설정 필요 |
| Pleos Connect 에뮬레이터 및 화면 규격 | **Pleos Connect Emulator v2.1.0**, Android Automotive OS, **2560×1440 가로 화면** |
| APK 파일 위치 | [`apk/아빠어디가_여기들렀다가_release.apk`](apk/아빠어디가_여기들렀다가_release.apk) |

- 저장소: [tjdtn5324-bit/family-route](https://github.com/tjdtn5324-bit/family-route)
- [제출용 release APK 다운로드](https://github.com/tjdtn5324-bit/family-route/raw/refs/heads/main/apk/아빠어디가_여기들렀다가_release.apk)
- 파일명은 공식 형식 **`팀명_앱이름_release.apk`**를 따릅니다. 무결성 확인: [`apk/SHA256SUMS`](apk/SHA256SUMS).
- `assembleRelease`로 빌드한 뒤 에뮬레이터 업데이트 설치를 위해 개발 인증서로 서명한 APK입니다. 앱마켓 배포용 개인 서명 키는 포함하지 않습니다.
- 접수 메일에는 저장소 주소와 **최종 커밋 전체 번호**를 함께 기재해야 합니다. `git rev-parse HEAD`로 확인할 수 있습니다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission#github)

## 주요 기능과 사용자 흐름

| 기능 | 사용자가 거치는 과정 |
| --- | --- |
| 여러 가족방 | 휴대폰 가입·로그인 → 가족방 생성 또는 초대 참여 → 소속 가족방 목록에서 선택. 차량도 연결한 여러 가족방 사이를 전환 |
| 가족 초대 | 휴대폰 또는 차량에서 가족방 생성 → 초대 링크·QR·코드로 공유. 휴대폰에서 만든 가족방은 차량 연결 코드로 연결 |
| 목적지·경유지 제안 | 여행 선택 → 장소 검색 → 목적지 또는 경유지 제안 → 가족 공감·댓글 → 운전자 또는 연결된 차량의 확인 후 반영 |
| 출발 전 준비 | 휴대폰에서 여행과 코스 준비 → 차량에서 확인. 준비 완료나 공감만으로 대기 중 제안을 자동 승인하지 않으며, 차량의 ‘가족 제안 담고 출발’은 명시적인 일괄 승인 동작 |
| 코스 편집 | 차량·휴대폰에서 목적지 변경, 경유지 추가·제거·순서 변경 → 경로 재계산. 경로 계산 실패 시 기존 경로 보존 |
| 추천 여행 | 지역 검색 및 인기·좋아요·최신·참고 코스 탐색 → 상세 장소 확인 → 원하는 경유지를 골라 혼자 운전 또는 가족 여행에 적용 |
| 가족 이야기 | 경유지 도착 후 정차 상태에서 도착 확인 → 장소를 제안한 가족의 이유·메시지 카드와 한국어 음성 안내 |
| 가족 추억 | 여행 종료 → 휴대폰에서 사진 업로드·앨범 감상. 차량에서 가족방을 고른 뒤 사진 열람·권한에 따른 삭제 |
| 커뮤니티 | 다녀온 여행의 장소와 소개를 코스로 공개 → 다른 사용자가 좋아요·후기·코스 가져오기. 가족 사진·이름·메시지는 공개 코스에서 제외 |
| 여행 정리 | 권한이 있는 사용자가 여행 목록 삭제. 출발 취소와 코스 비우기도 지원 |
| 차량 설정 | 위치 사용 허용 → 차량 위치 수신 확인 / 안내 음성 들어보기 / 출발 전 문 상태 확인 |

## 실행 방법

### 1. 시연 서버 실행

Node.js **24 이상**을 설치하고 저장소 루트에서 실행합니다.

```powershell
npm --prefix server ci
node tools/preview-phone.mjs 8789
```

PC 브라우저에서 `http://127.0.0.1:8789`를 엽니다. 심사자가 직접 가입할 수 있으며, 별도의 공용 관리자 계정은 제공하지 않습니다.

- 기본 서버는 PC 내부에서만 접속할 수 있습니다. 차량 에뮬레이터는 `http://10.0.2.2:8789`로 접속합니다.
- 다른 휴대폰으로 접속하려면 같은 Wi-Fi 공개 설정 또는 HTTPS 터널이 필요합니다. [휴대폰 접속 안내](docs/PHONE_ACCESS.md)를 확인해 주세요. 로컬 전용 주소를 QR로 만들어도 다른 휴대폰에서 접속할 수 없습니다.
- Windows `demo.cmd`, macOS/Linux `sh demo.sh`도 실행할 수 있습니다. 이 실행기는 APK 설치를 시도하고, `cloudflared`가 있으면 임시 인터넷 주소를 만들며 없으면 같은 Wi-Fi에 서버를 공개합니다. 이 공개 범위를 이해한 뒤 사용하세요.
- 계정·가족방·여행·사진은 `.runtime/v2-8789/family-archive.enc`에 저장하고 `archive.key`로 암호화합니다. **두 파일을 함께 보관**해야 재실행 시 복원됩니다. GitHub에는 포함하지 않습니다. 파일·키 분실, PC 고장에 대한 별도 백업은 직접 준비해야 합니다.
- 새 PC에 자동 시작 설정이 설치되는 것은 아닙니다. 재부팅 후 서버를 다시 실행해야 합니다.

### 2. 차량 앱 설치와 실행

Pleos Connect Emulator를 켠 뒤 Android SDK의 `platform-tools`가 있는 환경에서 실행합니다.

```powershell
adb install -r "apk/아빠어디가_여기들렀다가_release.apk"
adb shell am start --user current -n ai.familyroute.demo/.MainActivity
```

- 패키지: `ai.familyroute.demo`, 진입 화면: `.MainActivity`. 앱 이름 변경에도 기존 설치의 가족 연결을 유지하도록 패키지는 동일하게 유지합니다.
- 서버를 먼저 실행해 주세요. 앱을 찾지 못하면 현재 운전자 사용자에 설치되었는지 확인합니다.
- `차량 설정 → 차량 위치 사용`에서 OS 권한을 허용하면 Pleos 위치를 받습니다. 미허용·미수신·오래된 위치는 서울 시연 출발지로 구분합니다.
- `차량 설정 → 안내 음성 들어보기`로 한국어 음성을 확인할 수 있습니다.

### 3. 소스에서 빌드

JDK 21을 설치하고 `JAVA_HOME`을 해당 JDK로, `ANDROID_HOME`을 Android SDK 경로로 설정합니다. `local.properties`의 `sdk.dir`을 사용할 수도 있습니다. 최초 빌드에는 Maven 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

산출물은 `app/build/outputs/apk/release/app-release-unsigned.apk`입니다. 직접 만든 release APK는 본인 인증서로 서명한 후 설치해야 합니다. 저장소의 `apk/`에는 이미 서명한 실행용 APK를 제공합니다. 로컬 키·인증서 개인키는 저장소에 넣지 마세요.

앱의 기본 서버는 `10.0.2.2:8789`입니다. 다른 서버로 빌드하려면 `-PfamilyServerUrl=https://YOUR_SERVER_HOST`를 지정합니다. `family-server.properties` 로컬 설정도 지원하며 Git에서는 제외합니다.

## Pleos SDK와 차량 기능

| SDK / API | 버전 | 실제 적용 |
| --- | --- | --- |
| **Vehicle SDK** | 2.3.2 | 초기화 → 도어 capability 확인 → 현재 문 위치 조회. 문 열림 시 출발 전 확인 안내. 차량 제어 명령은 호출하지 않음 |
| **FusedLocation SDK** | 1.1.3 | 지원 확인·초기화·마지막 위치 조회·위치 구독. 지도 차량 마커, 새 길찾기 출발지, 가족 여행 출발 시 출발지 반영, 가족 제안과의 직선거리 계산 |
| **TextToSpeech SDK** | 3.3.1 | `ON_DEVICE` 모드, `ko_KR`. 가족 제안 알림과 도착 이야기 낭독. 서버 합성용 자격 증명 불필요 |
| **Android Automotive API** | 시스템 제공 | `CarPropertyManager.GEAR_SELECTION`과 `CarUxRestrictionsManager`로 기어 P·UX 제한 확인. 신호가 없거나 주행 중이면 복잡한 조작 잠금 |

Vehicle 2.3.2의 Gear API는 Android의 GEAR_SELECTION 사용을 권장하며 deprecated로 표시하므로 기어 판정은 AAOS 방식을 유지합니다. 위치를 읽기 위해 `FUSED_LOCATION` 실행 권한을 요청하며, `CAR_DOORS`·`TTS_SERVICE` 권한도 선언합니다.

자체 지도·경로를 유지하며 **NaviHelper를 통한 순정 내비 경로 동기화는 구현 범위에 포함하지 않습니다.** 실차와 앱마켓의 권한 승인은 공모전 에뮬레이터 검증과 별도로 확인해야 합니다.

[SDK 적용 내역·접수양식 5번 개발 계획·검증 결과](docs/pleos-sdk-integration.md) · [공식 SDK 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)

## 외부 서비스와 시연 데이터 구분

| 구성 | 현재 동작과 필요한 설정 |
| --- | --- |
| 카카오 Local REST API | 서버 `KAKAO_REST_API_KEY` 또는 Git 제외 파일 `place-search.local.json`의 `kakaoRestApiKey`를 설정하면 전국 실제 장소 검색. 미설정 시 내장 시연 장소 목록만 검색 |
| 카카오모빌리티 Directions | 키가 있으면 실제 도로 경로 요청. 키가 없거나 요청이 실패하면 출발지·경유지·목적지를 잇는 **시연 경로**로 대체. 시연 거리·시간은 실제 주행 안내로 사용하지 않음 |
| 위치 | Pleos의 최신 위치를 수신하면 반영. 위치가 없거나 5분 이상 오래되면 서울 시연 위치 사용. 연속 이동 이력은 저장하지 않으며 가족 여행 출발지는 해당 가족 여행에 저장 |
| 지도 | Leaflet 및 OpenStreetMap 타일 사용. 타일 표시에 인터넷 연결 필요 |
| AWS | 과거 버전의 연동 코드·의존성은 남아 있지만 **현재 v2 실행 경로의 계정·가족·사진 저장에는 AWS 자격 증명이 필요하지 않음**. 로컬 서버·암호화 파일 사용 |
| 추천 코스 | 참고용 시드 코스와 사용자가 공개한 커뮤니티 코스를 구분. 시드 코스가 실제 인기 순위를 의미하지 않음 |

API 키와 비밀번호는 클라이언트/APK에 넣지 않습니다. 외부 서비스의 이용 조건·쿼터는 각 제공자의 정책을 확인해야 합니다. 비용이 없는 시연을 원하면 키를 설정하지 않은 내장 자료 모드를 사용하세요.

## 테스트 환경과 결과

| 항목 | 검증 환경 |
| --- | --- |
| Pleos Connect Emulator | v2.1.0, AAOS, **2560×1440** |
| Android | minSdk 28 / compileSdk 34 / targetSdk 34 |
| 언어·UI | Kotlin, Jetpack Compose |
| 빌드 | Gradle Wrapper 8.10.2, JDK 21, Java target 17 |
| 서버 | Node.js 26.13.0에서 검증, 사용 권장 Node.js 24 이상 |
| Pleos 서비스 | Vehicle 2.3.22 / FusedLocation 1.0.0 / Speech 4.2.10-coral-aosp |

```powershell
npm --prefix server test
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

- **서버 테스트 50개 통과**: 계정 재로그인·복원, 여러 가족방·세션 분리, 사진 권한, 운전자 승인, 추천 코스, 여행 삭제, 목적지 변경, 차량 출발지·경로 실패 시 보존 등.
- **Android JVM 단위 테스트 29개 통과**: 여행 엔진·시뮬레이션·경로·제안 거리·차량 위치 유효성 등. 이 테스트에는 에뮬레이터가 필요하지 않습니다.
- 에뮬레이터에서 SDK 최신 위치 수신과 지도 표시, 문 닫힘 상태 응답, 한국어 음성 **재생 완료(onDone)** 확인.
- 실차 도로 주행 검증은 수행하지 않았습니다. 문 열림 변화와 가족의 새 제안에 대한 거리 음성 전체 흐름은 이번 수동 검증 범위에 포함하지 않았습니다.

## 심사 시연 순서

1. 서버 실행·차량 앱 설치 → 차량 설정에서 위치와 안내 음성 확인.
2. 차량 또는 휴대폰에서 가족방 생성 → 링크/QR로 다른 휴대폰의 별도 계정 참여.
3. 여행과 최종 목적지 설정 → 가족이 장소와 방문 이유·메시지 제안.
4. 운전자 또는 차량이 제안 확인 → 경유지 순서·목적지 편집 → 출발.
5. 정차 상태에서 경유지 도착 확인 → 가족 이야기와 음성 안내.
6. 여행 종료 → 가족 사진 업로드·앨범 열람 → 장소 중심 코스 공개·다른 가족의 코스 가져오기.

## 소스 안내

- `app/`: Pleos 차량 앱. `phone/CarTts.kt`, `PleosVehicleLocation.kt`, `PleosDepartureCheck.kt`가 SDK 연결을 담당합니다.
- `server/v2/`: 계정·가족·여행·제안·사진·커뮤니티 및 휴대폰 웹.
- `server/local-persistence.mjs`: 로컬 AES-256-GCM 저장소.
- `tools/preview-phone.mjs`: 현재 시연 서버 진입점.
- `apk/`: 제출용 release APK와 SHA-256.
- [계정·가족·차량 권한](docs/ACCOUNT_CONNECTION.md) · [휴대폰 접속](docs/PHONE_ACCESS.md) · [제3자 라이선스 고지](docs/licenses/THIRD_PARTY_NOTICES.md)
