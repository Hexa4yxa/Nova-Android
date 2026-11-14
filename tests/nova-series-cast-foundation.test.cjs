const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

function setup(){
 const data=new Map(),window={};
 const localStorage={getItem:key=>data.get(key)||null,setItem:(key,val)=>data.set(key,String(val))};
 const context={window,localStorage,Date,JSON,Error,Number,String,Array,Object,Math,Set};
 vm.runInNewContext(fs.readFileSync('src/nova_cast_library.js','utf8'),context);
 vm.runInNewContext(fs.readFileSync('src/nova_story_director.js','utf8'),context);
 vm.runInNewContext(fs.readFileSync('src/nova_story_chat.js','utf8'),{...context,setTimeout});
 return {cast:window.NovaCastLibrary,director:window.NovaStoryDirector,data};
}
const action='A lonely traveler approaches an old lighthouse entrance at night, harsh wind and pale moonlight fall across the doorway.';
const base={
 title:'Маяк во тьме',
 hero:'a stranger with changing outfit',
 style:'random bright photo',
 scenes:[
  {beat:'Подходит к маяку',prompt:action},
  {beat:'Входит в башню',prompt:'A traveler enters the abandoned lighthouse through a heavy doorway, lantern light on the wall, wide interior shot.'},
  {beat:'Слышит скрип',prompt:'A traveler climbs the narrow stairs inside the lighthouse, his face turned toward an unexplained sound.'}
 ]
};
const continuation={
 title:'Маяк во тьме',
 hero:'different hero, silver hair',
 style:'different photo style',
 scenes:[
  {beat:'Оборачивается',prompt:'A traveler turns at the top of a staircase to look toward a strange sound, moonlight through the window.'},
  {beat:'Видит свет',prompt:'A traveler walks toward a mysterious light through the dark lighthouse corridor, close perspective.'},
  {beat:'Встречает тень',prompt:'A traveler stands still in the lighthouse lantern room and sees a shadow in the bright fog.'}
 ]
};
const concept='История о путешественнике, заброшенном маяке и его загадочном враге';

function build(){
 const {cast,director,data}=setup();
 const series=cast.createSeries('Маяк Тьмы','comic');
 const hero=cast.addCharacter(series.id,{name:'Андрей',role:'hero',
  appearance:'young slender man with black hair and blue eyes',
  outfit:'long black coat and a bright red scarf'});
 const villain=cast.addCharacter(series.id,{name:'Теневой смотритель',role:'antagonist',
  appearance:'very tall older man with pale expressionless face',
  outfit:'dark navy uniform with silver badge'});
 const friend=cast.addCharacter(series.id,{name:'Марта',role:'support',
  appearance:'young woman with short auburn hair and brown eyes',
  outfit:'brown leather jacket and yellow hat'});
 const plan=cast.snapshot(series.id,hero.id);
 return {cast,director,data,series,hero,villain,friend,plan};
}
test('series cast bible stores persistent heroes, antagonists and supporting actors',()=>{
 const a=build();
 assert.deepEqual(Array.from(a.cast.read()[0].characters,c=>c.role),['hero','antagonist','support']);
 assert.equal(a.cast.read()[0].characters.length,3);
 assert.equal(a.cast.snapshot(a.series.id,a.hero.id).leadCharacterId,a.hero.id);
 assert.throws(()=>a.cast.addCharacter(a.series.id,{
  name:'Андрей',role:'hero',appearance:'young boy with silver eyes',
  outfit:'white coat'}),/имя/);
 assert.throws(()=>a.cast.addCharacter(a.series.id,{
  name:'Чудовище',role:'hero',appearance:'мальчик с красными глазами',
  outfit:'red coat'}),/по-английски/);
});
test('new episode ignores changing Gemma look and locks actor face AND outfit',()=>{
 const a=build();
 const request=a.director.ideaRequest(concept,3,'','comic',a.plan);
 assert.match(request,/SERIES BIBLE/);
 assert.match(request,/CANON LOOK/);
 assert.match(request,/long black coat and a bright red scarf/);
 const first=a.director.parse(JSON.stringify(base),concept,'comic',a.plan);
 assert.equal(first.seriesId,a.series.id);
 assert.equal(first.leadCharacterId,a.hero.id);
 assert.equal(first.scenes.length,3);
 assert.ok(first.scenes.every(x=>x.castIds[0]===a.hero.id));
 for(const scene of first.scenes){
  assert.match(scene.prompt,/black hair/);
  assert.match(scene.prompt,/red scarf/);
  assert.match(scene.prompt,/graphic novel/);
  assert.ok(scene.prompt.length<=590);
 }
 const next=a.director.appendContinuation(first,JSON.stringify(continuation));
 assert.equal(next.scenes.length,6);
 assert.ok(next.scenes.every(x=>x.prompt.includes('red scarf')));
 assert.equal(next.styleId,'comic');
 assert.equal(next.hero.includes('red scarf'),true);
 assert.doesNotMatch(next.scenes[5].prompt,/silver hair/);
});
test('multiple characters can be assigned per scene; existing PNGs are immutable',()=>{
 const a=build(),first=a.director.parse(JSON.stringify(base),concept,'comic',a.plan);
 first.id='series-episode-1';
 assert.equal(a.director.write([first]),true);
 const changed=a.director.assignSceneCast(first.id,2,[a.hero.id,a.villain.id]);
 assert.equal(changed.scenes[1].castIds.length,2);
 assert.match(changed.scenes[1].prompt,/second actor/);
 assert.match(changed.scenes[1].prompt,/navy uniform/);
 assert.match(changed.scenes[1].prompt,/bright red scarf/);
 assert.throws(()=>a.director.assignSceneCast(first.id,2,[a.hero.id,a.hero.id]),/разных/);
 assert.throws(()=>a.director.assignSceneCast(first.id,2,['not-in-cast']),/не входит/);
 const saved=a.director.read();saved[0].scenes[1].imageUri='content://media/external/images/media/43';
 assert.equal(a.director.write(saved),true);
 assert.throws(()=>a.director.assignSceneCast(first.id,2,[a.friend.id]),/Готовый кадр/);
 assert.equal(a.director.read()[0].scenes[1].imageUri,'content://media/external/images/media/43');
});
test('editing a cast bible affects future episodes, never already saved episode snapshots',()=>{
 const a=build(),old=a.director.parse(JSON.stringify(base),concept,'comic',a.plan);
 old.id='episode-before-character-edit';
 assert.equal(a.director.write([old]),true);
 const before=JSON.stringify(a.director.read()[0]);
 a.cast.updateCharacter(a.series.id,a.hero.id,{...a.hero,
  outfit:'green explorer jacket and a bright orange hat'});
 const newSnapshot=a.cast.snapshot(a.series.id,a.hero.id);
 const next=a.director.parse(JSON.stringify(base),concept,'comic',newSnapshot);
 assert.match(next.scenes[0].prompt,/orange hat/);
 assert.doesNotMatch(next.scenes[0].prompt,/red scarf/);
 assert.equal(JSON.stringify(a.director.read()[0]),before);
});
test('old projects remain valid, backups include series cast and merge without duplicates',()=>{
 const a=build();
 const old=a.director.parse(JSON.stringify(base),concept,'comic');
 old.id='pre-series-story';old.scenes[0].imageUri='content://media/external/images/media/123';
 const series=a.director.parse(JSON.stringify(base),concept,'comic',a.plan);
 series.id='series-stable';
 a.director.write([old,series]);
 const backup=a.director.exportBackup();
 const parsed=JSON.parse(backup);
 assert.equal(parsed.format,'NOVA_STORIES_V1');
 assert.equal(parsed.seriesLibrary.length,1);
 assert.equal(parsed.stories.length,2);
 const fresh=setup();
 assert.equal(fresh.director.mergeBackup(backup).added,2);
 assert.equal(fresh.cast.read()[0].characters.length,3);
 assert.equal(fresh.director.read()[0].scenes[0].imageUri,old.scenes[0].imageUri);
 assert.equal(fresh.director.read()[1].castSnapshot.length,3);
 assert.equal(fresh.director.mergeBackup(backup).added,0);
 assert.equal(fresh.cast.read().length,1);
 const before=JSON.stringify(fresh.director.read());
 const broken=JSON.stringify({...parsed,seriesLibrary:[{id:'corrupt',title:42,characters:[]}]});
 assert.throws(()=>fresh.director.mergeBackup(broken),/Неверная библиотека/);
 assert.equal(JSON.stringify(fresh.director.read()),before);
});
test('UI uses cast library on serial episodes; performance metrics survive project persistence',()=>{
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 const chat=fs.readFileSync('src/nova_story_chat.js','utf8');
 const html=fs.readFileSync('src/index.html','utf8');
 assert.ok(html.indexOf('src="nova_cast_library.js"')<html.indexOf('src="nova_story_director.js"'));
 assert.match(ui,/🎭 Сериал · постоянные герои и антигерои/);
 assert.match(ui,/cast\.createSeries/);
 assert.match(ui,/cast\.addCharacter/);
 assert.match(ui,/cast\.updateCharacter/);
 assert.match(ui,/cast\.snapshot\(state\.seriesId,state\.leadCharacterId\)/);
 assert.match(ui,/director\.assignSceneCast\(story\.id,scene\.number,ids\)/);
 assert.match(ui,/const response=await brain\.chat\(\{message:question,maxTokens:170\}\)/);
 assert.match(ui,/scene\.modelLoadMs/);
 assert.match(ui,/scene\.samplingMs/);
 assert.match(chat,/scene\.modelLoadMs/);
 assert.match(chat,/scene\.samplingMs/);
 assert.match(html,/brain\.releaseForImage\(\)/);
 assert.doesNotThrow(()=>new vm.Script(ui));
});
