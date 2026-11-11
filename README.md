# 아빠어디가

**가족 각자의 가고 싶은 곳을 모아 차량에서 함께 여행 경로를 정하고, 가족방 사진으로 추억을 나누는 PLEOS 차량용 앱.**

현대자동차그룹 차량용 앱 개발 공모전 구현 제출용 프로젝트입니다. 이전 이름은 가족경로이며, 최신 실행 앱은 `ai.familyroute.demo` / `MainActivity`입니다. Android Studio의 **Pleos Connect VM(Android Automotive)** 에서 실행합니다. 일반 휴대폰에는 APK 대신 웹 가족방을 사용합니다.

## 제출 APK

- [release APK 다운로드](apk/familyroute_아빠어디가_release.apk)
- 파일 경로: `apk/familyroute_아빠어디가_release.apk`
- `familyroute`는 기존 제출 파일의 식별 접두사입니다. 정식 팀명/개인 성명은 접수양식 작성 시 확정해야 합니다.
- release 변형으로 빌드한 실행 APK입니다. 현재 VM의 앱과 데이터를 유지하며 업데이트할 수 있도록 기존 개발 인증서로 서명합니다. 스토어 배포용 서명키가 아닙니다.
- 기존 `가족경로` APK가 아닌 위 최신 APK를 사용해 주세요. 직접 `assembleRelease`로 빌드하면 unsigned APK가 생성되므로 본인 인증서로 별도 서명해야 설치할 수 있습니다.

## 구현 기능과 사용 순서

| 기능 | 사용자 흐름 |
|---|---|
| 목적지와 경유지 | 차량의 목적지 찾기 → 장소 선택 → 가족 제안 확인 → 경유지 반영·순서 변경·삭제 |
| 가족방 초대 | 차량 가족 메뉴 → QR/초대 링크 또는 휴대폰 홈페이지 → 방 생성·초대 코드 참여 |
| 휴대폰에서 여행 준비 | 가족방 생성 → 장소 또는 저장 묶음 제안 → 차량 연결 코드로 연결 |
| 추천 여행·커뮤니티 | 지역 검색 → 코스 상세 → 코스 시작 또는 기존 목적지의 경유지로 담기 |
| 저장 장소·묶음 | 휴대폰 내 장소 → 장소 검색 → 묶음에 저장 → 개별 장소/묶음 제안 |
| 후기·인기 코스 | 코스 둘러보기 → 후기·좋아요 → 앱 내부 활동 기반 추천 |
| 여행 마무리 | 안내 종료 → 개인 추억 저장 또는 공개 코스 공유 |
| 가족 사진 | 휴대폰 가족방에서 업로드 → 차량 사진 → 가족방 선택 → 해당 방 사진 보기 |
| 사진 삭제 | 차량 사진의 삭제 버튼 → 확인. 휴대폰에서는 자신이 올린 사진 삭제 |

사진은 가족방 참가 권한으로 접근하며 공개 코스에 포함하지 않습니다. 삭제하면 해당 가족방의 모든 기기에서 사라집니다. 차량의 사진 열람·삭제 등 주요 조작은 주차 상태로 제한합니다. 다른 방 앨범을 보는 동안 현재 여행 경로는 바뀌지 않습니다.

## 실행 방법

### 1. 비용 없는 모의 서버 실행

Node.js 24 이상을 설치하고 저장소 루트에서 실행합니다.

```powershell
npm --prefix server ci
node tools/preview-phone.mjs 8789
```

PC에서는 `http://127.0.0.1:8789`를 엽니다. 이 모드는 AWS나 카카오 자격 증명이 필요하지 않습니다. 부산 예시 장소·예시 후기·고정된 모의 경로를 사용하므로 실제 길찾기 품질을 평가하는 모드가 아닙니다.

### 2. 차량 앱 실행

```powershell
adb install -r "apk/familyroute_아빠어디가_release.apk"
adb shell am start -n ai.familyroute.demo/.MainActivity
```

제출 APK의 차량 API는 에뮬레이터 호스트(`10.0.2.2:8789`)에 연결합니다. 위 모의 서버를 실행하면 추천과 가족방을 이용할 수 있습니다. 휴대폰 초대 링크에는 제작 시점의 임시 HTTPS 주소가 들어 있습니다. 다른 PC에서 휴대폰까지 참여하려면 같은 서버를 가리키는 공개 주소로 아래 설정을 변경합니다.

```powershell
# JDK 17/21 및 Android SDK 경로 설정 후 저장소 루트에서 실행
.\gradlew.bat :app:assembleDebug -PfamilyServerUrl=http://10.0.2.2:8789
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

휴대폰 참여까지 확인하려면 서버를 HTTPS로 접근 가능하게 준비하고 그 주소로 APK를 다시 빌드합니다. `familyInviteUrl=https://YOUR_SERVER`로 초대 주소를 별도 설정할 수 있습니다. 주소가 없으면 차량이 10자리 초대 코드를 QR과 글자로 표시합니다. 휴대폰에서 같은 서버의 홈페이지를 열어 코드를 입력합니다. `family-server.properties`에 `familyServerUrl=https://YOUR_SERVER`를 지정하거나 `-PfamilyServerUrl=...`을 사용합니다. 임시 주소와 가족방 토큰을 소스에 커밋하지 않습니다. [접속 설정](docs/PHONE_ACCESS.md)

### 3. 심사 시연 순서

1. PLEOS VM 기어를 P로 두고 아빠어디가를 실행합니다.
2. 목적지 찾기에서 부산역을 선택합니다.
3. 가족 메뉴에서 방을 만들고 휴대폰으로 링크/QR/초대 코드로 참여합니다.
4. 휴대폰에서 광안리 등을 제안하고 차량에서 경유지로 수락합니다.
5. 추천 코스의 장소 목록과 경유지 반영을 확인합니다. 모의 서버의 지도 선은 고정 예시입니다.
6. 휴대폰에서 시험용 사진을 올립니다. 차량 사진 메뉴에서 먼저 가족방을 선택하고 해당 사진을 확인합니다.
7. 시험용 사진의 삭제 버튼과 확인창으로 삭제하고 휴대폰에서도 사라지는지 확인합니다.

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
| 위치·주행 | 서울 시연 출발 위치. 실제 차량 GPS·실도로 주행 검증 미완료 |

PLEOS 기본 내비게이션에 경유지를 보내는 앱이 아니라 자체 지도/경로 UI를 사용합니다. Vehicle/FusedLocation/NaviHelper SDK 전체 연동을 완료했다고 주장하지 않습니다.

## 카카오·AWS 연동 범위

| 서비스 | 코드에 구현된 역할 | 설정/현재 시연과의 차이 |
|---|---|---|
| 카카오 Local REST API | 키워드 장소 검색 | 서버의 `KAKAO_REST_API_KEY` 필요 |
| 카카오모빌리티 Directions API | 목적지·최대 5개 경유지 도로 경로 조회 | 같은 서버 키를 전달하는 구조. 모의 서버에서는 외부 호출하지 않음 |
| Amazon Cognito | 사용자 로그인·토큰 검증 | 아래 계정 환경 설정 필요. 기본 모의 서버에서는 미연결 |
| Amazon DynamoDB | 계정별 저장 장소·개인 추억 경로 | 서버 자격 증명과 테이블 설정 필요 |
| 로컬 암호화 보관 | 가족방·사진·커뮤니티 상태 복원 | `FAMILY_LOCAL_ARCHIVE_DIR`를 저장소 밖 경로로 설정한 dev 서버에서 사용 |

실제 연동은 `server/dev.mjs`를 사용합니다. `PORT=8789`, `KAKAO_REST_API_KEY`, 선택적으로 `FAMILY_SAMPLE_COURSES=1`을 환경 변수에 지정합니다. 계정 기능은 `AWS_REGION=ap-southeast-2`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `COGNITO_AUTH_DOMAIN`, `SAVED_PLACES_TABLE`을 모두 설정하고 서버 실행 환경에 AWS 인증을 제공합니다. Cognito 콜백은 공개 주소의 `/account`와 일치해야 합니다. 키·비밀번호·인증서 개인키는 저장소/APK에 포함하지 않습니다.

현재 제공하는 임시 미리보기 서버는 메모리 저장이므로 종료하면 사진·제안 등이 사라집니다. 로컬 보관 코드는 별도 설정이 필요하며 운영용 영구 서버·백업을 제공하는 상태가 아닙니다. AWS/카카오 실제 서비스 비용을 무료라고 보장하지 않으며, 기본 모의 실행은 두 서비스에 요청하지 않습니다. 이 제출 업데이트에서는 AWS 리소스를 생성하지 않았습니다.

## 검증

```powershell
node --test server/*.test.mjs
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

2026-11-11 기준 서버 테스트 36개, Android 단위 테스트 26개 통과. PLEOS VM에서 가족방 선택 → 사진 표시 → 확인용 사진 삭제와 삭제 후 서버 접근 불가를 확인했습니다. 가족방 간 접근 차단·사진의 공개 코스 제외·삭제 권한은 자동 테스트에 포함됩니다. 실제 차량 주행·운전자 주의 분산에 대한 인증 시험은 수행하지 않았습니다.

## 소스 구조

- `app/`: 최신 Android 앱, 차량 상태 제한, 가족방 클라이언트, 지도 UI
- `server/`: 가족방·사진·검색·코스·계정 API와 웹 화면
- `tools/preview-phone.mjs`: 외부 API 없이 실행하는 모의 서버
- `server/dev.mjs`: 카카오·선택적 계정/로컬 보관 연동 서버
- `apk/`: 공모전 제출 release APK
- `docs/licenses/`: 포함 폰트 등 라이선스 자료

저장소에는 초기 시뮬레이터 코드도 남아 있습니다. 최신 진입점은 `MainActivity`이며 이전 phase 문서보다 이 README를 우선합니다. 앱 아이콘은 제공된 가족 자동차 이미지에서 제작한 시연 자산이며 현대자동차 공식 앱을 의미하지 않습니다.

## 제출 안내

[공식 GitHub 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission#github)와 [개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)에 맞춰 앱 소개·실행 방법·환경·APK 경로를 정리했습니다. 비공개 저장소이므로 제출자는 공지된 심사용 계정의 접근 권한을 별도로 확인해야 합니다. GitHub 업데이트가 참가 신청 이메일 제출을 대신하지 않습니다.
