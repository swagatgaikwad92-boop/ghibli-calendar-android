const fs=require('fs'), vm=require('vm');
let pass=0, fail=0; const ok=(n,c)=>{ if(c){pass++;console.log('PASS  '+n);} else {fail++;console.log('FAIL  '+n);} };
function run(injected){
  const store={}; const pushed=[]; const notes=[]; const listeners={};
  class Storage{ setItem(k,v){ store[k]=String(v); } getItem(k){ return k in store?store[k]:null; } }
  const localStorage=new Storage();
  class SWR{}
  const win={ Storage, localStorage, ServiceWorkerRegistration:SWR, addEventListener:(e,f)=>{listeners[e]=f},
    GFNative:{ saveState:s=>pushed.push(s), permission:()=> 'granted', requestPermission:()=>{ win.__gfPerm('granted'); },
      showNotification:(t,b,d,tag,a)=>notes.push({t,b,d,tag,a}) } };
  win.window=win;
  let code=fs.readFileSync('android-src/gf-bridge.js','utf8');
  const lit = injected==null ? 'null' : JSON.stringify(injected);
  code=code.replace('__GF_STATE__', lit);
  vm.createContext(win); vm.runInContext(code, win, {});
  return {win, store, pushed, notes, listeners, localStorage, SWR};
}
(async()=>{
  let r=run('{"tasks":[{"id":"a","done":true}]}');
  ok('injects native data into localStorage before the website loads', r.store['ghibliforest_v1']==='{"tasks":[{"id":"a","done":true}]}');
  r.localStorage.setItem('ghibliforest_v1','{"tasks":[]}');
  ok('website save is forwarded to the phone', r.pushed.length===1 && r.pushed[0]==='{"tasks":[]}');
  r.localStorage.setItem('other_key','x');
  ok('other keys are not forwarded', r.pushed.length===1);
  r.listeners.load(); ok('load pushes current state once', r.pushed.length===2);
  r=run(null); ok('no native data => localStorage untouched', !('ghibliforest_v1' in r.store));
  ok('Notification.permission maps to Android', r.win.Notification.permission==='granted');
  const p=await r.win.Notification.requestPermission(); ok('Notification.requestPermission resolves', p==='granted');
  const reg=new r.SWR(); await reg.showNotification('Hi',{body:'B',tag:'t1',data:{day:'2026-11-21'},actions:[{action:'mark-done:abc',title:'Done'}]});
  ok('registration.showNotification becomes an Android notification', r.notes.length===1 && r.notes[0].t==='Hi' && r.notes[0].d==='2026-11-21' && JSON.parse(r.notes[0].a)[0].a==='mark-done:abc');
  console.log(`\n${pass} passed, ${fail} failed`); process.exit(fail?1:0);
})();
