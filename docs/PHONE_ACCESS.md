# 휴대폰 접속 설정

이 PC의 현재 외부 주소는 Git에서 제외한 family-server.properties에 저장한다.
Gradle -PfamilyServerUrl 설정이 있으면 그것을 우선하고, 없으면 이 파일을 읽는다.
둘 다 없을 때만 에뮬레이터 내부용 http://10.0.2.2:8789 를 사용한다.

Cloudflare 임시 주소는 실행 중인 터널에 의존한다. 터널을 닫으면 접속되지 않으며 다시 만들면 주소가 바뀔 수 있다.
새 주소가 생기면 family-server.properties의 familyServerUrl을 바꾸고 APK를 다시 빌드·설치한다.
현재 서버는 tools/preview-phone.mjs의 모의 데이터 서버다. 실제 카카오 검색과 AWS 계정 연결 환경을 뜻하지 않는다.

휴대폰에서는 외부 홈페이지 주소를 열어 가족방을 만들거나 초대 코드를 입력한다.
차량 가족방에서는 QR 또는 초대 링크로 참여한다. 가족방 사진은 해당 방의 참가 권한이 있어야 조회할 수 있다.
