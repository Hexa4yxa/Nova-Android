const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const load=p=>fs.readFileSync(p,'utf8');
class Element {
 constructor(tag){this.tagName=tag.toUpperCase();this.children=[];this.textContent='';
  this.style={};this.attributes={};this.hidden=false;this.disabled=false;this.value='';}
 appendChild(el){this.children.push(el);return el;}
 replaceChildren(...els){this.children=[...els];}
 setAttribute(name,value){this.attributes[name]=value;}
 focus(){}
}
const all=el=>[el,...el.children.flatMap(all)];
const STAGES={
 title:'Ранний свет',hero:'young person with a brown coat',
 style:'cinematic storybook illustration',
 scenes:[
  {beat:'Первый кадр',prompt:'A person in a brown coat walks along a narrow street at sunrise with warm dramatic light.'},
  {beat:'Второй кадр',prompt:'The same person visits a small brick building beside the road on a cloudy morning.'},
  {beat:'Третий кадр',prompt:'The person watches the sun appear above the hills, a quiet cinematic ending.'}
 ]};
function setup(ui=false){
 const store=new Map(),localStorage={
  getItem:key=>store.get(key)||null,
  setItem:(key,val)=>store.set(key,String(val))
 };
 const window={Capacitor:{Plugins:{}}};
 const document={createElement:tag=>new Element(tag)};
 const ctx=vm.createContext({window,document,localStorage,Date,JSON,Error,Number,String,Array,Object,Math,
  Set,confirm:()=>true,setTimeout});
 for(const path of ['src/nova_story_director.js','src/nova_story_chat.js',...(ui?['src/nova_story_ui.js']:[])])
  vm.runInContext(load(path),ctx,{filename:path});
 const director=window.NovaStoryDirector;
 const story=director.parse(JSON.stringify(STAGES),'История уличного света','comic');
 story.id='checkpoint-project';
 director.write([story]);
 const content=new Element('main');
 const button=label=>all(content).find(n=>n.tagName==='BUTTON'&&n.textContent.includes(label));
 return {director,story,content,button,
  chat:window.NovaStoryChat,
  render:api=>window.NovaStoryUI.render({content,brain:{},isBusy:()=>false,...api})};
}
test('missing or fake MediaStore URI never records a completed image checkpoint',async()=>{
 const {director,story,chat}=setup();
 for(const response of [{saved:true},{saved:true,imageUri:'saved'},
  {saved:true,imageUri:'file:///tmp/fake.png'},
  {saved:true,imageUri:'content://media/image/101'},
  {saved:true,imageUri:'content://media/external/video/media/101'},
  {saved:true,imageUri:'content://media/external/images/media/NOT_A_NUMBER'},
  {saved:false,imageUri:'content://media/external/images/media/1'}]){
  await assert.rejects(()=>chat.resumeExisting({
   storyId:story.id,generate:async()=>response
  }),/не подтвердил сохранение PNG/);
  assert.equal(director.read()[0].scenes[0].imageUri,'');
 }
 const urls=['content://media/external/images/media/101','content://media/external/images/media/102','content://media/external/images/media/103'];
 let called=0;
 const done=await chat.resumeExisting({storyId:story.id,generate:async()=>({
  saved:true,imageUri:urls[called++],modelLoadMs:24000,samplingMs:270000
 })});
 assert.equal(done.completed,3);
 assert.equal(called,3);
 assert.deepEqual(Array.from(director.read()[0].scenes,s=>s.imageUri),urls);
 await chat.resumeExisting({storyId:story.id,generate:async()=>{throw Error('Already saved');}});
 assert.equal(called,3);
});
test('Projects shows measured generation cost and never overwrites another saved edit',async()=>{
 const x=setup(true);
 let finish;
 const generated=new Promise(resolve=>{finish=resolve;});
 x.render({generate:async()=>generated});
 const draw=x.button('Создать кадр 1');
 assert.equal(draw.disabled,false);
 const active=draw.onclick();
 // Another UI action can save new project metadata while native CPU sampling
 // is in progress. Completion must patch only the corresponding scene.
 const records=x.director.read();
 records[0].voiceEngineId='preferred-voice';
 records[0].scenes[1].beat='Параллельное изменение сценария';
 assert.ok(x.director.write(records));
 finish({saved:true,imageUri:'content://media/external/images/media/471',
  elapsedSeconds:321,modelLoadMs:21000,samplingMs:300000});
 await active;
 const updated=x.director.read()[0];
 assert.equal(updated.voiceEngineId,'preferred-voice');
 assert.equal(updated.scenes[1].beat,'Параллельное изменение сценария');
 assert.equal(updated.scenes[0].imageUri,'content://media/external/images/media/471');
 assert.equal(updated.scenes[0].modelLoadMs,21000);
 x.render({generate:async()=>{throw Error('Must not regenerate a completed image');}});
 assert.equal(x.button('Создать кадр 1').disabled,true);
 const content=all(x.content).map(e=>e.textContent).join(' ');
 assert.match(content,/Диагностика 1 кадр/);
 assert.match(content,/загрузка SD1.5 0\.3 мин/);
 assert.match(content,/рисование CPU 5\.0 мин/);
});
test('manual generation does not mark an unverified PNG as complete',async()=>{
 const x=setup(true);
 x.render({generate:async()=>({saved:true,imageUri:'saved'})});
 await x.button('Создать кадр 1').onclick();
 assert.equal(x.director.read()[0].scenes[0].imageUri,'');
 assert.equal(x.button('Создать кадр 1').disabled,false);
});

test('late CPU render never replaces an already completed frame or an edited scene',async()=>{
 const x=setup();
 let completeFirst;
 const firstRender=new Promise(resolve=>{completeFirst=resolve;});
 const first=x.chat.resumeExisting({storyId:x.story.id,generate:async()=>firstRender});
 const records=x.director.read();
 records[0].scenes[0].imageUri='content://media/external/images/media/800';
 assert.ok(x.director.write(records));
 completeFirst({saved:true,imageUri:'content://media/external/images/media/801'});
 await assert.rejects(first,/уже сохранён/);
 assert.equal(x.director.read()[0].scenes[0].imageUri,'content://media/external/images/media/800');
 let completeSecond;
 const secondRender=new Promise(resolve=>{completeSecond=resolve;});
 const second=x.chat.resumeExisting({storyId:x.story.id,generate:async()=>secondRender});
 const changed=x.director.read();
 changed[0].scenes[1].prompt='A new approved scene, written while the native image engine was busy.';
 assert.ok(x.director.write(changed));
 completeSecond({saved:true,imageUri:'content://media/external/images/media/802'});
 await assert.rejects(second,/Описание кадра 2 изменилось/);
 assert.equal(x.director.read()[0].scenes[1].imageUri,'');
 assert.equal(x.director.read()[0].scenes[1].prompt,changed[0].scenes[1].prompt);
 assert.equal(x.director.read()[0].scenes[0].imageUri,'content://media/external/images/media/800');
});

test('concurrently completed later frames are skipped and changed future prompts are refreshed',async()=>{
 const x=setup();
 let finishFirst;
 const waiting=new Promise(resolve=>{finishFirst=resolve;});
 const calls=[],onFrameCounts=[];
 const task=x.chat.resumeExisting({storyId:x.story.id,
  generate:async(prompt,progress,scene)=>{
   calls.push({number:scene.number,prompt});
   if(scene.number===1)return waiting;
   return {saved:true,imageUri:'content://media/external/images/media/903'};
  },
  onFrame:({completed})=>onFrameCounts.push(completed)
 });
 // Another app screen saves the second shot and edits the third while the
 // first frame is spending minutes inside the native SD image engine.
 const current=x.director.read();
 current[0].scenes[1].imageUri='content://media/external/images/media/902';
 current[0].scenes[2].prompt='A revised third shot, approved while the renderer was busy, with a different focal point.';
 assert.ok(x.director.write(current));
 finishFirst({saved:true,imageUri:'content://media/external/images/media/901'});
 const done=await task;
 assert.equal(done.completed,3);
 assert.deepEqual(calls.map(c=>c.number),[1,3]);
 assert.equal(calls[1].prompt,current[0].scenes[2].prompt);
 assert.deepEqual(onFrameCounts,[2,3]);
 assert.deepEqual(Array.from(x.director.read()[0].scenes,s=>s.imageUri),[
  'content://media/external/images/media/901',
  'content://media/external/images/media/902',
  'content://media/external/images/media/903'
 ]);
});
