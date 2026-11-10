import { setDefaultAutoSelectFamilyAttemptTimeout } from 'node:net';
// 해외 AWS 연결이 느린 시연 환경에서도 IPv4 연결을 기다린다.
setDefaultAutoSelectFamilyAttemptTimeout(2000);
import { createApp } from './app.mjs';
import { Rooms } from './rooms.mjs';
import { openLocalArchive } from './local-persistence.mjs';
import { createIdentityVerifier, createSavedPlaceStore } from './profile.mjs';
const port = Number(process.env.PORT || 8788);
const { COGNITO_USER_POOL_ID: pool, COGNITO_CLIENT_ID: clientId, COGNITO_AUTH_DOMAIN: domain, SAVED_PLACES_TABLE: table } = process.env;
const region = process.env.AWS_REGION || 'ap-southeast-2';
const anyConfigured = [pool,clientId,domain,table].some(Boolean);
if (anyConfigured && ![pool,clientId,domain,table].every(Boolean)) throw new Error('계정 저장 설정이 완전하지 않습니다');
if (anyConfigured && !/^https:\/\/[a-z0-9-]+\.auth\.[a-z0-9-]+\.amazoncognito\.com$/.test(domain)) throw new Error('로그인 주소 설정을 확인해 주세요');
const profile = anyConfigured ? { config:{clientId,domain,region}, verify:createIdentityVerifier({region,userPoolId:pool,clientId}), store:createSavedPlaceStore({region,tableName:table}) } : null;
const archive = process.env.FAMILY_LOCAL_ARCHIVE_DIR ? openLocalArchive(process.env.FAMILY_LOCAL_ARCHIVE_DIR) : null;
const snapshot = archive?.load();
const rooms = snapshot ? Rooms.fromSnapshot(snapshot,{permanentMemories:true}) : new Rooms({sampleCourses:process.env.FAMILY_SAMPLE_COURSES==='1',permanentMemories:!!archive});
createApp({profile,rooms,persist:archive?.save}).listen(port, '127.0.0.1', () => console.log(`개발 서버: http://127.0.0.1:${port} (이 PC에서만 접속)`));
