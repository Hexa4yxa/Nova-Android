const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
function setup(){
 const memory=new Map();
 const localStorage={
  getItem:k=>memory.get(k)||null,
  setItem:(k,v)=>memory.set(k,String(v))
 };
 const window={};
 const context=vm.createContext({window,localStorage,Date,JSON,Error,Number,String,Array,Object,Math,Set});
 vm.runInContext(fs.readFileSync('src/nova_character_bible.js','utf8'),context);
 vm.runInContext(fs.readFileSync('src/nova_story_director.js','utf8'),context);
 return {bible:window.NovaCharacterBible,director:window.NovaStoryDirector,memory};
}
const original='Парень и девушка ищут маяк, а странный человек пытается им помешать';
const frames={title:'Маяк',hero:'man in black jacket',
 style:'soft black and white comic',
 scenes:[
  {beat:'Они видят маяк',prompt:'Two travelers approach an old lighthouse by the coast, a dark figure appears behind the narrow doors.'},
  {beat:'Они входят',prompt:'The same travelers enter a corridor inside the abandoned lighthouse, the black door closes behind them.'},
  {beat:'Антагонист ждёт',prompt:'A tall mysterious stranger waits upstairs at the lighthouse window, dramatic light and strong shadows.'}
 ]};
const later={...frames,scenes:[
  {beat:'Герои ищут лестницу',prompt:'A traveler finds a spiral staircase leading to the lighthouse tower, shadows spread across the wall.'},
  {beat:'Зло приближается',prompt:'The shadowy enemy walks toward an old wooden door, moonlight glows behind his dark cape.'},
  {beat:'Финал',prompt:'A lone traveler opens the tower window and sees a powerful beam of light, wide cinematic shot.'}
]};
function createCast(b){
 const series=b.createSeries('Маяк Тьмы','comic');
 const hero=b.addCharacter(series.id,{name:'Марк',role:'hero',
  appearance:'young man, short dark hair, oval face, grey hoodie, black jacket, red scarf'});
 const villain=b.addCharacter(series.id,{name:'Тень',role:'antagonist',
  appearance:'tall pale man in black suit, narrow face, white gloves and top hat'});
 const friend=b.addCharacter(series.id,{name:'Лия',role:'support',
  appearance:'young woman with silver hair and green eyes in blue winter coat'});
 return {series,hero,villain,friend};
}
test('series bible persists multiple characters and keeps separate role IDs across episodes',()=>{
 const {bible}=setup();
 const {series,hero,villain,friend}=createCast(bible);
 assert.equal(bible.read().series.length,1);
 assert.equal(bible.getCast(series.id).length,3);
 assert.deepEqual(Array.from(bible.getCast(series.id),x=>x.role),['hero','antagonist','support']);
 assert.notEqual(hero.id,villain.id);
 assert.equal(bible.snapshot(series.id).cast[2].id,friend.id);
 assert.equal(bible.createSeries('Другая история','anime').styleId,'anime');
 assert.equal(bible.getCast(series.id).length,3);
});
test('reference anchor accepts only PNGs actually in NOVA saved frames',()=>{
 const {bible}=setup(),{hero}=createCast(bible);
 const image='content://media/external/images/media/391';
 assert.throws(()=>bible.setReference(hero.id,'file:///root/private.png',[image]),/эталон|Эталон/);
 assert.throws(()=>bible.setReference(hero.id,'content://media/external/images/media/999',[image]),/эталон|Эталон/);
 assert.equal(bible.setReference(hero.id,image,[image]).referenceImageUri,image);
 assert.equal(bible.read().characters[0].referenceImageUri,image);
 assert.equal(bible.setReference(hero.id,'',[image]).referenceImageUri,'');
});
test('six-scene project binds a true multi-person series without Cyrillic in image prompts',()=>{
 const {bible,director}=setup(),{series,hero,villain,friend}=createCast(bible);
 const cast=bible.snapshot(series.id);
 const req=director.ideaRequest(original,3,'','comic',cast);
 assert.match(req,/FIXED SERIES CAST/);
 assert.match(req,/white gloves and top hat/);
 const first=director.parse(JSON.stringify(frames),original,'comic');
 const question=director.continuationRequest(original,first,'',cast);
 assert.match(question,/registered cast remains unchanged/);
 const six=director.appendContinuation(first,JSON.stringify(later));
 director.bindSeries(six,cast);
 six.id='ep-one';
 assert.equal(six.scenes.length,6);
 assert.equal(six.cast.length,3);
 assert.equal(six.seriesId,series.id);
 assert.deepEqual(Array.from(six.scenes[0].castIds),[hero.id]);
 assert.ok(six.scenes[0].prompt.includes(hero.appearance));
 assert.doesNotMatch(six.scenes[0].prompt,/[а-яёіїєґ]/iu);
 assert.equal(director.write([six]),true);
 const shot=director.setSceneCast('ep-one',5,[villain.id]);
 assert.deepEqual(Array.from(shot.castIds),[villain.id]);
 assert.match(shot.prompt,/white gloves and top hat/);
 assert.doesNotMatch(shot.prompt,/red scarf/);
 const group=director.setSceneCast('ep-one',6,[hero.id,friend.id]);
 assert.equal(group.castIds.length,2);
 assert.match(group.prompt,/blue winter coat/);
 assert.match(group.prompt,/red scarf/);
 assert.throws(()=>director.setSceneCast('ep-one',6,[hero.id,hero.id]),/трёх разных/);
 const saved=director.read();
 saved[0].scenes[1].imageUri='content://media/external/images/media/101';
 director.write(saved);
 assert.throws(()=>director.setSceneCast('ep-one',2,[villain.id]),/уже нарисован/);
 assert.equal(director.read()[0].scenes[1].imageUri,saved[0].scenes[1].imageUri);
});
test('existing legacy projects, gallery PNG and voice remain unchanged after series cast edits',()=>{
 const {bible,director}=setup();
 const old=director.parse(JSON.stringify(frames),original);old.id='legacy';
 old.scenes[0].imageUri='content://media/external/images/media/9';
 old.scenes[0].voiceText='Где ты?';
 const savedBefore=JSON.stringify(old);
 assert.ok(director.write([old]));
 createCast(bible);
 assert.equal(JSON.stringify(director.read()[0]),savedBefore);
 const archive=director.exportBackup();
 assert.equal(JSON.parse(archive).stories[0].scenes[0].voiceText,'Где ты?');
});
test('story backup restores shared series cast without clobbering existing people',()=>{
 const {bible,director,memory}=setup();
 const {series,hero}=createCast(bible);
 const first=director.parse(JSON.stringify(frames),original,'comic');
 director.bindSeries(first,bible.snapshot(series.id));
 first.id='episode-one';
 assert.equal(director.write([first]),true);
 const archive=director.exportBackup();
 assert.equal(JSON.parse(archive).characterBible.characters.length,3);
 memory.delete('nova_series_character_bible_v1');
 assert.equal(bible.read().characters.length,0);
 assert.equal(director.mergeBackup(archive).added,0);
 assert.equal(bible.getCast(series.id).length,3);
 assert.equal(bible.getCast(series.id)[0].id,hero.id);
 const before=JSON.stringify(bible.read());
 assert.equal(bible.mergeBackup(JSON.parse(archive).characterBible).characters,0);
 assert.equal(JSON.stringify(bible.read()),before);
 const bad=JSON.stringify({...JSON.parse(archive),characterBible:{version:1,series:[],characters:[{id:'bad'}]}});
 assert.throws(()=>director.mergeBackup(bad),/библи|Библи|Неверные данные/);
 assert.equal(JSON.stringify(bible.read()),before);
});

test('offline UI exposes one shared cast library and opt-in native img2img safely',()=>{
 const html=fs.readFileSync('src/index.html','utf8');
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 const seriesUi=fs.readFileSync('src/nova_series_ui.js','utf8');
 const cpp=fs.readFileSync('android/nova-image/src/main/cpp/nova_image_jni.cpp','utf8');
 const kt=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaImagePlugin.kt','utf8');
 const bridge=fs.readFileSync('android/nova-image/src/main/java/ai/nova/image/NativeImageEngine.kt','utf8');
 assert.match(html,/src="nova_character_bible\.js"/);
 assert.match(html,/src="nova_series_ui\.js"/);
 assert.match(ui,/director\.bindSeries\(story,seriesCast\)/);
 assert.match(ui,/director\.setSceneCast\(story.id,scene.number/);
 assert.match(seriesUi,/addCharacter/);
 assert.match(seriesUi,/setReference/);
 assert.match(seriesUi,/img2img/);
 assert.match(html,/referenceOptions\(scene,story\)/);
 assert.match(cpp,/image_params\.init_image\.data = reference_pixels\.data\(\)/);
 assert.match(cpp,/image_params\.strength = denoiseStrength/);
 assert.match(cpp,/NOVA_GPU_IMAGE_BACKEND/);
 assert.match(kt,/private fun referenceRgb\(/);
 assert.match(kt,/put\("referenceApplied", initRgb != null\)/);
 assert.match(bridge,/referenceRgb: ByteArray\?/);
 assert.doesNotThrow(()=>new vm.Script(ui));
 assert.doesNotThrow(()=>new vm.Script(seriesUi));
});
test('timing diagnostics are persisted instead of claiming artificial speedup',()=>{
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 const chat=fs.readFileSync('src/nova_story_chat.js','utf8');
 assert.match(ui,/scene\.modelLoadMs=Number\(result\.modelLoadMs\)/);
 assert.match(ui,/scene\.samplingMs=Number\(result\.samplingMs\)/);
 assert.match(chat,/scene\.modelLoadMs=Number\(result\.modelLoadMs\)/);
 assert.match(chat,/scene\.samplingMs=Number\(result\.samplingMs\)/);
});

test('editing a series actor keeps its id, approved PNG and immutable episode cast',()=>{
 const {bible,director}=setup();
 const {series,hero,villain}=createCast(bible);
 const uri='content://media/external/images/media/123';
 bible.setReference(hero.id,uri,[uri]);
 const originalCast=bible.snapshot(series.id);
 const story=director.parse(JSON.stringify(frames),original,'comic');
 director.bindSeries(story,originalCast);
 story.id='episode-before-edit';
 assert.equal(director.write([story]),true);
 const savedBefore=JSON.stringify(director.read());
 const updated=bible.updateCharacter(hero.id,{
  name:'Марк после путешествия',role:'support',
  appearance:'adult man with a short black haircut, scar above left eyebrow, grey hoodie, navy jacket'
 });
 assert.equal(updated.id,hero.id);
 assert.equal(updated.referenceImageUri,uri);
 assert.equal(updated.role,'support');
 assert.match(bible.getCast(series.id)[0].appearance,/scar above left eyebrow/);
 assert.equal(bible.snapshot(series.id).cast[0].id,hero.id);
 assert.equal(JSON.stringify(director.read()),savedBefore,'older episodes never mutate on actor edit');
 assert.equal(director.read()[0].cast[0].name,'Марк');
 assert.throws(()=>bible.updateCharacter(hero.id,{name:villain.name,role:'hero',
  appearance:updated.appearance}),/уже есть/);
 assert.throws(()=>bible.updateCharacter(hero.id,{name:'Марк',role:'hero',
  appearance:'новая внешность'}),/английском/);
 assert.equal(bible.getCast(series.id)[0].name,updated.name);
 const seriesUi=fs.readFileSync('src/nova_series_ui.js','utf8');
 assert.match(seriesUi,/bible\.updateCharacter\(actor\.id/);
 assert.match(seriesUi,/Сохранить паспорт/);
 assert.match(seriesUi,/как img2img/);
});
