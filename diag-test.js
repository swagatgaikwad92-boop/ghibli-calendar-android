const fs=require('fs'),vm=require('vm');
const html=fs.readFileSync('../website-restored/diagnose.html','utf8'); const code=html.match(/<script>([\s\S]*?)<\/script>/)[1];
function run(existing, url){
  const els={}; const mk=id=>els[id]||(els[id]={id,children:[],innerHTML:'',textContent:'',className:'',appendChild(c){this.children.push(c)},set onclick(f){this._c=f}});
  const doc={getElementById:mk, createElement:()=>({className:'',innerHTML:'',children:[{},{}],set textContent(v){}})};
  const win={location:{href:url}, document:doc, navigator:{serviceWorker:{getRegistrations:async()=>[]}}, caches:{keys:async()=>[]},
    fetch:async(u)=>{ const name=u.split('/').pop().split('?')[0]; const ok=existing.includes(name); return {ok,status:ok?200:404}; }, Date, Intl, Promise, String, Math, encodeURIComponent};
  win.window=win; Object.assign(win,{document:doc});
  vm.createContext(win); vm.runInContext(code,win);
  return new Promise(r=>setTimeout(()=>r(els.verdict.innerHTML),50));
}
const ALL=["index.html","core.css","components.css","themes.css","manifest.json","sw.js","dabsy-core.js","state.js","companion.js","calendar.js","tasks.js","search.js","render.js","notify.js","voice.js","engine.js","app.js","icon-180.png","icon-192.png","icon-512.png"];
(async()=>{
  let a=await run(ALL,'https://u.github.io/Ghibli-Calander/diagnose.html'); console.log(/All files found/.test(a)?'PASS all present':'FAIL all present');
  let b=await run([], 'https://u.github.io/Ghibli-Calander/optional-website-sync/diagnose.html'); console.log(/Nothing else is in this folder/.test(b)?'PASS wrong folder detected':'FAIL wrong folder');
  let c=await run(ALL.filter(x=>x!=='core.css'&&x!=='app.js'),'https://u.github.io/x/diagnose.html'); console.log(/2 file\(s\) missing.*core.css, app.js/.test(c)?'PASS partial missing listed':'FAIL partial: '+c);
})();
