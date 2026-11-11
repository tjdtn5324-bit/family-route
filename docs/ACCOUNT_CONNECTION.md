# 시연 서버의 기존 계정 연결

시연 PC의 `tools/start-preview.ps1`은 저장소 밖으로 공개하지 않는 `account-config.local.json`을 절대경로로 지정한다. `tools/preview-phone.mjs`는 설정 파일이 있으면 기존 Cognito 로그인과 DynamoDB 개인 장소 저장을 연결한다. 설정이 없으면 계정 기능을 준비 중으로 표시하고 기존 게스트 시연을 제공한다. 장소 검색과 도로 경로는 이 서버에서 계속 모의 데이터다.

설정 파일에는 `region`, `profile`, `userPoolId`, `clientId`, `domain`, `tableName`만 넣는다. 비밀번호, AWS 액세스 키, 세션 토큰은 넣지 않는다. `FAMILY_ACCOUNT_CONFIG`로 다른 파일을 지정할 수 있다. 현재 프로젝트의 선택 리전은 `ap-southeast-2`다.

서버는 기존 `family-route` AWS CLI 로그인 프로필을 SDK의 기본 자격 증명 제공자로 읽는다. 개발용 AWS 로그인이 만료되면 `aws login --profile family-route`로 다시 로그인한다. `aws configure`로 임시 키를 복사하거나 코드에 키를 넣지 않는다. 자격 증명 파일은 서버 코드가 관리하지 않는다.

휴대폰 로그인은 Cognito에 등록한 콜백 주소와 일치해야 한다. `tools/start-preview.ps1`은 서버가 현재 프로세스에 연결된 것으로 검증한 새 임시 터널 주소마다 `tools/sync-account-origin.ps1`을 한 번 실행한다. 이 스크립트는 기존 앱 클라이언트 설정을 읽고 모든 업데이트 가능한 필드를 보존하면서 현재 주소의 `/account`를 callback 및 logout 목록에 추가한다. 기존 URL을 삭제하지 않는다. URL 목록이 100개에 도달하면 등록을 중단하고 오래된 주소 검토를 요청한다.

AWS 로그인이 만료되면 백그라운드 로그인 창을 반복해서 열지 않는다. `.runtime/account-login-status.log`에 조치 문구를 남긴다. 로그인 갱신 후 현재 주소를 다시 등록하려면 다음을 실행한다.

```powershell
$origin = (Invoke-RestMethod http://127.0.0.1:8789/api/invite-origin).origin
.\tools\sync-account-origin.ps1 -Origin $origin
```

이 설정은 기존 리소스만 사용한다. Cognito 기능 플랜 변경이나 리소스 신규 생성은 하지 않는다. 실제 로그인 및 DynamoDB 사용량은 기존 AWS 프로젝트의 요금·무료 사용 한도에 적용된다.
