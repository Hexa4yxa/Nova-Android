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
 assert.match(prompt,/actual scene subject and setting BEFORE decorative style/);
 assert.match(prompt,/objects, architecture, geography and spatial relations/);
 assert.match(prompt,/exactly 3 scenes/);
 assert.throws(()=>story.ideaRequest('Кот'),/идею истории/);
});

test('Gemma produces offline scene plan with shared continuity and English image prompts',()=>{
 const result=story.parse('Here is JSON: '+valid,'История про зеркало');
 assert.equal(result.scenes.length,3);
 assert.match(result.scenes[0].prompt,/same protagonist: young man with short dark hair/i);
 assert.ok(result.scenes[0].prompt.startsWith('A young man wearing a grey jacket enters an abandoned house'));
 assert.ok(result.scenes[0].prompt.indexOf('enters an abandoned house') < result.scenes[0].prompt.indexOf('same protagonist:'));
 assert.ok(result.scenes[1].prompt.indexOf('looks into a mirror') < result.scenes[1].prompt.indexOf('visual continuity:'));

 assert.match(result.scenes[2].prompt,/visual continuity: moody cinematic horror/);
 assert.match(result.scenes[1].prompt,/looks into a mirror/);
 assert.ok(result.scenes.every(s=>s.prompt.length<=590));
 assert.ok(result.scenes.every(s=>!s.imageUri));
 assert.ok(story.write([result]));
 assert.equal(story.read().length,1);
 assert.equal(story.read()[0].scenes[2].beat,'Отражение улыбается');
});

test('NOVA never drops old projects after the sixth saved story',()=>{
 const payload=Array.from({length:9},(_,i)=>Object.assign(
  story.parse(valid,'История '+i),{id:'story-test-'+i}
 ));
 assert.ok(story.write(payload));
 const saved=story.read();
 assert.equal(saved.length,9);
 assert.equal(saved[0].id,'story-test-0');
 assert.equal(saved[8].id,'story-test-8');
});

test('Long scene prompts prioritize the main shot without chopping words',()=>{
 const obj=JSON.parse(valid);
 obj.hero='traveler with pale complexion and long dark coat, wearing a wool scarf, carrying an old leather satchel through the frozen countryside';
 obj.style='moody atmospheric cinematic photographic style, gentle film grain, low contrast backlighting, blue winter shadows';
 obj.scenes[0].prompt='A weathered coastal lighthouse rises above the stormy sea, towering over the jagged cliff, as a lone traveler discovers a stone staircase carved into the rock. Wide shot, visible ocean and lighthouse, long rolling waves. '.repeat(3);
 const output=story.parse(JSON.stringify(obj),'Маяк у моря');
 const prompt=output.scenes[0].prompt;
 assert.ok(prompt.startsWith('A weathered coastal lighthouse rises above the stormy sea'));
 assert.ok(prompt.includes('jagged cliff'));
 assert.ok(prompt.includes('same protagonist:'));
 assert.ok(prompt.includes('visual continuity:'));
 assert.ok(prompt.length<=590);
});

test('Invalid or Cyrillic image prompt does not launch an expensive SD render',()=>{
 const payload=JSON.parse(valid);
 payload.scenes[0].prompt='Парень заходит в дом, тёмно';
 assert.throws(()=>story.parse(JSON.stringify(payload),'Мистика'),/английский/);
 assert.throws(()=>story.parse('I cannot write JSON','Мистика'),/раскадровку/);
 assert.throws(()=>story.parse('{"title":"hello"','Мистика'),/раскадровку|JSON/);
});


test('Portable story archive is offline, versioned, merge-only and idempotent',()=>{
 const baseline=story.read();
 const existing=story.parse(valid,'Существующая история');
 existing.id='existing-owner-project';
 assert.ok(story.write([existing]));
 const restored=story.parse(valid,'Восстановленная история');
 restored.id='restored-extra-project';
 restored.scenes[0].imageUri='content://media/external/images/media/45';
 restored.scenes[0].renderSeconds=1472;
 const conflicting=Object.assign({},existing,{title:'НЕ ДОЛЖНО ЗАМЕНИТЬ ИСХОДНИК'});
 const backup=JSON.stringify({format:'NOVA_STORIES_V1',version:1,stories:[conflicting,restored]});
 const merge=story.mergeBackup(backup);
 assert.equal(merge.added,1);
 assert.equal(merge.skipped,1);
 assert.equal(merge.total,2);
 assert.equal(story.read()[0].id,'existing-owner-project');
 assert.equal(story.read()[0].title,existing.title);
 assert.equal(story.read()[1].scenes[0].renderSeconds,1472);
 assert.equal(story.mergeBackup(backup).added,0);
 const exported=JSON.parse(story.exportBackup());
 assert.equal(exported.format,'NOVA_STORIES_V1');
 assert.equal(exported.version,1);
 assert.equal(exported.stories.length,2);
 assert.equal(exported.stories[1].scenes[0].imageUri,'content://media/external/images/media/45');
 assert.ok(story.write(baseline));
});

test('Invalid backup never replaces or deletes saved NOVA projects',()=>{
 const before=JSON.stringify(story.read());
 const bad=JSON.stringify({format:'NOVA_STORIES_V1',version:1,stories:[{id:'bad',title:'fake',scenes:[{beat:'x',prompt:42}]}]});
 assert.throws(()=>story.mergeBackup(bad),/Неверный формат/);
 assert.throws(()=>story.mergeBackup('not json'),/JSON/);
 assert.equal(JSON.stringify(story.read()),before);
});

test('Native Android document picker and Projects UI offer safe backup/restore',()=>{
 const native=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaToolsPlugin.kt','utf8');
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 assert.match(native,/fun exportStoryProjects\(call: PluginCall\)/);
 assert.match(native,/fun importStoryProjects\(call: PluginCall\)/);
 assert.match(native,/Intent\.ACTION_CREATE_DOCUMENT/);
 assert.match(native,/Intent\.ACTION_OPEN_DOCUMENT/);
 assert.match(native,/MAX_STORY_BACKUP_BYTES/);
 assert.match(ui,/archiveTools\.exportStoryProjects\(\{payload:director\.exportBackup\(\)\}\)/);
 assert.match(ui,/director\.mergeBackup\(result\?\.payload\)/);
 assert.match(ui,/💾 Сохранить копию историй/);
 assert.match(ui,/♻️ Восстановить истории из копии/);
 assert.doesNotThrow(()=>new vm.Script(ui));
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
 assert.match(ui,/await brain\.chat\(\{message:question,maxTokens:512\}\)/);
 assert.match(ui,/confirm\('Нарисовать сцену /);
 assert.match(ui,/await generate\(scene\.prompt/);
 assert.match(ui,/director\.write/);
 assert.match(main,/generate:\(prompt,update\)=>executeImageGeneration\(prompt,update,512,12\)/);
});
