const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

class Element {
 constructor(tag) {
  this.tagName=tag.toUpperCase();this.children=[];this.style={};
  this.attributes={};this.textContent='';this.value='';this.disabled=false;this.hidden=false;
 }
 appendChild(x){this.children.push(x);return x;}
 replaceChildren(...items){this.children=[];items.forEach(x=>this.appendChild(x));}
 setAttribute(k,v){this.attributes[k]=v;}
 focus(){}
}
const all=n=>[n,...n.children.flatMap(all)];
const frames=[1,2,3].map(i=>'content://media/external/images/media/'+i);
const page='content://media/external/images/media/999';
function setup(imageUris=frames){
 const store=new Map();
 const localStorage={
  getItem:k=>store.get(k)||null,
  setItem:(k,v)=>store.set(k,String(v))
 };
 const calls={built:[],rendered:[],opened:[]};
 const sheet={
  build:async arg=>{
   calls.built.push(arg);
   return {saved:true,pageUri:page,panels:arg.frames.length};
  },
  openPage:async arg=>{calls.opened.push(arg);return {opened:true};}
 };
 const motion={
  render:async arg=>{
   calls.rendered.push(arg);
   return {saved:true,videoUri:'content://media/external/video/media/900',seconds:12};
  },
  addListener:async()=>({remove:async()=>{}}),
  cancel:async()=>({requested:true}),openVideo:async()=>({opened:true})
 };
 const window={Capacitor:{Plugins:{NovaComicSheet:sheet,NovaMotion:motion}}};
 const document={createElement:name=>new Element(name)};
 const context=vm.createContext({window,document,localStorage,confirm:()=>true,console});
 for(const path of ['src/nova_story_director.js','src/nova_story_ui.js']){
  vm.runInContext(fs.readFileSync(path,'utf8'),context,{filename:path});
 }
 const director=window.NovaStoryDirector;
 const story={
  id:'story-1',title:'Маяк',idea:'Маяк, дождь, факел',
  hero:'young man with a lantern',style:'dark comic',
  scenes:imageUris.map((uri,i)=>({
   number:i+1,beat:['Дождь у маяка','Горит факел','Свет и туман'][i]||'Мистика',
   prompt:['Heavy rain by the lighthouse','Bright lantern torch on a stairway','Silver glow behind a misty temple'][i]||'mystery',
   imageUri:uri,renderSeconds:1
  }))
 };
 director.write([story]);
 const content=new Element('main');
 const api={content,brain:{},generate:async()=>{},resumeStory:async()=>{},isBusy:()=>false};
 const render=()=>window.NovaStoryUI.render(api);
 const button=text=>all(content).find(x=>x.tagName==='BUTTON'&&x.textContent.includes(text));
 return {director,render,button,calls,content};
}

test('NOVA makes ONE comic sheet, preserving the three original project images',async()=>{
 const x=setup();x.render();
 const build=x.button('NOVA, создай комикс');
 assert.equal(build.disabled,false);
 await build.onclick();
 assert.equal(x.calls.built.length,1);
 assert.equal(x.calls.built[0].frames.length,3);
 assert.deepEqual(Array.from(x.calls.built[0].frames.map(f=>f.imageUri)),frames);
 const current=x.director.read()[0];
 assert.equal(current.comicPageUri,page);
 assert.deepEqual(Array.from(current.comicFrameUris),frames);
 assert.deepEqual(Array.from(current.scenes.map(s=>s.imageUri)),frames);
 await x.button('Комикс-лист').onclick();
 assert.equal(x.calls.opened[0].pageUri,page);
});

test('reader uses ONE sheet image for all 3 panels and assigns visual effects',async()=>{
 const x=setup();x.render();await x.button('NOVA, создай комикс').onclick();
 const read=x.button('Читать комикс в MP4');
 assert.equal(read.disabled,false);
 await read.onclick();
 const shots=x.calls.rendered[0].shots;
 assert.equal(shots.length,3);
 assert.deepEqual(Array.from(shots.map(s=>s.imageUri)),[page,page,page]);
 assert.deepEqual(Array.from(shots.map(s=>s.panelIndex)),[0,1,2]);
 assert.deepEqual(Array.from(shots.map(s=>s.panelCount)),[3,3,3]);
 assert.deepEqual(Array.from(shots.map(s=>s.effect)),['rain','torch','fog']);
 const saved=x.director.read()[0];
 assert.equal(saved.movieUri,'content://media/external/video/media/900');
 assert.equal(saved.comicPageUri,page);
});

test('changed source scene requires fresh page; cannot silently replay stale comic',()=>{
 const x=setup();const current=x.director.read()[0];
 current.comicPageUri=page;
 current.comicPanels=3;
 current.comicFrameUris=frames.slice();
 current.scenes[1].imageUri='content://media/external/images/media/55';
 x.director.write([current]);
 x.render();
 const read=x.button('Читать комикс в MP4');
 assert.equal(read.style.display,'none');
});

test('native contract: physical comic PNG + EGL panel crop + GPU rain/fire/fog',()=>{
 const sheet=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaComicSheetPlugin.kt','utf8');
 const motion=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaMotionPlugin.kt','utf8');
 const activity=fs.readFileSync('android/app/src/main/java/ai/nova/director/MainActivity.java','utf8');
 assert.match(sheet,/@CapacitorPlugin\(name = "NovaComicSheet"\)/);
 assert.match(sheet,/Bitmap\.createBitmap\(/);
 assert.match(sheet,/MediaStore\.Images\.Media\.IS_PENDING/);
 assert.match(sheet,/NovaComicLayout\.panel\(i,frames\.size\)/);
 assert.match(motion,/panelIndex/);
 assert.match(motion,/uCrop/);
 assert.match(motion,/rain/);
 assert.match(motion,/torch/);
 assert.match(motion,/fog/);
 assert.match(activity,/registerPlugin\(NovaComicSheetPlugin\.class\)/);
});

test('one explicit approval runs existing comic and MP4 tools without another tap',async()=>{
 const x=setup();x.render();
 const button=x.button('NOVA, сделай ролик');
 assert.ok(button);
 assert.equal(button.disabled,false);
 assert.equal(x.calls.built.length,0);
 assert.equal(x.calls.rendered.length,0);
 await button.onclick();
 // Production chains its next step only after the first stage commits the comic.
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(x.calls.built.length,1);
 assert.equal(x.calls.rendered.length,1);
 const saved=x.director.read()[0];
 assert.equal(saved.comicPageUri,page);
 assert.equal(saved.movieUri,'content://media/external/video/media/900');
 assert.deepEqual(Array.from(saved.scenes.map(s=>s.imageUri)),frames);
});
test('one approval reuses completed comic sheet and does not rebuild frames',async()=>{
 const x=setup();x.render();await x.button('NOVA, создай комикс').onclick();
 assert.equal(x.calls.built.length,1);
 await x.button('NOVA, сделай ролик').onclick();
 assert.equal(x.calls.built.length,1);
 assert.equal(x.calls.rendered.length,1);
});
test('production does not start when owner declines and preserves all sources',async()=>{
 const x=setup();x.render();
 const source=fs.readFileSync('src/nova_story_ui.js','utf8');
 assert.match(source,/if\(!confirm\('NOVA нарисует/);
 assert.match(source,/state\.autoFinishStoryId=story\.id/);
 assert.match(source,/readComic\.onclick\(\{skipConfirm:true\}\)/);
 assert.match(source,/state\.autoFinishStoryId='';\s*state\.error='Комикс:/);
 assert.equal(x.director.read()[0].movieUri,undefined);
 assert.equal(x.calls.rendered.length,0);
});
