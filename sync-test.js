const S = require('../website-with-sync/sync.js');
let pass=0, fail=0;
const ok=(n,c)=>{ if(c){pass++;console.log('PASS  '+n);} else {fail++;console.log('FAIL  '+n);} };

// ---- fake GitHub (sha based, like the real contents API) ----
function makeGitHub(){ const g={file:null, sha:0, puts:0, failNextPut409:0, corrupt:false, status:null};
  g.fetch = async (url, opts={})=>{
    const method=(opts.method||'GET');
    if(g.status) return {status:g.status, ok:false, json:async()=>({})};
    if(method==='GET'){
      if(g.file===null) return {status:404, ok:false, json:async()=>({})};
      const text = g.corrupt ? 'not json {{' : g.file;
      return {status:200, ok:true, json:async()=>({sha:'s'+g.sha, encoding:'base64', content:Buffer.from(text).toString('base64').replace(/(.{60})/g,'$1\n')})};
    }
    const b=JSON.parse(opts.body); g.puts++;
    if(g.failNextPut409>0){ g.failNextPut409--; // simulate another device writing first
      return {status:409, ok:false, json:async()=>({})}; }
    const curSha = g.file===null? undefined : 's'+g.sha;
    if(b.sha!==curSha) return {status:(g.file===null?422:409), ok:false, json:async()=>({})};
    g.file=Buffer.from(b.content,'base64').toString('utf8'); g.sha++; return {status:200, ok:true, json:async()=>({})};
  }; return g; }

function makeDevice(g, initial, cfg=true){
  const store={}; const ls={ getItem:k=>k in store?store[k]:null, setItem:(k,v)=>{store[k]=String(v)}, removeItem:k=>{delete store[k]}, key:i=>Object.keys(store)[i], get length(){return Object.keys(store).length} };
  if(initial) ls.setItem(S.KEY, JSON.stringify(initial));
  if(cfg) ls.setItem(S.CFG, JSON.stringify({owner:'me',repo:'data',token:'t'}));
  const d={ls, reloads:0, selfWrites:0, state:()=>JSON.parse(ls.getItem(S.KEY)),
    env:{ls, fetch:g.fetch, now:()=>Date.now()+Math.random(), selfWrite:()=>{d.selfWrites++}, apply:()=>{d.reloads++}} };
  d.sync=()=>S.syncOnce(d.env); return d; }

const T=(id,title,extra={})=>Object.assign({id,title,date:'2026-11-21',time:null,category:'study',done:false,subtasks:[],futureField:'keep'},extra);
const E=(id,title,extra={})=>Object.assign({id,title,date:'2026-11-21',startTime:'10:00',category:'meeting',updatedAt:'2026-11-21T00:00:00.000Z'},extra);
const ids=a=>a.map(x=>x.id).sort().join(',');
const st=(tasks=[],events=[],more={})=>Object.assign({version:1,settings:{theme:'auto'},tasks,events,reminders:[],days:{},streak:{count:0,lastActiveDate:null},companion:{mood:'content'},notifyLog:{x:1}},more);

(async()=>{
  // 1 inert without config
  { const g=makeGitHub(); const d=makeDevice(g, st([T('a','A')]), false); const r=await d.sync(); ok('1 inert when not configured (no network, no change)', r.skipped && g.puts===0); }

  // 2 first sync pushes; cloud has only synced keys
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')],[E('e1','Meet')])); const r=await A.sync();
    const cloud=JSON.parse(g.file);
    ok('2 first sync creates cloud file', r.ok && g.puts===1 && ids(cloud.tasks)==='a' && ids(cloud.events)==='e1');
    ok('2 device-only keys never uploaded', !('settings' in cloud) && !('notifyLog' in cloud) && !('companion' in cloud));
    ok('2 local data untouched, no reload', A.reloads===0 && A.state().settings.theme==='auto' && A.state().tasks[0].futureField==='keep'); }

  // 3 second device first sync: union, nothing deleted, backup taken, reload requested
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); await A.sync();
    const B=makeDevice(g, st([T('b','B only on phone')])); const r=await B.sync();
    ok('3 union on first sync (both kept)', ids(B.state().tasks)==='a,b' && ids(JSON.parse(g.file).tasks)==='a,b');
    ok('3 backup made + reload requested', r.localChanged && B.reloads===1 && Array.from({length:B.ls.length},(_,i)=>B.ls.key(i)).some(k=>k.indexOf('gf_sync_backup_')===0));
    ok('3 device-only keys of B preserved', B.state().notifyLog.x===1 && B.state().settings.theme==='auto');
    await A.sync(); ok('3 A receives B\'s task', ids(A.state().tasks)==='a,b'); }

  // 4 edits and completion propagate
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); await A.sync(); const B=makeDevice(g, st()); await B.sync();
    const s=A.state(); s.tasks[0].done=true; s.tasks[0].doneAt='2026-11-21T10:00:00.000Z'; A.ls.setItem(S.KEY,JSON.stringify(s)); await A.sync();
    await B.sync(); ok('4 completion on A reaches B', B.state().tasks[0].done===true && B.state().tasks[0].doneAt); }

  // 5 deletion propagates (needs base)
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A'),T('b','B'),T('c','C')])); await A.sync(); const B=makeDevice(g, st()); await B.sync();
    const s=A.state(); s.tasks=s.tasks.filter(t=>t.id!=='b'); A.ls.setItem(S.KEY,JSON.stringify(s)); await A.sync();
    ok('5 delete pushed to cloud', ids(JSON.parse(g.file).tasks)==='a,c');
    await B.sync(); ok('5 delete reaches B', ids(B.state().tasks)==='a,c'); }

  // 6 concurrent edits of DIFFERENT items are both kept
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A'),T('b','B')])); await A.sync(); const B=makeDevice(g, st()); await B.sync();
    let s=A.state(); s.tasks[0].title='A edited on A'; A.ls.setItem(S.KEY,JSON.stringify(s));
    let t=B.state(); t.tasks[1].title='B edited on B'; t.tasks.push(T('n','new on B')); B.ls.setItem(S.KEY,JSON.stringify(t));
    await A.sync(); await B.sync(); await A.sync();
    const fa=A.state().tasks, fb=B.state().tasks;
    ok('6 both edits + new item present on both devices', ids(fa)===ids(fb) && fa.find(x=>x.id==='a').title==='A edited on A' && fa.find(x=>x.id==='b').title==='B edited on B' && fa.some(x=>x.id==='n')); }

  // 7 same item edited on both: this device wins; no data duplicated
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); await A.sync(); const B=makeDevice(g, st()); await B.sync();
    let s=A.state(); s.tasks[0].title='from A'; A.ls.setItem(S.KEY,JSON.stringify(s)); await A.sync();
    let t=B.state(); t.tasks[0].title='from B'; B.ls.setItem(S.KEY,JSON.stringify(t)); await B.sync(); await A.sync();
    ok('7 conflict resolved the same everywhere, no duplicates', A.state().tasks.length===1 && A.state().tasks[0].title==='from B' && B.state().tasks[0].title==='from B'); }

  // 8 edit beats delete
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A'),T('z','Z')])); await A.sync(); const B=makeDevice(g, st()); await B.sync();
    let s=A.state(); s.tasks=s.tasks.filter(t=>t.id!=='a'); A.ls.setItem(S.KEY,JSON.stringify(s)); await A.sync();
    let t=B.state(); t.tasks.find(x=>x.id==='a').title='edited on B'; B.ls.setItem(S.KEY,JSON.stringify(t)); await B.sync(); await A.sync();
    ok('8 edit on B beats delete on A', A.state().tasks.some(x=>x.id==='a'&&x.title==='edited on B')); }

  // 9 409 retry
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); await A.sync(); g.failNextPut409=2;
    let s=A.state(); s.tasks.push(T('q','Q')); A.ls.setItem(S.KEY,JSON.stringify(s)); const r=await A.sync();
    ok('9 retries after 409 conflicts and succeeds', r.ok && ids(JSON.parse(g.file).tasks)==='a,q'); }

  // 10 invalid cloud file: nothing changes
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); await A.sync(); g.corrupt=true; const before=A.ls.getItem(S.KEY); const puts=g.puts;
    const r=await A.sync(); ok('10 corrupt cloud file => stop, local untouched, nothing uploaded', !r.ok && A.ls.getItem(S.KEY)===before && g.puts===puts); }

  // 11 bad token / network errors leave data alone
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); g.status=401; const before=A.ls.getItem(S.KEY); const r=await A.sync();
    ok('11 401 => clear error, data untouched', !r.ok && /token/i.test(r.error) && A.ls.getItem(S.KEY)===before); g.status=null;
    const g2={fetch:async()=>{throw new Error('offline')}}; A.env.fetch=g2.fetch; const r2=await A.sync(); ok('11 offline => no crash, data untouched', !r2.ok && A.ls.getItem(S.KEY)===before); }

  // 12 mass-deletion safety net
  { const g=makeGitHub(); const many=Array.from({length:10},(_,i)=>T('t'+i,'T'+i)); const A=makeDevice(g, st(many)); await A.sync(); 
    A.ls.setItem(S.KEY, JSON.stringify(st([T('t0','T0')]))); // e.g. a corrupted / reset local copy
    const r=await A.sync(); ok('12 refuses to wipe 9 of 10 items', !r.ok && /Sync stopped/.test(r.error) && ids(JSON.parse(g.file).tasks).split(',').length===10); }

  // 13 user edits while sync is in flight: edit is not overwritten
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); await A.sync(); const B=makeDevice(g, st()); await B.sync();
    let s=A.state(); s.tasks.push(T('r','remote add')); A.ls.setItem(S.KEY,JSON.stringify(s)); await A.sync();
    const slow=B.env.fetch; B.env.fetch=async(u,o)=>{ const r=await slow(u,o); if((o&&o.method)==='PUT'||true){ const x=B.state(); if(!x.tasks.some(t=>t.id==='mid')){ x.tasks.push(T('mid','typed during sync')); B.ls.setItem(S.KEY,JSON.stringify(x)); } } return r; };
    await B.sync(); B.env.fetch=slow; await B.sync(); await A.sync();
    ok('13 edit made during sync survives and reaches the other device', B.state().tasks.some(t=>t.id==='mid') && A.state().tasks.some(t=>t.id==='mid') && B.state().tasks.some(t=>t.id==='r')); }

  // 14 idempotent: syncing again changes nothing, no reload loop
  { const g=makeGitHub(); const A=makeDevice(g, st([T('a','A')])); await A.sync(); const B=makeDevice(g, st([T('b','B')])); await B.sync(); const rl=B.reloads; const puts=g.puts;
    const r=await B.sync(); const r2=await B.sync(); ok('14 repeated sync is a no-op (no reload, no upload)', !r.localChanged && !r2.localChanged && B.reloads===rl && g.puts===puts); }

  // 15 days (notes/journal) and streak
  { const g=makeGitHub(); const A=makeDevice(g, st([],[],{days:{'2026-11-20':{mood:'happy',notes:'x'}},streak:{count:3,lastActiveDate:'2026-11-21'}})); await A.sync();
    const B=makeDevice(g, st([],[],{days:{'2026-11-19':{mood:'tired'}},streak:{count:1,lastActiveDate:'2026-11-20'}})); await B.sync();
    ok('15 day notes merged, newest streak wins', Object.keys(B.state().days).sort().join()==='2026-11-19,2026-11-20' && B.state().streak.count===3); }

  console.log(`\n${pass} passed, ${fail} failed`); process.exit(fail?1:0);
})();
