// 받아서 바로 시연: 저장소를 받은 뒤 이 파일 하나만 실행하면 된다(Node.js 24 이상, 추가 설치 없음).
//   node tools/demo.mjs            (Windows는 demo.cmd 더블클릭, macOS/Linux는 ./demo.sh)
// 하는 일: ① 시연 서버 실행 ② 휴대폰 접속 주소 준비(cloudflared가 있으면 임시 https 주소, 없으면 같은 Wi-Fi 주소)
//          ③ 켜져 있는 Pleos Connect 에뮬레이터에 apk/의 APK를 설치하고 앱 실행.
// 옵션: --no-install (APK 설치·실행 생략)  --no-lan (같은 Wi-Fi 공개 안 함)  --port=8789
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const port = Number((args.find(a => a.startsWith('--port=')) || '').slice(7)) || 8789;
const win = process.platform === 'win32';
const exe = n => (win ? n + '.exe' : n);
const say = (...a) => console.log(...a);
const children = [];
const stop = () => { for (const c of children) { try { c.kill(); } catch {} } process.exit(0); };
process.on('SIGINT', stop); process.on('SIGTERM', stop);

if (Number(process.versions.node.split('.')[0]) < 24) { say('Node.js 24 이상이 필요해요. 지금 버전: ' + process.version); process.exit(1); }
if (port !== 8789) say('알림: 차량 앱은 10.0.2.2:8789 를 봅니다. 다른 포트는 휴대폰 화면만 확인할 때 쓰세요.');

const onPath = name => { const r = spawnSync(win ? 'where' : 'which', [name], { encoding: 'utf8' }); return r.status === 0 ? r.stdout.split(/\r?\n/)[0].trim() : ''; };
const firstExisting = list => list.find(p => p && existsSync(p)) || '';
const json = async (url, ms = 3000) => (await fetch(url, { signal: AbortSignal.timeout(ms) })).json();
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ① 시연 서버
let running = false;
try { running = !!(await json(`http://127.0.0.1:${port}/api/preview-instance`, 1500)).instance; } catch {}
const tunnel = firstExisting([process.env.CLOUDFLARED, join(root, 'tools', 'bin', exe('cloudflared')), onPath('cloudflared'), win ? 'C:\\dev\\tools\\cloudflared.exe' : '']);
const lan = !args.includes('--no-lan') && !tunnel;
if (running) say(`① 시연 서버가 이미 켜져 있어요 (http://127.0.0.1:${port}). 그대로 씁니다.`);
else {
  const server = spawn(process.execPath, [join(root, 'tools', 'preview-phone.mjs'), String(port)], { cwd: root, stdio: ['ignore', 'ignore', 'inherit'], env: { ...process.env, ...(lan ? { FAMILY_LAN: '1' } : {}) } });
  children.push(server);
  server.on('exit', code => { say('시연 서버가 멈췄어요 (코드 ' + code + '). 8789 포트를 다른 프로그램이 쓰고 있는지 확인해 주세요.'); process.exit(1); });
  for (let i = 0; i < 40 && !running; i++) { await sleep(250); try { running = !!(await json(`http://127.0.0.1:${port}/api/preview-instance`, 1000)).instance; } catch {} }
  if (!running) { say('시연 서버를 시작하지 못했어요.'); stop(); }
  say(`① 시연 서버 시작: http://127.0.0.1:${port}  (자료는 .runtime/ 에 암호화되어 남아요)`);
}

// ② 휴대폰 접속 주소
if (tunnel) {
  let up = false; try { up = !!(await json('http://127.0.0.1:20241/quicktunnel', 1000)).hostname; } catch {}
  if (!up) children.push(spawn(tunnel, ['tunnel', '--no-autoupdate', '--protocol', 'http2', '--metrics', '127.0.0.1:20241', '--url', `http://127.0.0.1:${port}`], { stdio: 'ignore' }));
}
let phone = '';
for (let i = 0; i < (tunnel ? 30 : 3) && !phone; i++) { try { phone = (await json(`http://127.0.0.1:${port}/api/invite-origin`, 8000)).origin || ''; } catch {} if (!phone) await sleep(1500); }
if (phone) say(`② 휴대폰 주소: ${phone}` + (tunnel ? '  (임시 https 주소)' : '  (같은 Wi-Fi에서만 열려요. 방화벽 허용 창이 뜨면 허용해 주세요)'));
else say('② 휴대폰 주소를 만들지 못했어요. 휴대폰 없이 이 PC 브라우저로 시연할 수 있어요 (아래 안내).');

// ③ 에뮬레이터에 설치·실행
if (!args.includes('--no-install')) {
  const sdk = firstExisting([process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, win ? join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk') : '', join(homedir(), 'Library', 'Android', 'sdk'), join(homedir(), 'Android', 'Sdk')]);
  const adb = firstExisting([sdk && join(sdk, 'platform-tools', exe('adb')), onPath('adb')]);
  const apkDir = join(root, 'apk');
  const apk = existsSync(apkDir) ? readdirSync(apkDir).find(f => f.endsWith('_release.apk')) : '';
  const run = a => spawnSync(adb, a, { encoding: 'utf8', timeout: 120000 });
  if (!adb) say('③ adb를 찾지 못했어요. Android SDK platform-tools를 설치하거나 ANDROID_HOME을 지정한 뒤 다시 실행해 주세요.');
  else if (!apk) say('③ apk/ 폴더에 제출용 APK가 없어요.');
  else {
    const devices = (run(['devices']).stdout || '').split(/\r?\n/).slice(1).filter(l => /\tdevice$/.test(l)).map(l => l.split('\t')[0]);
    if (!devices.length) say('③ 켜져 있는 에뮬레이터가 없어요. Pleos Connect 에뮬레이터를 켠 뒤 다시 실행하면 앱을 설치하고 열어 드려요.');
    else {
      const d = devices.find(x => x.startsWith('emulator-')) || devices[0];
      const r = run(['-s', d, 'install', '-r', join(apkDir, apk)]);
      if (r.status !== 0) say('③ APK 설치 실패: ' + ((r.stdout || '') + (r.stderr || '')).trim().split(/\r?\n/).pop());
      else { run(['-s', d, 'shell', 'am', 'start', '-n', 'ai.familyroute.demo/.MainActivity']); say(`③ ${d} 에 앱을 설치하고 열었어요.`); }
    }
  }
}

say(`
시연 순서
  1. 차량 화면: 가족 › 새 가족방 만들기 (이름 + 비밀번호 숫자 6자리) → 초대 QR이 나와요.
  2. 휴대폰: QR을 찍어 들어오기.${phone ? '' : ' (휴대폰 주소가 없으면 생략)'}
     휴대폰이 없으면 이 PC 브라우저에서 http://localhost:${port} 를 열고, 차량 화면의 초대 코드와 비밀번호로 들어오면 돼요.
  3. 휴대폰에서 가고 싶은 곳 담기 → 차량에서 수락 → 여행 시작 → 도착 → 사진 올리기 → 차량 ‘가족 › 사진’에서 보기.
끝내려면 이 창에서 Ctrl+C 를 누르세요.`);
if (!children.length) process.exit(0);
setInterval(() => {}, 1 << 30);
