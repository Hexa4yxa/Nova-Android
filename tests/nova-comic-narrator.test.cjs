const test=require('node:test');
const assert=require('node:assert/strict');
const narrator=require('../src/nova_comic_narrator.js');
const ruVoice={available:true,voices:[{id:'ru-local',language:'ru'}]};
const ukVoice={available:true,voices:[{id:'uk-local',language:'uk'}]};
function story(beats=['Young man arrives at abandoned lighthouse','A torch burns in an empty house','The angel wakes up']){
 return {idea:'Парень пришёл к маяку и встретил ангела',scenes:beats.map((beat,i)=>({
  beat,number:i+1,voiceRole:i===2?'character':'narrator'
 }))};
}
test('English beats become short Russian lines BEFORE the local voice is called',async()=>{
 let asked='';
 const answer=JSON.stringify({lines:['Парень пришёл к маяку.','Внутри горел старый факел.','Ангел внезапно проснулся.']});
 const out=await narrator.prepare(story(),ruVoice,{
  chat:async ({message})=>{asked=message;return {answer};}
 });
 assert.match(asked,/RUSSIAN/);
 assert.match(asked,/Young man arrives/);
 assert.equal(out.language,'ru');
 assert.deepEqual(out.lines.map(x=>x.text),[
  'Парень пришёл к маяку.','Внутри горел старый факел.','Ангел внезапно проснулся.'
 ]);
 assert.equal(out.lines[2].speaker,'character');
 assert.ok(out.lines.every(x=>x.durationSeconds===4));
});
test('Gemma output in English is rejected, avoiding unintelligible dubbed movie',async()=>{
 await assert.rejects(()=>narrator.prepare(story(),ruVoice,{
  chat:async()=>({answer:JSON.stringify({lines:[
   'He walks home','A storm starts','The ghost wakes'
  ]})})
 }),/английский/);
});
test('Russian source is translated into Ukrainian if only Ukrainian offline voice exists',async()=>{
 const s=story(['Подошёл к маяку','Увидел огонь','Ангел проснулся']);
 const out=await narrator.prepare(s,ukVoice,{
  chat:async({message})=>{
   assert.match(message,/UKRAINIAN/);
   return {answer:JSON.stringify({lines:['Він підійшов до маяка.','Усередині горів старий вогонь.','Ангел раптом прокинувся.']})};
  }
 });
 assert.equal(out.language,'uk');
 assert.equal(out.lines[0].text,'Він підійшов до маяка.');
});
test('Short Cyrillic captions are spoken unchanged in matching local voice',async()=>{
 const s=story(['Сильный дождь у маяка','«Кто там?» — спросил герой','Загорелся факел']);
 const out=await narrator.prepare(s,ruVoice,null);
 assert.equal(out.lines[0].text,'Сильный дождь у маяка');
 assert.equal(out.lines[1].speaker,'character');
 assert.equal(out.lines[1].text,'Кто там?');
});
test('No local voice means no synth request',async()=>{
 await assert.rejects(()=>narrator.prepare(story(),{available:false},null),/Нет локального голоса/);
});
test('No offline Gemma for English captions blocks TTS, instead of raw English speech',async()=>{
 await assert.rejects(()=>narrator.prepare(story(),ruVoice,null),/локальная Gemma/);
});
test('Parser accepts fenced JSON but rejects long, Latin or wrong count',()=>{
 assert.equal(narrator.parse('Narration:\n```json\n{"lines":["Ночь у моря."]}\n```',1)[0],'Ночь у моря.');
 assert.throws(()=>narrator.parse('{"lines":["English words"]}',1),/английский/);
 assert.throws(()=>narrator.parse('{"lines":["Привет"]}',2),/все реплики/);
});
test('Native implementation rejects Latin letters and matches voice language before dubbing',()=>{
 const fs=require('node:fs');
 const kt=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaVoicePlugin.kt','utf8');
 assert.match(kt,/selectedLanguage=call\.getString\("language"\)/);
 assert.match(kt,/it\.locale\.language==selectedLanguage/);
 assert.match(kt,/latin\.containsMatchIn/);
 assert.match(kt,/out\[start\+i\]\.toInt\(\)\*0\.24/);
 assert.match(kt,/actualFrames<=allowed/);
});

test('six frames align speech and effects with a thirty-second short',async()=>{
 const six=story([
  'Первый кадр: герой у входа','Второй кадр: шаги',
  'Третий кадр: герой оглянулся','Четвёртый кадр: тень',
  'Пятый кадр: отражение шевелится','Шестой кадр: финал'
 ]);
 const out=await narrator.prepare(six,ruVoice,null,undefined,5);
 assert.equal(out.lines.length,6);
 assert.deepEqual(out.lines.map(x=>x.startSeconds),[0,5,10,15,20,25]);
 assert.ok(out.lines.every(x=>x.durationSeconds===5));
 assert.equal(out.lines[5].startSeconds+out.lines[5].durationSeconds,30);
});
