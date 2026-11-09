import { randomBytes, randomUUID } from 'node:crypto';

const token = () => randomBytes(24).toString('base64url');
const clean = (value, limit) => typeof value === 'string' ? [...value.trim()].slice(0, limit).join('') : '';
const ROOM_TTL_MS = 12 * 60 * 60 * 1000;
const COMPANIONS = new Set(['CHILD', 'PARENTS', 'PET']);
const STYLES = new Set(['FOOD', 'RELAX', 'ACTIVITY']);
const defaultPreferences = () => ({ companions: [], styles: [], hours: null });

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
  #pairings = new Map();

  #waypoints(room) {
    return room.proposals.filter(p => p.status === 'ACCEPTED' && p.type === 'WAYPOINT')
      .map((p, index) => ({ p, index })).sort((a,b) => (a.p.waypointOrder ?? a.index) - (b.p.waypointOrder ?? b.index)).map(x => x.p);
  }

  constructor({ sampleCourses = false } = {}) {
    if (sampleCourses) {
      const destination = { name:'부산역', latitude:35.115, longitude:129.042, address:'부산광역시' };
      const examples = [
        { id:'sample-busan-sea', title:'부산 바다와 시장 여행', description:'시연을 위한 예시 코스입니다.', waypoints:[{name:'광안리',latitude:35.153,longitude:129.118,address:'부산광역시'},{name:'자갈치시장',latitude:35.097,longitude:129.030,address:'부산광역시'}], preferences:{companions:['PARENTS'],styles:['FOOD','RELAX'],hours:4} },
        { id:'sample-busan-relax', title:'부산 바다에서 천천히', description:'시연을 위한 예시 코스입니다.', waypoints:[{name:'광안리',latitude:35.153,longitude:129.118,address:'부산광역시'}], preferences:{companions:[],styles:['RELAX'],hours:2} },
      ];
      for (const item of examples) this.#courses.set(item.id, {...item, destination, sample:true, createdAt:0});
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
      courses: structuredClone([...this.#courses]),
    };
  }

  static fromSnapshot(snapshot) {
    if (!snapshot || snapshot.version !== 1 || !['rooms', 'sessions', 'invites', 'courses'].every(k => Array.isArray(snapshot[k]))) throw new Error('저장된 가족방 데이터를 읽을 수 없습니다');
    const result = new Rooms();
    result.#rooms = new Map(snapshot.rooms.map(([id, room]) => [id, { ...structuredClone(room), listeners: new Set() }]));
    result.#sessions = new Map(structuredClone(snapshot.sessions));
    result.#invites = new Map(structuredClone(snapshot.invites));
    result.#courses = new Map(structuredClone(snapshot.courses));
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
    room.members.push(member);
    this.#sessions.set(memberToken, { roomId: room.id, role: 'member', ...member });
    this.#notify(room);
    return { memberToken, roomId: room.id };
  }

  pairing(auth) {
    const member = this.session(auth);
    if (member.role !== 'member') throw new Error('가족방에서 연결 코드를 만들어 주세요');
    const driver = [...this.#sessions].find(([, s]) => s.roomId === member.roomId && s.role === 'driver');
    const invite = [...this.#invites].find(([, id]) => id === member.roomId);
    if (!driver || !invite) throw new Error('가족방을 다시 확인해 주세요');
    for (const [key, value] of this.#pairings) if (value.expiresAt <= Date.now() || value.roomId === member.roomId) this.#pairings.delete(key);
    const code = randomBytes(5).toString('hex').toUpperCase();
    this.#pairings.set(code, {roomId:member.roomId, driverToken:driver[0], invite:invite[0], expiresAt:Date.now()+10*60*1000});
    return {code};
  }

  claimPairing(code) {
    const key = typeof code === 'string' ? code.trim().toUpperCase() : '';
    const pair = this.#pairings.get(key);
    if (!pair || pair.expiresAt <= Date.now()) { this.#pairings.delete(key); throw new Error('연결 코드를 다시 확인해 주세요'); }
    this.session(pair.driverToken);
    this.#pairings.delete(key);
    return {invite:pair.invite, driverToken:pair.driverToken};
  }

  session(auth) {
    this.#prune();
    const session = this.#sessions.get(auth);
    if (!session) throw new Error('가족방에 다시 참여해 주세요');
    return session;
  }

  state(auth) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    return { room: { id: room.id, name: room.name }, me: { id: s.id ?? null, name: s.name, role: s.role }, members: room.members, proposals: room.proposals, route: room.route, preferences: structuredClone(room.preferences ?? defaultPreferences()), itinerary: room.route ? { version:room.routeVersion, destination: room.destination, waypoints: this.#waypoints(room).map(p => ({...p.place, proposalId:p.id})) } : null };
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

  publishCourse(auth, title, description) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const courseTitle = clean(title, 80);
    if (!courseTitle || !room.route) throw new Error('제목과 완성된 가족 경로가 필요합니다');
    if (this.#courses.size >= 100) throw new Error('공유 코스 한도에 도달했습니다');
    const course = { id: randomUUID(), title: courseTitle, description: clean(description, 300), destination: structuredClone(room.destination), waypoints: this.#waypoints(room).map(p => structuredClone(p.place)), preferences: structuredClone(room.preferences ?? defaultPreferences()), createdAt: Date.now() };
    this.#courses.set(course.id, course);
    return course;
  }

  courses(auth, query = '') {
    this.session(auth);
    return this.publicCourses(query);
  }

  publicCourses(query = '') {
    const q = clean(query, 80).toLocaleLowerCase();
    return [...this.#courses.values()].filter(c => !q || [c.title, c.description, c.destination.name, c.destination.address || '', ...c.waypoints.flatMap(p => [p.name, p.address || ''])].some(v => v.toLocaleLowerCase().includes(q))).reverse();
  }

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
    room.destination = destination; room.route = { ...route, destinationName: destination.name }; room.routeVersion++; this.#notify(room);
  }

  proposeCourse(auth, id, allowDriver = false) {
    const course = this.#courses.get(id);
    if (!course) throw new Error('공유 코스를 찾을 수 없습니다');
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
      const places = add ? [...proposal.course.waypoints, proposal.course.destination] : proposal.course.waypoints;
      for (const place of places) {
        const same = x => Math.abs(x.latitude - place.latitude) < 0.00001 && Math.abs(x.longitude - place.longitude) < 0.00001;
        if (add && (same(room.destination) || room.proposals.some(p => p.status === 'ACCEPTED' && p.type === 'WAYPOINT' && same(p.place)))) continue;
        room.proposals.push({ id: randomUUID(), type: 'WAYPOINT', member: proposal.member, place, message: proposal.course.title, status: 'ACCEPTED', waypointOrder:this.#waypoints(room).length, votes: {}, createdAt: Date.now() });
      }
      if (!add) room.destination = proposal.course.destination;
      const shared = this.#courses.get(proposal.course.id); if (shared) shared.uses = (shared.uses || 0) + 1;
    }
    if (proposal.type === 'WAYPOINT') proposal.waypointOrder = this.#waypoints(room).length;
    proposal.status = 'ACCEPTED'; if (proposal.type === 'DESTINATION') room.destination = proposal.place;
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
    const proposal = { id: randomUUID(), type, member: { id: s.id, name: s.name }, place: { name, latitude: lat, longitude: lon, address: clean(place.address, 200) }, message: clean(message, 200), status: 'PENDING', votes: {}, createdAt: Date.now() };
    room.proposals.push(proposal); this.#notify(room); return proposal;
  }

  vote(auth, id, choice) {
    const s = this.session(auth), room = this.#rooms.get(s.roomId);
    const proposal = room.proposals.find((p) => p.id === id);
    if (s.role !== 'member' || !proposal || proposal.status !== 'PENDING' || !['YES', 'NO'].includes(choice)) throw new Error('투표할 수 없는 제안입니다');
    proposal.votes[s.id] = choice; this.#notify(room);
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
