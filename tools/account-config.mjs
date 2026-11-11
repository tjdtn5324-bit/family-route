import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createIdentityVerifier, createSavedPlaceStore } from '../server/profile.mjs';

export function loadAccountConfig({ environment = process.env, read = readFileSync } = {}) {
  const path = environment.FAMILY_ACCOUNT_CONFIG || join(environment.LOCALAPPDATA || homedir(), 'AppaEodiga', 'account-config.json');
  let config;
  try { config = JSON.parse(read(path, 'utf8').replace(/^\uFEFF/, '')); }
  catch (error) {
    if (error.code === 'ENOENT' && !environment.FAMILY_ACCOUNT_CONFIG) return null;
    if (error.code === 'ENOENT') throw new Error('지정한 계정 설정 파일이 없습니다. 서버 실행 설정을 확인해 주세요.');
    throw new Error('계정 설정 파일을 읽을 수 없습니다. account-config.json을 확인해 주세요.');
  }
  if (config.region !== 'ap-southeast-2' || !/^[a-zA-Z0-9_-]+$/.test(config.userPoolId || '') ||
      !/^[a-z0-9]+$/.test(config.clientId || '') ||
      !/^https:\/\/[a-z0-9-]+\.auth\.ap-southeast-2\.amazoncognito\.com$/.test(config.domain || '') ||
      !/^[a-zA-Z0-9_.-]+$/.test(config.tableName || '') || !/^[a-zA-Z0-9_-]+$/.test(config.profile || '')) {
    throw new Error('계정 설정값을 확인해 주세요.');
  }
  return config;
}

export function createConfiguredProfile(config = loadAccountConfig()) {
  if (!config) return null;
  // The SDK reads and refreshes the existing AWS CLI login session. Never export credentials to a file.
  process.env.AWS_PROFILE = config.profile;
  process.env.AWS_REGION = config.region;
  return {
    config: { clientId: config.clientId, domain: config.domain, region: config.region },
    verify: createIdentityVerifier({ region: config.region, userPoolId: config.userPoolId, clientId: config.clientId }),
    store: createSavedPlaceStore({ region: config.region, tableName: config.tableName })
  };
}
