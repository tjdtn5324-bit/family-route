// 아빠어디가 2판 HTTP 서버.
// 사람(휴대폰)은 HttpOnly 쿠키 세션으로, 차량은 Authorization 헤더의 차량 토큰으로 구분한다.
// 권한 검사는 모두 service.mjs에서 한다. 여기서는 요청을 읽고, 횟수를 제한하고, 상태 코드를 붙인다.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { Service, HttpError } from './service.mjs';

const COOKIE = 'fr_session';
const json = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };

function readCookie(req, name) {
  for (const part of String(req.headers.cookie || '').split(';')) {
    const i = part.indexOf('='); if (i < 0) continue;
    if (part.slice(0, i).trim() === name) return decodeURIComponent(part.slice(i + 1).trim());
  }
  return '';
}
// 터널(https)로 들어온 요청이면 Secure 쿠키를 쓴다.
const isHttps = req => req.headers['x-forwarded-proto'] === 'https' || /"scheme":"https"/.test(String(req.headers['cf-visitor'] || ''));
const sessionCookie = (req, token, maxAge) => `${COOKIE}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${isHttps(req) ? '; Secure' : ''}`;

export function createServer({ service = new Service(), apiKey = process.env.KAKAO_REST_API_KEY, search, directions, persist = null, pageFile = new URL('./app.html', import.meta.url), assets = {} } = {}) {
  if (directions) service.directions = context => directions(context, { apiKey });
  const limits = new Map();
  let writes = Promise.resolve();
  const commit = fn => {
    const operation = writes.catch(() => {}).then(async () => {
      const before = persist ? service.exportSnapshot() : null;
      const result = await fn();
      if (persist) {
        try { await persist(service.exportSnapshot()); }
        catch {
          service.restoreSnapshot(before);
          throw new HttpError(503, '계정과 변경 내용을 저장하지 못했어요. 잠시 후 다시 시도해 주세요');
        }
      }
      return result;
    });
    writes = operation;
    return operation;
  };
  const server = http.createServer(async (req, res) => {
    const send = (status, body, headers = {}) => {
      res.writeHead(status, { ...json, ...headers }); res.end(JSON.stringify(body));
    };
    try {
      const url = new URL(req.url, 'http://localhost'), path = url.pathname, method = req.method;
      // ── 화면 ──
      if (method === 'GET' && ['/', '/index.html', '/courses', '/account'].includes(path)) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY' });
        return res.end(await readFile(pageFile));
      }
      if (method === 'GET' && assets[path]) {
        const [file, type] = assets[path];
        res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'public, max-age=86400', 'X-Content-Type-Options': 'nosniff' });
        return res.end(await readFile(file));
      }
      if (!path.startsWith('/api/')) { res.writeHead(404, json); return res.end(JSON.stringify({ error: '찾을 수 없어요' })); }

      // ── 요청 횟수 제한(IP 기준, 1분) ──
      const source = req.socket.remoteAddress || '';
      const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(source);
      const ip = local && typeof req.headers['cf-connecting-ip'] === 'string' ? req.headers['cf-connecting-ip'].slice(0, 80) : source;
      const category = /^\/api\/auth\/(login|signup)$/.test(path) ? 'auth' : (path === '/api/vehicle/pair' || path === '/api/vehicle/family') ? 'pair' : path === '/api/places' || path === '/api/drive/route' ? 'search' : method === 'GET' ? 'read' : 'write';
      const max = { auth: 10, pair: 5, search: 30, read: 600, write: 120 }[category];
      const now = Date.now(), key = ip + ':' + category;
      if (limits.size > 20000) for (const [k, v] of limits) if (v.until <= now) limits.delete(k);
      let bucket = limits.get(key); if (!bucket || bucket.until <= now) { bucket = { count: 0, until: now + 60000 }; limits.set(key, bucket); }
      if (++bucket.count > max) return send(429, { error: '요청이 많아요. 잠시 후 다시 시도해 주세요' });

      const body = async (maxSize = 16384) => {
        if (!(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) throw new HttpError(415, 'JSON 요청이 필요해요');
        const chunks = []; let size = 0;
        for await (const c of req) { size += c.length; if (size > maxSize) throw new HttpError(413, '요청이 너무 커요'); chunks.push(c); }
        return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      };
      if (method === 'GET') await writes.catch(() => {});
      const bearer = String(req.headers.authorization || '').replace(/^Bearer /, '');
      // 차량 요청은 Authorization 헤더로, 사람 요청은 쿠키로만 인증한다(서로 섞지 않는다).
      const actor = () => bearer ? service.vehicleActor(bearer) : service.userActor(readCookie(req, COOKIE));
      const user = () => service.userActor(readCookie(req, COOKIE));
      const write = async (fn, status = 200) => { const result = await commit(fn); return send(status, result ?? { ok: true }); };
      const m = (re) => path.match(re);
      let p;

      // ── 계정 ──
      if (method === 'POST' && path === '/api/auth/signup') { const b = await body(); const r = await commit(() => service.signup(b)); return send(201, { user: r.user }, { 'Set-Cookie': sessionCookie(req, r.token, 30 * 86400) }); }
      if (method === 'POST' && path === '/api/auth/login') { const b = await body(); const r = await commit(() => service.login(b)); return send(200, { user: r.user }, { 'Set-Cookie': sessionCookie(req, r.token, 30 * 86400) }); }
      if (method === 'POST' && path === '/api/auth/logout') { await commit(() => service.logout(readCookie(req, COOKIE))); return send(200, { ok: true }, { 'Set-Cookie': sessionCookie(req, '', 0) }); }
      if (method === 'GET' && path === '/api/me') return send(200, service.me(user()));

      // ── 가족 ──
      if (method === 'POST' && path === '/api/family/select') return await write(async () => service.selectFamily(user(), await body()));
      if (method === 'POST' && path === '/api/family') return await write(async () => service.createFamily(user(), await body()), 201);
      if (method === 'POST' && path === '/api/family/pin') return await write(async () => service.setFamilyPin(user(), await body()));
      if (method === 'GET' && path === '/api/family/photos') return send(200, { photos: service.familyPhotos(actor()) });
      if (method === 'POST' && path === '/api/family/rename') return await write(async () => service.renameFamily(user(), await body()));
      if (method === 'POST' && path === '/api/family/invite') return await write(async () => { await body(); return service.createInvite(user()); });
      if (method === 'GET' && path === '/api/family/invite') return send(200, service.invitePreview(user(), url.searchParams.get('code')));
      if (method === 'POST' && path === '/api/family/join') return await write(async () => service.joinFamily(user(), await body()));
      if (method === 'POST' && path === '/api/family/leave') return await write(async () => service.leaveFamily(user(), await body()));
      if ((p = m(/^\/api\/family\/members\/([\w-]{36})\/remove$/)) && method === 'POST') return await write(async () => { await body(); return service.removeMember(user(), p[1]); });
      if ((p = m(/^\/api\/family\/members\/([\w-]{36})\/admin$/)) && method === 'POST') return await write(async () => { await body(); return service.transferAdmin(user(), p[1]); });
      if (method === 'POST' && path === '/api/family/vehicle-code') return await write(async () => { await body(); return service.vehicleCode(user()); });
      if ((p = m(/^\/api\/family\/vehicles\/([\w-]{36})$/)) && method === 'DELETE') return await write(() => service.removeVehicle(user(), p[1]));

      // ── 차량 ──
      if (method === 'POST' && path === '/api/vehicle/pair') return await write(async () => service.pairVehicle(await body()), 201);
      if (method === 'POST' && path === '/api/vehicle/family') return await write(async () => service.createFamilyFromVehicle(await body()), 201);
      if (method === 'POST' && path === '/api/vehicle/invite') return await write(async () => { await body(); return service.vehicleInvite(service.vehicleActor(bearer)); });
      if (method === 'POST' && path === '/api/vehicle/trips') return await write(async () => service.createTripFromVehicle(service.vehicleActor(bearer), await body()), 201);
      if (method === 'GET' && path === '/api/vehicle/home') return await write(() => service.vehicleHome(service.vehicleActor(bearer)));
      if (method === 'DELETE' && path === '/api/vehicle/pair') return await write(() => service.unpairSelf(service.vehicleActor(bearer)));

      // ── 장소 검색과 혼자 운전하는 경로 ──
      // 로그인이나 가족 연결 없이도 쓸 수 있다(차량은 가족과 연결하지 않아도 길안내가 되어야 한다).
      // 개인 자료를 읽거나 남기지 않고, IP별 요청 수만 제한한다.
      if (method === 'GET' && path === '/api/places') { if (!search) throw new HttpError(503, '장소 검색을 지금 쓸 수 없어요'); const result = await search(url.searchParams.get('q'), { apiKey }); return send(200, Array.isArray(result) ? { places: result } : result); }
      if (method === 'POST' && path === '/api/drive/route') return send(200, await service.soloRoute(await body()));

      // ── 추천 코스 ──
      // 둘러보기는 로그인 없이도 된다(차량 포함). 좋아요·후기·공개는 휴대폰 로그인이 필요하다.
      const viewer = () => { try { return actor(); } catch (e) { if (e instanceof HttpError && e.status === 401) return null; throw e; } };
      if (method === 'GET' && path === '/api/courses') return send(200, { courses: service.listCourses(viewer(), { sort: url.searchParams.get('sort') || 'trending', kind: url.searchParams.get('kind') || '', q: url.searchParams.get('q') || '' }) });
      if (method === 'POST' && path === '/api/courses') return await write(async () => service.publishCourse(user(), await body()), 201);
      if ((p = m(/^\/api\/courses\/([\w-]{3,64})$/))) {
        if (method === 'GET') return send(200, service.courseDetail(viewer(), p[1]));
        if (method === 'DELETE') return await write(() => service.unpublishCourse(user(), p[1]));
      }
      if ((p = m(/^\/api\/courses\/([\w-]{3,64})\/(like|reviews)$/))) {
        const [, id, action] = p;
        if (method === 'POST' && action === 'like') return await write(async () => service.likeCourse(user(), id, await body()));
        if (method === 'POST' && action === 'reviews') return await write(async () => service.reviewCourse(user(), id, await body()));
        if (method === 'DELETE' && action === 'reviews') return await write(() => service.deleteMyReview(user(), id));
      }

      // ── 여행 ──
      if (method === 'GET' && path === '/api/trips') return send(200, { trips: service.listTrips(actor()) });
      if (method === 'POST' && path === '/api/trips') return await write(async () => service.createTrip(actor(), await body()), 201);
      if ((p = m(/^\/api\/trips\/([\w-]{36})$/))) {
        if (method === 'GET') return send(200, service.tripDetail(actor(), p[1]));
        if (method === 'DELETE') return await write(() => service.deleteTrip(actor(), p[1]));
      }
      if ((p = m(/^\/api\/trips\/([\w-]{36})\/(update|proposals|stops|course|clear-course|order|ready|quick-start|start|cancel-start|finish|photos)$/)) && method === 'POST') {
        const [, id, action] = p;
        if (action === 'update') return await write(async () => service.updateTrip(actor(), id, await body()));
        if (action === 'proposals') return await write(async () => service.proposePlace(actor(), id, await body()), 201);
        if (action === 'stops') return await write(async () => service.addStop(actor(), id, await body()), 201);
        if (action === 'course') return await write(async () => service.applyCourse(actor(), id, await body()));
        if (action === 'order') return await write(async () => service.reorderStops(actor(), id, await body()));
        if (action === 'ready') return await write(async () => service.setTripReady(actor(), id, await body()));
        if (action === 'quick-start') return await write(async () => { const data = await body(); const a = actor(); if (data.origin != null) await service.setVehicleOrigin(a, id, data.origin); return service.quickStartTrip(a, id); });
        if (action === 'start') return await write(async () => { const data = await body(); const a = actor(); if (data.origin != null) await service.setVehicleOrigin(a, id, data.origin); return service.startTrip(a, id); });
        if (action === 'cancel-start') return await write(async () => { await body(); return service.cancelStart(actor(), id); });
        if (action === 'clear-course') return await write(async () => { await body(); return service.clearCourse(actor(), id); });
        if (action === 'finish') return await write(async () => { await body(); return service.finishTrip(actor(), id); });
        if (action === 'photos') return await write(async () => service.addPhoto(actor(), id, await body(360000)), 201);
      }
      if ((p = m(/^\/api\/proposals\/([\w-]{36})(\/(vote|react|comment|decide|arrive))?$/))) {
        const [, id, , action] = p;
        if (method === 'DELETE' && !action) return await write(() => service.removeProposal(actor(), id));
        if (method === 'POST' && action === 'vote') return await write(async () => service.vote(actor(), id, await body()));
        if (method === 'POST' && action === 'react') return await write(async () => service.reactProposal(actor(), id, await body()));
        if (method === 'POST' && action === 'comment') return await write(async () => service.commentProposal(actor(), id, await body()));
        if (method === 'POST' && action === 'decide') return await write(async () => service.decide(actor(), id, await body()));
        if (method === 'POST' && action === 'arrive') return await write(async () => { await body(); return service.arriveAt(actor(), id); });
      }
      if ((p = m(/^\/api\/photos\/([\w-]{36})$/))) {
        if (method === 'GET') return send(200, service.photo(actor(), p[1]));
        if (method === 'DELETE') return await write(() => service.deletePhoto(actor(), p[1]));
      }
      if (method === 'GET' && path === '/api/album') return send(200, { trips: service.album(actor()) });
      return send(404, { error: '찾을 수 없어요' });
    } catch (error) {
      if (res.headersSent) return res.end();
      const status = error instanceof SyntaxError ? 400 : error instanceof HttpError ? error.status : 500;
      if (status === 500) console.error(error);
      send(status, { error: status === 500 ? '잠시 문제가 생겼어요. 다시 시도해 주세요' : error instanceof SyntaxError ? '요청 형식을 확인해 주세요' : error.message },
        status === 401 && !String(req.headers.authorization || '') ? { 'Set-Cookie': sessionCookie(req, '', 0) } : {});
    }
  });
  server.headersTimeout = 10000; server.requestTimeout = 30000;
  return server;
}
