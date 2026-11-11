import { randomBytes, randomInt, randomUUID } from 'node:crypto';
import { curatedCourses } from './curated-courses.mjs';

const token = () => randomBytes(24).toString('base64url');
const clean = (value, limit) => typeof value === 'string' ? [...value.trim()].slice(0, limit).join('') : '';
const ROOM_TTL_MS = 12 * 60 * 60 * 1000;
const COMPANIONS = new Set(['CHILD', 'PARENTS', 'PET']);
const STYLES = new Set(['FOOD', 'RELAX', 'ACTIVITY']);
const defaultPreferences = () => ({ companions: [], styles: [], hours: null });
const DAY_MS = 24 * 60 * 60 * 1000;
const TREND_WINDOW_MS = 7 * DAY_MS, TREND_HALF_LIFE_MS = 2 * DAY_MS;
const METROS = ['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '제주'];
const PROVINCES = { 경기: '경기', 강원: '강원', 충북: '충북', 충청북도: '충북', 충남: '충남', 충청남도: '충남', 전북: '전북', 전라북도: '전북', 전남: '전남', 전라남도: '전남', 경북: '경북', 경상북도: '경북', 경남: '경남', 경상남도: '경남' };
// 다른 도에 같은 이름이 있는 시·군은 도 이름을 붙여 구분한다.
const AMBIGUOUS = new Set(['광주', '고성']);
const TREND_BADGE_SCORE = 3;
const RESERVED_NAME = /운영|관리자|공식|현대|기아|가족경로/;
const MAX_TRIPS = 20, PHOTOS_PER_TRIP = 8;

// 상태 코드가 있는 오류. 서버는 이 값을 그대로 응답 코드로 쓴다(없으면 400).
export class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (status, message) => { throw new HttpError(status, message); };

// 코스가 속한 지역 이름. 알려진 시·도 주소일 때만 값을 내고, 아니면 빈 문자열이다.
// 주소는 사용자가 보낸 값이라 아무 글이나 지역 이름으로 화면에 올라가지 않게 한다.
export function regionOf(place) {
  const tokens = String(place?.address || '').trim().split(/\s+/).filter(Boolean);
  const first = tokens[0] || '';
  const metro = METROS.find(m => first.startsWith(m));
  if (metro) return metro;
  const province = Object.keys(PROVINCES).find(p => first.startsWith(p));
  if (!province || !/^[가-힣]{1,6}(시|군)$/.test(tokens[1] || '')) return '';
  const city = tokens[1].replace(/(시|군)$/, '');
  return AMBIGUOUS.has(city) ? PROVINCES[province] + ' ' + city : city;
}

function validatedPreferences(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('여행 취향을 확인해 주세요');
  const selected = (value, allowed) => {
    if (!Array.isArray(value) || value.length > allowed.size || value.some(v => !allowed.has(v))) throw new Error('선택한 여행 취향을 확인해 주세요');
    return [...new Set(value)];
  };
  const hours = input.hours ?? null;
  if (hours !== null && ![2, 4, 6, 8].includes(hours)) throw new Error('여행 시간을 확인해 주세요');
  return { companions: selected(input.companions, COMPANIONS), styles: selected(input.styles, STYLES), hours };
}

// 개발용 저장소: 프로세스 종료 시 데이터가 사라진다. 외부 공개 배포용이 아니다.
export class Rooms {
  #rooms = new Map();
  #sessions = new Map();
  #invites = new Map();
  #courses = new Map();
  #likes = new Map();
  #reviews = new Map();
  #activity = new Map();
  #pairings = new Map();
  #joinCodes = new Map();
  #permanentMemories = false;

  #waypoints(room) {
    return room.proposals.filter(p => p.status === 'ACCEPTED' && p.type === 'WAYPOINT')
      .map((p, index) => ({ p, index })).sort((a,b) => (a.p.waypointOrder ?? a.index) - (b.p.waypointOrder ?? b.index)).map(x => x.p);
  }

  constructor({ sampleCourses = false, permanentMemories = false } = {}) {
    this.#permanentMemories = permanentMemories;
    if (sampleCourses) {
      const destination = { name:'부산역', latitude:35.115, longitude:129.042, address:'부산광역시' };
      const examples = [
        { id:'sample-busan-sea', title:'부산 바다와 시장 여행', description:'시연을 위한 예시 코스입니다.', waypoints:[{name:'광안리',latitude:35.153,longitude:129.118,address:'부산광역시'},{name:'자갈치시장',latitude:35.097,longitude:129.030,address:'부산광역시'}], preferences:{companions:['PARENTS'],styles:['FOOD','RELAX'],hours:4} },
        { id:'sample-busan-relax', title:'부산 바다에서 천천히', description:'시연을 위한 예시 코스입니다.', waypoints:[{name:'광안리',latitude:35.153,longitude:129.118,address:'부산광역시'}], preferences:{companions:[],styles:['RELAX'],hours:2} },
      ];
      for (const item of examples) this.#courses.set(item.id, {...item, destination, sample:true, createdAt:0});
      for (const item of curatedCourses) this.#courses.set(item.id, structuredClone(item));
    }
  }

  // 직렬화한 상태는 초대·세션 토큰을 포함하므로 서버 저장소에서만 취급한다.
  exportSnapshot() {
    this.#prune();
    return {
      version: 1,
      rooms: [...this.#rooms].map(([id, room]) => {
        const { listeners, ...data } = room;
        return [id, structuredClone(data)];
      }),
      sessions: structuredClone([...this.#sessions]),
      invites: structuredClone([...this.#invites]),
      joinCodes: this.#permanentMemories ? [...this.#joinCodes].filter(([, v]) => v.expiresAt > Date.now()) : [],
      pairings: this.#permanentMemories ? [...this.#pairings].filter(([, v]) => v.expiresAt > Date.now()) : [],
      courses: structuredClone([...this.#courses].filter(([, c]) => !c.sample)),
      likes: [...this.#likes].map(([id, users]) => [id, [...users]]),
      reviews: [...this.#reviews].map(([id, byUser]) => [id, structuredClone([...byUser])]),
      activity: structuredClone([...this.#activity]),
    };
  }

  static fromSnapshot(snapshot, { permanentMemories = false, sampleCourses = false } = {}) {
    if (!snapshot || snapshot.version !== 1 || !['rooms', 'sessions', 'invites', 'courses'].every(k => Array.isArray(snapshot[k]))) throw new Error('저장된 가족방 데이터를 읽을 수 없습니다');
    const result = new Rooms({permanentMemories, sampleCourses});
    result.#rooms = new Map(snapshot.rooms.map(([id, room]) => [id, { ...structuredClone(room), listeners: new Set() }]));
    result.#sessions = new Map(structuredClone(snapshot.sessions));
    result.#invites = new Map(structuredClone(snapshot.invites));
    result.#joinCodes = new Map((snapshot.joinCodes || []).filter(([, v]) => v.expiresAt > Date.now()));
    result.#pairings = new Map((snapshot.pairings || []).filter(([, v]) => v.expiresAt > Date.now()));
    // 예시·참고 코스는 코드에서 다시 채우고(위 생성자), 보관된 가족 공유 코스를 그 위에 더한다.
    for (const [id, course] of structuredClone(snapshot.courses)) if (!course.sample) result.#courses.set(id, course);
    // 좋아요·후기·최근 활동은 나중에 추가된 항목이라 예전 저장 파일에는 없을 수 있다.
    if (Array.isArray(snapshot.likes)) result.#likes = new Map(snapshot.likes.map(([id, users]) => [id, new Set(users)]));
    if (Array.isArray(snapshot.reviews)) result.#reviews = new Map(snapshot.reviews.map(([id, entries]) => [id, new Map(structuredClone(entries))]));
    if (Array.isArray(snapshot.activity)) result.#activity = new Map(structuredClone(snapshot.activity));
    result.#prune();
    return result;
  }

  create(name) {
    this.#prune();
    if (this.#rooms.size >= 100) throw new Error('현재 만들 수 있는 가족방이 가득 찼습니다');
    const room = { id: randomUUID(), name: clean(name, 60) || '가족 여행', members: [], proposals: [], route: null, routeVersion: 0, preferences: defaultPreferences(), destination: null, listeners: new Set(), expiresAt: Date.now() + ROOM_TTL_MS };
    const invite = token(), driverToken = token();
    this.#rooms.set(room.id, room);
    this.#invites.set(invite, room.id);
    this.#sessions.set(driverToken, { roomId: room.id, role: 'driver', name: '운전자' });
    return { roomId: room.id, invite, driverToken };
  }

  join(invite, name) {
    this.#prune();
    const room = this.#rooms.get(this.#invites.get(invite));
    const memberName = clean(name, 30);
    if (!room || !memberName) throw new Error('초대 링크와 이름을 확인해 주세요');
    if (room.members.length >= 20) throw new Error('가족방 인원이 가득 찼습니다');
    const member = { id: randomUUID(), name: memberName };
    const memberToken = token();
    room.members.push(member); this.#touch(room);
    this.#sessions.set(memberToken, { roomId: room.id, role: 'member', ...member });
    this.#notify(room);
    return { memberToken, roomId: room.id };
  }

  inviteCode(auth) {
    const member = this.session(auth);
    for (const [key, value] of this.#joinCodes) if (value.expiresAt <= Date.now()) this.#joinCodes.delete(key);
    const old = [...this.#joinCodes].find(([,v]) => v.roomId === member.roomId);
    if (old) return {code:old[0],expiresAt:old[1].expiresAt};
    const invite = [...this.#invites].find(([,id]) => id === member.roomId)?.[0];
    if (!invite) throw new Error('가족방을 다시 확인해 주세요');
    let code; do { code=randomBytes(5).toString('hex').toUpperCase(); } while(this.#joinCodes.has(code));
    const expiresAt=Date.now()+600000;
    this.#joinCodes.set(code,{invite,roomId:member.roomId,expiresAt});
    return {code,expiresAt};
  }
  joinWithCode(code,name) {
    const key=typeof code==='string'?code.replace(/[\s-]/g,'').toUpperCase():'';
    const entry=this.#joinCodes.get(key);
    if(!entry || entry.expiresAt<=Date.now()) throw new Error('초대 코드가 만료됐거나 올바르지 않아요. 가족에게 새 코드를 요청해 주세요.');
    return {...this.join(entry.invite,name),invite:entry.invite};
  }

  pairing(auth) {
    const member = this.session(auth);
    if (member.role !== 'member') throw new Error('가족방에서 연결 코드를 만들어 주세요');
    const driver = [...this.#sessions].find(([, s]) => s.roomId === member.roomId && s.role === 'driver');
    const invite = [...this.#invites].find(([, id]) => id === member.roomId);
    if (!driver || !invite) throw new Error('가족방을 다시 확인해 주세요');
    for (const [key, value] of this.#pairings) if (value.expiresAt <= Date.now() || value.roomId === member.roomId) this.#pairings.delete(key);
    // 운전자에게 소리 내어 읽어 주기 쉽도록 숫자 6자리로 만든다. 10분 동안 한 번만 쓸 수 있다.
    let code; do { code = String(randomInt(0, 1000000)).padStart(6, '0'); } while (this.#pairings.has(code));
    const expiresAt = Date.now() + 10 * 60 * 1000;
    this.#pairings.set(code, {roomId:member.roomId, driverToken:driver[0], invite:invite[0], expiresAt});
    return {code, expiresAt};
  }

  claimPairing(code) {
    const key = typeof code === 'string' ? code.replace(/[\s-]/g, '').toUpperCase() : '';
    const pair = this.#pairings.get(key);
    if (!pair || pair.expiresAt <= Date.now()) { this.#pairings.delete(key); fail(404, '연결 코드를 다시 확인해 주세요'); }
    this.session(pair.driverToken);
    this.#pairings.delete(key);
    const linked = this.#rooms.get(pair.roomId); if (linked) { linked.carLinkedAt = Date.now(); this.#notify(linked); }
    return {invite:pair.invite, driverToken:pair.driverToken};
  }

  session(auth) {
    this.#prune();
    const session = this.#sessions.get(auth);
    if (!session) fail(401, '가족방에 다시 참여해 주세요');
    return session;
  }

  state(auth) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const trips = this.#tripsOf(room), active = this.#activeTrip(room);
    const photos = room.photos || [];
    return { room: { id: room.id, name: room.name, expiresAt: room.expiresAt < 8640000000000000 ? room.expiresAt : null, carLinkedAt: room.carLinkedAt || null }, me: { id: s.id ?? null, name: s.name, role: s.role }, archiveAvailable: this.#permanentMemories, archived: !!room.archived, members: room.members, proposals: room.proposals, route: room.route, completedTrip:room.completedTrip||null,
      photos: photos.map(({id,memberId,memberName,createdAt,tripId})=>({id,memberId,memberName,createdAt,tripId:tripId||null})),
      // 가족방 안의 여행 묶음. 최근 여행이 앞에 온다. 사진은 여행별로 나뉜다.
      currentTripId: active?.id || null,
      trips: trips.slice().reverse().map(t => ({ id: t.id, status: t.status, title: t.title || (t.destination ? t.destination.name + ' 여행' : '준비 중인 여행'), destination: t.destination || null, waypoints: t.waypoints || [], fromCourse: t.fromCourse || null, startedAt: t.startedAt || null, finishedAt: t.finishedAt || null, choice: t.choice || null, photoCount: photos.filter(p => p.tripId === t.id).length })),
      preferences: structuredClone(room.preferences ?? defaultPreferences()), itinerary: room.route ? { version:room.routeVersion, destination: room.destination, waypoints: this.#waypoints(room).map(p => ({...p.place, proposalId:p.id})), fromCourse: active?.fromCourse || null } : null };
  }

  // ── 여행 묶음 ──
  // 예전 저장 데이터에는 trips가 없으므로, 마지막으로 끝낸 여행이 있으면 그것을 첫 묶음으로 삼는다.
  #tripsOf(room) {
    if (!Array.isArray(room.trips)) room.trips = room.completedTrip ? [{ ...structuredClone(room.completedTrip), status: 'DONE', startedAt: room.completedTrip.finishedAt }] : [];
    return room.trips;
  }
  #activeTrip(room) { return this.#tripsOf(room).find(t => t.status === 'ACTIVE') || null; }
  // 경로가 처음 생기면 새 여행 묶음을 연다.
  #openTrip(room) {
    let trip = this.#activeTrip(room);
    if (!trip) {
      trip = { id: randomUUID(), status: 'ACTIVE', startedAt: Date.now(), fromCourse: null };
      const trips = this.#tripsOf(room); trips.push(trip);
      // 오래된 여행은 사진과 함께 정리한다(메모리 보호).
      while (trips.length > MAX_TRIPS) { const old = trips.shift(); room.photos = (room.photos || []).filter(p => p.tripId !== old.id); }
    }
    return trip;
  }
  #findTrip(room, tripId) {
    const trip = this.#tripsOf(room).find(t => t.id === tripId);
    if (!trip) fail(404, '여행 기록을 찾을 수 없어요');
    return trip;
  }
  /** 추억 저장에 쓰는 여행. tripId가 없으면 마지막으로 끝낸 여행이다. */
  tripForMemory(auth, tripId) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    if (!tripId) return this.completedTrip(auth);
    const trip = this.#findTrip(room, tripId);
    if (trip.status !== 'DONE') fail(409, '여행을 마친 뒤에 추억으로 저장할 수 있어요');
    return structuredClone({ id: trip.id, destination: trip.destination, waypoints: trip.waypoints, finishedAt: trip.finishedAt, fromCourse: trip.fromCourse || null });
  }

  // tripId를 주면 그 여행 묶음에, 없으면 지금 여행(없으면 '여행 전 사진')에 담는다.
  addPhoto(auth, data, tripId = null) {
    const s=this.session(auth), room=this.#rooms.get(s.roomId);
    if(s.role!=='member') fail(403, '가족으로 참여한 뒤 사진을 올려 주세요');
    const target = tripId ? this.#findTrip(room, tripId).id : this.#activeTrip(room)?.id || null;
    if(typeof data!=='string' || data.length>350000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) throw new Error('사진 크기나 형식을 확인해 주세요');
    const bytes=Buffer.from(data,'base64');
    if(bytes.length>250000 || bytes.length<4 || bytes[0]!==0xff || bytes[1]!==0xd8 || bytes[2]!==0xff) throw new Error('사진을 다시 선택해 주세요');
    room.photos ||= [];
    if(room.photos.filter(p => (p.tripId || null) === target).length>=PHOTOS_PER_TRIP) fail(409, '한 여행에는 사진 8장까지 올릴 수 있어요');
    const photo={id:randomUUID(),memberId:s.id,memberName:s.name,createdAt:Date.now(),data,tripId:target};
    room.photos.push(photo);this.#touch(room);this.#notify(room);
    return {id:photo.id,memberName:photo.memberName,createdAt:photo.createdAt,tripId:target};
  }

  getPhoto(auth,id) {
    const s=this.session(auth), photo=(this.#rooms.get(s.roomId).photos||[]).find(p=>p.id===id);
    if(!photo) fail(404, '사진을 찾을 수 없어요');
    return {data:photo.data,contentType:'image/jpeg'};
  }

  deletePhoto(auth,id) {
    const s=this.session(auth),room=this.#rooms.get(s.roomId),photo=(room.photos||[]).find(p=>p.id===id);
    if(!photo) fail(404, '사진을 찾을 수 없어요');
    if(s.role!=='driver' && photo.memberId!==s.id) fail(403, '본인이 올린 사진만 지울 수 있어요');
    room.photos=room.photos.filter(p=>p.id!==id);this.#notify(room);
  }

  finishTrip(auth,choice) {
    const s=this.session(auth),room=this.#rooms.get(s.roomId);
    if(s.role!=='driver') fail(403, '운전자만 안내를 끝낼 수 있어요');
    if(!room.route || !room.destination) fail(409, '종료할 여행 경로가 없어요');
    // REVIEW: 추천받은 코스로 다녀온 여행이라 공유 대신 좋아요·후기를 남긴다.
    if(!['MEMORY','SHARE','REVIEW','SKIP'].includes(choice)) throw new Error('여행을 마칠 방법을 선택해 주세요');
    // 지금 여행 묶음을 끝난 여행으로 닫는다. 그냥 끝내도 가족방의 여행 기록에는 남는다.
    const trip = this.#openTrip(room), now = Date.now();
    if (trip.fromCourse && choice === 'SHARE') choice = 'REVIEW';
    Object.assign(trip, { status: 'DONE', title: room.destination.name + ' 여행', destination: structuredClone(room.destination), waypoints: this.#waypoints(room).map(p => structuredClone(p.place)), preferences: structuredClone(room.preferences), finishedAt: now, choice });
    // 이 기능 전에 올라와 묶음 정보가 없는 사진은 이 여행에 담는다(예전 데이터 호환).
    for (const p of room.photos || []) if (p.tripId === undefined && p.createdAt >= (trip.startedAt || 0)) p.tripId = trip.id;
    if (choice !== 'SKIP') room.completedTrip={id:trip.id,destination:trip.destination,waypoints:trip.waypoints,preferences:trip.preferences,finishedAt:now,choice,fromCourse:trip.fromCourse||null};
    if (choice !== 'SKIP' && this.#permanentMemories) { room.expiresAt = 8640000000000000; room.archived = true; }
    // 끝난 여행의 들르는 곳이 다음 여행 경로에 다시 붙지 않게 한다.
    for (const p of room.proposals) if (p.status === 'ACCEPTED' && p.type === 'WAYPOINT') p.status = 'FINISHED';
    this.#touch(room);
    room.route=null;room.destination=null;room.routeVersion++;this.#notify(room);
    return structuredClone(room.completedTrip);
  }

  // 여행 중이거나 막 끝낸 방이 중간에 사라지지 않도록 만료 시간을 다시 12시간 뒤로 미룬다.
  #touch(room) { if (room.expiresAt < 8640000000000000) room.expiresAt = Date.now() + ROOM_TTL_MS; }

  completedTrip(auth) {
    const s=this.session(auth),trip=this.#rooms.get(s.roomId).completedTrip;
    if(!trip) fail(409, '먼저 차량에서 안내 종료를 눌러 주세요');
    return structuredClone(trip);
  }

  itineraryContext(auth, ids, expectedVersion = null) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    if (s.role !== 'driver' || !room.destination || !room.route) throw new Error('운전자만 경유지를 변경할 수 있습니다');
    if (expectedVersion !== null && expectedVersion !== room.routeVersion) throw new Error('경로가 변경됐어요. 다시 확인해 주세요');
    if (!Array.isArray(ids) || ids.length > 5 || ids.some(id => typeof id !== 'string') || new Set(ids).size !== ids.length) throw new Error('경유지 순서를 확인해 주세요');
    const existing = this.#waypoints(room), selected = ids.map(id => existing.find(p => p.id === id));
    if (selected.some(p => !p)) throw new Error('경유지가 변경됐어요. 다시 확인해 주세요');
    return {version:room.routeVersion, origin:{latitude:37.5133,longitude:127.1002}, destination:room.destination, waypoints:selected.map(p => p.place), ids};
  }

  applyItinerary(auth, version, ids, route) {
    const context = this.itineraryContext(auth, ids);
    if (context.version !== version) throw new Error('경로가 변경됐어요. 다시 확인해 주세요');
    const room = this.#rooms.get(this.session(auth).roomId);
    for (const p of this.#waypoints(room)) {
      const index = ids.indexOf(p.id);
      if (index < 0) p.status = 'REMOVED'; else p.waypointOrder = index;
    }
    room.route = {...route, destinationName:room.destination.name}; room.routeVersion++; this.#notify(room);
  }

  setPreferences(auth, input) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const preferences = validatedPreferences(input);
    room.preferences = preferences; this.#notify(room);
    return structuredClone(preferences);
  }

  // exclude: 공개하지 않을 장소의 순서 번호(0부터, 마지막이 도착지). 집·숙소처럼 알리고 싶지 않은 곳을 뺀다.
  // tripId: 가족방의 여행 묶음 중 공유할 여행. 없으면 source(지금 경로 / 마지막으로 끝낸 여행)를 쓴다.
  publishCourse(auth, title, description, source = 'current', exclude = [], tripId = null) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const courseTitle = clean(title, 80);
    if(!['current','completed'].includes(source)) throw new Error('공유할 여행을 다시 확인해 주세요');
    const picked = tripId ? this.#findTrip(room, tripId) : null;
    if (picked && picked.status !== 'DONE') fail(409, '여행을 마친 뒤에 공유할 수 있어요');
    const trip = picked || (source==='completed' ? room.completedTrip : room.route ? {destination:room.destination,waypoints:this.#waypoints(room).map(p=>p.place),preferences:room.preferences,fromCourse:this.#activeTrip(room)?.fromCourse} : room.completedTrip);
    if (!courseTitle || !trip) throw new Error('제목과 완성된 가족 경로가 필요합니다');
    // 남이 올린 코스를 그대로 다시 올리지 않게 한다. 다녀온 코스에는 좋아요나 후기를 남긴다.
    if (trip.fromCourse) fail(409, '추천받은 코스로 다녀온 여행은 공유하지 않아요. 그 코스에 좋아요나 후기를 남겨 주세요');
    if (!Array.isArray(exclude) || exclude.some(i => !Number.isInteger(i))) throw new Error('공개할 장소를 다시 골라 주세요');
    const places = [...trip.waypoints, trip.destination].filter((_, i) => !exclude.includes(i));
    if (!places.length) throw new Error('공개할 장소를 한 곳 이상 남겨 주세요');
    const mine = [...this.#courses.values()].filter(c => c.ownerRoomId === room.id);
    if (mine.length >= 3) throw new Error('한 가족방에서는 코스를 3개까지 공유할 수 있어요. 먼저 올린 코스를 내려 주세요');
    const key = list => list.map(p => p.latitude.toFixed(5) + ',' + p.longitude.toFixed(5)).join('|');
    if (mine.some(c => key([...c.waypoints, c.destination]) === key(places))) throw new Error('같은 장소로 이미 공유한 코스가 있어요');
    if ([...this.#courses.values()].filter(c => !c.sample).length >= 90) throw new Error('공유 코스 한도에 도달했습니다');
    const course = { id: randomUUID(), title: courseTitle, description: clean(description, 300), destination: structuredClone(places.at(-1)), waypoints: structuredClone(places.slice(0, -1)), preferences: structuredClone(trip.preferences ?? defaultPreferences()), createdAt: Date.now(), ownerRoomId: room.id };
    this.#courses.set(course.id, course);
    return this.#summary(course, Date.now(), room.id);
  }

  // 올린 가족방의 사람만 코스를 내릴 수 있다. 좋아요·후기·인기 기록도 함께 지운다.
  unpublishCourse(auth, id) {
    const s = this.session(auth), course = this.#courses.get(id);
    if (!course) fail(404, '공유 코스를 찾을 수 없습니다');
    if (course.sample || course.ownerRoomId !== s.roomId) fail(403, '이 가족방에서 올린 코스만 내릴 수 있어요');
    this.#courses.delete(id); this.#likes.delete(id); this.#reviews.delete(id); this.#activity.delete(id);
  }

  // 신고는 로그인한 계정당 한 번. 서로 다른 3명이 신고하면 목록에서 숨긴다.
  reportCourse(userId, id) {
    const course = this.#courses.get(id);
    if (!course || course.sample) throw new Error('신고할 수 없는 코스예요');
    course.reports = [...new Set([...(course.reports || []), userId])];
    if (course.reports.length >= 3) course.hidden = true;
    return { reported: true, hidden: !!course.hidden };
  }

  courses(auth, query = '') {
    this.session(auth);
    return this.publicCourses(query);
  }

  // 최근 7일의 좋아요(1)·후기(2)·실제 길 안내에 쓰임(3)을 더하고, 이틀마다 절반으로 줄인다.
  #bump(id, weight, key) {
    const now = Date.now(), events = (this.#activity.get(id) || []).filter(e => now - e.t <= TREND_WINDOW_MS);
    if (events.some(e => e.k === key)) { this.#activity.set(id, events); return; }
    events.push({ t: now, w: weight, k: key });
    this.#activity.set(id, events.slice(-300));
  }
  #unbump(id, key) {
    const events = this.#activity.get(id);
    if (events) this.#activity.set(id, events.filter(e => e.k !== key));
  }
  #trend(id, now = Date.now()) {
    let score = 0;
    for (const e of this.#activity.get(id) || []) if (now - e.t <= TREND_WINDOW_MS) score += e.w * 0.5 ** ((now - e.t) / TREND_HALF_LIFE_MS);
    return Math.round(score * 100) / 100;
  }
  // 공개 목록에 내보내는 모양. 올린 가족방과 신고 기록은 내보내지 않는다. 여행 취향 태그는 코스를 고르는 데 쓰여 함께 공개된다.
  #summary(c, now = Date.now(), roomId = null) {
    const reviews = [...(this.#reviews.get(c.id)?.values() || [])];
    const rating = reviews.length ? Math.round(reviews.reduce((sum, r) => sum + r.rating, 0) / reviews.length * 10) / 10 : null;
    const { ownerRoomId, reports, hidden, ...shown } = c;
    const trendScore = this.#trend(c.id, now);
    return { ...shown, likes: this.#likes.get(c.id)?.size || 0, reviewCount: reviews.length, rating, trendScore, trending: trendScore >= TREND_BADGE_SCORE, region: regionOf(c.destination), official: !!c.curated, mine: roomId !== null && ownerRoomId === roomId };
  }

  // sort: popular(좋아요순, 기본) | trending(요즘 인기) | new(새로 올라온). kind: community(가족 공유) | official(관광 안내 참고).
  publicCourses(query = '', { sort = 'popular', kind = '', roomId = null } = {}) {
    const q = clean(query, 80).toLocaleLowerCase(), now = Date.now();
    const base = (a, b) => b.likes - a.likes || Number(!!a.sample) - Number(!!b.sample) || b.createdAt - a.createdAt;
    const order = sort === 'trending' ? (a, b) => b.trendScore - a.trendScore || base(a, b) : sort === 'new' ? (a, b) => Number(!!a.sample) - Number(!!b.sample) || b.createdAt - a.createdAt : base;
    return [...this.#courses.values()]
      .filter(c => !c.hidden || (roomId !== null && c.ownerRoomId === roomId))
      .filter(c => kind === 'official' ? !!c.curated : kind === 'community' ? !c.sample : true)
      .filter(c => !q || [c.title, c.description, c.destination.name, c.destination.address || '', ...c.waypoints.flatMap(p => [p.name, p.address || ''])].some(v => v.toLocaleLowerCase().includes(q)))
      .map(c => this.#summary(c, now, roomId)).sort(order);
  }
  /** 가족방 토큰이 유효하면 그 방의 id, 아니면 null. 공개 목록에서 '내가 올린 코스'를 표시하는 데 쓴다. */
  roomIdOf(auth) { return this.#sessions.get(auth)?.roomId ?? null; }

  // 요즘 앱 안에서 활동이 많은 지역과 코스. 앱 밖의 실제 관광객 수가 아니다.
  trending() {
    const all = this.publicCourses('', { sort: 'trending' }), regions = new Map();
    for (const c of all) {
      if (!c.region) continue;
      const r = regions.get(c.region) || { region: c.region, score: 0, courses: 0, known: false };
      r.score = Math.round((r.score + c.trendScore) * 100) / 100; r.courses++; r.known ||= !!c.sample; regions.set(c.region, r);
    }
    return { regions: [...regions.values()].filter(r => r.known || r.courses >= 2).map(({ known, ...r }) => r).sort((a, b) => b.score - a.score || b.courses - a.courses).slice(0, 8), courses: all.filter(c => c.trending).slice(0, 5) };
  }

  // 후기는 계정당 코스별 하나. 다시 쓰면 고쳐진다. 화면에는 이름만 보이고 계정 정보는 내보내지 않는다.
  reviewCourse(userId, id, input) {
    const course = this.#courses.get(id);
    if (!course) fail(404, '공유 코스를 찾을 수 없습니다');
    if (course.sample && !course.curated) fail(403, '시연 예시 코스에는 후기를 남길 수 없어요');
    const rating = input?.rating, text = clean(input?.text, 300), typed = clean(input?.name, 20);
    const name = !typed || RESERVED_NAME.test(typed) ? '여행자' : typed;
    if (!Number.isInteger(rating) || rating < 1 || rating > 5) throw new Error('별점을 1점부터 5점 사이로 골라 주세요');
    let byUser = this.#reviews.get(id); if (!byUser) { byUser = new Map(); this.#reviews.set(id, byUser); }
    const existing = byUser.get(userId);
    if (!existing && byUser.size >= 200) throw new Error('이 코스의 후기 한도에 도달했어요');
    byUser.set(userId, { name, rating, text, createdAt: existing?.createdAt || Date.now(), updatedAt: Date.now() });
    this.#bump(id, 2, 'review:' + userId);
    return this.courseReviews(id, userId);
  }
  deleteReview(userId, id) {
    this.#reviews.get(id)?.delete(userId); this.#unbump(id, 'review:' + userId);
    return this.courseReviews(id, userId);
  }
  courseReviews(id, userId = null) {
    const course = this.#courses.get(id);
    if (!course) fail(404, '공유 코스를 찾을 수 없습니다');
    const entries = [...(this.#reviews.get(id) || [])].sort((a, b) => b[1].updatedAt - a[1].updatedAt);
    const summary = this.#summary(course);
    return { rating: summary.rating, count: summary.reviewCount, reviews: entries.map(([user, r]) => ({ name: r.name, rating: r.rating, text: r.text, createdAt: r.createdAt, mine: userId !== null && user === userId })) };
  }

  // 가족이 공유한 코스와 관광 안내를 참고한 코스에 좋아요를 누를 수 있다(시연용 예시 코스는 제외).
  setCourseLike(userId,id,liked) {
    const course=this.#courses.get(id);
    if(!course) fail(404, '공유 코스를 찾을 수 없습니다');
    if(course.sample && !course.curated) fail(403, '시연 예시 코스에는 좋아요를 누를 수 없어요');
    if(typeof liked!=='boolean') throw new Error('좋아요 상태를 확인해 주세요');
    let likes=this.#likes.get(id);if(!likes){likes=new Set();this.#likes.set(id,likes)}
    if(liked && !likes.has(userId) && likes.size>=1000) throw new Error('좋아요 한도에 도달했어요');
    if(liked) { likes.add(userId); this.#bump(id, 1, 'like:' + userId); } else { likes.delete(userId); this.#unbump(id, 'like:' + userId); }
    return {likes:likes.size,liked};
  }

  likedCourses(userId) { return [...this.#likes].filter(([,users])=>users.has(userId)).map(([id])=>id); }

  recommendedCourses(auth, latitude, longitude) {
    const session = this.session(auth), room = this.#rooms.get(session.roomId);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) throw new Error('목적지 좌표를 확인해 주세요');
    const radians = x => x * Math.PI / 180;
    const distance = p => {
      const dLat = radians(p.latitude - latitude), dLon = radians(p.longitude - longitude);
      const h = Math.sin(dLat / 2) ** 2 + Math.cos(radians(latitude)) * Math.cos(radians(p.latitude)) * Math.sin(dLon / 2) ** 2;
      return 12742 * Math.asin(Math.min(1, Math.sqrt(h)));
    };
    const desired = room.preferences ?? defaultPreferences();
    return [...this.#courses.values()].map(c => {
      const tagged = c.preferences ?? defaultPreferences();
      const matchedPreferences = { companions: desired.companions.filter(v => tagged.companions.includes(v)), styles: desired.styles.filter(v => tagged.styles.includes(v)), hours: desired.hours !== null && desired.hours === tagged.hours ? desired.hours : null };
      const matchCount = matchedPreferences.companions.length + matchedPreferences.styles.length + (matchedPreferences.hours === null ? 0 : 1);
      return { ...c, distanceKm: distance(c.destination), matchedPreferences, matchCount };
    }).filter(c => c.distanceKm <= 30).sort((a, b) => Number(!!a.sample) - Number(!!b.sample) || b.matchCount - a.matchCount || (b.uses || 0) - (a.uses || 0) || a.distanceKm - b.distanceKm).slice(0, 10);
  }

  destinationContext(auth, place) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const name = clean(place?.name, 100), latitude = place?.latitude, longitude = place?.longitude;
    if (s.role !== 'driver' || !name || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) throw new Error('목적지를 확인해 주세요');
    const destination = { name, latitude, longitude, address: clean(place.address, 200) };
    return { version: room.routeVersion, origin: {latitude:37.5133, longitude:127.1002}, destination, waypoints: [] };
  }

  applyDestination(auth, version, destination, route) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    if (s.role !== 'driver' || room.routeVersion !== version) throw new Error('경로가 변경됐어요. 다시 시도해 주세요');
    for (const p of room.proposals) if (p.status === 'ACCEPTED' && p.type === 'WAYPOINT') p.status = 'REPLACED';
    // 새 목적지를 직접 정하면 추천 코스와는 다른 여행이 된다.
    this.#openTrip(room).fromCourse = null;
    this.#touch(room);
    room.destination = destination; room.route = { ...route, destinationName: destination.name }; room.routeVersion++; this.#notify(room);
  }

  proposeSavedGroup(auth, title, places) {
    const name = clean(title, 80);
    if (!name || !Array.isArray(places) || places.length < 1 || places.length > 5) throw new Error('묶음 이름과 장소 1~5곳을 선택해 주세요');
    const validated = places.map(place => {
      const name = clean(place?.name, 100), latitude = place?.latitude, longitude = place?.longitude;
      if (!name || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) throw new Error('장소와 좌표를 확인해 주세요');
      return {name, latitude, longitude, address:clean(place.address, 200)};
    });
    const destination = validated[validated.length-1];
    const proposal = this.propose(auth, {...destination, name}, name + ' 묶음 제안', 'DESTINATION');
    proposal.type = 'COURSE';
    proposal.course = {id:null, title:name, destination, waypoints:validated.slice(0,-1), mode:'ADD', optimize:true};
    return proposal;
  }

  proposeCourse(auth, id, allowDriver = false) {
    const course = this.#courses.get(id);
    if (!course) fail(404, '공유 코스를 찾을 수 없습니다');
    const proposal = this.propose(auth, course.destination, course.title, 'DESTINATION', allowDriver);
    proposal.type = 'COURSE';
    proposal.course = structuredClone({ id: course.id, title: course.title, destination: course.destination, waypoints: course.waypoints, mode: 'ADD' });
    return proposal;
  }

  cancelDriverCourse(auth, id) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    if (s.role !== 'driver') throw new Error('운전자만 취소할 수 있습니다');
    const index = room.proposals.findIndex(p => p.id === id && p.type === 'COURSE' && p.status === 'PENDING' && p.member.id == null);
    if (index >= 0) { room.proposals.splice(index, 1); this.#notify(room); }
  }

  routeContext(auth, id) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const proposal = room.proposals.find(p => p.id === id);
    if (s.role !== 'driver' || !proposal || proposal.status !== 'PENDING') throw new Error('운전자만 경로를 변경할 수 있습니다');
    const accepted = this.#waypoints(room);
    if (proposal.type === 'WAYPOINT' && accepted.length >= 5) throw new Error('경유지는 5곳까지 선택할 수 있습니다');
    const addCourse = proposal.type === 'COURSE' && proposal.course.mode === 'ADD';
    const destination = addCourse ? room.destination : proposal.type === 'COURSE' ? proposal.course.destination : proposal.type === 'DESTINATION' ? proposal.place : room.destination;
    if (!destination) throw new Error('먼저 목적지를 정해 주세요');
    let waypoints = proposal.type === 'COURSE' ? proposal.course.waypoints : proposal.type === 'DESTINATION' ? accepted.map(p => p.place) : [...accepted.map(p => p.place), proposal.place];
    if (addCourse) {
      waypoints = [...accepted.map(p => p.place)];
      for (const p of [...proposal.course.waypoints, proposal.course.destination]) {
        const same = x => Math.abs(x.latitude - p.latitude) < 0.00001 && Math.abs(x.longitude - p.longitude) < 0.00001;
        if (!same(destination) && !waypoints.some(same)) waypoints.push(p);
      }
      if (waypoints.length > 5) throw new Error('경유지는 총 5곳까지 추가할 수 있습니다');
      if (proposal.course.optimize) {
        const fixed = accepted.map(p => p.place), additions = waypoints.slice(fixed.length);
        const start = fixed.at(-1) || {latitude:37.5133, longitude:127.1002};
        const distance = (a,b) => { const rad=x=>x*Math.PI/180, dy=rad(b.latitude-a.latitude), dx=rad(b.longitude-a.longitude); const h=Math.sin(dy/2)**2+Math.cos(rad(a.latitude))*Math.cos(rad(b.latitude))*Math.sin(dx/2)**2; return 2*Math.asin(Math.min(1,Math.sqrt(h))); };
        let best = additions, bestDistance = Infinity;
        const visit = (path, left, length) => {
          if (!left.length) { const total=length+distance(path.at(-1)||start,destination); if(total<bestDistance){bestDistance=total;best=path} return; }
          for (let i=0;i<left.length;i++) visit([...path,left[i]], [...left.slice(0,i),...left.slice(i+1)], length+distance(path.at(-1)||start,left[i]));
        };
        visit([],additions,0); waypoints=[...fixed,...best];
      }
    }
    if (proposal.type === 'COURSE' && proposal.course.optimize && !addCourse) {
      const start={latitude:37.5133,longitude:127.1002};
      const distance=(a,b)=>{const r=x=>x*Math.PI/180,dy=r(b.latitude-a.latitude),dx=r(b.longitude-a.longitude),h=Math.sin(dy/2)**2+Math.cos(r(a.latitude))*Math.cos(r(b.latitude))*Math.sin(dx/2)**2;return 2*Math.asin(Math.min(1,Math.sqrt(h)))};
      let best=waypoints,bestDistance=Infinity;
      const visit=(path,left,length)=>{if(!left.length){const total=length+distance(path.at(-1)||start,destination);if(total<bestDistance){bestDistance=total;best=path}return}for(let i=0;i<left.length;i++)visit([...path,left[i]],[...left.slice(0,i),...left.slice(i+1)],length+distance(path.at(-1)||start,left[i]))};
      visit([],waypoints,0); waypoints=best;
    }
    return { version: room.routeVersion, origin: {latitude: 37.5133, longitude: 127.1002}, destination, destinationName: destination.name, waypoints };
  }

  applyRoute(auth, id, version, route) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const proposal = room.proposals.find(p => p.id === id);
    if (s.role !== 'driver' || !proposal || proposal.status !== 'PENDING' || room.routeVersion !== version) throw new Error('경로가 변경됐어요. 다시 시도해 주세요');
    if (proposal.type === 'COURSE') {
      const add = proposal.course.mode === 'ADD';
      if (!add) for (const p of room.proposals) if (p.status === 'ACCEPTED' && p.type === 'WAYPOINT') p.status = 'REPLACED';
      const places = proposal.course.optimize ? this.routeContext(auth, id).waypoints : add ? [...proposal.course.waypoints, proposal.course.destination] : proposal.course.waypoints;
      for (const place of places) {
        const same = x => Math.abs(x.latitude - place.latitude) < 0.00001 && Math.abs(x.longitude - place.longitude) < 0.00001;
        if (add && (same(room.destination) || room.proposals.some(p => p.status === 'ACCEPTED' && p.type === 'WAYPOINT' && same(p.place)))) continue;
        room.proposals.push({ id: randomUUID(), type: 'WAYPOINT', member: proposal.member, place, message: proposal.course.title, status: 'ACCEPTED', waypointOrder:this.#waypoints(room).length, votes: {}, createdAt: Date.now() });
      }
      if (!add) room.destination = proposal.course.destination;
      const shared = this.#courses.get(proposal.course.id);
      if (shared) {
        shared.uses = (shared.uses || 0) + 1; this.#bump(shared.id, 3, 'use:' + room.id);
        // 추천받은 코스로 가는 여행임을 기록한다. 이 여행은 다시 공유하지 않고 좋아요·후기를 남긴다.
        this.#openTrip(room).fromCourse = { id: shared.id, title: shared.title };
      }
    }
    this.#openTrip(room);
    if (proposal.type === 'WAYPOINT') proposal.waypointOrder = this.#waypoints(room).length;
    proposal.status = 'ACCEPTED'; if (proposal.type === 'DESTINATION') room.destination = proposal.place;
    this.#touch(room);
    room.route = { ...route, destinationName: room.destination.name }; room.routeVersion++; this.#notify(room);
  }

  propose(auth, place, message, type = 'WAYPOINT', allowDriver = false) {
    const s = this.session(auth);
    if (s.role !== 'member' && !(allowDriver && s.role === 'driver')) throw new Error('가족만 제안할 수 있습니다');
    const room = this.#rooms.get(s.roomId);
    if (room.proposals.length >= 200) throw new Error('제안 한도에 도달했습니다');
    if (!['WAYPOINT','DESTINATION'].includes(type)) throw new Error('제안 종류를 확인해 주세요');
    const name = clean(place?.name, 100), lat = place?.latitude, lon = place?.longitude;
    if (!name || typeof lat !== 'number' || typeof lon !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new Error('장소와 좌표를 확인해 주세요');
    // 신호가 약해 같은 버튼을 다시 눌러도 같은 제안이 두 번 올라가지 않게 한다.
    const same = room.proposals.find(p => p.status === 'PENDING' && p.type === type && !p.course && p.member.id === s.id && p.place.latitude === lat && p.place.longitude === lon);
    if (same && !allowDriver) return same;
    const proposal = { id: randomUUID(), type, member: { id: s.id, name: s.name }, place: { name, latitude: lat, longitude: lon, address: clean(place.address, 200) }, message: clean(message, 200), status: 'PENDING', votes: {}, createdAt: Date.now() };
    this.#touch(room);
    room.proposals.push(proposal); this.#notify(room); return proposal;
  }

  vote(auth, id, choice) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const proposal = room.proposals.find((p) => p.id === id);
    if (s.role !== 'member' || !proposal || proposal.status !== 'PENDING' || !['YES', 'NO'].includes(choice)) throw new Error('투표할 수 없는 제안입니다');
    proposal.votes[s.id] = choice; this.#touch(room); this.#notify(room);
  }

  decide(auth, id, decision) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const proposal = room.proposals.find((p) => p.id === id);
    if (s.role !== 'driver' || !proposal || proposal.status !== 'PENDING' || decision !== 'DECLINED') throw new Error('운전자만 대기 중인 제안을 거절할 수 있습니다');
    proposal.status = decision; this.#notify(room);
  }

  subscribe(auth, callback) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    room.listeners.add(callback);
    return () => room.listeners.delete(callback);
  }
  #notify(room) { for (const callback of room.listeners) callback(); }
  #prune() {
    const expired = new Set();
    for (const [id, room] of this.#rooms) if (room.expiresAt <= Date.now()) { expired.add(id); this.#rooms.delete(id); }
    if (!expired.size) return;
    for (const [invite, id] of this.#invites) if (expired.has(id)) this.#invites.delete(invite);
    for (const [key, session] of this.#sessions) if (expired.has(session.roomId)) this.#sessions.delete(key);
  }
}
