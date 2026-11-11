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
test('Russian planning captions are NOT spoken verbatim: one local Gemma request writes a connected story',async()=>{
 const s=story(['Сильный дождь у маяка','«Кто там?» — спросил герой','Загорелся факел']);
 s.title='Последний сигнал';
 s.councilPlan='Мужчина должен выбрать между бегством и поиском друга.';
 let calls=0,prompt='';
 const out=await narrator.prepare(s,ruVoice,{chat:async({message,maxTokens})=>{
  calls++;prompt=message;
  assert.equal(maxTokens,768);
  return {answer:JSON.stringify({lines:[
   'Он услышал зов сквозь шторм.',
   'Но голос звучал изнутри маяка.',
   'И вдруг он узнал этот голос.'
  ]})};
 }});
 assert.equal(calls,1);
 assert.match(prompt,/not an image captioner/);
 assert.match(prompt,/NOT verified descriptions/);
 assert.match(prompt,/Последний сигнал/);
 assert.match(prompt,/Мужчина должен выбрать/);
 assert.equal(out.lines[0].text,'Он услышал зов сквозь шторм.');
 assert.equal(out.lines[1].text,'Но голос звучал изнутри маяка.');
});
test('Hand-written voice and character dialogue are preserved, others are written by Gemma',async()=>{
 const s=story(['Сильный дождь','Темнота внутри','Свет вдалеке']);
 s.scenes[0].voiceText='Я не должен был сюда приходить.';
 s.scenes[0].voiceRole='character';
 s.scenes[1].voiceText='«Кто здесь?» — спросил герой';
 s.scenes[1].voiceRole='character';
 const out=await narrator.prepare(s,ruVoice,{chat:async()=>({answer:JSON.stringify({lines:[
  'Он решил остаться навсегда.',
  'Никто не ответил на его зов.',
  'За дверью всё внезапно стихло.'
 ]})})});
 assert.equal(out.lines[0].text,'Я не должен был сюда приходить.');
 assert.equal(out.lines[0].speaker,'character');
 assert.equal(out.lines[1].text,'Кто здесь?');
 assert.equal(out.lines[2].text,'За дверью всё внезапно стихло.');
});
test('All manually written Cyrillic lines can be voiced offline with no LLM',async()=>{
 const s=story(['Первый план','Второй план','Третий план']);
 s.scenes[0].voiceText='Всю ночь он слышал шаги.';
 s.scenes[1].voiceText='Но рядом никого не было.';
 s.scenes[2].voiceText='И тогда он увидел себя.';
 const out=await narrator.prepare(s,ruVoice,null);
 assert.deepEqual(out.lines.map(x=>x.text),s.scenes.map(x=>x.voiceText));
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

test('six linked story lines align with thirty-second video and require one script generation',async()=>{
 const six=story([
  'Первый кадр: герой у входа','Второй кадр: шаги',
  'Третий кадр: герой оглянулся','Четвёртый кадр: тень',
  'Пятый кадр: отражение шевелится','Шестой кадр: финал'
 ]);
 let calls=0;
 const reply=['Он вошёл, хотя дверь была открыта.',
  'Его шаги повторил кто-то ещё.',
  'Он обернулся. Там никого не было.',
  'Но тень уже стояла рядом.',
  'Она двигалась раньше него.',
  'А потом он увидел себя.'];
 const out=await narrator.prepare(six,ruVoice,{chat:async ({message})=>{
  calls++;assert.match(message,/SPEAKER: character/);
  return {answer:JSON.stringify({lines:reply})};
 }},undefined,5);
 assert.equal(calls,1);
 assert.equal(out.lines.length,6);
 assert.deepEqual(out.lines.map(x=>x.startSeconds),[0,5,10,15,20,25]);
 assert.ok(out.lines.every(x=>x.durationSeconds===5));
 assert.equal(out.lines[5].startSeconds+out.lines[5].durationSeconds,30);
});
test('Android chooses quality-first offline voices and preserves original MP4 if dubbing fails',()=>{
 const fs=require('node:fs');
 const kt=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaVoicePlugin.kt','utf8');
 assert.match(kt,/compareByDescending<Voice> \{ it\.quality \}/);
 assert.match(kt,/!it\.isNetworkConnectionRequired/);
 assert.match(kt,/val voices=localVoices\(t\)\.filter \{ it\.locale\.language==selectedLanguage \}/);
 assert.match(kt,/val softStart=min\(1\.0,i\/1323\.0\)/);
 assert.match(kt,/Исходный MP4 и картинки не изменены/);
});
test('NOVA project keeps a readable copy of the script used for voice-over',()=>{
 const fs=require('node:fs');
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 assert.match(ui,/current\.narrationDraft=narrationDraft/);
 assert.match(ui,/Сценарий озвучки/);
 assert.match(ui,/NOVA сама напишет связный рассказ/);
});
