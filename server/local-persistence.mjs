import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';

// Local demonstration only. The key and encrypted archive are kept outside the repository.
export function openLocalArchive(directory) {
  mkdirSync(directory, {recursive:true, mode:0o700});
  const keyPath=join(directory,'archive.key'), path=join(directory,'family-archive.enc'), tmp=join(directory,'family-archive.tmp');
  if (!existsSync(keyPath)) writeFileSync(keyPath,randomBytes(32),{mode:0o600,flag:'wx'});
  const key=readFileSync(keyPath);
  if (key.length!==32) throw new Error('가족 추억 저장 키를 읽을 수 없습니다');
  function load() {
    if (!existsSync(path)) return null;
    const raw=readFileSync(path);if(raw.length<29 || raw[0]!==1)throw new Error('가족 추억 저장 파일을 읽을 수 없습니다');
    const decipher=createDecipheriv('aes-256-gcm',key,raw.subarray(1,13));decipher.setAuthTag(raw.subarray(13,29));
    return JSON.parse(Buffer.concat([decipher.update(raw.subarray(29)),decipher.final()]).toString('utf8'));
  }
  function save(snapshot) {
    const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
    const bytes=Buffer.concat([cipher.update(JSON.stringify(snapshot),'utf8'),cipher.final()]);
    const raw=Buffer.concat([Buffer.from([1]),iv,cipher.getAuthTag(),bytes]);
    writeFileSync(tmp,raw,{mode:0o600});renameSync(tmp,path);
  }
  return {load,save};
}
