/* NOVA STORY DIRECTOR — completely offline, no network and no additional AI model. 
   Gemma creates scene prompts. The user approves each expensive render. */
(function(root) {
'use strict';
const KEY='nova_story_projects_v1';
function clean(value,max) { return String(value==null?'':value).replace(/[\u0000-\u001f]/g,' ').trim().slice(0,max); }
function ideaRequest(idea,count=3) {
 const subject=clean(idea,380);
 if(subject.length<12)throw Error('Опиши идею истории хотя бы одним предложением.');
 const number=Math.max(1,Math.min(3,Number(count)||3));
 return [
  'You are NOVA, a creative film director. Create a cohesive ORIGINAL three-scene storyboard.',
  'Story idea from owner (must preserve it): '+subject,
  'Return ONLY a compact JSON object, NO Markdown, with these exact fields:',
  '{"title":"short Russian title","hero":"one clear English character appearance anchor","style":"short English visual style and mood anchor","scenes":[{"beat":"short Russian event","prompt":"single English photorealistic Stable Diffusion 1.5 prompt"}] }',
  'Make exactly '+number+' scenes. One single protagonist look across all scenes.',
  'Each English prompt must name the protagonist and explain a concrete visible ACTION, location, framing and light.',
  'Each scene changes an action while preserving person, outfit, era, atmosphere.',
  'Keep prompts concise (under 145 English words each). No self-references, no imaginary image output.',
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
   // Shared anchors go into EVERY prompt for better consistency.
   const anchor='Same protagonist: '+hero+'. Visual continuity: '+style+'. ';
   const prompt=(anchor+rawPrompt).slice(0,590);
   if(!english(prompt))throw Error('Сцена '+(i+1)+': промпт не на английском.');
   return {number:i+1,beat,prompt,imageUri:'',renderSeconds:0};
 });
 return {
   id:'story-'+Date.now(),title,idea:clean(original,380),hero,style,
   createdAt:new Date().toISOString(),scenes
 };
}
function parse(raw,original){return validate(extract(raw),original);}
function read(){
 try{
  const saved=JSON.parse(localStorage.getItem(KEY)||'[]');
  if(!Array.isArray(saved))return [];
  return saved.filter(x=>x&&typeof x==='object'&&typeof x.id==='string'&&
    typeof x.title==='string'&&Array.isArray(x.scenes)&&x.scenes.length<=3).slice(-6);
 }catch(_){return []}
}
function write(stories) {
 const list=stories.slice(-6);
 try{localStorage.setItem(KEY,JSON.stringify(list));return true}
 catch(_){return false}
}
root.NovaStoryDirector=Object.freeze({ideaRequest,parse,read,write});
})(typeof window !== 'undefined' ? window : globalThis);
