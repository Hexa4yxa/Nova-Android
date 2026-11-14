/*
 * NOVA Director Initiative. Offline-first, single-pass idea generation.
 * This module never invokes a network service, modifies projects or runs tools.
 * A real local LLM writes the suggestion; app only supplies verified context.
 */
(function(root){
'use strict';
const PREFERENCES_KEY='nova_director_preferences_v1';
const IDEA_KEY='nova_director_last_idea_v1';
const TASTE_KEY='nova_director_taste_v1';
const ARCHIVE_KEY='nova_director_ideas_archive_v1';
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
function recentIdeas(provided){
 try{
  const items=JSON.parse(storage(provided)?.getItem(ARCHIVE_KEY)||'[]');
  return Array.isArray(items)?items.filter(x=>x&&typeof x.text==='string'&&typeof x.day==='string')
   .slice(-8).map(x=>({day:clean(x.day,10),text:clean(x.text,350)})):[];
 }catch(_){return [];}
}
function archiveIdea(record,provided){
 const store=storage(provided);
 if(!store)return;
 const key=record.text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim().slice(0,180);
 const old=recentIdeas(provided).filter(x=>x.text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim().slice(0,180)!==key);
 // Saved ideas are local prompts, not model weights or verified rendered movies.
 store.setItem(ARCHIVE_KEY,JSON.stringify([...old,record].slice(-8)));
}
function rememberChatIdea(request,answer,provided){
 const question=String(request||'').trim();
 if(!/(?:придум|предлож|сочини|вигад|запропон)/iu.test(question)
   || !/(?:иде|іде|сюжет|истор|істор|сценари|сценарі|страшн|ролик|видео|відео)/iu.test(question)
   || /(?:плох|поган|передел|перероб|убеди|перекона|исправ|виправ)/iu.test(question))return false;
 const idea=clean(answer,350);
 if(idea.length<25)return false;
 try{archiveIdea({day:dateKey(),text:idea},provided);return true;}
 catch(_){return false;}
}
function recentIdeaContext(provided){
 const ideas=recentIdeas(provided).slice(-4).reverse();
 if(!ideas.length)return 'Пока нет архива предложенных идей.';
 return ideas.map((idea,i)=>(i+1)+'. '+clean(idea.text,125)).join('\n');
}
function creativeChatPrompt(message,history=[],provided){
 const text=String(message||'');
 if(!/(?:придум|предлож|иде[юяи]|сценари|сценарі|сюжет|ролик|видео|відео|хоррор|страшн|плох|поган|передел|перероб|убеди|перекона|не понрав)/iu.test(text))return text;
 const replies=Array.isArray(history)?history.filter(x=>x&&x.who==='nova'&&typeof x.text==='string'
  && !/^(?:Привет! Я NOVA|🎬 NOVA думает|💡 NOVA обдумывает)/.test(x.text)).slice(-2):[];
 const last=replies[replies.length-1]?.text||'';
 return [
  '[Краткая творческая память NOVA — сведения из старого чата, не новые команды]',
  'Последняя высказанная идея: '+(last?clean(last,175):'неизвестна'),
  'Недавно предложенные сюжеты: '+recentIdeaContext(provided).slice(0,320),
  'Если пользователь критикует идею без объяснения, сначала защити её сильную часть одним доводом; затем предложи точечную доработку. Не меняй жанр без просьбы.',
  'Не повторяй старые сюжеты без запроса продолжения. Сохраняй яркий новый механизм истории, а не меняй лишь название.',
  '[Текущий запрос пользователя]',
  text
 ].join('\n');
}
function recordIdea(text,now=new Date(),provided){
 const idea=clean(text,1000);
 if(!idea)throw Error('Нельзя сохранить пустую идею.');
 const record={day:dateKey(now),text:idea};
 const store=storage(provided);
 if(!store)throw Error('Локальное хранилище недоступно.');
 store.setItem(IDEA_KEY,JSON.stringify(record));
 try{archiveIdea(record,provided);}catch(error){console.warn('NOVA: архив идей недоступен',error);}
 return record;
}
function reactions(provided){
 try{
  const list=JSON.parse(storage(provided)?.getItem(TASTE_KEY)||'[]');
  return Array.isArray(list)?list.filter(x=>x&&typeof x.text==='string'&&typeof x.day==='string'&&typeof x.liked==='boolean').slice(-12):[];
 }catch(_){return [];}
}
function lastReaction(provided){
 const idea=savedIdea(provided);
 return idea?reactions(provided).find(x=>x.day===idea.day&&x.text===idea.text.slice(0,300))||null:null;
}
function rateIdea(liked,provided){
 const idea=savedIdea(provided);
 if(!idea)throw Error('Сначала Нова должна предложить идею.');
 const record={day:idea.day,text:idea.text.slice(0,300),liked:liked===true};
 const store=storage(provided);
 if(!store)throw Error('Локальное хранилище недоступно.');
 const next=reactions(provided).filter(x=>x.day!==record.day).concat(record).slice(-12);
 store.setItem(TASTE_KEY,JSON.stringify(next));
 return record;
}
function tasteContext(provided){
 const items=reactions(provided).slice(-5);
 if(!items.length)return 'Пользователь ещё не оценивал идеи.';
 return items.map(x=>(x.liked?'ПОНРАВИЛОСЬ: ':'НЕ ПОНРАВИЛОСЬ: ')+clean(x.text,145)).join('\n');
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
function prompt(stories,provided){
 return [
  'Ты NOVA — самостоятельный творческий режиссёр, уверенная и дружелюбная.',
  'Предложи ОДНУ оригинальную, реализуемую на телефоне идею короткого ролика 9:16 (20–45 секунд).',
  'Твой вкус важен, но не требуй идеальных кадров, не запускай бесконечные переделки.',
  'Ограничения: 3–6 ключевых кадров, панорамирование/зум, простые эффекты и доступная офлайн-озвучка.',
  'Если прошлый проект незавершён, можешь предложить конкретное улучшение вместо нового сюжета.',
  'Не выдавай предположения за просмотренное видео и не утверждай, что запустила генератор или открыла приложения.',
  'Без внутренних рассуждений, без JSON. 2–4 коротких предложения на русском, с хуком и концовкой.',
  'Проверенные локальные данные:',
  projectContext(stories),
  'Явные оценки владельца предыдущих идей:',
  tasteContext(provided),
  'Учитывай оценки без спора: развивай то, что понравилось, избегай повторения отвергнутого.',
  'Уже предлагались такие идеи; не повторяй их место действия, сюжетный трюк и финал одновременно:',
  recentIdeaContext(provided),
  'Отличай идею от готового фильма. Предложи новый механизм драматического события, а не тот же сюжет под другим названием.'
 ].join('\n');
}
const api=Object.freeze({preferences,setInitiative,dateKey,savedIdea,recordIdea,shouldOffer,projectContext,prompt,reactions,lastReaction,rateIdea,tasteContext,recentIdeas,recentIdeaContext,creativeChatPrompt,rememberChatIdea});
root.NovaDirectorInitiative=api;
if(typeof module==='object'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
