const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

function setup(){
 const memory=new Map();
 const localStorage={
  getItem:key=>memory.get(key)||null,
  setItem:(key,value)=>memory.set(key,String(value))
 };
 const window={};
 const context=vm.createContext({window,localStorage,Date,JSON,Error,Number,String,Array,Object,Math,Set});
 vm.runInContext(fs.readFileSync('src/nova_story_director.js','utf8'),context);
 vm.runInContext(fs.readFileSync('src/nova_story_chat.js','utf8'),context);
 return {director:window.NovaStoryDirector,chat:window.NovaStoryChat};
}
const hero='young man in a black winter jacket';
const style='cinematic horror cold blue shadows';
const base={
 title:'Дом за зеркалом',hero,style,scenes:[
  {beat:'Он подходит к зеркалу',prompt:'A young man in a winter jacket approaches a tall mirror in a dim bedroom, cold light on his face, medium shot.'},
  {beat:'Отражение не повторяет движение',prompt:'A young man raises his right hand in a bedroom mirror while his reflection stays motionless, cinematic close up.'},
  {beat:'Он отходит от зеркала',prompt:'A young man steps away from a bedroom mirror as the lights flicker above him, wide shot with cold shadows.'}
 ]
};
const continuation={
 title:'Different title',hero:'different protagonist in a red jacket',
 style:'bright summer comedy',scenes:[
  {beat:'Зеркало трескается',prompt:'The same young man watches a crack spread across a tall bedroom mirror while he stands in the darkness, dramatic close up.'},
  {beat:'Из отражения тянется рука',prompt:'The same young man watches a pale hand emerge from the dark bedroom mirror, moonlight reflecting across the wooden floor.'},
  {beat:'Он исчезает',prompt:'A young man disappears inside the bedroom mirror while his reflection steps out into the dark empty room, wide final shot.'}
 ]
};
const original='Парень понимает, что отражение в старом зеркале живёт отдельно от него';

test('two short Gemma prompts produce six ordered scenes with the same character',()=>{
 const {director}=setup();
 const first=director.parse(JSON.stringify(base),original);
 const request=director.continuationRequest(original,first);
 assert.match(request,/scenes FOUR, FIVE, SIX/);
 assert.match(request,/The SAME protagonist MUST be:/);
 assert.match(request,/Отражение не повторяет движение/);
 const result=director.appendContinuation(first,JSON.stringify(continuation));
 assert.equal(result.scenes.length,6);
 assert.deepEqual(Array.from(result.scenes.map(s=>s.number)),[1,2,3,4,5,6]);
 assert.equal(result.id,first.id);
 assert.equal(result.hero,hero);
 assert.equal(result.style,style);
 assert.ok(result.scenes[4].prompt.includes('same protagonist: '+hero));
 assert.ok(!result.scenes[4].prompt.includes('red jacket'));
 assert.ok(result.scenes[5].prompt.includes('visual continuity: '+style));
});

test('six-scene project survives read, backup, and repeated backup merge',()=>{
 const {director}=setup();
 const first=director.parse(JSON.stringify(base),original);
 const six=director.appendContinuation(first,JSON.stringify(continuation));
 six.id='story-six-safe';
 six.scenes[5].imageUri='content://media/external/images/media/77';
 six.movieUri='content://media/external/video/media/88';
 const legacy=director.parse(JSON.stringify(base),original);
 legacy.id='story-three-safe';
 assert.equal(director.write([legacy,six]),true);
 assert.equal(director.read()[0].scenes.length,3);
 assert.equal(director.read()[1].scenes.length,6);
 const backup=director.exportBackup();
 assert.equal(JSON.parse(backup).stories[1].scenes[5].imageUri,six.scenes[5].imageUri);
 assert.equal(director.write([legacy]),true);
 const merged=director.mergeBackup(backup);
 assert.equal(merged.added,1);
 assert.equal(director.read()[1].scenes.length,6);
 assert.equal(director.read()[1].movieUri,six.movieUri);
 assert.equal(director.mergeBackup(backup).added,0);
});

test('chat can plan six scenes, checkpoint once and only then render frames',async()=>{
 const {director,chat}=setup();
 let planCalls=0;
 const brain={
  status:async()=>({engine:'ModelReady'}),
  chat:async req=>{
   planCalls++;
   assert.equal(req.maxTokens,512);
   return {answer:JSON.stringify(planCalls===1?base:continuation)};
  }
 };
 let planned=null;const rendered=[];
 const result=await chat.run({text:original,brain,sceneCount:6,
  generate:async prompt=>{
   assert.equal(director.read().length,1,'plan must be saved before first expensive render');
   rendered.push(prompt);
   return {saved:true,imageUri:'content://media/external/images/media/'+rendered.length,elapsedSeconds:1};
  },
  onPlanned:story=>{planned=story;}
 });
 assert.equal(planCalls,2);
 assert.equal(planned.scenes.length,6);
 assert.equal(result.completed,6);
 assert.equal(rendered.length,6);
 assert.equal(director.read()[0].scenes.filter(s=>s.imageUri).length,6);
});

test('failed second Gemma reply cannot erase existing saved project',async()=>{
 const {director,chat}=setup();
 const legacy=director.parse(JSON.stringify(base),original);
 legacy.id='old';
 director.write([legacy]);
 let calls=0;
 await assert.rejects(()=>chat.run({text:original,sceneCount:6,
  brain:{status:async()=>({engine:'ModelReady'}),chat:async()=>{
   calls++;return {answer:calls===1?JSON.stringify(base):'broken JSON'};
  }},
  generate:async()=>{throw Error('Must not start GPU or CPU rendering');}
 }),/раскадровку|JSON/);
 assert.equal(director.read().length,1);
 assert.equal(director.read()[0].id,'old');
});

test('UI and real chat request six scenes without changing old 3-scene projects',()=>{
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 const html=fs.readFileSync('src/index.html','utf8');
 assert.match(ui,/придумай 6 сцен/);
 assert.match(ui,/director\.continuationRequest\(state\.idea,first,state\.councilBrief\)/);
 assert.match(ui,/director\.appendContinuation\(first,second\?\.answer\)/);
 assert.match(html,/text,brain,sceneCount:6/);
});
