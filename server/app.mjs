import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { Rooms } from './rooms.mjs';
import { searchPlaces } from './place-search.mjs';
import { getDirections } from './directions.mjs';

// 로컬 개발용. 인터넷 공개 전에 영속 저장, 인증·초대 만료, HTTPS 및 남용 방지가 필요하다.
export function createApp({ rooms = new Rooms({sampleCourses: process.env.FAMILY_SAMPLE_COURSES === '1'}), apiKey = process.env.KAKAO_REST_API_KEY, createSecret = process.env.ROOM_CREATE_SECRET, search = searchPlaces, directions = getDirections, profile = null } = {}) {
  const limits = new Map();
  const server = http.createServer(async (req, res) => {
    const send = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(body)); };
    try {
      const url = new URL(req.url, 'http://localhost');
      const now = Date.now();
      // Cloudflare Tunnel은 로컬에서만 접속하므로 신뢰 가능한 연결에서만 전달된 IP를 쓴다.
      const source = req.socket.remoteAddress || 'unknown';
      const forwarded = source === '127.0.0.1' || source === '::1' || source === '::ffff:127.0.0.1' ? req.headers['cf-connecting-ip'] : null;
      const ip = typeof forwarded === 'string' && forwarded.length < 80 ? forwarded : source;
      const category = ['/api/places','/api/profile/search'].includes(url.pathname) ? 'search' : ['/api/rooms','/api/rooms/prepare','/api/rooms/claim'].includes(url.pathname) ? 'create' : 'general';
      const key = `${ip}:${category}`;
      if (limits.size > 10000) for (const [k, v] of limits) if (v.until <= now) limits.delete(k);
      if (limits.size > 10000 && !limits.has(key)) return send(429, { error: '요청이 많습니다. 잠시 후 다시 시도해 주세요' });
      let bucket = limits.get(key);
      if (!bucket || bucket.until <= now) { bucket = { count: 0, until: now + 60000 }; limits.set(key, bucket); }
      if (++bucket.count > (category === 'create' ? 5 : category === 'search' ? 30 : 240)) return send(429, { error: '요청이 많습니다. 잠시 후 다시 시도해 주세요' });
      const auth = (req.headers.authorization || '').replace(/^Bearer /, '');
      if (req.method === 'GET' && url.pathname === '/') {
        const html = await readFile(new URL('./phone.html', import.meta.url));
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' }); res.end(html); return;
      }
      if (req.method === 'GET' && url.pathname === '/courses') {
        const html = await readFile(new URL('./courses.html', import.meta.url));
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' }); res.end(html); return;
      }
      if (req.method === 'GET' && url.pathname === '/account') {
        const html = await readFile(new URL('./account.html', import.meta.url));
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' }); res.end(html); return;
      }
      if (req.method === 'GET' && url.pathname === '/assets/busan-illustration.png') {
        const image = await readFile(new URL('./assets/busan-illustration.png', import.meta.url));
        res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=86400', 'X-Content-Type-Options': 'nosniff' }); res.end(image); return;
      }
      const body = async () => {
        if (!(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) throw new Error('JSON 요청이 필요합니다');
        const chunks = []; let size = 0;
        for await (const chunk of req) { size += chunk.length; if (size > 16384) throw new Error('요청이 너무 큽니다'); chunks.push(chunk); }
        return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      };
      if (req.method === 'POST' && url.pathname === '/api/rooms') {
        if (createSecret) {
          const expected = Buffer.from(createSecret), supplied = Buffer.from(String(req.headers['x-room-create-secret'] || ''));
          if (expected.length !== supplied.length || !timingSafeEqual(expected, supplied)) return send(403, { error: '차량 등록이 필요합니다' });
        }
        return send(201, rooms.create((await body()).name));
      }
      if (req.method === 'POST' && url.pathname === '/api/rooms/prepare') {
        if (!profile) return send(503, {error:'로그인을 준비 중이에요'});
        try { await profile.verify(auth); } catch { return send(401, {error:'로그인을 다시 해 주세요'}); }
        const b=await body(); if (!String(b.memberName||'').trim()) throw new Error('이름을 입력해 주세요');
        const room=rooms.create(b.name), member=rooms.join(room.invite,b.memberName);
        return send(201, {invite:room.invite, memberToken:member.memberToken, ...rooms.pairing(member.memberToken)});
      }
      if (req.method === 'POST' && url.pathname === '/api/rooms/pairing') { await body(); return send(200, rooms.pairing(auth)); }
      if (req.method === 'POST' && url.pathname === '/api/rooms/claim') {
        if (createSecret) {const expected=Buffer.from(createSecret), supplied=Buffer.from(String(req.headers['x-room-create-secret']||'')); if (expected.length!==supplied.length||!timingSafeEqual(expected,supplied)) return send(403,{error:'차량 등록이 필요합니다'});}
        return send(200,rooms.claimPairing((await body()).code));
      }
      if (req.method === 'POST' && url.pathname === '/api/join') { const b = await body(); return send(201, rooms.join(b.invite, b.name)); }
      if (req.method === 'GET' && url.pathname === '/api/state') return send(200, rooms.state(auth));
      if (req.method === 'GET' && url.pathname === '/api/profile/config') return send(200, profile ? {enabled:true, ...profile.config} : {enabled:false});
      if (req.method === 'GET' && url.pathname === '/api/profile/search') {
        if (!profile) return send(503, {error:'계정 저장은 아직 준비 중이에요'});
        try { await profile.verify(auth); } catch { return send(401, {error:'로그인을 다시 해 주세요'}); }
        return send(200, {places:await search(url.searchParams.get('q'), {apiKey})});
      }
      if (url.pathname === '/api/profile/places' || url.pathname.startsWith('/api/profile/places/')) {
        if (!profile) return send(503, {error:'계정 저장은 아직 준비 중이에요'});
        let userId; try { userId=await profile.verify(auth); } catch { return send(401, {error:'로그인을 다시 해 주세요'}); }
        if (req.method === 'GET' && url.pathname === '/api/profile/places') return send(200, {places:await profile.store.list(userId)});
        if (req.method === 'POST' && url.pathname === '/api/profile/places') return send(201, {place:await profile.store.save(userId,(await body()).place)});
        const match=url.pathname.match(/^\/api\/profile\/places\/([a-f0-9]{64})$/);
        if (req.method === 'DELETE' && match) { await profile.store.remove(userId,match[1]); return send(200, {ok:true}); }
        return send(404, {error:'찾을 수 없습니다'});
      }
      if (req.method === 'POST' && url.pathname === '/api/preferences') return send(200, { preferences: rooms.setPreferences(auth, await body()) });
      if (req.method === 'GET' && url.pathname === '/api/places') { rooms.session(auth); return send(200, { places: await search(url.searchParams.get('q'), { apiKey }) }); }
      if (req.method === 'GET' && url.pathname === '/api/courses') return send(200, { courses: rooms.courses(auth, url.searchParams.get('q')) });
      if (req.method === 'GET' && url.pathname === '/api/public/courses') return send(200, { courses: rooms.publicCourses(url.searchParams.get('q')) });
      if (req.method === 'GET' && url.pathname === '/api/recommended-courses') return send(200, { courses: rooms.recommendedCourses(auth, Number(url.searchParams.get('latitude')), Number(url.searchParams.get('longitude'))) });
      if (req.method === 'POST' && url.pathname === '/api/courses') { const b = await body(); return send(201, rooms.publishCourse(auth, b.title, b.description)); }
      const course = url.pathname.match(/^\/api\/courses\/([^/]+)\/propose$/);
      if (req.method === 'POST' && course) { await body(); return send(201, rooms.proposeCourse(auth, course[1])); }
      const useCourse = url.pathname.match(/^\/api\/courses\/([^/]+)\/use$/);
      if (req.method === 'POST' && useCourse) { await body(); if (rooms.session(auth).role !== 'driver') throw new Error('운전자만 경로를 변경할 수 있습니다'); const p = rooms.proposeCourse(auth, useCourse[1], true); try { p.course.mode = 'ADD'; const context = rooms.routeContext(auth, p.id); const result = await directions(context, {apiKey}); rooms.applyRoute(auth, p.id, context.version, result); return send(200, {route: result}); } catch (error) { rooms.cancelDriverCourse(auth, p.id); throw error; } }
      if (req.method === 'POST' && url.pathname === '/api/destination') { const b = await body(); const context = rooms.destinationContext(auth, b.place); const result = await directions(context, {apiKey}); rooms.applyDestination(auth, context.version, context.destination, result); return send(200, {route: result}); }
      if (req.method === 'POST' && url.pathname === '/api/itinerary/waypoints') { const b = await body(); if (!Number.isInteger(b.version)) throw new Error('경로를 다시 확인해 주세요'); const context = rooms.itineraryContext(auth, b.ids, b.version); const result = await directions(context, {apiKey}); rooms.applyItinerary(auth, context.version, context.ids, result); return send(200, {ok:true}); }
      if (req.method === 'POST' && url.pathname === '/api/proposals') { const b = await body(); return send(201, rooms.propose(auth, b.place, b.message, b.type)); }
      const route = url.pathname.match(/^\/api\/proposals\/([^/]+)\/route$/);
      if (req.method === 'POST' && route) {
        await body();
        const context = rooms.routeContext(auth, route[1]);
        const result = await directions(context, { apiKey });
        rooms.applyRoute(auth, route[1], context.version, result);
        return send(200, { route: result });
      }
      const vote = url.pathname.match(/^\/api\/proposals\/([^/]+)\/vote$/);
      if (req.method === 'POST' && vote) { rooms.vote(auth, vote[1], (await body()).choice); return send(200, { ok: true }); }
      const decide = url.pathname.match(/^\/api\/proposals\/([^/]+)\/decision$/);
      if (req.method === 'POST' && decide) { rooms.decide(auth, decide[1], (await body()).decision); return send(200, { ok: true }); }
      // EventSource cannot set Authorization; clients use fetch streaming with a bearer header.
      if (req.method === 'GET' && url.pathname === '/api/events') {
        rooms.session(auth);
        res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
        const update = () => res.write(`event: state\ndata: ${JSON.stringify(rooms.state(auth))}\n\n`);
        const unsubscribe = rooms.subscribe(auth, update); update();
        const heartbeat = setInterval(() => res.write(': ping\n\n'), 15000);
        req.on('close', () => { clearInterval(heartbeat); unsubscribe(); });
        return;
      }
      send(404, { error: '찾을 수 없습니다' });
    } catch (error) { if (!res.headersSent) send(400, { error: error.message }); else res.end(); }
  });
  server.headersTimeout = 10000;
  server.requestTimeout = 20000;
  return server;
}
