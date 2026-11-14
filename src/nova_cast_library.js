/* NOVA Series Cast Bible v1. Offline, shared across episodes.
 * Cast snapshots in saved projects NEVER auto-change when an actor is edited.
 * Image reference metadata is not image-to-image conditioning. */
(function(root){
'use strict';
const KEY='nova_series_cast_v1';
const ROLES=Object.freeze(['hero','antagonist','support']);
function clean(value,max){return String(value??'').replace(/[\u0000-\u001f]/g,' ').trim().slice(0,max)}
function english(value){return /[a-z]/i.test(value)&&!/[а-яёіїєґ]/iu.test(value)}
function copy(value){return JSON.parse(JSON.stringify(value))}
function read(){
 try{
  const data=JSON.parse(localStorage.getItem(KEY)||'[]');
  return Array.isArray(data)?data.filter(x=>x&&typeof x.id==='string'&&Array.isArray(x.characters)).map(copy):[];
 }catch(_){return []}
}
function write(items){
 if(!Array.isArray(items)||items.length>100)return false;
 try{localStorage.setItem(KEY,JSON.stringify(items));return true}catch(_){return false}
}
function newId(prefix){return prefix+'-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,10)}
function createSeries(title,styleId='comic'){
 const name=clean(title,80);
 if(name.length<3)throw Error('Укажи название сериала (не менее 3 символов).');
 if(!['comic','anime','painting'].includes(styleId))throw Error('Выбери стиль сериала.');
 const items=read();
 const series={id:newId('series'),title:name,styleId,characters:[],createdAt:new Date().toISOString()};
 items.push(series);
 if(!write(items))throw Error('Нет места для сохранения сериала. Ничего не изменено.');
 return copy(series);
}
function normalizeCharacter(data,id){
 const name=clean(data?.name,64),role=clean(data?.role,20);
 const appearance=clean(data?.appearance,150),outfit=clean(data?.outfit,110);
 if(name.length<2)throw Error('Укажи имя персонажа.');
 if(!ROLES.includes(role))throw Error('Выбери роль персонажа.');
 if(appearance.length<15||!english(appearance))throw Error('Внешность: 15–150 символов по-английски для SD1.5.');
 if(outfit.length<5||!english(outfit))throw Error('Одежда: 5–110 символов по-английски для SD1.5.');
 // Store the complete canonical appearance, not just one scene's cropped prompt.
 return {id,name,role,appearance,outfit};
}
function addCharacter(seriesId,data){
 const items=read(),series=items.find(x=>x.id===seriesId);
 if(!series)throw Error('Сериал не найден.');
 if(series.characters.length>=24)throw Error('Максимум 24 персонажа в одном сериале.');
 const character=normalizeCharacter(data,newId('actor'));
 if(series.characters.some(x=>x.name.toLowerCase()===character.name.toLowerCase()))
  throw Error('Такое имя персонажа уже есть в сериале.');
 series.characters.push(character);
 if(!write(items))throw Error('Не удалось сохранить персонажа.');
 return copy(character);
}
function updateCharacter(seriesId,characterId,data){
 const items=read(),series=items.find(x=>x.id===seriesId);
 const index=series?.characters.findIndex(x=>x.id===characterId)??-1;
 if(index<0)throw Error('Персонаж не найден.');
 const character=normalizeCharacter(data,characterId);
 if(series.characters.some(x=>x.id!==characterId&&x.name.toLowerCase()===character.name.toLowerCase()))
  throw Error('Такое имя персонажа уже есть в сериале.');
 series.characters[index]=character;
 if(!write(items))throw Error('Не удалось обновить персонажа.');
 return copy(character);
}
function anchor(character){
 if(!character)return '';
 return clean(character.appearance,150)+', '+clean(character.outfit,110);
}
function snapshot(seriesId,leadId){
 const series=read().find(x=>x.id===seriesId);
 if(!series)throw Error('Выбери существующий сериал.');
 const lead=series.characters.find(x=>x.id===leadId);
 if(!lead)throw Error('Выбери персонажа для главной роли.');
 return {seriesId:series.id,seriesTitle:series.title,styleId:series.styleId,
  leadCharacterId:lead.id,characters:copy(series.characters)};
}
function validSeries(x){
 return x&&typeof x.id==='string'&&x.id.length<=100&&typeof x.title==='string'&&
  x.title.length>=3&&x.title.length<=80&&['comic','anime','painting'].includes(x.styleId)&&
  Array.isArray(x.characters)&&x.characters.length<=24&&x.characters.every(c=>{
   try{return c&&typeof c.id==='string'&&c.id.length<=100&&
    normalizeCharacter(c,c.id).name===c.name}catch(_){return false}
  });
}
function validateBackup(series){
 if(!Array.isArray(series)||series.length>100||!series.every(validSeries))
  throw Error('Неверная библиотека персонажей в архиве.');
 return copy(series);
}
function mergeBackup(series){
 const safe=validateBackup(series),items=read(),seen=new Set(items.map(x=>x.id));
 const additional=safe.filter(x=>!seen.has(x.id));
 if(additional.length&&!write(items.concat(additional)))
  throw Error('Не удалось восстановить библиотеку персонажей.');
 return {added:additional.length,total:items.length+additional.length};
}
root.NovaCastLibrary=Object.freeze({
 read,createSeries,addCharacter,updateCharacter,anchor,snapshot,validateBackup,mergeBackup,roles:ROLES
});
})(typeof window!=='undefined'?window:globalThis);
