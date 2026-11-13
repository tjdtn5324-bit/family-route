import { readFileSync } from 'node:fs';
import { searchPlaces } from './place-search.mjs';
import { HttpError } from './v2/service.mjs';

/** The key is read by the server only and is never included in a response. */
export function loadPlaceSearchKey({ environment = process.env, path, read = readFileSync } = {}) {
  const fromEnvironment = environment.KAKAO_REST_API_KEY?.trim();
  if (fromEnvironment) return validateKey(fromEnvironment);
  if (!path) return '';
  let config;
  try { config = JSON.parse(read(path, 'utf8').replace(/^\uFEFF/, '')); }
  catch (error) {
    if (error.code === 'ENOENT') return '';
    throw Error('place-search.local.json 파일의 형식을 확인해 주세요.');
  }
  const value = config?.kakaoRestApiKey;
  if (value === undefined || value === '') return '';
  return validateKey(value);
}

function validateKey(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{16,200}$/.test(value.trim())) {
    throw Error('장소 검색 REST API 키 설정을 확인해 주세요.');
  }
  return value.trim();
}

/** Preserve the offline demonstration while clearly identifying its search limit. */
export function createPlaceSearch({ apiKey = '', localSearch, search = searchPlaces }) {
  return async query => {
    const q = typeof query === 'string' ? query.trim() : '';
    if (!q || [...q].length > 100) throw new HttpError(400, '검색어는 1~100자로 입력해 주세요.');
    if (apiKey) {
      try { return { places: await search(q, { apiKey }), source: 'KAKAO' }; }
      catch { throw new HttpError(503, '실제 장소 검색에 연결하지 못했어요. 잠시 후 다시 검색해 주세요.'); }
    }
    const places = localSearch(q);
    if (!places.length) throw new HttpError(503, '현재 시연용 목록에 없는 장소예요. 전국 장소 검색을 사용하려면 서버의 지도 API 설정이 필요해요.');
    return { places, source: 'DEMO', notice: '현재는 시연용 장소 목록에서 검색해요. 실제 장소 검색을 연결하려면 지도 API 설정이 필요해요.' };
  };
}
