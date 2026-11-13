# 여기들렀다가 — Pleos Connect SDK 적용

## 접수양식 5번 개발 계획에 반영할 내용

여기들렀다가는 Pleos Connect 차량 앱과 가족 구성원의 휴대폰 웹을 연결하는 가족 여행 서비스입니다. 출발 전에는 여러 가족방에서 장소와 코스를 준비하고, 차량에서는 가족의 제안을 확인해 경유지로 반영합니다. 여행 중에는 제안자의 이름과 가족 이야기를 안내하며, 여행 후 사진은 소속 가족방 안에서만 공유합니다.

차량 앱은 Kotlin과 Jetpack Compose로 구현하고 Pleos Connect Emulator v2.1.0의 2560×1440 화면에서 검증합니다. 자체 지도와 경로 서비스는 유지하며 다음 Pleos SDK를 연결합니다.

1. **FusedLocation 1.1.3:** 차량 설정에서 위치 권한을 허용한 뒤 지원 여부 확인, 초기화, 마지막 위치 조회와 위치 업데이트 콜백을 사용합니다. 최신 유효 좌표를 지도에 표시하고, 새 길찾기 및 차량에서 가족 여행 출발을 확인할 때 출발지에 반영합니다. 가족 제안에는 현재 차량과의 직선거리를 표시합니다. 위치가 없거나 5분 이상 오래된 경우 서울 시연 위치임을 표시합니다. 연속 이동 이력은 서버에 저장하지 않습니다.
2. **TextToSpeech 3.3.1:** 차량 Speech Service의 ON_DEVICE 모드로 주행 중 가족 제안과 도착 시 가족 이야기를 읽습니다. 초기화와 합성 오류는 상태에 표시하고 텍스트 안내를 유지합니다. 서버 합성이나 외부 유료 음성 서비스를 사용하지 않습니다.
3. **Vehicle 2.3.2:** 도어 위치 capability를 확인하고 지원되는 문의 현재 열림 상태를 조회합니다. 문이 열린 경우 지도에 출발 전 확인 안내를 표시합니다. 확인되지 않은 상태를 닫힘으로 표시하지 않으며, 차량 제어 명령은 호출하지 않습니다.

기어 P와 주행 중 조작 제한은 Android Automotive의 CarPropertyManager와 CarUxRestrictionsManager를 사용합니다. Vehicle SDK 2.3.2에 포함된 Gear API는 Google GEAR_SELECTION 사용을 권장하며 deprecated로 표시되어 있어 이를 따릅니다. 신호가 없거나 운전 중인 경우 복잡한 조작을 잠그는 기존 동작을 유지합니다.

가족방 접근, 사진 열람·삭제, 경유지 승인 권한은 서버에서 검사합니다. SDK 사용을 이유로 기존 회원·가족방 자료를 초기화하지 않습니다.

## 구현 범위와 한계

- NaviHelper를 통한 순정 내비 경로 동기화는 사용하지 않습니다. 지도와 경로는 기존 자체 기능입니다.
- 실제 도로 주행·실차 검증은 완료했다고 주장하지 않습니다. 에뮬레이터 위치도 실제 차량 위치와 구분해서 시연합니다.
- 문 상태 확인은 보조 안내이며 안전을 보증하지 않습니다. 센서 응답이 없는 경우 직접 확인하도록 안내합니다.
- 공모전 개발 환경의 권한 선언과 Pleos App Market 입점을 위한 권한 승인은 별개로 확인해야 합니다.

## 검증 절차

1. `gradlew.bat :app:assembleRelease :app:testDebugUnitTest`로 SDK 의존성과 Android 단위 테스트를 확인합니다.
2. Node.js 24 이상에서 `cd server` 후 `npm test`를 실행합니다. 차량 출발지의 입력 검증, 권한, 경유지 유지, 재시작 복원, 경로 실패 시 기존 경로 보존을 포함합니다.
3. 에뮬레이터에 업데이트 설치 후 로그 태그 `FamilyRoutePleosLocation`, `FamilyRoutePleosTts`, `FamilyRoutePleosVehicle`에서 실제 서비스 응답을 확인합니다. 클래스 초기화 로그만으로 데이터 수신·음성 재생 성공을 판단하지 않습니다.
4. 차량 설정의 **차량 위치 사용**에서 권한을 허용하고 위치 업데이트 후 지도 차량 마커와 새 경로 출발지를 비교합니다. 위치 미수신·오래된 위치는 시연 출발지 표시를 확인합니다.
5. 문 열림·닫힘 변화에 안내가 반응하는지 확인합니다. 실패나 미지원 시 닫힘으로 표시하지 않는지 확인합니다.
6. 차량 설정의 **안내 음성 들어보기**를 눌러 초기화 성공뿐 아니라 `onStart`/`onDone` 이벤트를 확인합니다.
7. 기어 D에서 조작이 잠기고 P로 복귀하면 허용되는지 확인합니다. 가족방 전환·사진 접근 범위·여행 편집도 기존 회귀 테스트로 확인합니다.

## 공식 문서

- [SDK Overview](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)
- [Vehicle SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)
- [FusedLocation SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro)
- [TextToSpeech SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro)

## 2026-11-13 검증 결과

| 항목 | 결과 |
| --- | --- |
| Release 빌드와 lint | `BUILD SUCCESSFUL`, 개발 인증서 서명 및 검증 후 기존 앱에 업데이트 설치 |
| 서버 | README의 `npm --prefix server test`로 50개 통과, 실패 0 |
| Android JVM 단위 테스트 | 29개 통과, 실패·오류 0 |
| Vehicle 서비스 2.3.22 | 지원 범위 확인 후 문 닫힘 조회 응답 수신. 문 상태 설정은 호출하지 않음 |
| FusedLocation 서비스 1.0.0 | 위치 사용 버튼 → OS 권한 허용 → 최신 위치 콜백 수신 및 지도 차량 마커 표시. 재설치 후에도 허용 상태와 위치 수신 유지 |
| Speech 서비스 4.2.10-coral-aosp | ON_DEVICE, `Locale.KOREA`(`ko_KR`)로 `onReady` → `onStart` → `onDone` 확인. 한국어 안내 재생 완료 |
| 기존 여행 | 기존 가족 연결과 진행 중 여행·경유지 유지 확인 |
| 차량 출발지 | SDK 출발지 입력 검증·가족 여행 경유지 유지·저장 복원·경로 실패 시 기존 상태 유지 자동 테스트 통과. 실행 중 서버의 새 출발지 길찾기 응답 확인 |

제출용 APK와 설치한 APK의 SHA-256: `88b61921c43e00945534e92670be48c66c09e80a87565fda818e5e31e6247382`

SDK 서비스 응답과 에뮬레이터 화면을 기준으로 한 검증이며 실차 주행 검증은 아닙니다. 문 열림 변화 시나리오와 가족의 새 제안에 대한 실제 거리 음성 안내 전체 흐름은 이번 수동 검증에 포함하지 않았습니다. 거리 계산은 단위 테스트로 검증했습니다.
