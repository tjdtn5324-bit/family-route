// 아빠어디가 2판 서비스 로직.
// 구조: 사용자 계정 → 가족 그룹(한 사람은 한 번에 한 가족) → 여행 → 장소 제안·사진.
// 차량은 가족 그룹에 연결된 별도 주체이며, 계정과 섞지 않는다.
// 모든 읽기·쓰기는 여기서 "누가(actor) 어느 가족의 무엇에" 접근하는지 검사한다. 화면에서 가리는 것만으로는 막지 않는다.
import { randomBytes, randomInt, randomUUID, createHash, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { REFERENCE_COURSES } from './reference-courses.mjs';

const scrypt = promisify(scryptCb);

export class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (status, message) => { throw new HttpError(status, message); };

const DAY = 24 * 60 * 60 * 1000;
const SESSION_TTL = 30 * DAY;
const INVITE_TTL = 7 * DAY;
const VEHICLE_CODE_TTL = 10 * 60 * 1000;
const LIMITS = { users: 2000, families: 1000, membersPerFamily: 20, tripsPerFamily: 60, proposalsPerTrip: 100, stopsPerTrip: 5, photosPerTrip: 30, vehiclesPerFamily: 5, courses: 2000, reviewsPerCourse: 500 };
const WEEK = 7 * DAY;
const INVITE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 헷갈리는 0/O, 1/I 제외
// 차량이 실제 위치를 아직 받지 못하므로 출발지는 서울 시연 좌표로 둔다.
export const DEMO_ORIGIN = { name: '서울 (시연 출발지)', latitude: 37.5133, longitude: 127.1002, address: '' };

const clean = (v, max) => typeof v === 'string' ? [...v.trim()].slice(0, max).join('') : '';
const hashToken = t => createHash('sha256').update(String(t)).digest('hex');
const newToken = () => randomBytes(32).toString('base64url');
const isEmail = e => /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/.test(e);

function validPlace(place) {
  const name = clean(place?.name, 100), latitude = place?.latitude, longitude = place?.longitude;
  if (!name || typeof latitude !== 'number' || typeof longitude !== 'number' || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) fail(400, '장소 이름과 위치를 확인해 주세요');
  return { name, latitude, longitude, address: clean(place.address, 200) };
}
const samePlace = (a, b) => a && b && Math.abs(a.latitude - b.latitude) < 1e-6 && Math.abs(a.longitude - b.longitude) < 1e-6;

export class Service {
  #users = new Map();      // id → {id, email, name, passwordHash, createdAt}
  #emails = new Map();     // email → userId
  #sessions = new Map();   // sha256(token) → {userId, expiresAt}
  #families = new Map();   // id → {id, name, createdAt, members:[{userId, role:'ADMIN'|'MEMBER', joinedAt}]}
  #invites = new Map();    // code → {familyId, expiresAt}
  #vehicleCodes = new Map(); // code → {familyId, expiresAt}
  #vehicles = new Map();   // id → {id, familyId, name, tokenHash, pairedAt, lastSeenAt}
  #trips = new Map();      // id → trip
  #proposals = new Map();  // id → proposal
  #photos = new Map();     // id → photo
  #failedLogins = new Map(); // email → [timestamps]
  // 추천 코스: 참고 코스(REFERENCE_COURSES, 고정) + 가족이 다녀온 여행을 공개한 코스.
  // 공개 코스에는 장소와 제목·소개만 담는다. 사진, 가족 이름, 사람 이름은 담지 않는다.
  #courses = new Map();    // id → {id, kind:'COMMUNITY', familyId, tripId, publishedBy, title, description, destination, waypoints, createdAt}
  #likes = new Map();      // courseId → {userId: likedAt}
  #reviews = new Map();    // id → {id, courseId, userId, rating, text, createdAt}
  #directions;
  #now;

  constructor({ directions = null, now = () => Date.now() } = {}) { this.#directions = directions; this.#now = now; }
  set directions(fn) { this.#directions = fn; }

  // ───────── 저장과 복원 ─────────
  exportSnapshot() {
    this.#pruneSessions();
    const now = this.#now();
    for (const [code, v] of this.#vehicleCodes) if (v.expiresAt <= now) this.#vehicleCodes.delete(code);
    const list = m => [...m.values()].map(v => structuredClone(v));
    return { version: 2, users: list(this.#users), sessions: [...this.#sessions].map(([k, v]) => [k, { ...v }]), families: list(this.#families),
      invites: [...this.#invites].map(([k, v]) => [k, { ...v }]), vehicleCodes: [...this.#vehicleCodes].map(([k, v]) => [k, { ...v }]),
      vehicles: list(this.#vehicles), trips: list(this.#trips), proposals: list(this.#proposals), photos: list(this.#photos),
      courses: list(this.#courses), likes: [...this.#likes].map(([k, v]) => [k, { ...v }]), reviews: list(this.#reviews) };
  }
  static fromSnapshot(snap, options = {}) {
    if (!snap || snap.version !== 2) fail(500, '저장된 자료의 형식이 맞지 않아요');
    const s = new Service(options);
    for (const u of snap.users || []) { s.#users.set(u.id, u); s.#emails.set(u.email, u.id); }
    for (const [k, v] of snap.sessions || []) s.#sessions.set(k, v);
    for (const f of snap.families || []) s.#families.set(f.id, f);
    for (const [k, v] of snap.invites || []) s.#invites.set(k, v);
    for (const [k, v] of snap.vehicleCodes || []) s.#vehicleCodes.set(k, v);
    for (const v of snap.vehicles || []) s.#vehicles.set(v.id, v);
    for (const t of snap.trips || []) {
      t.stops = Array.isArray(t.stops) ? t.stops : [];
      t.readyForCar = Boolean(t.readyForCar);
      t.readyAt = t.readyAt || null;
      t.readyBy = t.readyBy || null;
      s.#trips.set(t.id, t);
    }
    for (const p of snap.proposals || []) {
      p.votes = p.votes && typeof p.votes === 'object' ? p.votes : {};
      p.reactions = p.reactions && typeof p.reactions === 'object' ? p.reactions : {};
      p.comments = Array.isArray(p.comments) ? p.comments : [];
      p.mood = p.mood || '';
      s.#proposals.set(p.id, p);
    }
    for (const p of snap.photos || []) {
      p.caption = p.caption || '';
      s.#photos.set(p.id, p);
    }
    for (const c of snap.courses || []) s.#courses.set(c.id, c);
    for (const [k, v] of snap.likes || []) s.#likes.set(k, v);
    for (const r of snap.reviews || []) s.#reviews.set(r.id, r);
    return s;
  }

  // ───────── 계정 ─────────
  async signup({ email, password, name }) {
    const e = clean(email, 254).toLowerCase(), n = clean(name, 20);
    if (!isEmail(e)) fail(400, '이메일 주소를 확인해 주세요');
    if (typeof password !== 'string' || password.length < 8 || password.length > 128) fail(400, '비밀번호는 8자 이상으로 정해 주세요');
    if (!n) fail(400, '가족에게 보일 이름을 적어 주세요');
    if (this.#emails.has(e)) fail(409, '이미 가입된 이메일이에요. 로그인해 주세요');
    if (this.#users.size >= LIMITS.users) fail(503, '지금은 가입을 받을 수 없어요');
    const salt = randomBytes(16);
    const hash = await scrypt(password, salt, 64);
    if (this.#emails.has(e)) fail(409, '이미 가입된 이메일이에요. 로그인해 주세요'); // 동시에 같은 이메일로 가입한 경우
    const user = { id: randomUUID(), email: e, name: n, passwordHash: salt.toString('base64') + ':' + hash.toString('base64'), createdAt: this.#now() };
    this.#users.set(user.id, user); this.#emails.set(e, user.id);
    return { user: this.#userView(user), token: this.#openSession(user.id) };
  }

  async login({ email, password }) {
    const e = clean(email, 254).toLowerCase(), now = this.#now();
    const recent = (this.#failedLogins.get(e) || []).filter(t => now - t < 15 * 60 * 1000);
    if (recent.length >= 5) fail(429, '로그인을 여러 번 실패했어요. 15분 뒤에 다시 시도해 주세요');
    const user = this.#users.get(this.#emails.get(e));
    // 계정이 없을 때도 같은 시간만큼 계산해 이메일 존재 여부가 드러나지 않게 한다.
    const [saltB64, hashB64] = (user?.passwordHash || 'AAAAAAAAAAAAAAAAAAAAAA==:').split(':');
    const actual = await scrypt(typeof password === 'string' ? password : '', Buffer.from(saltB64, 'base64'), 64);
    const expected = Buffer.from(hashB64 || '', 'base64');
    if (!user || expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
      this.#failedLogins.set(e, [...recent, now]);
      fail(401, '이메일 또는 비밀번호가 맞지 않아요');
    }
    this.#failedLogins.delete(e);
    return { user: this.#userView(user), token: this.#openSession(user.id) };
  }

  logout(token) { if (token) this.#sessions.delete(hashToken(token)); }

  #openSession(userId) {
    const token = newToken();
    this.#sessions.set(hashToken(token), { userId, expiresAt: this.#now() + SESSION_TTL });
    return token;
  }
  #pruneSessions() { const now = this.#now(); for (const [k, v] of this.#sessions) if (v.expiresAt <= now) this.#sessions.delete(k); }
  #userView(u) { return { id: u.id, email: u.email, name: u.name }; }

  // ───────── 접근 주체(actor) ─────────
  /** 사람: 로그인 세션으로만 만든다. 없거나 만료되면 401. */
  userActor(token) {
    if (!token) fail(401, '로그인해 주세요');
    const s = this.#sessions.get(hashToken(token));
    if (!s || s.expiresAt <= this.#now()) { if (s) this.#sessions.delete(hashToken(token)); fail(401, '로그인이 끝났어요. 다시 로그인해 주세요'); }
    const user = this.#users.get(s.userId);
    if (!user) fail(401, '로그인해 주세요');
    const family = this.#familyOf(user.id);
    return { kind: 'user', user, family, role: family?.members.find(m => m.userId === user.id).role || null };
  }
  /** 차량: 가족이 연결해 준 차량 토큰으로만 만든다. 가족에서 차량을 지우면 즉시 401. */
  vehicleActor(token) {
    if (!token) fail(401, '차량 연결이 필요해요');
    const h = hashToken(token);
    const vehicle = [...this.#vehicles.values()].find(v => v.tokenHash === h);
    const family = vehicle && this.#families.get(vehicle.familyId);
    if (!vehicle || !family) fail(401, '이 차량의 가족 연결이 끝났어요. 다시 연결해 주세요');
    vehicle.lastSeenAt = this.#now();
    return { kind: 'vehicle', vehicle, family, role: 'VEHICLE' };
  }
  #requireFamily(actor) { if (!actor.family || !this.#families.has(actor.family.id)) fail(404, '아직 가족이 없어요. 가족을 만들거나 초대를 받아 주세요'); return actor.family; }
  #requireMember(actor) { if (actor.kind !== 'user') fail(403, '가족 구성원만 할 수 있어요'); return this.#requireFamily(actor); }
  #requireAdmin(actor) { const f = this.#requireMember(actor); if (actor.role !== 'ADMIN') fail(403, '가족 관리자만 할 수 있어요'); return f; }
  #familyOf(userId) { for (const f of this.#families.values()) if (f.members.some(m => m.userId === userId)) return f; return null; }
  #name(userId) { return userId === 'VEHICLE' ? '차량' : this.#users.get(userId)?.name || '떠난 가족'; }
  #avatar(userId) {
    if (userId === 'VEHICLE') return '🚗';
    const n = this.#name(userId);
    if (/아빠|아버지|부친|남편|아비/.test(n)) return '👨';
    if (/엄마|어머니|모친|아내/.test(n)) return '👩';
    if (/할머니|할아버지|조모|조부/.test(n)) return '👵';
    if (/딸|민지|언니|누나|여동생|공주/.test(n)) return '👧';
    if (/아들|오빠|형|남동생|왕자/.test(n)) return '👦';
    return '😊';
  }

  // ───────── 내 정보 ─────────
  me(actor) {
    return { user: this.#userView(actor.user), family: actor.family ? this.#familyView(actor.family, actor.user.id) : null };
  }
  #familyView(f, viewerId) {
    const order = { ADMIN: 0, MEMBER: 1 };
    return { id: f.id, name: f.name, createdAt: f.createdAt, myRole: f.members.find(m => m.userId === viewerId)?.role || null,
      members: f.members.slice().sort((a, b) => order[a.role] - order[b.role] || a.joinedAt - b.joinedAt).map(m => ({ id: m.userId, name: this.#name(m.userId), avatar: this.#avatar(m.userId), role: m.role, joinedAt: m.joinedAt, me: m.userId === viewerId })),
      vehicles: [...this.#vehicles.values()].filter(v => v.familyId === f.id).map(v => ({ id: v.id, name: v.name, pairedAt: v.pairedAt, lastSeenAt: v.lastSeenAt || null })) };
  }

  // ───────── 가족 그룹 ─────────
  createFamily(actor, { name }) {
    if (actor.kind !== 'user') fail(403, '가족 구성원만 할 수 있어요');
    if (actor.family) fail(409, '이미 가족에 속해 있어요. 새 가족을 만들려면 지금 가족에서 먼저 나와 주세요');
    const n = clean(name, 30); if (!n) fail(400, '가족 이름을 적어 주세요');
    if (this.#families.size >= LIMITS.families) fail(503, '지금은 가족을 만들 수 없어요');
    const f = { id: randomUUID(), name: n, createdAt: this.#now(), members: [{ userId: actor.user.id, role: 'ADMIN', joinedAt: this.#now() }] };
    this.#families.set(f.id, f);
    this.#ensureInvite(f.id, true);
    return this.#familyView(f, actor.user.id);
  }
  renameFamily(actor, { name }) {
    const f = this.#requireAdmin(actor), n = clean(name, 30);
    if (!n) fail(400, '가족 이름을 적어 주세요');
    f.name = n; return this.#familyView(f, actor.user.id);
  }
  #ensureInvite(familyId, renew = false) {
    const now = this.#now();
    for (const [code, inv] of this.#invites) {
      if (inv.expiresAt <= now) this.#invites.delete(code);
      else if (inv.familyId === familyId) {
        if (!renew) return { code, expiresAt: inv.expiresAt };
        this.#invites.delete(code);
      }
    }
    let code; do { code = Array.from({ length: 8 }, () => INVITE_ALPHABET[randomInt(INVITE_ALPHABET.length)]).join(''); } while (this.#invites.has(code));
    const invite = { familyId, expiresAt: now + INVITE_TTL };
    this.#invites.set(code, invite);
    return { code, expiresAt: invite.expiresAt };
  }
  createInvite(actor) {
    const f = this.#requireMember(actor);
    return this.#ensureInvite(f.id, true);
  }
  #invite(code) {
    const key = clean(code, 20).replace(/[\s-]/g, '').toUpperCase();
    const inv = this.#invites.get(key);
    if (!inv || inv.expiresAt <= this.#now() || !this.#families.has(inv.familyId)) fail(404, '초대 코드가 만료됐거나 맞지 않아요. 가족에게 새 코드를 받아 주세요');
    return inv;
  }
  /** 가입 전에 어느 가족인지 보여 준다(가족 이름과 인원만). */
  invitePreview(actor, code) {
    if (actor.kind !== 'user') fail(403, '로그인해 주세요');
    const f = this.#families.get(this.#invite(code).familyId);
    return { familyName: f.name, memberCount: f.members.length, alreadyMember: f.members.some(m => m.userId === actor.user.id) };
  }
  joinFamily(actor, { code }) {
    if (actor.kind !== 'user') fail(403, '로그인해 주세요');
    const f = this.#families.get(this.#invite(code).familyId);
    if (actor.family?.id === f.id) return this.#familyView(f, actor.user.id);
    if (actor.family) fail(409, '이미 다른 가족에 속해 있어요. 지금 가족에서 먼저 나온 뒤 참여해 주세요');
    if (f.members.length >= LIMITS.membersPerFamily) fail(409, '이 가족은 인원이 가득 찼어요');
    // 차량에서 먼저 만든 가족방에 첫 번째로 들어오는 구성원은 자동으로 관리자가 된다.
    const role = f.members.some(m => m.role === 'ADMIN') ? 'MEMBER' : 'ADMIN';
    f.members.push({ userId: actor.user.id, role, joinedAt: this.#now() });
    return this.#familyView(f, actor.user.id);
  }
  /**
   * 가족에서 나가기.
   * - 일반 구성원은 언제든 나갈 수 있다.
   * - 관리자는 다른 구성원이 있으면 transferTo로 관리자를 넘겨야 나갈 수 있다.
   * - 마지막 한 사람이 나가면 가족과 그 안의 여행·제안·사진·차량 연결을 모두 지운다.
   * - deleteMyPhotos가 true면 내가 올린 사진을 지우고 나간다. 내 제안은 이름과 함께 가족 기록에 남는다.
   */
  leaveFamily(actor, { transferTo = null, deleteMyPhotos = false } = {}) {
    const f = this.#requireMember(actor), me = actor.user.id;
    const others = f.members.filter(m => m.userId !== me);
    if (actor.role === 'ADMIN' && others.length) {
      const next = others.find(m => m.userId === transferTo);
      if (!next) fail(409, '나가기 전에 관리자를 넘길 가족을 골라 주세요');
      next.role = 'ADMIN';
    }
    if (deleteMyPhotos) for (const [id, p] of this.#photos) if (p.familyId === f.id && p.userId === me) this.#photos.delete(id);
    f.members = others;
    if (!others.length) { this.#deleteFamily(f.id); return { familyDeleted: true }; }
    return { familyDeleted: false };
  }
  removeMember(actor, userId) {
    const f = this.#requireAdmin(actor);
    if (userId === actor.user.id) fail(400, '본인은 "가족에서 나가기"로 나가 주세요');
    const before = f.members.length;
    f.members = f.members.filter(m => m.userId !== userId);
    if (f.members.length === before) fail(404, '그 가족 구성원을 찾을 수 없어요');
    return this.#familyView(f, actor.user.id);
  }
  /** 관리자 넘기기: 대상이 관리자가 되고 나는 일반 구성원이 된다. */
  transferAdmin(actor, userId) {
    const f = this.#requireAdmin(actor);
    const target = f.members.find(m => m.userId === userId && m.userId !== actor.user.id);
    if (!target) fail(404, '그 가족 구성원을 찾을 수 없어요');
    target.role = 'ADMIN'; f.members.find(m => m.userId === actor.user.id).role = 'MEMBER';
    return this.#familyView(f, actor.user.id);
  }
  #deleteFamily(familyId) {
    this.#families.delete(familyId);
    for (const m of [this.#invites, this.#vehicleCodes]) for (const [k, v] of m) if (v.familyId === familyId) m.delete(k);
    for (const m of [this.#vehicles, this.#trips, this.#proposals, this.#photos]) for (const [k, v] of m) if (v.familyId === familyId) m.delete(k);
    // 가족이 사라지면 그 가족이 공개한 코스와 거기 달린 좋아요·후기도 함께 지운다.
    for (const [id, c] of this.#courses) if (c.familyId === familyId) this.#dropCourse(id);
  }
  #dropCourse(id) {
    this.#courses.delete(id); this.#likes.delete(id);
    for (const [k, r] of this.#reviews) if (r.courseId === id) this.#reviews.delete(k);
    for (const t of this.#trips.values()) if (t.publishedCourseId === id) t.publishedCourseId = null;
  }

  // ───────── 차량 ─────────
  vehicleCode(actor) {
    const f = this.#requireMember(actor);
    for (const [code, v] of this.#vehicleCodes) if (v.familyId === f.id || v.expiresAt <= this.#now()) this.#vehicleCodes.delete(code);
    let code; do { code = String(randomInt(0, 1000000)).padStart(6, '0'); } while (this.#vehicleCodes.has(code));
    const entry = { familyId: f.id, expiresAt: this.#now() + VEHICLE_CODE_TTL };
    this.#vehicleCodes.set(code, entry);
    return { code, expiresAt: entry.expiresAt };
  }
  pairVehicle({ code, name }) {
    const key = clean(code, 12).replace(/\D/g, '');
    const entry = this.#vehicleCodes.get(key);
    if (!entry || entry.expiresAt <= this.#now() || !this.#families.has(entry.familyId)) { this.#vehicleCodes.delete(key); fail(404, '연결 코드가 만료됐거나 맞지 않아요. 휴대폰에서 새 코드를 받아 주세요'); }
    this.#vehicleCodes.delete(key);
    const f = this.#families.get(entry.familyId);
    if ([...this.#vehicles.values()].filter(v => v.familyId === f.id).length >= LIMITS.vehiclesPerFamily) fail(409, '이 가족에는 차량을 5대까지 연결할 수 있어요');
    const token = newToken();
    const vehicle = { id: randomUUID(), familyId: f.id, name: clean(name, 30) || '우리 차', tokenHash: hashToken(token), pairedAt: this.#now(), lastSeenAt: this.#now() };
    this.#vehicles.set(vehicle.id, vehicle);
    return { vehicleToken: token, vehicle: { id: vehicle.id, name: vehicle.name }, family: { id: f.id, name: f.name } };
  }
  /** 차량 OS에서 바로 새 가족방을 만들고 이 차량을 연결한 뒤, 휴대폰 참가자용 초대 코드를 함께 돌려준다. */
  createFamilyFromVehicle({ familyName, vehicleName } = {}) {
    const fn = clean(familyName, 30); if (!fn) fail(400, '가족방 이름을 적어 주세요');
    if (this.#families.size >= LIMITS.families) fail(503, '지금은 가족을 만들 수 없어요');
    const f = { id: randomUUID(), name: fn, createdAt: this.#now(), members: [] };
    this.#families.set(f.id, f);
    const token = newToken();
    const vehicle = { id: randomUUID(), familyId: f.id, name: clean(vehicleName, 30) || '우리 차', tokenHash: hashToken(token), pairedAt: this.#now(), lastSeenAt: this.#now() };
    this.#vehicles.set(vehicle.id, vehicle);
    const inv = this.#ensureInvite(f.id, true);
    return {
      vehicleToken: token,
      vehicle: { id: vehicle.id, name: vehicle.name },
      family: { id: f.id, name: f.name, inviteCode: inv.code, inviteExpiresAt: inv.expiresAt, members: [] }
    };
  }
  vehicleInvite(actor) {
    if (actor.kind !== 'vehicle') fail(403, '차량에서만 할 수 있어요');
    const f = this.#requireFamily(actor);
    return this.#ensureInvite(f.id, true);
  }
  removeVehicle(actor, vehicleId) {
    const f = this.#requireAdmin(actor), v = this.#vehicles.get(vehicleId);
    if (!v || v.familyId !== f.id) fail(404, '그 차량을 찾을 수 없어요');
    this.#vehicles.delete(vehicleId);
    return this.#familyView(f, actor.user.id);
  }
  /** 차량 스스로 가족 연결을 끊는다(차량을 팔거나 다른 가족이 쓸 때). 아직 아무도 참여하지 않은 빈 가족방이면 함께 정리한다. */
  unpairSelf(actor) {
    if (actor.kind !== 'vehicle') fail(403, '차량에서만 할 수 있어요');
    const familyId = actor.vehicle.familyId;
    this.#vehicles.delete(actor.vehicle.id);
    const f = this.#families.get(familyId);
    if (f && f.members.length === 0 && ![...this.#vehicles.values()].some(v => v.familyId === familyId)) {
      this.#deleteFamily(familyId);
    }
  }
  vehicleHome(actor) {
    const inv = this.#ensureInvite(actor.family.id, false);
    return {
      vehicle: { id: actor.vehicle.id, name: actor.vehicle.name },
      family: {
        id: actor.family.id,
        name: actor.family.name,
        inviteCode: inv.code,
        inviteExpiresAt: inv.expiresAt,
        members: actor.family.members.map(m => ({ id: m.userId, name: this.#name(m.userId), role: m.role }))
      }
    };
  }

  // ───────── 여행 ─────────
  #trip(actor, tripId) {
    const f = this.#requireFamily(actor), t = this.#trips.get(tripId);
    if (!t || t.familyId !== f.id) fail(404, '여행을 찾을 수 없어요');
    return t;
  }
  /** 여행을 정리할 수 있는 사람: 관리자, 여행을 만든 사람, 운전자, 연결된 차량. */
  #canManage(actor, t) { return actor.kind === 'vehicle' || actor.role === 'ADMIN' || t.createdBy === actor.user.id || t.driverUserId === actor.user.id; }
  #requireManage(actor, t) { if (!this.#canManage(actor, t)) fail(403, '여행을 만든 사람, 운전자, 가족 관리자만 정할 수 있어요'); }
  #checkDriver(f, userId) { if (userId !== null && userId !== undefined && userId !== '' && !f.members.some(m => m.userId === userId)) fail(400, '운전자는 가족 구성원 중에서 골라 주세요'); return userId || null; }

  listTrips(actor) {
    const f = this.#requireFamily(actor), rank = { ONGOING: 0, PLANNING: 1, DONE: 2 };
    return [...this.#trips.values()].filter(t => t.familyId === f.id)
      .sort((a, b) => rank[a.status] - rank[b.status] || (a.status === 'DONE' ? b.finishedAt - a.finishedAt : (Number(Boolean(b.readyForCar)) - Number(Boolean(a.readyForCar))) || (b.readyAt || 0) - (a.readyAt || 0) || String(a.date || '9').localeCompare(String(b.date || '9')) || a.createdAt - b.createdAt))
      .map(t => this.#tripSummary(t));
  }
  #tripSummary(t) {
    const ps = [...this.#proposals.values()].filter(p => p.tripId === t.id);
    const photos = [...this.#photos.values()].filter(p => p.tripId === t.id);
    const storyItems = ps.filter(p => p.reason || p.message || p.mood || photos.some(ph => ph.proposalId === p.id));
    return { id: t.id, title: t.title, date: t.date, status: t.status, destination: t.destination, driver: t.driverUserId ? { id: t.driverUserId, name: this.#name(t.driverUserId), avatar: this.#avatar(t.driverUserId) } : null,
      course: t.courseId ? { id: t.courseId, title: t.courseTitle, exists: !!this.#courseById(t.courseId) } : null,
      publishedCourseId: t.publishedCourseId && this.#courses.has(t.publishedCourseId) ? t.publishedCourseId : null,
      readyForCar: Boolean(t.readyForCar), readyAt: t.readyAt || null, readyBy: t.readyBy ? { id: t.readyBy, name: this.#name(t.readyBy), avatar: this.#avatar(t.readyBy) } : null,
      storyCount: storyItems.length,
      stopCount: t.stops.length, pendingCount: ps.filter(p => p.status === 'PROPOSED').length, photoCount: photos.length, coverPhotoId: photos[0]?.id || null, startedAt: t.startedAt || null, finishedAt: t.finishedAt || null };
  }
  #proposalView(p, t, actor, me, allPhotos) {
    const votes = Object.values(p.votes || {});
    const reactionsObj = p.reactions || {};
    const reactionCounts = {};
    for (const em of Object.values(reactionsObj)) if (em) reactionCounts[em] = (reactionCounts[em] || 0) + 1;
    const totalReactions = Object.values(reactionCounts).reduce((a, b) => a + b, 0);
    const placePhotos = allPhotos.filter(ph => ph.proposalId === p.id).map(ph => ({ id: ph.id, caption: ph.caption || '', by: { id: ph.userId, name: this.#name(ph.userId), avatar: this.#avatar(ph.userId) }, createdAt: ph.createdAt }));
    return {
      id: p.id, kind: p.kind, place: p.place, reason: p.reason, message: p.message, mood: p.mood || '', status: p.status,
      by: { id: p.userId, name: this.#name(p.userId), avatar: this.#avatar(p.userId) },
      yes: votes.filter(v => v === 'YES').length, no: votes.filter(v => v === 'NO').length, myVote: me ? p.votes?.[me] || null : null,
      reactions: reactionCounts, totalReactions, myReaction: me ? reactionsObj[me] || null : null,
      comments: (p.comments || []).map(c => ({ id: c.id, text: c.text, createdAt: c.createdAt, by: { id: c.userId, name: this.#name(c.userId), avatar: this.#avatar(c.userId) } })),
      photos: placePhotos,
      visitedAt: p.visitedAt || null, canDelete: me !== null && (p.userId === me && p.status === 'PROPOSED' || this.#canManage(actor, t))
    };
  }
  tripDetail(actor, tripId) {
    const t = this.#trip(actor, tripId), me = actor.kind === 'user' ? actor.user.id : null;
    const ps = [...this.#proposals.values()].filter(p => p.tripId === t.id).sort((a, b) => a.createdAt - b.createdAt);
    const allPhotos = [...this.#photos.values()].filter(p => p.tripId === t.id).sort((a, b) => a.createdAt - b.createdAt);
    const view = p => this.#proposalView(p, t, actor, me, allPhotos);
    return { ...this.#tripSummary(t), origin: t.origin, createdBy: { id: t.createdBy, name: this.#name(t.createdBy), avatar: this.#avatar(t.createdBy) }, canManage: this.#canManage(actor, t),
      stops: t.stops.map(id => this.#proposals.get(id)).filter(Boolean).map(view), proposals: ps.filter(p => !t.stops.includes(p.id)).map(view),
      route: t.route ? { distance: t.route.distance, duration: t.route.duration, points: t.route.points, version: t.routeVersion } : null,
      routeError: t.routeError || null,
      photos: allPhotos.map(p => ({ id: p.id, caption: p.caption || '', by: { id: p.userId, name: this.#name(p.userId), avatar: this.#avatar(p.userId) }, proposalId: p.proposalId || null, createdAt: p.createdAt, canDelete: me !== null && (p.userId === me || actor.role === 'ADMIN') })) };
  }
  async #buildTrip(f, creatorId, actor, { title, date = null, destination = null, driverUserId = null, courseId = null, waypoints = null }) {
    const course = courseId ? this.#courseById(courseId) : null;
    if (courseId && !course) fail(404, '그 추천 코스를 찾을 수 없어요');
    const name = clean(title, 40) || course?.title || ''; if (!name) fail(400, '여행 이름을 적어 주세요');
    if (course && !destination) destination = course.destination;
    if (date !== null && date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) fail(400, '여행 날짜를 확인해 주세요');
    if (waypoints !== null && (!Array.isArray(waypoints) || waypoints.length > LIMITS.stopsPerTrip)) fail(400, `들르는 곳은 ${LIMITS.stopsPerTrip}곳까지예요`);
    const selectedWaypoints = waypoints !== null ? waypoints.map(validPlace) : (course?.waypoints || []).map(w => ({ ...w }));
    if ([...this.#trips.values()].filter(t => t.familyId === f.id).length >= LIMITS.tripsPerFamily) fail(409, '한 가족에는 여행을 60개까지 만들 수 있어요. 지난 여행을 정리해 주세요');
    const t = { id: randomUUID(), familyId: f.id, title: name, date: date || null, status: 'PLANNING', origin: { ...DEMO_ORIGIN }, destination: destination ? validPlace(destination) : null,
      driverUserId: this.#checkDriver(f, driverUserId), createdBy: creatorId, createdAt: this.#now(), stops: [], route: null, routeVersion: 0, startedAt: null, finishedAt: null,
      readyForCar: Boolean(destination && selectedWaypoints.length), readyAt: (destination && selectedWaypoints.length) ? this.#now() : null, readyBy: (destination && selectedWaypoints.length) ? creatorId : null,
      courseId: course?.id || null, courseTitle: course?.title || null, publishedCourseId: null };
    this.#trips.set(t.id, t);
    for (const place of selectedWaypoints) {
      const p = { id: randomUUID(), tripId: t.id, familyId: f.id, userId: creatorId, kind: 'STOP', place: { ...place }, reason: course ? '추천 코스 ‘' + course.title + '’' : '출발 전 추가한 경유지', message: '', mood: '추천 코스', status: 'CONFIRMED', votes: {}, reactions: {}, comments: [], createdAt: this.#now() };
      this.#proposals.set(p.id, p); t.stops.push(p.id);
    }
    if (t.destination) { try { await this.#reroute(t, t.destination, t.stops); } catch { t.routeError = '경로를 아직 찾지 못했어요. 목적지를 다시 정해 주세요'; } }
    return this.tripDetail(actor, t.id);
  }
  async createTrip(actor, options = {}) {
    const f = this.#requireMember(actor);
    return this.#buildTrip(f, actor.user.id, actor, options);
  }
  /** 차량 OS에서 바로 가족 여행방을 만든다. 휴대폰 참가자가 QR로 들어오면 이 여행방에 바로 함께 참여한다. */
  async createTripFromVehicle(actor, options = {}) {
    if (actor.kind !== 'vehicle') fail(403, '차량에서만 할 수 있어요');
    const f = this.#requireFamily(actor);
    const defaultDriver = f.members.find(m => m.role === 'ADMIN')?.userId || f.members[0]?.userId || null;
    return this.#buildTrip(f, defaultDriver || 'VEHICLE', actor, { ...options, driverUserId: options.driverUserId || defaultDriver });
  }
  async updateTrip(actor, tripId, { title, date, driverUserId, destination }) {
    const t = this.#trip(actor, tripId); this.#requireManage(actor, t);
    if (t.status === 'DONE') fail(409, '끝난 여행은 바꿀 수 없어요');
    if (title !== undefined) { const n = clean(title, 40); if (!n) fail(400, '여행 이름을 적어 주세요'); t.title = n; }
    if (date !== undefined) { if (date !== null && date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) fail(400, '여행 날짜를 확인해 주세요'); t.date = date || null; }
    if (driverUserId !== undefined) t.driverUserId = this.#checkDriver(this.#families.get(t.familyId), driverUserId);
    if (destination !== undefined) await this.#reroute(t, destination ? validPlace(destination) : null, t.stops);
    return this.tripDetail(actor, t.id);
  }
  deleteTrip(actor, tripId) {
    const t = this.#trip(actor, tripId); this.#requireManage(actor, t);
    this.#trips.delete(t.id);
    for (const m of [this.#proposals, this.#photos]) for (const [k, v] of m) if (v.tripId === t.id) m.delete(k);
  }
  /** 목적지·들르는 곳이 바뀌면 경로를 다시 찾는다. 경로를 못 찾으면 이전 상태를 그대로 둔다. */
  /** 가족 연결 없이 혼자 운전할 때의 경로. 개인 자료를 읽거나 저장하지 않는다. */
  async soloRoute({ destination, waypoints = [] } = {}) {
    const to = validPlace(destination);
    if (!Array.isArray(waypoints) || waypoints.length > LIMITS.stopsPerTrip) fail(400, `들르는 곳은 ${LIMITS.stopsPerTrip}곳까지예요`);
    const via = waypoints.map(validPlace);
    if (!this.#directions) fail(503, '길찾기를 지금 쓸 수 없어요');
    let route;
    try { route = await this.#directions({ origin: { ...DEMO_ORIGIN }, destination: to, waypoints: via }); }
    catch { fail(502, '경로를 찾지 못했어요. 잠시 후 다시 시도해 주세요'); }
    return { origin: { ...DEMO_ORIGIN }, destination: to, waypoints: via, route: { points: route.points, distance: route.distance, duration: route.duration } };
  }

  async #reroute(t, destination, stops) {
    const version = t.routeVersion;
    let route = null;
    if (destination && this.#directions) {
      const waypoints = stops.map(id => this.#proposals.get(id)?.place).filter(Boolean);
      try { route = await this.#directions({ origin: t.origin, destination, waypoints }); }
      catch (e) { fail(502, '경로를 찾지 못했어요. 잠시 후 다시 시도해 주세요'); }
      if (!this.#trips.has(t.id)) fail(404, '여행을 찾을 수 없어요');
      if (t.routeVersion !== version) fail(409, '그 사이 다른 가족이 경로를 바꿨어요. 다시 확인해 주세요');
    }
    t.destination = destination; t.stops = stops; t.route = route; t.routeError = null; t.routeVersion++;
  }

  // ───────── 장소 제안·스토리 ─────────
  proposePlace(actor, tripId, { place, reason = '', message = '', mood = '', kind = 'STOP' }) {
    const t = this.#trip(actor, tripId);
    if (actor.kind !== 'user') fail(403, '장소 제안은 가족 구성원이 휴대폰에서 해 주세요');
    if (t.status === 'DONE') fail(409, '끝난 여행에는 제안할 수 없어요');
    if (!['STOP', 'DESTINATION'].includes(kind)) fail(400, '제안 종류를 확인해 주세요');
    const p0 = validPlace(place);
    const mine = [...this.#proposals.values()].find(p => p.tripId === t.id && p.userId === actor.user.id && p.status === 'PROPOSED' && p.kind === kind && samePlace(p.place, p0));
    if (mine) return this.tripDetail(actor, t.id); // 약한 신호에서 다시 눌러도 하나만 남는다
    if ([...this.#proposals.values()].filter(p => p.tripId === t.id).length >= LIMITS.proposalsPerTrip) fail(409, '한 여행에는 장소를 100곳까지 제안할 수 있어요');
    const p = { id: randomUUID(), tripId: t.id, familyId: t.familyId, userId: actor.user.id, kind, place: p0, reason: clean(reason, 200), message: clean(message, 300), mood: clean(mood, 30), status: 'PROPOSED', votes: {}, reactions: {}, comments: [], createdAt: this.#now(), visitedAt: null };
    this.#proposals.set(p.id, p);
    return this.tripDetail(actor, t.id);
  }
  #proposal(actor, proposalId) {
    const p = this.#proposals.get(proposalId), f = this.#requireFamily(actor);
    if (!p || p.familyId !== f.id) fail(404, '제안을 찾을 수 없어요');
    return { p, t: this.#trips.get(p.tripId) };
  }
  vote(actor, proposalId, { choice }) {
    const { p, t } = this.#proposal(actor, proposalId);
    if (actor.kind !== 'user') fail(403, '가족 구성원만 의견을 남길 수 있어요');
    if (!['YES', 'NO', 'NONE'].includes(choice)) fail(400, '의견을 확인해 주세요');
    if (p.status !== 'PROPOSED') fail(409, '이미 정해진 제안이에요');
    if (choice === 'NONE') delete p.votes[actor.user.id]; else p.votes[actor.user.id] = choice;
    return this.tripDetail(actor, t.id);
  }
  /** 인스타 스토리형 공감 이모지 리액션(❤️, 🤤, 🔥, 👏, 🤩). 제안 중이거나 확정된 경유지·방문 완료한 곳 모두 남길 수 있다. */
  reactProposal(actor, proposalId, { emoji }) {
    const { p, t } = this.#proposal(actor, proposalId);
    if (actor.kind !== 'user') fail(403, '가족 구성원만 공감 표현을 남길 수 있어요');
    const allowed = ['❤️', '🤤', '🔥', '👏', '🤩', ''];
    const em = clean(emoji, 8);
    if (!allowed.includes(em)) fail(400, '공감 이모지를 확인해 주세요');
    p.reactions = p.reactions || {};
    if (!em || p.reactions[actor.user.id] === em) {
      delete p.reactions[actor.user.id];
    } else {
      p.reactions[actor.user.id] = em;
      if (p.status === 'PROPOSED' && !p.votes?.[actor.user.id]) {
        p.votes = p.votes || {};
        p.votes[actor.user.id] = 'YES';
      }
    }
    return this.tripDetail(actor, t.id);
  }
  /** 여행지 스토리에 가족 한마디 댓글을 남긴다. */
  commentProposal(actor, proposalId, { text }) {
    const { p, t } = this.#proposal(actor, proposalId);
    if (actor.kind !== 'user') fail(403, '가족 구성원만 한마디를 남길 수 있어요');
    const msg = clean(text, 120);
    if (!msg) fail(400, '남길 한마디를 적어 주세요');
    p.comments = Array.isArray(p.comments) ? p.comments : [];
    if (p.comments.length >= 30) fail(409, '한 장소에는 한마디를 30개까지 남길 수 있어요');
    p.comments.push({ id: randomUUID(), userId: actor.user.id, text: msg, createdAt: this.#now() });
    return this.tripDetail(actor, t.id);
  }
  /** CONFIRM: 들르는 곳이면 경로 끝에 더하고, 목적지면 목적지를 바꾼다. DECLINE: 이번에는 가지 않는다. */
  async decide(actor, proposalId, { decision }) {
    const { p, t } = this.#proposal(actor, proposalId); this.#requireManage(actor, t);
    if (t.status === 'DONE') fail(409, '끝난 여행이에요');
    if (p.status !== 'PROPOSED') fail(409, '이미 정해진 제안이에요');
    if (decision === 'DECLINE') { p.status = 'DECLINED'; return this.tripDetail(actor, t.id); }
    if (decision !== 'CONFIRM') fail(400, '결정을 확인해 주세요');
    if (p.kind === 'DESTINATION') {
      await this.#reroute(t, p.place, t.stops);
      for (const q of this.#proposals.values()) if (q.tripId === t.id && q.kind === 'DESTINATION' && q.status === 'CONFIRMED') q.status = 'REPLACED';
    } else {
      if (t.stops.length >= LIMITS.stopsPerTrip) fail(409, '들르는 곳은 5곳까지 정할 수 있어요. 먼저 한 곳을 빼 주세요');
      if (!t.destination) fail(409, '목적지를 먼저 정해 주세요');
      await this.#reroute(t, t.destination, [...t.stops, p.id]);
    }
    p.status = 'CONFIRMED';
    return this.tripDetail(actor, t.id);
  }
  /** 가족 구성원(출발 전) 또는 운전자·차량·여행 관리자(출발 전·이동 중)가 경로에 들르는 곳을 바로 추가한다. */
  async addStop(actor, tripId, { place, reason = '', message = '', mood = '' } = {}) {
    const t = this.#trip(actor, tripId);
    if (t.status !== 'PLANNING') this.#requireManage(actor, t);
    if (t.status === 'DONE') fail(409, '끝난 여행에는 경유지를 추가할 수 없어요');
    if (!t.destination) fail(409, '목적지를 먼저 정해 주세요');
    if (t.stops.length >= LIMITS.stopsPerTrip) fail(409, '들르는 곳은 5곳까지 정할 수 있어요. 먼저 한 곳을 빼 주세요');
    const p0 = validPlace(place);
    const authorId = actor.kind === 'user' ? actor.user.id : (t.driverUserId || t.createdBy);
    const p = { id: randomUUID(), tripId: t.id, familyId: t.familyId, userId: authorId, kind: 'STOP', place: p0,
      reason: clean(reason, 200) || (actor.kind === 'vehicle' ? '차량에서 추가한 경유지' : '추가한 경유지'), message: clean(message, 300), mood: clean(mood, 30),
      status: 'CONFIRMED', votes: {}, reactions: {}, comments: [], createdAt: this.#now(), visitedAt: null };
    this.#proposals.set(p.id, p);
    try { await this.#reroute(t, t.destination, [...t.stops, p.id]); }
    catch (e) { this.#proposals.delete(p.id); throw e; }
    return this.tripDetail(actor, t.id);
  }
  /**
   * 출발 전 가족이 다같이 짠 코스를 '차량 탑승 준비 완료(readyForCar)' 상태로 만든다.
   * autoConfirmPending=true이면 아직 대기 중인 목적지·경유지 제안을 가족 공감(리액션+좋아요) 순으로 경로에 자동 반영해,
   * 차에 타자마자 원터치로 바로 출발할 수 있게 해 준다.
   */
  async setTripReady(actor, tripId, { ready = true, autoConfirmPending = true } = {}) {
    const t = this.#trip(actor, tripId);
    if (t.status === 'DONE') fail(409, '끝난 여행은 바꿀 수 없어요');
    if (autoConfirmPending && ready) {
      const pending = [...this.#proposals.values()].filter(p => p.tripId === t.id && p.status === 'PROPOSED');
      const score = p => {
        const votes = Object.values(p.votes || {});
        const yes = votes.filter(v => v === 'YES').length;
        const no = votes.filter(v => v === 'NO').length;
        const reacts = Object.keys(p.reactions || {}).length;
        return yes + reacts * 2 - no;
      };
      let nextDest = t.destination;
      const destProposals = pending.filter(p => p.kind === 'DESTINATION').sort((a, b) => score(b) - score(a) || a.createdAt - b.createdAt);
      let chosenDestProposal = null;
      if (!nextDest && destProposals.length > 0) {
        chosenDestProposal = destProposals[0];
        nextDest = chosenDestProposal.place;
      }
      const stopCandidates = pending.filter(p => p.kind === 'STOP').sort((a, b) => score(b) - score(a) || a.createdAt - b.createdAt);
      if (!nextDest && stopCandidates.length > 0) {
        // 목적지가 아직 없고 경유지 제안만 있는 경우 마지막 제안을 도착지로 삼고 나머지를 경유지로 편입한다.
        chosenDestProposal = stopCandidates.pop();
        nextDest = chosenDestProposal.place;
      }
      const nextStops = [...t.stops];
      const newlyConfirmed = [];
      if (nextDest) {
        for (const cand of stopCandidates) {
          if (nextStops.length >= LIMITS.stopsPerTrip) break;
          if (!nextStops.includes(cand.id) && !samePlace(cand.place, nextDest)) {
            nextStops.push(cand.id);
            newlyConfirmed.push(cand);
          }
        }
      }
      if (chosenDestProposal || newlyConfirmed.length > 0) {
        await this.#reroute(t, nextDest, nextStops);
        if (chosenDestProposal) chosenDestProposal.status = 'CONFIRMED';
        for (const c of newlyConfirmed) c.status = 'CONFIRMED';
      }
    }
    t.readyForCar = Boolean(ready);
    t.readyAt = ready ? this.#now() : null;
    t.readyBy = ready ? (actor.kind === 'user' ? actor.user.id : 'VEHICLE') : null;
    return this.tripDetail(actor, t.id);
  }
  /** 차량(또는 운전자)에서 대기 중인 가족 제안을 모두 코스에 반영하고 즉시 길안내를 출발(ONGOING)한다. */
  async quickStartTrip(actor, tripId) {
    const t = this.#trip(actor, tripId); this.#requireManage(actor, t);
    await this.setTripReady(actor, tripId, { ready: true, autoConfirmPending: true });
    if (t.status === 'PLANNING') {
      if (!t.destination) fail(409, '목적지나 제안된 장소가 아직 없어요. 목적지를 먼저 골라 주세요');
      return this.startTrip(actor, tripId);
    }
    return this.tripDetail(actor, t.id);
  }
  /** 추천 코스(및 편집한 경유지 목록)를 현재 가족 여행의 경로에 바로 적용한다. */
  async applyCourse(actor, tripId, { courseId, waypoints = null, destination = null } = {}) {
    const t = this.#trip(actor, tripId);
    if (t.status !== 'PLANNING') this.#requireManage(actor, t);
    if (t.status === 'DONE') fail(409, '끝난 여행은 바꿀 수 없어요');
    const course = this.#course(courseId);
    if (waypoints !== null && (!Array.isArray(waypoints) || waypoints.length > LIMITS.stopsPerTrip)) fail(400, `들르는 곳은 ${LIMITS.stopsPerTrip}곳까지예요`);
    const via = waypoints !== null ? waypoints.map(validPlace) : course.waypoints.map(w => ({ ...w }));
    const dest = destination ? validPlace(destination) : { ...course.destination };
    const authorId = actor.kind === 'user' ? actor.user.id : (t.driverUserId || t.createdBy);
    const created = via.map(place => ({ id: randomUUID(), tripId: t.id, familyId: t.familyId, userId: authorId, kind: 'STOP', place,
      reason: '추천 코스 ‘' + course.title + '’', message: '', mood: '추천 코스', status: 'CONFIRMED', votes: {}, reactions: {}, comments: [], createdAt: this.#now(), visitedAt: null }));
    for (const p of created) this.#proposals.set(p.id, p);
    const oldStopIds = [...t.stops];
    try { await this.#reroute(t, dest, created.map(p => p.id)); }
    catch (e) { for (const p of created) this.#proposals.delete(p.id); throw e; }
    for (const oldId of oldStopIds) {
      const old = this.#proposals.get(oldId);
      if (old && old.status !== 'VISITED') this.#proposals.delete(oldId);
    }
    t.courseId = course.id; t.courseTitle = course.title;
    t.readyForCar = true; t.readyAt = this.#now(); t.readyBy = authorId;
    return this.tripDetail(actor, t.id);
  }
  async removeProposal(actor, proposalId) {
    const { p, t } = this.#proposal(actor, proposalId);
    const own = actor.kind === 'user' && p.userId === actor.user.id && p.status === 'PROPOSED';
    if (!own) this.#requireManage(actor, t);
    if (t.status === 'DONE') fail(409, '끝난 여행은 바꿀 수 없어요');
    if (t.stops.includes(p.id)) await this.#reroute(t, t.destination, t.stops.filter(id => id !== p.id));
    this.#proposals.delete(p.id);
    for (const ph of this.#photos.values()) if (ph.proposalId === p.id) ph.proposalId = null;
    return this.tripDetail(actor, t.id);
  }
  async reorderStops(actor, tripId, { ids, version }) {
    const t = this.#trip(actor, tripId); this.#requireManage(actor, t);
    if (!Array.isArray(ids) || ids.length !== t.stops.length || new Set(ids).size !== ids.length || ids.some(id => !t.stops.includes(id))) fail(400, '들르는 곳 순서를 확인해 주세요');
    if (version !== undefined && version !== t.routeVersion) fail(409, '그 사이 경로가 바뀌었어요. 다시 확인해 주세요');
    await this.#reroute(t, t.destination, ids);
    return this.tripDetail(actor, t.id);
  }

  // ───────── 함께 이동 ─────────
  startTrip(actor, tripId) {
    const t = this.#trip(actor, tripId); this.#requireManage(actor, t);
    if (t.status !== 'PLANNING') fail(409, t.status === 'ONGOING' ? '이미 출발한 여행이에요' : '끝난 여행이에요');
    if (!t.destination) fail(409, '목적지를 먼저 정해 주세요');
    if ([...this.#trips.values()].some(x => x.familyId === t.familyId && x.status === 'ONGOING')) fail(409, '진행 중인 다른 여행이 있어요. 그 여행을 먼저 끝내 주세요');
    t.status = 'ONGOING'; t.startedAt = this.#now();
    return this.tripDetail(actor, t.id);
  }
  /** 정차한 곳에 도착했다고 기록하고, 그곳을 제안한 가족의 이야기를 돌려준다. */
  arriveAt(actor, proposalId) {
    const { p, t } = this.#proposal(actor, proposalId); this.#requireManage(actor, t);
    if (t.status !== 'ONGOING') fail(409, '출발한 여행에서만 도착을 기록할 수 있어요');
    if (!t.stops.includes(p.id)) fail(409, '경로에 들어 있는 곳만 도착을 기록할 수 있어요');
    p.status = 'VISITED'; p.visitedAt = p.visitedAt || this.#now();
    const matching = [...this.#proposals.values()].filter(q => q.tripId === t.id && samePlace(q.place, p.place) && (q.reason || q.message));
    const stories = matching.map(q => ({ by: this.#name(q.userId), reason: q.reason, message: q.message }));
    const allPhotos = [...this.#photos.values()].filter(ph => ph.tripId === t.id);
    const storyCards = [...this.#proposals.values()].filter(q => q.tripId === t.id && samePlace(q.place, p.place))
      .map(q => this.#proposalView(q, t, actor, actor.kind === 'user' ? actor.user.id : null, allPhotos));
    return { place: p.place, stories, storyCards, trip: this.tripDetail(actor, t.id) };
  }
  finishTrip(actor, tripId) {
    const t = this.#trip(actor, tripId); this.#requireManage(actor, t);
    if (t.status !== 'ONGOING') fail(409, '출발한 여행만 끝낼 수 있어요');
    t.status = 'DONE'; t.finishedAt = this.#now();
    return this.tripDetail(actor, t.id);
  }

  // ───────── 사진·추억 ─────────
  addPhoto(actor, tripId, { data, proposalId = null, caption = '' }) {
    const t = this.#trip(actor, tripId);
    if (actor.kind !== 'user') fail(403, '사진은 가족 구성원이 휴대폰에서 올려 주세요');
    if (typeof data !== 'string' || data.length > 350000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) fail(400, '사진 크기나 형식을 확인해 주세요');
    const bytes = Buffer.from(data, 'base64');
    if (bytes.length > 250000 || bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) fail(400, 'JPG 사진만 올릴 수 있어요');
    if (proposalId && this.#proposals.get(proposalId)?.tripId !== t.id) fail(400, '사진을 붙일 장소를 확인해 주세요');
    if ([...this.#photos.values()].filter(p => p.tripId === t.id).length >= LIMITS.photosPerTrip) fail(409, '한 여행에는 사진을 30장까지 올릴 수 있어요');
    const photo = { id: randomUUID(), tripId: t.id, familyId: t.familyId, userId: actor.user.id, proposalId: proposalId || null, caption: clean(caption, 120), data, createdAt: this.#now() };
    this.#photos.set(photo.id, photo);
    return { id: photo.id, caption: photo.caption };
  }
  photo(actor, photoId) {
    const f = this.#requireFamily(actor), p = this.#photos.get(photoId);
    if (!p || p.familyId !== f.id) fail(404, '사진을 찾을 수 없어요');
    return { data: p.data };
  }
  deletePhoto(actor, photoId) {
    const f = this.#requireMember(actor), p = this.#photos.get(photoId);
    if (!p || p.familyId !== f.id) fail(404, '사진을 찾을 수 없어요');
    if (p.userId !== actor.user.id && actor.role !== 'ADMIN') fail(403, '내가 올린 사진만 지울 수 있어요');
    this.#photos.delete(photoId);
  }
  // ───────── 추천 코스 ─────────
  #courseById(id) { return REFERENCE_COURSES.find(c => c.id === id) || this.#courses.get(id) || null; }
  #course(id) { const c = this.#courseById(id); if (!c) fail(404, '그 추천 코스를 찾을 수 없어요'); return c; }
  /** viewer는 로그인한 사람, 차량, 또는 null(로그인 없이 둘러보기). 공개 코스에는 가족·사람 이름을 넣지 않는다. */
  #courseView(c, viewer) {
    const likes = this.#likes.get(c.id) || {}, times = Object.values(likes), now = this.#now();
    const reviews = [...this.#reviews.values()].filter(r => r.courseId === c.id);
    const me = viewer?.kind === 'user' ? viewer.user.id : null, myFamily = viewer?.family?.id || null;
    const thisWeek = times.filter(at => now - at < WEEK).length;
    return { id: c.id, kind: c.kind, title: c.title, description: c.description, destination: c.destination, waypoints: c.waypoints,
      source: c.kind === 'REFERENCE' ? { name: c.sourceName, url: c.sourceUrl } : null,
      likes: times.length, likesThisWeek: thisWeek, trending: thisWeek >= 2, reviewCount: reviews.length,
      rating: reviews.length ? Math.round(reviews.reduce((s, r) => s + r.rating, 0) / reviews.length * 10) / 10 : null,
      createdAt: c.createdAt, liked: !!(me && likes[me]), ours: !!(myFamily && c.familyId === myFamily),
      wentWithFamily: !!(myFamily && [...this.#trips.values()].some(t => t.familyId === myFamily && t.courseId === c.id && t.status === 'DONE')) };
  }
  listCourses(viewer, { sort = 'trending', kind = '', q = '' } = {}) {
    const query = clean(q, 40);
    let list = [...REFERENCE_COURSES, ...this.#courses.values()];
    if (kind === 'REFERENCE' || kind === 'COMMUNITY') list = list.filter(c => c.kind === kind);
    if (query) list = list.filter(c => [c.title, c.description, c.destination.name, c.destination.address, ...c.waypoints.map(w => w.name + ' ' + w.address)].some(s => String(s || '').includes(query)));
    const views = list.map(c => this.#courseView(c, viewer));
    const by = { popular: (a, b) => b.likes - a.likes || b.createdAt - a.createdAt, new: (a, b) => b.createdAt - a.createdAt || b.likes - a.likes,
      trending: (a, b) => b.likesThisWeek - a.likesThisWeek || b.likes - a.likes || b.createdAt - a.createdAt };
    return views.sort(by[sort] || by.trending).slice(0, 100);
  }
  courseDetail(viewer, id) {
    const c = this.#course(id), me = viewer?.kind === 'user' ? viewer.user.id : null;
    const reviews = [...this.#reviews.values()].filter(r => r.courseId === c.id).sort((a, b) => b.createdAt - a.createdAt)
      .map(r => ({ id: r.id, rating: r.rating, text: r.text, createdAt: r.createdAt, mine: r.userId === me }));
    return { ...this.#courseView(c, viewer), reviews, canUnpublish: !!(me && c.kind === 'COMMUNITY' && c.familyId === viewer.family?.id && (c.publishedBy === me || viewer.role === 'ADMIN')) };
  }
  #notOurs(actor, c, what) { if (c.familyId && c.familyId === actor.family?.id) fail(409, '우리 가족이 공개한 코스에는 ' + what + '를 남길 수 없어요'); }
  likeCourse(actor, id, { liked } = {}) {
    if (actor.kind !== 'user') fail(403, '좋아요는 휴대폰에서 로그인한 뒤 눌러 주세요');
    const c = this.#course(id); this.#notOurs(actor, c, '좋아요');
    const likes = this.#likes.get(c.id) || {};
    if (liked === false) delete likes[actor.user.id]; else likes[actor.user.id] = likes[actor.user.id] || this.#now();
    if (Object.keys(likes).length) this.#likes.set(c.id, likes); else this.#likes.delete(c.id);
    return this.courseDetail(actor, c.id);
  }
  reviewCourse(actor, id, { rating, text } = {}) {
    if (actor.kind !== 'user') fail(403, '후기는 휴대폰에서 로그인한 뒤 남겨 주세요');
    const c = this.#course(id); this.#notOurs(actor, c, '후기');
    const stars = Number(rating), body = clean(text, 300);
    if (!Number.isInteger(stars) || stars < 1 || stars > 5) fail(400, '별점은 1~5 사이로 골라 주세요');
    if (!body) fail(400, '후기 내용을 적어 주세요');
    const mine = [...this.#reviews.values()].find(r => r.courseId === c.id && r.userId === actor.user.id);
    if (mine) { mine.rating = stars; mine.text = body; mine.createdAt = this.#now(); }
    else {
      if ([...this.#reviews.values()].filter(r => r.courseId === c.id).length >= LIMITS.reviewsPerCourse) fail(409, '이 코스에는 후기가 너무 많아요');
      const r = { id: randomUUID(), courseId: c.id, userId: actor.user.id, rating: stars, text: body, createdAt: this.#now() };
      this.#reviews.set(r.id, r);
    }
    return this.courseDetail(actor, c.id);
  }
  deleteMyReview(actor, id) {
    if (actor.kind !== 'user') fail(403, '휴대폰에서 로그인해 주세요');
    const c = this.#course(id);
    for (const [k, r] of this.#reviews) if (r.courseId === c.id && r.userId === actor.user.id) this.#reviews.delete(k);
    return this.courseDetail(actor, c.id);
  }
  /**
   * 다녀온 여행을 다른 가족도 볼 수 있는 코스로 공개한다.
   * - 끝난 여행만, 여행을 정리할 수 있는 사람(관리자·만든 사람·운전자)만 공개한다.
   * - 추천 코스로 만든 여행은 다시 공개하지 않는다(좋아요·후기·추억 저장만).
   * - 장소와 제목·소개만 공개하고, 사진·가족 이름·제안한 사람·이유는 공개하지 않는다.
   */
  publishCourse(actor, { tripId, title, description = '', placeIds = [] } = {}) {
    if (actor.kind !== 'user') fail(403, '코스 공개는 휴대폰에서 해 주세요');
    const t = this.#trip(actor, tripId); this.#requireManage(actor, t);
    if (t.status !== 'DONE') fail(409, '다녀온 여행만 코스로 공개할 수 있어요');
    if (t.courseId) fail(409, '추천 코스로 다녀온 여행은 다시 공개할 수 없어요. 그 코스에 좋아요나 후기를 남겨 주세요');
    if (t.publishedCourseId && this.#courses.has(t.publishedCourseId)) fail(409, '이미 코스로 공개한 여행이에요');
    if (!t.destination) fail(409, '목적지가 있는 여행만 공개할 수 있어요');
    if (!Array.isArray(placeIds) || placeIds.some(id => !t.stops.includes(id))) fail(400, '공개할 장소를 확인해 주세요');
    const name = clean(title, 40) || t.title;
    if (this.#courses.size >= LIMITS.courses) fail(409, '지금은 코스를 더 공개할 수 없어요');
    const c = { id: randomUUID(), kind: 'COMMUNITY', familyId: t.familyId, tripId: t.id, publishedBy: actor.user.id, title: name, description: clean(description, 300),
      destination: { ...t.destination }, waypoints: t.stops.filter(id => placeIds.includes(id)).map(id => ({ ...this.#proposals.get(id).place })), createdAt: this.#now() };
    this.#courses.set(c.id, c); t.publishedCourseId = c.id;
    return this.courseDetail(actor, c.id);
  }
  unpublishCourse(actor, id) {
    const c = this.#courses.get(id);
    if (!c || actor.kind !== 'user' || c.familyId !== actor.family?.id) fail(404, '그 코스를 찾을 수 없어요');
    if (c.publishedBy !== actor.user.id && actor.role !== 'ADMIN') fail(403, '공개한 사람이나 가족 관리자만 내릴 수 있어요');
    this.#dropCourse(c.id);
  }

  /** 추억 앨범: 끝난 여행을 최근 순서로. 자세한 내용은 tripDetail로 본다. */
  album(actor) {
    const f = this.#requireFamily(actor);
    return [...this.#trips.values()].filter(t => t.familyId === f.id && t.status === 'DONE').sort((a, b) => b.finishedAt - a.finishedAt).map(t => {
      const visited = t.stops.map(id => this.#proposals.get(id)).filter(p => p?.status === 'VISITED');
      return { ...this.#tripSummary(t), visitedCount: visited.length };
    });
  }
}
