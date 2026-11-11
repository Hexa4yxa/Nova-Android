/* NOVA STORY DIRECTOR — completely offline, no network and no additional AI model. 
   Gemma creates scene prompts. The user approves each expensive render. */
(function(root) {
'use strict';
const KEY='nova_story_projects_v1';
function clean(value,max) { return String(value==null?'':value).replace(/[\u0000-\u001f]/g,' ').trim().slice(0,max); }
function ideaRequest(idea,count=3,councilBrief='') {
 const subject=clean(idea,380);
 if(subject.length<12)throw Error('Опиши идею истории хотя бы одним предложением.');
 const number=Math.max(1,Math.min(3,Number(count)||3));
 return [
  'You are NOVA, a creative film director. Create a cohesive ORIGINAL three-scene storyboard.',
  'Story idea from owner (must preserve it): '+subject,
  ...(clean(councilBrief,480)?['Approved DIRECTOR COUNCIL outline (guidance for six scenes, not already filmed): '+clean(councilBrief,480)]:[]),
  'Return ONLY a compact JSON object, NO Markdown, with these exact fields:',
  '{"title":"short Russian title","hero":"one clear English character appearance anchor","style":"short English visual style and mood anchor","scenes":[{"beat":"short Russian event","prompt":"single English photorealistic Stable Diffusion 1.5 prompt"}] }',
  'Make exactly '+number+' scenes. One single protagonist look across all scenes.',
  'Each English prompt must START with a concrete visible subject doing an ACTION in a specific LOCATION, then framing and light.',
  'Preserve user-mentioned objects, architecture, geography and spatial relations in the relevant scenes. Do not replace them with generic scenery.',
  'Place the actual scene subject and setting BEFORE decorative style or character-consistency notes so SD 1.5 sees them first.',
  'Each scene changes an action while preserving person, outfit, era, atmosphere.',
  'Keep each scene prompt within 25-38 English words. Put essential visible details first for SD 1.5 and fit three concise scenes into one local JSON reply. No self-references or imaginary image output.',
  'Do not describe actual violence or pornography. No explanations outside JSON.'
 ].join('\n');
}
function extract(raw){
 const text=String(raw||'').trim();
 const start=text.indexOf('{'),end=text.lastIndexOf('}');
 if(start<0||end<=start)throw Error('Gemma не вернула понятную раскадровку. Повтори создание плана.');
 try{return JSON.parse(text.slice(start,end+1))}catch(_){throw Error('Gemma вернула неполный JSON-план. Повтори подготовку сцен.');}
}
function english(s) {return /[a-z]/i.test(s)&&!/[а-яёіїєґ]/iu.test(s)}
// Trim only at word boundaries: never cut a cinematography term in half.
function clipWords(value,maxLength) {
 const str=String(value||'').trim();
 if(str.length<=maxLength)return str;
 const slice=str.slice(0,maxLength);
 const cut=slice.lastIndexOf(' ');
 return (cut>0?slice.slice(0,cut):slice).replace(/[.,;:\s]+$/,'');
}
function validate(result,original) {
 if(!result||typeof result!=='object'||!Array.isArray(result.scenes))throw Error('Не удалось получить список сцен.');
 const title=clean(result.title,100);
 const hero=clean(result.hero,220),style=clean(result.style,160);
 if(!title||!english(hero)||!english(style))throw Error('Gemma не подготовила английское описание героя и стиля.');
 if(result.scenes.length<1||result.scenes.length>3)throw Error('Нужно от одной до трёх сцен.');
 const scenes=result.scenes.map((x,i)=>{
   if(!x||typeof x!=='object')throw Error('Сцена содержит неверные данные.');
   const beat=clean(x.beat,240), rawPrompt=clean(x.prompt,470);
   if(!beat||!english(rawPrompt)||rawPrompt.length<25)throw Error('Сцена '+(i+1)+': нужен понятный английский промпт.');
   // SD 1.5's text context is limited. Put the scene-specific subject,
   // action and location FIRST, and continuity anchors AFTER them.
   // Otherwise the tokenizer may drop the real action at the end.
   const shot=clipWords(rawPrompt,360);
   const heroAnchor=clipWords(hero,100);
   const styleAnchor=clipWords(style,75);
   const prompt=shot+', same protagonist: '+heroAnchor+
     ', visual continuity: '+styleAnchor;
   if(prompt.length>590)throw Error('Сцена '+(i+1)+': слишком длинный промпт.');
   if(!english(prompt))throw Error('Сцена '+(i+1)+': промпт не на английском.');
   return {number:i+1,beat,prompt,imageUri:'',renderSeconds:0};
 });
 return {
   id:'story-'+Date.now(),title,idea:clean(original,380),hero,style,
   createdAt:new Date().toISOString(),scenes
 };
}
function parse(raw,original){return validate(extract(raw),original);}

/* Producing six long JSON shots in one 512-token Gemma reply is unreliable.
   Plan scenes 1-3 and 4-6 in two small offline calls, then save ONCE.
   This does not change the format of older 1-3 scene stories. */
function continuationRequest(original,first,councilBrief='') {
 if(!first||!Array.isArray(first.scenes)||first.scenes.length!==3)
  throw Error('Сначала нужно подготовить три начальные сцены.');
 const subject=clean(original,380);
 return [
  'You are NOVA, continuing an ORIGINAL six-scene motion-comic story.',
  'The owner\'s original story: '+subject,
  ...(clean(councilBrief,300)?['Director Council outline for continuity: '+clean(councilBrief,300)]:[]),
  'The first THREE scenes have ALREADY happened:',
  first.scenes.map(scene=>String(scene.number)+'. '+clean(scene.beat,190)).join('\n'),
  'The SAME protagonist MUST be: '+clean(first.hero,180),
  'The SAME visual style MUST be: '+clean(first.style,130),
  'Write ONLY the NEXT THREE distinct scenes (scenes FOUR, FIVE, SIX). Each advances the plot; scene 6 gives a powerful ending.',
  'Return ONLY one compact JSON object, no Markdown, with these fields:',
  '{"title":"same story title","hero":"same English appearance anchor","style":"same English style anchor","scenes":[{"beat":"short Russian action","prompt":"short English SD1.5 visible scene prompt"}]}',
  'Use exactly 3 new scenes, no duplicates of the first three.',
  'Each English prompt starts with concrete subject, ACTION and LOCATION; maintain protagonist identity and settings.',
  'Keep each new prompt around 25-38 English words; be concise enough for a 512-token local reply.',
  'No explanations outside JSON.'
 ].join('\n');
}
function appendContinuation(first,raw) {
 if(!first||!Array.isArray(first.scenes)||first.scenes.length!==3)
  throw Error('Не найден первый блок из трёх сцен.');
 // Validate the second reply with the ORIGINAL hero and visual style:
 // the second model call must never silently replace our protagonist.
 const parsed=extract(raw);
 if(!Array.isArray(parsed.scenes)||parsed.scenes.length!==3)
  throw Error('NOVA должна завершить историю тремя дополнительными сценами.');
 const more=validate({
  title:first.title,hero:first.hero,style:first.style,scenes:parsed.scenes
 },first.idea);
 return {...first,
  scenes:first.scenes.concat(more.scenes.map((scene,i)=>({...scene,number:i+4})))
 };
}

function read(){
 try{
  const saved=JSON.parse(localStorage.getItem(KEY)||'[]');
  if(!Array.isArray(saved))return [];
  // Never discard the owner's older projects when a seventh story is created.
  return saved.filter(x=>x&&typeof x==='object'&&typeof x.id==='string'&&
    typeof x.title==='string'&&Array.isArray(x.scenes)&&x.scenes.length<=6);
 }catch(_){return []}
}
function write(stories) {
 // Keep every project. If device storage is full, report failure instead of
 // silently removing any of the owner's previous work.
 try{localStorage.setItem(KEY,JSON.stringify(stories));return true}
 catch(_){return false}
}
// A portable, offline backup. Keep the same project records so image URIs,
// render durations, dates, generated prompts and scene edits survive restore.
function exportBackup() {
 return JSON.stringify({
   format:'NOVA_STORIES_V1',version:1,
   exportedAt:new Date().toISOString(),
   stories:read()
 });
}
function validBackupStory(item) {
 return item && typeof item==='object' && !Array.isArray(item) &&
   typeof item.id==='string' && item.id.length>0 && item.id.length<=180 &&
   typeof item.title==='string' && item.title.length>0 &&
   Array.isArray(item.scenes) && item.scenes.length>0 && item.scenes.length<=6 &&
   item.scenes.every(scene=>scene && typeof scene==='object' &&
     typeof scene.beat==='string' && typeof scene.prompt==='string' &&
     scene.beat.length<=1000 && scene.prompt.length<=1500);
}
function mergeBackup(payload) {
 let archive;
 try{archive=JSON.parse(String(payload||''));}
 catch(_){throw Error('Невозможно прочитать JSON резервной копии.');}
 if(!archive || archive.format!=='NOVA_STORIES_V1' || archive.version!==1 ||
    !Array.isArray(archive.stories) || archive.stories.length>2500 ||
    !archive.stories.every(validBackupStory))
   throw Error('Неверный формат копии NOVA. Ничего не изменено.');
 const existing=read();
 const seen=new Set(existing.map(item=>item.id));
 const additions=[];
 let skipped=0;
 for(const item of archive.stories) {
   if(seen.has(item.id)){skipped++;continue;}
   seen.add(item.id);
   additions.push(item);
 }
 // This is an all-or-nothing single storage write. Original projects stay
 // untouched on a parse error, invalid data, or storage quota failure.
 if(additions.length && !write(existing.concat(additions)))
   throw Error('Мало памяти для восстановления. Старые истории не изменены.');
 return {added:additions.length,skipped,total:existing.length+additions.length};
}
root.NovaStoryDirector=Object.freeze({ideaRequest,continuationRequest,appendContinuation,parse,read,write,exportBackup,mergeBackup});
})(typeof window !== 'undefined' ? window : globalThis);
