const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const memory=new Map();
const localStorage={getItem:key=>memory.get(key)||null,setItem:(key,val)=>memory.set(key,String(val))};
const win={};
const ctx=vm.createContext({window:win,localStorage,Date,JSON,Error,Number,String,Array,Object,Math});
vm.runInContext(fs.readFileSync('src/nova_story_director.js','utf8'),ctx);
const story=win.NovaStoryDirector;
const valid=JSON.stringify({
 title:'Зеркало в заброшенном доме',
 hero:'young man with short dark hair, wearing a grey jacket',
 style:'moody cinematic horror, cold blue light, 35mm film',
 scenes:[
 {beat:'Он входит в дом',prompt:'A young man wearing a grey jacket enters an abandoned house, wide shot with cold window light.'},
 {beat:'Он видит отражение',prompt:'A young man wearing a grey jacket looks into a mirror, his reflection stands still, medium shot.'},
 {beat:'Отражение улыбается',prompt:'The same young man wearing a grey jacket backs away, the mirror reflection smiles, dramatic close-up.'}
 ]});

test('Gemma receives full story idea and consistent character/scene instructions',()=>{
 const prompt=story.ideaRequest('История про двойника в старом зеркале и человека, который его видит');
 assert.match(prompt,/двойника в старом зеркале/);
 assert.match(prompt,/One single protagonist look across all scenes/);
 assert.match(prompt,/exactly 3 scenes/);
 assert.throws(()=>story.ideaRequest('Кот'),/идею истории/);
});

test('Gemma produces offline scene plan with shared continuity and English image prompts',()=>{
 const result=story.parse('Here is JSON: '+valid,'История про зеркало');
 assert.equal(result.scenes.length,3);
 assert.match(result.scenes[0].prompt,/Same protagonist: young man with short dark hair/i);
 assert.match(result.scenes[2].prompt,/Visual continuity: moody cinematic horror/);
 assert.match(result.scenes[1].prompt,/looks into a mirror/);
 assert.ok(result.scenes.every(s=>s.prompt.length<=590));
 assert.ok(result.scenes.every(s=>!s.imageUri));
 assert.ok(story.write([result]));
 assert.equal(story.read().length,1);
 assert.equal(story.read()[0].scenes[2].beat,'Отражение улыбается');
});

test('Invalid or Cyrillic image prompt does not launch an expensive SD render',()=>{
 const payload=JSON.parse(valid);
 payload.scenes[0].prompt='Парень заходит в дом, тёмно';
 assert.throws(()=>story.parse(JSON.stringify(payload),'Мистика'),/английский/);
 assert.throws(()=>story.parse('I cannot write JSON','Мистика'),/раскадровку/);
 assert.throws(()=>story.parse('{"title":"hello"','Мистика'),/раскадровку|JSON/);
});

test('Projects tab uses Gemma and requires user approval per image frame',()=>{
 const html=fs.readFileSync('src/index.html','utf8');
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 const main=html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
 assert.ok(main);
 assert.doesNotThrow(()=>new vm.Script(main));
 assert.doesNotThrow(()=>new vm.Script(ui));
 assert.match(html,/<script src="nova_story_director\.js"><\/script>\s*<script src="nova_story_ui\.js"><\/script>/);
 assert.match(main,/if\(selected==='projects'\)return window\.NovaStoryUI\.render/);
 assert.match(ui,/await brain\.prepare\(\)/);
 assert.match(ui,/await brain\.chat\(\{message:question\}\)/);
 assert.match(ui,/confirm\('Нарисовать сцену /);
 assert.match(ui,/await generate\(scene\.prompt/);
 assert.match(ui,/director\.write/);
 assert.match(main,/generate:\(prompt,update\)=>executeImageGeneration\(prompt,update,512,12\)/);
});
