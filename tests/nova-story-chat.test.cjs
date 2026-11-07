const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

function setup(){
 const data=new Map();
 const localStorage={
  getItem:k=>data.get(k)||null,
  setItem:(k,v)=>data.set(k,String(v))
 };
 const window={};
 const ctx=vm.createContext({window,localStorage,Date,JSON,Error,Number,String,Array,Object,Math});
 vm.runInContext(fs.readFileSync('src/nova_story_director.js','utf8'),ctx);
 vm.runInContext(fs.readFileSync('src/nova_story_chat.js','utf8'),ctx);
 return {director:window.NovaStoryDirector,bridge:window.NovaStoryChat,window};
}
const storyboard=JSON.stringify({
 title:'Профессор Флип и космический пончик',
 hero:'quirky professor with purple coat and round glasses',
 style:'whimsical cinematic comedy, soft blue space station light',
 scenes:[
  {beat:'Профессор запускает вечеринку',prompt:'A professor in a purple coat prepares a space station dance party beside a round window, colorful planets outside, cinematic wide shot.'},
  {beat:'Шмель танцует на орбите',prompt:'A cheerful round robot vacuum dances in zero gravity inside a futuristic space station, professor reaches for it, colorful party decorations.'},
  {beat:'В небе появляется пончик',prompt:'A huge glowing donut appears outside the space station window while the professor and robot vacuum celebrate, whimsical science fiction shot.'}
 ]
});
function fakeBrain(answer=storyboard){
 let calls=0;
 return {
  status:async()=>({engine:'ModelReady'}),
  prepare:async()=>{},
  chat:async(request)=>{
   calls++;
   assert.equal(request.maxTokens,512);
   assert.match(request.message,/NOVA, a creative film director/);
   return {answer};
  },
  get calls(){return calls}
 };
}
test('classify combined story/art request before generic chat (Russian/Ukrainian)',()=>{
 const {bridge}=setup();
 assert.equal(bridge.wantsStoryImages('Придумай историю веселую . И сделай к ним картинки'),true);
 assert.equal(bridge.wantsStoryImages('Сочини сказку и нарисуй иллюстрации'),true);
 assert.equal(bridge.wantsStoryImages('Вигадай історію та зроби малюнки'),true);
 assert.equal(bridge.wantsStoryImages('Придумай историю без картинок'),false);
 assert.equal(bridge.wantsStoryImages('Нарисуй картинку'),false);
 assert.equal(bridge.wantsStoryImages('Привет, как дела?'),false);
 assert.equal(bridge.wantsStoryImages('Напиши историю'),false);
});
test('actual native saves: plan -> project -> three renders -> picture references',async()=>{
 const {bridge,director}=setup();
 const brain=fakeBrain();
 const existing=director.parse(storyboard,'Старая история');
 existing.id='old-story';
 assert.ok(director.write([existing]));
 const prompts=[];
 const pictures=[];
 let planned=false;
 const result=await bridge.run({
  text:'Придумай историю веселую. И сделай к ним картинки',
  brain,
  generate:async(prompt,update)=>{
   assert.ok(planned,'must persist storyboard before SD engine starts');
   assert.ok(!/[а-яёіїєґ]/iu.test(prompt),'SD receives validated English prompt');
   prompts.push(prompt);
   update('rendering');
   return {saved:true,imageUri:'content://media/image/'+prompts.length,
    elapsedSeconds:prompts.length*12,preview:'data:image/png;base64,PNG'+prompts.length};
  },
  onPlanned:story=>{
   planned=true;
   assert.equal(director.read().length,2);
   assert.equal(story.scenes.length,3);
  },
  onFrame:item=>pictures.push(item.completed)
 });
 assert.equal(result.completed,3);
 assert.equal(result.cancelled,false);
 assert.equal(prompts.length,3);
 assert.equal(brain.calls,1);
 assert.deepEqual(pictures,[1,2,3]);
 assert.equal(director.read().length,2);
 assert.equal(director.read()[0].id,'old-story');
 const newStory=director.read()[1];
 assert.equal(newStory.scenes[2].imageUri,'content://media/image/3');
 assert.equal(newStory.scenes[2].renderSeconds,36);
});
test('stop after first frame preserves original story and completed result',async()=>{
 const {bridge,director}=setup();
 let stopped=false;let frames=0;
 const res=await bridge.run({
  text:'Сочини сказку и нарисуй иллюстрации',
  brain:fakeBrain(),isCancelled:()=>stopped,
  generate:async()=>{
   frames++;return {saved:true,imageUri:'content://media/first',elapsedSeconds:12};
  },
  onFrame:()=>{stopped=true;}
 });
 assert.equal(res.cancelled,true);
 assert.equal(frames,1);
 const current=director.read()[0];
 assert.equal(current.scenes[0].imageUri,'content://media/first');
 assert.equal(current.scenes[1].imageUri,'');
});
test('bad storyboard and unsuccessful native call never claim pictures',async()=>{
 const {bridge,director}=setup();
 await assert.rejects(()=>bridge.run({
  text:'Придумай историю и картинки',
  brain:fakeBrain('I cannot do this'),
  generate:async()=>{throw Error('must not run');}
 }),/раскадровку/);
 assert.equal(director.read().length,0);
 await assert.rejects(()=>bridge.run({
  text:'Придумай историю и картинки',
  brain:fakeBrain(),
  generate:async()=>({saved:false})
 }),/не подтвердил сохранение/);
 assert.equal(director.read().length,1);
 assert.ok(director.read()[0].scenes.every(s=>!s.imageUri));
});
test('official APK wires real image backend and keeps project/gallery logic',()=>{
 const html=fs.readFileSync('src/index.html','utf8');
 const js=html.match(/<script>([\s\S]*?)<\/script>/)?.[1]||'';
 const kotlin=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaBrainPlugin.kt','utf8');
 assert.doesNotThrow(()=>new vm.Script(js));
 assert.match(html,/<script src="nova_story_chat\.js"><\/script>/);
 assert.ok(js.indexOf('window.NovaStoryChat?.wantsStoryImages(text)')<
           js.indexOf('const imagePrompt=imageRequest(text)'));
 assert.match(js,/await window\.NovaStoryChat\.run\(/);
 assert.match(js,/executeImageGeneration\(prompt,onProgress,512,12\)/);
 assert.match(js,/await image\?\.cancel\?\.\(\)/);
 assert.match(kotlin,/call\.getInt\("maxTokens"\)/);
 assert.match(kotlin,/brain\.chat\(message, maxTokens\)/);
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 assert.match(ui,/brain\.chat\(\{message:question,maxTokens:512\}\)/);
});
