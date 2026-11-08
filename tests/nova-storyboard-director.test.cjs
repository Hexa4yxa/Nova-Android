const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const planner=require('../src/nova_storyboard.js');
const uri=n=>'content://media/external/images/media/'+n;
const makeStory=(count=6)=>({
 id:'story-seven',title:'Танец ангела',idea:'Танец под закат',
 hero:'angel in a straw hat',style:'sunset sunflower field',
 scenes:Array.from({length:count},(_,index)=>({
  number:index+1,beat:'Движение '+(index+1),prompt:'angel dancing',
  imageUri:uri(index+20),renderSeconds:0
 }))
});
test('six saved pictures generate 15 varied camera shots with no image generation',()=>{
 const story=makeStory(6),before=JSON.stringify(story);
 const shots=planner.plan(story);
 assert.equal(shots.length,15);
 assert.equal(new Set(shots.map(s=>s.imageUri)).size,6);
 assert.ok(shots.some(s=>s.plan==='close'));
 assert.ok(shots.some(s=>s.plan==='wide'));
 assert.ok(shots.some(s=>s.plan==='medium'));
 assert.ok(shots.some(s=>s.motion==='pan-right'));
 assert.ok(shots.some(s=>s.motion==='tilt-up'));
 assert.ok(shots.every(s=>s.durationSeconds>=2&&s.durationSeconds<=5));
 assert.ok(shots.every(s=>/content:\/\/media\/external\/images\/media\/\d+/.test(s.imageUri)));
 assert.equal(JSON.stringify(story),before,'never modify the original images or project');
});
test('old three-scene stories can render and invalid image references are skipped',()=>{
 const old=makeStory(3);
 assert.equal(planner.plan(old).length,9);
 old.scenes[1].imageUri='saved';
 const shots=planner.plan(old);
 assert.equal(shots.length,6);
 assert.ok(shots.every(s=>s.imageUri!== 'saved'));
 assert.equal(planner.plan({scenes:[]}).length,0);
 assert.equal(planner.plan(null).length,0);
});
test('preview and director export are offered from existing project without changing legacy button',async()=>{
 class Element{
  constructor(tag){this.tagName=tag.toUpperCase();this.children=[];this.textContent='';
   this.value='';this.style={};this.hidden=false;this.disabled=false;this.attributes={};}
  appendChild(child){this.children.push(child);return child;}
  replaceChildren(...children){this.children=[];children.forEach(x=>this.appendChild(x));}
  setAttribute(k,v){this.attributes[k]=v;}
  focus(){}
 }
 const walk=e=>[e,...e.children.flatMap(walk)];
 const storage=new Map();
 const localStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,String(v))};
 const calls=[];
 const motion={
  render:async args=>{
   calls.push(args);
   return {saved:true,videoUri:'content://media/external/video/media/999',seconds:36,silent:true};
  },
  addListener:async()=>({remove:async()=>{}}),
  cancel:async()=>({requested:true}),
  openVideo:async()=>({opened:true})
 };
 const window={Capacitor:{Plugins:{NovaMotion:motion}}};
 const context=vm.createContext({window,document:{createElement:t=>new Element(t)},localStorage,
  confirm:()=>true,Number,String,Set});
 for(const path of ['src/nova_storyboard.js','src/nova_story_director.js','src/nova_story_ui.js'])
  vm.runInContext(fs.readFileSync(path,'utf8'),context,{filename:path});
 const original=makeStory(6);
 assert.ok(window.NovaStoryDirector.write([original]));
 const content=new Element('main'),api={
  content,brain:{},generate:async()=>{},resumeStory:async()=>{},isBusy:()=>false
 };
 window.NovaStoryUI.render(api);
 const getButton=t=>walk(content).find(e=>e.tagName==='BUTTON'&&e.textContent.includes(t));
 assert.equal(getButton('Создать видеокомикс').disabled,false);
 const preview=getButton('Планы (15)');
 assert.ok(preview);
 await preview.onclick();
 const texts=walk(content).map(x=>x.textContent);
 assert.ok(texts.some(s=>s.includes('Панорама')));
 const director=getButton('Режиссёрский монтаж · 15 планов');
 assert.equal(director.disabled,false);
 await director.onclick();
 assert.equal(calls.length,1);
 assert.equal(calls[0].shots.length,15);
 assert.ok(!('frames' in calls[0]));
 const saved=window.NovaStoryDirector.read()[0];
 assert.equal(saved.movieUri,'content://media/external/video/media/999');
 assert.equal(saved.scenes.length,6);
 assert.deepEqual(Array.from(saved.scenes.map(s=>s.imageUri)),original.scenes.map(s=>s.imageUri));
});
test('native Android encoder supports shot plans while retaining simple video mode',()=>{
 const src=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaMotionPlugin.kt','utf8');
 assert.match(src,/call\.getArray\("shots"\)/);
 assert.match(src,/call\.getArray\("frames"\)/);
 assert.match(src,/shots\.length\(\) in 1\.\.16|editList\.length\(\) in 1\.\.16/);
 assert.match(src,/checkedImageUri/);
 assert.match(src,/sumOf \{ it\.seconds \}/);
 assert.match(src,/renderer\.draw\(\s*progress,\s*scene,\s*shot\.plan,\s*shot\.motion,\s*shot\.effect\s*\)/);
 assert.match(src,/"pan-left"/);
 assert.match(src,/"close" -> 1\.65f/);
 assert.match(src,/MediaCodec\.createEncoderByType\(MediaFormat\.MIMETYPE_VIDEO_AVC\)/);
});
