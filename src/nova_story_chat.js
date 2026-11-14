/* NOVA Story Chat -> existing offline storyboard and native SD 1.5.
 * A combined owner request is explicit consent for sequential frames.
 * Never infer successful rendering from Gemma's text. All story records stay
 * in the same local storage as the Projects tab and its merge-only backup.
 */
(function(root){
 'use strict';
 function wantsStoryImages(text){
  if(typeof text!=='string')return false;
  const story=/(?:истори|історі|сказк|казк|сюжет|сценари|сценарі)/iu.test(text);
  const pictures=/(?:картин|изображени|зображенн|малюн|рисунк|иллюстрац|ілюстрац|кадр)/iu.test(text);
  // "Подготовь шесть кадров" is a storyboard request, not permission to render.
  const explicitRender=/(?:нарисуй|намалюй|нарисовать|намалювати|сгенерируй|згенеруй|рисуй|малюй)|(?:создай|сделай|зроби|створи)\s+(?:(?:к\s+ним|для\s+не[её]|мне|сразу|ещ[её]|шість|шесть|6|три|3)\s+){0,4}(?:картин|изображени|зображенн|малюн|рисунк|иллюстрац|ілюстрац|кадр)/iu.test(text);
  const exclusion=/(?:без\s+(?:картин|изображени|зображенн|малюн|рисунк)|не\s+(?:рисуй|создавай|делай|генерируй|малюй|створюй)\s+(?:картин|изображени|зображенн|малюн|рисунк))/iu.test(text);
  return story&&pictures&&explicitRender&&!exclusion;
 }
 // Director Council is a separate native tool. Don't fake independent
 // criticism in a one-shot chat or launch costly image rendering instead.
 function wantsCouncilPlanning(text){
  if(typeof text!=='string')return false;
  return /(?:внутренн(?:им|ий|его)\s+голос|внутрішн(?:ім|ій)\s+голос|режисс[её]рск(?:ий|им)\s+совет|режисерськ(?:у|ою)\s+рад|\bcritic\b|критик)/iu.test(text)
    && /(?:истори|історі|сценари|сценарі|сюжет|кадр|ролик|видео|відео)/iu.test(text)
    && !wantsStoryImages(text);
 }

 // An initial model can still be Initializing, or its loading task may
 // already be underway from app startup. Avoid overlapping prepare calls.
 async function readyBrain(brain,onStatus=()=>{},isCancelled=()=>false){
  const deadline=Date.now()+90000;
  let triedPrepare=false;
  const pause=()=>new Promise(resolve=>setTimeout(resolve,450));
  while(Date.now()<deadline){
   if(isCancelled())return false;
   const state=await brain.status();
   if(state.engine==='ModelReady')return true;
   if(state.engine==='Error')throw Error('Локальная модель: '+(state.detail||'ошибка движка'));
   if(state.modelLoading || state.generating){
    onStatus('🧠 NOVA ожидает завершения загрузки мозга...');
    await pause();
    continue;
   }
   if(!triedPrepare){
    triedPrepare=true;
    onStatus('🧠 NOVA запускает локальную модель...');
    try{await brain.prepare();}
    catch(error){
     // Another bridge call can win the race after status() is read.
     if(!/уже загружает|already|пока занят|Initializing/i.test(String(error?.message||error)))throw error;
    }
    continue;
   }
   onStatus('🧠 Жду готовности локального движка...');
   await pause();
  }
  throw Error('Движок NOVA не стал готов за 90 секунд. Проверь «Ещё → Мозги»; проекты не удалены.');
 }
 function saveScene(director,storyId,sceneNumber,result){
  // Reload current records before changing one: no clearing, slicing or
  // replacing unrelated owner stories. A failed write must halt the job.
  const entries=director.read();
  const index=entries.findIndex(s=>s.id===storyId);
  if(index<0)throw Error('Не найден сохранённый проект. PNG остаётся в Галерее.');
  const story=entries[index];
  const scene=story.scenes.find(s=>s.number===sceneNumber);
  if(!scene)throw Error('Не найдена сцена проекта. PNG остаётся в Галерее.');
  scene.imageUri=result.imageUri||'saved';
  scene.renderSeconds=Number(result.elapsedSeconds)||0;
  if(!director.write(entries))throw Error('Кадр готов, но не получилось обновить проект. PNG остаётся в Галерее.');
 }
 async function run(options){
  const {text,brain,generate,onStatus=()=>{},onPlanned=()=>{},
   onFrame=()=>{},isCancelled=()=>false,sceneCount=3}=options||{};
  const director=root.NovaStoryDirector;
  if(!director||!brain||typeof generate!=='function')
   throw Error('Режиссёр или графический инструмент NOVA недоступен.');
  if(isCancelled())return {cancelled:true,completed:0};
  const idea=String(text||'').trim();
  onStatus('🧠 NOVA создаёт раскадровку и промпты...');
  if(!(await readyBrain(brain,onStatus,isCancelled)))return {cancelled:true,completed:0};
  if(isCancelled())return {cancelled:true,completed:0};
  const styleId=director.detectArtStyle(idea);
  const plan=await brain.chat({message:director.ideaRequest(idea,3,'',styleId),maxTokens:512});
  if(isCancelled())return {cancelled:true,completed:0};
  const first=director.parse(plan?.answer,idea,styleId);
  let story=first;
  if(sceneCount===6){
   onStatus('🧠 Сцены 1–3 готовы, NOVA планирует сцены 4–6...');
   const followup=await brain.chat({
    message:director.continuationRequest(idea,first),maxTokens:512
   });
   if(isCancelled())return {cancelled:true,completed:0};
   story=director.appendContinuation(first,followup?.answer);
  }
  const existing=director.read();
  if(existing.some(item=>item.id===story.id))throw Error('Совпал идентификатор истории. Попробуй ещё раз.');
  if(!director.write(existing.concat([story])))
   throw Error('План не удалось сохранить в Проектах. Рисование не запущено.');
  onPlanned(story);
  return await renderRemaining({director,story,generate,onStatus,onFrame,isCancelled});
 }
 // The Android WebView can be recreated after a long CPU render. Recover by
 // reading the durable Projects checkpoint, not by regenerating the story.
 async function renderRemaining({director,story,generate,onStatus,onFrame,isCancelled}){
  let completed=story.scenes.filter(scene=>!!scene.imageUri).length;
  for(const scene of story.scenes){
   // Ignore already exported PNGs. Never spend another hour on finished frames.
   if(scene.imageUri)continue;
   if(isCancelled())return {cancelled:true,completed,total:story.scenes.length,story};
   onStatus('🎨 Рисую кадр '+scene.number+' из '+story.scenes.length+'...');
   const result=await generate(scene.prompt,progress=>{
    onStatus('Кадр '+scene.number+' из '+story.scenes.length+': '+progress);
   });
   if(!result?.saved)throw Error('Генератор не подтвердил сохранение кадра '+scene.number+'.');
   saveScene(director,story.id,scene.number,result);
   // Keep this in-memory scene current too, for follow-up iteration guards.
   scene.imageUri=result.imageUri||'saved';
   scene.renderSeconds=Number(result.elapsedSeconds)||0;
   completed++;
   onFrame({scene,result,completed,total:story.scenes.length,story});
  }
  return {cancelled:false,completed,total:story.scenes.length,story};
 }
 async function resumeExisting(options){
  const {storyId,generate,onStatus=()=>{},onFrame=()=>{},
   isCancelled=()=>false}=options||{};
  const director=root.NovaStoryDirector;
  if(!director||typeof generate!=='function')throw Error('Нет режиссёра или генератора NOVA.');
  const story=director.read().find(item=>item.id===storyId);
  if(!story)throw Error('Не удалось найти сохранённую историю. Старые проекты не изменены.');
  if(isCancelled())return {cancelled:true,completed:story.scenes.filter(s=>!!s.imageUri).length,total:story.scenes.length,story};
  onStatus('📂 Продолжаю историю «'+story.title+'» с первого несозданного кадра...');
  return await renderRemaining({director,story,generate,onStatus,onFrame,isCancelled});
 }
 root.NovaStoryChat=Object.freeze({wantsStoryImages,wantsCouncilPlanning,readyBrain,run,resumeExisting});
})(typeof window!=='undefined'?window:globalThis);
