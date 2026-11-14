const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

function setup(){
 const memory=new Map();
 const localStorage={getItem:k=>memory.get(k)||null,setItem:(k,v)=>memory.set(k,String(v))};
 const window={};
 vm.runInNewContext(fs.readFileSync('src/nova_story_director.js','utf8'),
  {window,localStorage,Date,JSON,Error,Number,String,Array,Object,Math,Set});
 return window.NovaStoryDirector;
}
const hero='young woman with short black hair, amber eyes, navy coat and red scarf';
const input={
 title:'Маяк',
 hero,
 style:'photorealistic image',
 scenes:[
  {beat:'Героиня идёт к маяку',prompt:'A young woman walks toward an old coastal lighthouse, red scarf fluttering as waves crash behind her in the dark.'},
  {beat:'Она открывает дверь',prompt:'A young woman opens a heavy lighthouse door, looking into a narrow stairwell, cinematic light in the darkness.'},
  {beat:'Она слышит голос',prompt:'A young woman hears a mysterious voice above the lighthouse steps, staring at the upper floor in fear.'}
 ]
};
const continuation={
 title:'Другой фильм',
 hero:'older man with gray hair and different clothes',
 style:'realistic oil photography',
 scenes:[
  {beat:'Она поднимается',prompt:'A woman climbs the stairs while holding her scarf, bright lanterns above the old wooden steps.'},
  {beat:'Звон колокола',prompt:'A woman finds an old bell in the upper lighthouse tower, late-night wind through a broken window.'},
  {beat:'Она видит тень',prompt:'A woman sees her own shadow inside a glass lantern, a disturbing final discovery in the lighthouse.'}
 ]
};
const idea='Девушка в красном шарфе идёт в старый маяк и слышит там чужой голос';

test('NOVA exposes exactly three offline art styles and respects explicit chat style',()=>{
 const director=setup();
 assert.deepEqual(Array.from(director.artStyles(),x=>x.id),['comic','anime','painting']);
 assert.equal(director.detectArtStyle('Хочу аниме про ангела'),'anime');
 assert.equal(director.detectArtStyle('Акварельная история у моря'),'painting');
 assert.equal(director.detectArtStyle('Сделай хоррор у маяка'),'comic');
 assert.throws(()=>director.ideaRequest(idea,3,'','unknown'),/Неизвестный стиль/);
});
test('first three scenes use the selected preset, not Gemma changing the art medium',()=>{
 const d=setup();
 for(const preset of d.artStyles()){
  const request=d.ideaRequest(idea,3,'',preset.id);
  assert.match(request,/LOCKED ART STYLE/);
  assert.doesNotMatch(request,/single English photorealistic Stable Diffusion 1\.5 prompt/);
  const first=d.parse(JSON.stringify(input),idea,preset.id);
  assert.equal(first.styleId,preset.id);
  assert.notEqual(first.style,input.style);
  for(const frame of first.scenes){
   assert.ok(frame.prompt.startsWith('A young woman'));
   assert.ok(frame.prompt.includes('same protagonist: '+hero));
   assert.ok(frame.prompt.includes('visual continuity: '+first.style.slice(0,30)));
   assert.ok(frame.prompt.indexOf('same protagonist:')<frame.prompt.indexOf('visual continuity:'));
   assert.ok(frame.prompt.length<=590);
  }
 }
});
test('scenes 4-6 keep both the selected art style and original protagonist',()=>{
 const d=setup();
 const first=d.parse(JSON.stringify(input),idea,'anime');
 const continuationRequest=d.continuationRequest(idea,first);
 assert.match(continuationRequest,/Keep the same face, hairstyle, outfit/);
 const six=d.appendContinuation(first,JSON.stringify(continuation));
 assert.equal(six.scenes.length,6);
 assert.equal(six.styleId,'anime');
 assert.equal(six.hero,hero);
 assert.equal(six.style,first.style);
 for(const frame of six.scenes){
   assert.ok(frame.prompt.includes('same protagonist: '+hero));
   assert.ok(frame.prompt.includes('visual continuity: '+six.style.slice(0,30)));
 }
 assert.ok(!six.scenes[5].prompt.includes('older man'));
});
test('projects and backup retain art preset and existing PNGs, without rewriting old projects',()=>{
 const d=setup();
 const legacy=d.parse(JSON.stringify(input),idea);
 assert.equal(legacy.style,input.style);
 assert.equal(legacy.styleId,undefined);
 legacy.id='legacy';
 legacy.scenes[0].imageUri='content://media/external/images/media/17';
 const styled=d.parse(JSON.stringify(input),idea,'painting');
 styled.id='styled';
 styled.scenes[1].imageUri='content://media/external/images/media/18';
 assert.equal(d.write([legacy,styled]),true);
 const backup=d.exportBackup();
 assert.equal(JSON.parse(backup).stories[1].styleId,'painting');
 assert.equal(d.write([legacy]),true);
 assert.equal(d.mergeBackup(backup).added,1);
 assert.equal(d.read()[0].scenes[0].imageUri,legacy.scenes[0].imageUri);
 assert.equal(d.read()[1].scenes[1].imageUri,styled.scenes[1].imageUri);
 assert.equal(d.read()[1].styleId,'painting');
});
test('SD1.5 does not turn the chosen comic or painterly preset back into a photograph',()=>{
 const html=fs.readFileSync('src/index.html','utf8');
 const js=html.match(/<script>([\s\S]*?)<\/script>/)?.[1]||'';
 const start=js.indexOf('function imageNegativePrompt(prompt)');
 const end=js.indexOf('async function executeImageGeneration(',start);
 assert.ok(start>=0&&end>start);
 const functions=vm.runInNewContext(js.slice(start,end)+'\n({imageEnhancedPrompt,imageNegativePrompt})');
 for(const style of [
  'cinematic graphic novel, bold ink outlines',
  'anime film illustration, clean line art',
  'painterly storybook illustration, soft brush textures'
 ]){
  const actual=functions.imageEnhancedPrompt('A woman enters a lighthouse, '+style);
  assert.doesNotMatch(actual,/photorealistic cinematic photograph/);
  assert.match(functions.imageNegativePrompt(actual),/low quality, ugly/);
 }
 assert.match(functions.imageEnhancedPrompt('A dog in a park'),/photorealistic cinematic photograph/);
});

test('the editor chooses art style once, and does not change rendered old frames',()=>{
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 const chat=fs.readFileSync('src/nova_story_chat.js','utf8');
 assert.match(ui,/Стиль рисования новой истории/);
 assert.match(ui,/artPick\.onchange/);
 assert.match(ui,/director\.ideaRequest\(state\.idea,3,state\.councilBrief,chosenStyle,seriesCast\)/);
 assert.match(ui,/director\.parse\(response\?\.answer,state\.idea,chosenStyle\)/);
 assert.match(chat,/director\.detectArtStyle\(idea\)/);
 assert.match(chat,/director\.parse\(plan\?\.answer,idea,styleId\)/);
 assert.match(ui,/Полное совпадение лиц SD1\.5 пока не гарантируется/);
 assert.doesNotThrow(()=>new vm.Script(ui));
});
