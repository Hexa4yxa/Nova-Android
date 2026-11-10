/*
 * NOVA Director Initiative. Offline-first, single-pass idea generation.
 * This module never invokes a network service, modifies projects or runs tools.
 * A real local LLM writes the suggestion; app only supplies verified context.
 */
(function(root){
'use strict';
const PREFERENCES_KEY='nova_director_preferences_v1';
const IDEA_KEY='nova_director_last_idea_v1';
const clean=(value,max)=>String(value==null?'':value).replace(/[\u0000-\u001f]/g,' ').trim().slice(0,max);
function storage(provided){
 try{return provided||root.localStorage||null;}catch(_){return null;}
}
function preferences(provided){
 try{
  const raw=JSON.parse(storage(provided)?.getItem(PREFERENCES_KEY)||'{}');
  return {initiative:raw.initiative!==false};
 }catch(_){return {initiative:true};}
}
function setInitiative(enabled,provided){
 const value={initiative:enabled===true};
 const store=storage(provided);
 if(!store)throw Error('Локальное хранилище недоступно.');
 store.setItem(PREFERENCES_KEY,JSON.stringify(value));
 return value;
}
function dateKey(now=new Date()){
 const d=now instanceof Date?now:new Date(now);
 if(!Number.isFinite(d.getTime()))return '';
 return [d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-');
}
function savedIdea(provided){
 try{
  const value=JSON.parse(storage(provided)?.getItem(IDEA_KEY)||'null');
  return value&&typeof value.text==='string'&&typeof value.day==='string'?
   {day:value.day,text:clean(value.text,1000)}:null;
 }catch(_){return null;}
}
function recordIdea(text,now=new Date(),provided){
 const idea=clean(text,1000);
 if(!idea)throw Error('Нельзя сохранить пустую идею.');
 const record={day:dateKey(now),text:idea};
 const store=storage(provided);
 if(!store)throw Error('Локальное хранилище недоступно.');
 store.setItem(IDEA_KEY,JSON.stringify(record));
 return record;
}
function shouldOffer(now=new Date(),provided){
 if(!preferences(provided).initiative)return false;
 const previous=savedIdea(provided);
 return !previous||previous.day!==dateKey(now);
}
function projectContext(stories){
 if(!Array.isArray(stories)||!stories.length)return 'Сохранённых проектов пока нет.';
 const project=[...stories].reverse().find(s=>s&&typeof s.title==='string'&&Array.isArray(s.scenes));
 if(!project)return 'Сохранённых проектов пока нет.';
 const count=project.scenes.length;
 const ready=project.scenes.filter(x=>x&&typeof x.imageUri==='string'&&x.imageUri.length>0).length;
 const movie=typeof project.movieUri==='string'&&!!project.movieUri;
 return [
  'Последний сохранённый проект: '+clean(project.title,90),
  'Задумка пользователя: '+clean(project.idea,220),
  'Кадры: '+ready+' из '+count+' изображений.',
  'Готовый MP4 отмечен в проекте: '+(movie?'да':'нет')+'.'
 ].join('\n');
}
function prompt(stories){
 return [
  'Ты NOVA — самостоятельный творческий режиссёр, уверенная и дружелюбная.',
  'Предложи ОДНУ оригинальную, реализуемую на телефоне идею короткого ролика 9:16 (20–45 секунд).',
  'Твой вкус важен, но не требуй идеальных кадров, не запускай бесконечные переделки.',
  'Ограничения: 3–6 ключевых кадров, панорамирование/зум, простые эффекты и доступная офлайн-озвучка.',
  'Если прошлый проект незавершён, можешь предложить конкретное улучшение вместо нового сюжета.',
  'Не выдавай предположения за просмотренное видео и не утверждай, что запустила генератор или открыла приложения.',
  'Без внутренних рассуждений, без JSON. 2–4 коротких предложения на русском, с хуком и концовкой.',
  'Проверенные локальные данные:',
  projectContext(stories)
 ].join('\n');
}
const api=Object.freeze({preferences,setInitiative,dateKey,savedIdea,recordIdea,shouldOffer,projectContext,prompt});
root.NovaDirectorInitiative=api;
if(typeof module==='object'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
