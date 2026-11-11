import test from 'node:test';
import assert from 'node:assert/strict';
import { loadAccountConfig } from './account-config.mjs';

const config = {region:'ap-southeast-2', profile:'family-route', userPoolId:'ap-southeast-2_test', clientId:'abc123', domain:'https://test.auth.ap-southeast-2.amazoncognito.com', tableName:'saved-places'};
test('missing local account configuration keeps guest preview mode', () => {
  assert.equal(loadAccountConfig({environment:{LOCALAPPDATA:'test'},read:()=>{const error=Error();error.code='ENOENT';throw error;}}),null);
});
test('local account configuration accepts only the selected region and a Cognito HTTPS domain', () => {
  const load = value => loadAccountConfig({environment:{FAMILY_ACCOUNT_CONFIG:'local-test'},read:()=>JSON.stringify(value)});
  assert.deepEqual(load(config),config);
  assert.throws(()=>load({...config,region:'us-east-1'}));
  assert.throws(()=>load({...config,domain:'https://untrusted.example'}));
  assert.throws(()=>load({...config,clientId:''}));
});
test('an explicitly configured missing file fails instead of silently disabling accounts', () => {
  assert.throws(() => loadAccountConfig({environment:{FAMILY_ACCOUNT_CONFIG:'missing-config'},read:()=>{const error=Error();error.code='ENOENT';throw error;}}), /계정 설정 파일이 없습니다/);
});
