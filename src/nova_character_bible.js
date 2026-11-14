/* NOVA CHARACTER BIBLE V1
 * One offline library for series, protagonists, antagonists and supporting cast.
 * IDs are immutable; appearances and approved Gallery anchors are shared across
 * episodes. No network, no new image model, and no destructive migrations.
 */
(function(root){
'use strict';
const KEY='nova_series_character_bible_v1';
const IMAGE=/^content:\/\/media\/[^/?]+\/images\/media\/\d+$/i;
const ROLES=Object.freeze(['hero','antagonist','support']);
const STYLES=Object.freeze(['comic','anime','painting']);
const clean=(v,max)=>String(v==null?'':v).replace(/[\u0000-\u001f]/g,' ').trim().slice(0,max);
const empty=()=>({version:1,series:[],characters:[]});
function check(data) {
 if(!data||data.version!==1||!Array.isArray(data.series)||!Array.isArray(data.characters)||
    data.series.length>400||data.characters.length>2000)throw Error('Библия персонажей повреждена. Данные не перезаписаны.');
 const seriesIds=new Set();
 for(const s of data.series){
  if(!s||typeof s.id!=='string'||!s.id||seriesIds.has(s.id)||typeof s.title!=='string'||
     !s.title||!STYLES.includes(s.styleId))throw Error('Неверные данные сериала. Библия не изменена.');
  seriesIds.add(s.id);
 }
 const actorIds=new Set();
 for(const a of data.characters){
  if(!a||typeof a.id!=='string'||!a.id||actorIds.has(a.id)||!seriesIds.has(a.seriesId)||
     typeof a.name!=='string'||!a.name||!ROLES.includes(a.role)||
     typeof a.appearance!=='string'||a.appearance.length<12||a.appearance.length>300||
     typeof a.referenceImageUri!=='string'||
     (a.referenceImageUri&&!IMAGE.test(a.referenceImageUri)))
    throw Error('Неверные данные персонажа. Библия не изменена.');
  actorIds.add(a.id);
 }
 return data;
}
function read() {
 const raw=localStorage.getItem(KEY);
 if(!raw)return empty();
 try{return check(JSON.parse(raw));}
 catch(e){throw Error('Не удалось прочитать библиотеку сериалов: '+(e?.message||String(e)));}
}
function save(data) {
 check(data);
 try{localStorage.setItem(KEY,JSON.stringify(data));}
 catch(e){throw Error('Не хватило памяти для сохранения библии персонажей. Старые данные остались.');}
}
function id(prefix) {return prefix+'-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,10);}
function createSeries(title,styleId='comic'){
 const name=clean(title,90);
 if(name.length<2)throw Error('Введи название сериала.');
 if(!STYLES.includes(styleId))throw Error('Выбери один из трёх стилей.');
 const db=read(),s={id:id('series'),title:name,styleId,createdAt:new Date().toISOString()};
 db.series.push(s);save(db);return s;
}
function addCharacter(seriesId,entry){
 const db=read();
 if(!db.series.some(s=>s.id===seriesId))throw Error('Сначала выбери сериал.');
 const name=clean(entry?.name,60),appearance=clean(entry?.appearance,300);
 const role=ROLES.includes(entry?.role)?entry.role:'support';
 if(name.length<2)throw Error('Назови персонажа.');
 if(appearance.length<12||!/[a-z]/i.test(appearance)||/[а-яёіїєґ]/iu.test(appearance))
  throw Error('Для генератора SD1.5 нужен английский паспорт внешности. Можешь взять его из готовой истории.');
 const a={id:id('actor'),seriesId,name,role,appearance,referenceImageUri:'',
   createdAt:new Date().toISOString()};
 db.characters.push(a);save(db);return a;
}
// Edit the shared actor passport without changing its permanent ID, approved
// image reference, or the immutable cast snapshots inside older episodes.
function updateCharacter(characterId,entry){
 const db=read(),actor=db.characters.find(a=>a.id===characterId);
 if(!actor)throw Error('Персонаж не найден.');
 const name=clean(entry?.name,60),appearance=clean(entry?.appearance,300);
 const role=entry?.role;
 if(name.length<2)throw Error('Назови персонажа.');
 if(!ROLES.includes(role))throw Error('Неизвестная роль персонажа.');
 if(appearance.length<12||!/[a-z]/i.test(appearance)||/[а-яёіїєґ]/iu.test(appearance))
  throw Error('Внешность и одежда должны быть на английском для SD1.5.');
 if(db.characters.some(a=>a.seriesId===actor.seriesId&&a.id!==actor.id&&
   a.name.toLocaleLowerCase()===name.toLocaleLowerCase()))
  throw Error('В этом сериале уже есть персонаж с таким именем.');
 actor.name=name;
 actor.role=role;
 actor.appearance=appearance;
 save(db);
 return {...actor};
}
function setReference(characterId,uri,availableFrames){
 const db=read(),actor=db.characters.find(a=>a.id===characterId);
 if(!actor)throw Error('Персонаж не найден.');
 const chosen=String(uri||'');
 if(chosen && (!IMAGE.test(chosen)||!Array.isArray(availableFrames)||!availableFrames.includes(chosen)))
  throw Error('Эталон можно взять только из сохранённых кадров NOVA; произвольный путь запрещён.');
 actor.referenceImageUri=chosen;
 save(db);return actor;
}
function getCast(seriesId){
 const db=read();return db.characters.filter(a=>a.seriesId===seriesId).map(a=>({...a}));
}
function getSeries(id){return read().series.find(s=>s.id===id)||null;}
function snapshot(seriesId){
 const series=getSeries(seriesId);
 if(!series)throw Error('Сериал не найден.');
 return {seriesId,seriesTitle:series.title,styleId:series.styleId,
  cast:getCast(seriesId).map(a=>({id:a.id,name:a.name,role:a.role,appearance:a.appearance,
    referenceImageUri:a.referenceImageUri}))};
}
function mergeBackup(part){
 if(!part)return {series:0,characters:0};
 const source=check(part),db=read();
 const knownSeries=new Set(db.series.map(x=>x.id));
 const knownChars=new Set(db.characters.map(x=>x.id));
 const newSeries=source.series.filter(x=>!knownSeries.has(x.id));
 const availableSeries=new Set([...knownSeries,...newSeries.map(x=>x.id)]);
 const newChars=source.characters.filter(x=>!knownChars.has(x.id)&&availableSeries.has(x.seriesId));
 if(newSeries.length||newChars.length)save({version:1,series:db.series.concat(newSeries),
   characters:db.characters.concat(newChars)});
 return {series:newSeries.length,characters:newChars.length};
}
root.NovaCharacterBible=Object.freeze({read,createSeries,addCharacter,updateCharacter,setReference,
 getCast,getSeries,snapshot,validateBackup:check,mergeBackup});
})(typeof window!=='undefined'?window:globalThis);
