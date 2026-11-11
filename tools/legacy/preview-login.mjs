// 로그인 기능 확인용. 실제 Cognito·DynamoDB에 연결하고, 장소 검색과 경로만 가짜로 둔다.
// 먼저 `aws login --profile family-route` 후 실행: node tools/preview-login.mjs → http://localhost:8800
// 설정값은 실행할 때 AWS에서 읽어 오며 파일이나 화면에 남기지 않는다.
import { setDefaultAutoSelectFamilyAttemptTimeout } from 'node:net';
setDefaultAutoSelectFamilyAttemptTimeout(2000);
import { execFileSync } from 'node:child_process';
import { createApp } from '../../server/legacy/app.mjs';
import { Rooms } from '../../server/legacy/rooms.mjs';
import { createIdentityVerifier, createSavedPlaceStore } from '../../server/legacy/profile.mjs';

const region = 'ap-southeast-2', profileName = 'family-route', tableName = 'family-route-saved-places';
process.env.AWS_PROFILE = profileName;
process.env.AWS_REGION = region;
const aws = (...args) => execFileSync('aws', [...args, '--profile', profileName, '--region', region, '--output', 'text'], { encoding: 'utf8', shell: true }).trim();
const userPoolId = aws('cognito-idp', 'list-user-pools', '--max-results', '20', '--query', '"UserPools[?Name==\'family-route-users\'].Id | [0]"');
const clientId = aws('cognito-idp', 'list-user-pool-clients', '--user-pool-id', userPoolId, '--query', '"UserPoolClients[0].ClientId"');
const prefix = aws('cognito-idp', 'describe-user-pool', '--user-pool-id', userPoolId, '--query', '"UserPool.Domain"');
if (![userPoolId, clientId, prefix].every(v => v && v !== 'None')) throw new Error('로그인 설정을 AWS에서 읽지 못했습니다');
const domain = `https://${prefix}.auth.${region}.amazoncognito.com`;

const places = [
  { name: '부산역', latitude: 35.1152, longitude: 129.0415, address: '부산 동구 중앙대로 206' },
  { name: '광안리해수욕장', latitude: 35.1532, longitude: 129.1187, address: '부산 수영구 광안해변로 219' },
  { name: '감천문화마을', latitude: 35.0974, longitude: 129.0106, address: '부산 사하구 감천동 1-17' },
  { name: '해운대해수욕장', latitude: 35.1587, longitude: 129.1604, address: '부산 해운대구 우동' }
];
createApp({
  apiKey: 'preview',
  rooms: new Rooms({ sampleCourses: true }),
  search: async q => places.filter(p => !q || p.name.includes(q) || p.address.includes(q) || q.includes('부산')),
  directions: async () => ({ points: [[37.55, 126.97], [35.1152, 129.0415]], distance: 400000, duration: 16000 }),
  profile: { config: { clientId, domain, region }, verify: createIdentityVerifier({ region, userPoolId, clientId }), store: createSavedPlaceStore({ region, tableName }) }
}).listen(8800, '127.0.0.1', () => console.log('로그인 확인용 서버: http://localhost:8800'));
