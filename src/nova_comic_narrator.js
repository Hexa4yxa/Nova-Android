/* NOVA local narrator: NEVER feed English image prompts to Ukrainian/Russian TTS.
 * Prepare short understandable voice lines with the already-connected offline Gemma.
 * Nothing gets synthesized unless the script passes deterministic language checks. */
(function(root){
'use strict';
const CYRILLIC=/[А-Яа-яЁёІіЇїЄєҐґ]/u;
const LATIN=/[A-Za-z]/;
const UK_MARKERS=/[ІіЇїЄєҐґ]/u;
const MAX_CHARS=78;
function tidy(value){
 return String(value||'').replace(/\s+/g,' ').trim();
}
function readable(value){
 const line=tidy(value);
 return line.length>=5&&line.length<=MAX_CHARS&&CYRILLIC.test(line)&&!LATIN.test(line);
}
function quote(value){
 const text=tidy(value);
 const match=text.match(/[«"]([^»"]{3,90})[»"]/u);
 return match?tidy(match[1]):text;
}

function words(value){
 return tidy(value).toLowerCase().replace(/[^а-яёіїєґa-z0-9\s]/gu,' ')
  .split(/\s+/).filter(w=>w.length>=4)
  .map(w=>w.slice(0,Math.min(w.length,5)));
}
function scriptProblem(lines,story,manualMask=[]){
 const seen=new Set();let copied=0;let plot=0;
 for(let i=0;i<lines.length;i++){
  if(manualMask[i])continue;
  const line=tidy(lines[i]),normalized=line.toLowerCase();
  if(/(?:на (?:этой )?(?:картинке|фотографи[ия]|изображени[ие]|кадре)|в кадре|мы видим|камера (?:движ|показ)|на (?:цьому )?кадрі|на малюнку|ми бачимо)/iu.test(normalized))
   return 'это описание изображения, а не рассказ';
  if(seen.has(normalized))return 'одинаковые реплики в разных сценах';
  seen.add(normalized);
  const spoken=new Set(words(line));
  const event=new Set(words(story.scenes[i]?.beat));
  const common=[...spoken].filter(w=>event.has(w)).length;
  if(event.size>=3&&spoken.size>=3&&common/Math.min(event.size,spoken.size)>=0.75)copied++;
  if(/(?:^|\W)(?:он|она|они|его|её|ей|ему|я|мне|мой|мы|ты|их|меня|себя|кто|никто|но|вдруг|тогда|почему|понял|услышал|почувствовал|решил|хотел|оказалось|він|вона|вони|його|її|мені|ніхто|раптом|зрозумів|відчув|почув)(?:$|\W)/iu.test(normalized))plot++;
 }
 if(copied>0)return 'повторение описаний раскадровки';
 if(lines.length>=4 && plot<2)return 'нет связного повествования от лица героя или рассказчика';
 return '';
}

function language(story, status){
 const available=new Set((Array.isArray(status?.voices)?status.voices:[])
  .map(v=>v?.language).filter(v=>v==='ru'||v==='uk'));
 // A Russian-language idea should use a Russian-speaking local voice.
 // Ukrainian markers take precedence; never silently switch the voice language
 // after a script has been generated.
 const idea=tidy(story?.idea||'');
 const preferred=UK_MARKERS.test(idea)?'uk':'ru';
 if(available.has(preferred))return preferred;
 if(available.has(preferred==='ru'?'uk':'ru'))return preferred==='ru'?'uk':'ru';
 // Older Android bridges expose only available=true. Native side still verifies.
 return preferred;
}
function prompt(story, lang, secondsPerScene=4){
 const name=lang==='uk'?'UKRAINIAN (українською)':'RUSSIAN (по-русски)';
 const scenes=story.scenes.map((scene,i)=>{
  const beat=tidy(scene.beat).slice(0,180);
  const handWritten=tidy(scene.voiceText).slice(0,90);
  return String(i+1)+'. STORY EVENT: '+beat+
   (scene.voiceRole==='character'?' | SPEAKER: character':'')+
   (handWritten?' | AUTHOR VOICE LINE: '+handWritten:'');
 }).join('\n');
 return [
  'You are NOVA, an experienced SHORT FILM SCREENWRITER, not an image captioner.',
  'Write exactly '+story.scenes.length+' short connected spoken STORY lines IN '+name+'.',
  'Each line corresponds to one successive event. Build cause and effect, suspense, and a meaningful ending.',
  'Begin with a hook, progressively raise the tension, and end with a memorable reveal or emotional turn.',
  'The scene beats are story planning notes, NOT verified descriptions of what the PNGs depict.',
  'Do not claim to see specific things in the pictures; say what the character feels, fears, realizes or chooses.',
  'Avoid listing visuals, objects, camera angles, color, clothing, lighting or movements.',
  'Never start lines with "На картинке", "На кадре", "Мы видим", "В кадре", or "Камера".',
  'Stick to the approved plot, locations, objects and roles. Do not invent new characters or unrelated events.',
  'The text must sound like ONE coherent narrative, not six separate scene summaries.',
  'BAD CAPTION: «Тень скользит по стене. Шипение. Взгляд вдаль.»',
  'GOOD NARRATION: «Он понял: кто-то ждёт, когда он обернётся.»',
  'Do not narrate visual descriptions. Use character motives, fears, decisions, reveals.',
  'If AUTHOR VOICE LINE appears, preserve its meaning and role; author-entered text takes precedence later.',
  'Each line: about '+(secondsPerScene===5?'8 to 11 SHORT words; aim for 55-75 characters':'6 to 9 SHORT words; aim for 35-55 characters')+', MAXIMUM '+MAX_CHARS+' characters. Fill the '+(secondsPerScene===5?'5':'4')+'-second scene with meaningful speech, but never add filler or clip a sentence.',
  'Natural punctuation and speech rhythm. No Latin letters, English words, phonetic nonsense, hashtags or stage directions.',
  'Return ONLY a JSON object: {"lines":["...","..."]}. No markdown, no explanation.',
  'Story title: '+tidy(story.title).slice(0,90),
  'Approved story outline: '+tidy(story.councilPlan||story.idea).slice(0,420),
  'Main character: '+tidy(story.hero).slice(0,130),
  'Sequential story beats:\n'+scenes
 ].join('\n');
}
function parse(answer,count){
 let raw=String(answer||'').replace(/<think>[\s\S]*?<\/think>/gi,'').trim();
 const begin=raw.indexOf('{'),end=raw.lastIndexOf('}');
 if(begin<0||end<begin)throw Error('Gemma не вернула JSON с репликами.');
 let obj;
 try{obj=JSON.parse(raw.slice(begin,end+1));}
 catch(e){throw Error('Gemma неправильно оформила текст озвучки.');}
 if(!Array.isArray(obj?.lines)||obj.lines.length!==count)
  throw Error('Gemma не подготовила все реплики для сцен.');
 const lines=obj.lines.map(tidy);
 if(!lines.every(readable))
  throw Error('В тексте озвучки остался английский язык или слишком длинные фразы.');
 return lines;
}
async function prepare(story,status,brain,onProgress,secondsPerScene=4){
 if(!story||!Array.isArray(story.scenes)||!story.scenes.length||story.scenes.length>6)
  throw Error('Некорректная история для озвучки.');
 if(!status?.available)throw Error('Нет локального голоса Android для озвучки.');
 const lang=language(story,status);
 // Descriptions (scene.beat) are planning notes, NEVER the default voice-over.
 // Only explicitly authored voiceText can be played unchanged.
 const original=story.scenes.map(scene=>{
  const raw=tidy(scene.voiceText);
  // Previous versions could store voiceText equal to the caption; that is not authored speech.
  const manual=raw&&raw!==tidy(scene.beat)?raw:'';
  const quoted=manual?quote(manual):'';
  return {text:quoted,manual:!!manual,
   role:manual&&(scene.voiceRole==='character'||quoted!==manual)?'character':'narrator'};
 });
 const idea=tidy(story.idea||'');
 const sourceLanguage=UK_MARKERS.test(idea)?'uk':'ru';
 // At least one missing authored line means NOVA must write a REAL linked
 // narrative with local Gemma, even when scene beats are already Cyrillic.
 const mustWrite=original.some(item=>!item.manual||!readable(item.text))||
  sourceLanguage!==lang;
 let generated=null;
 const seconds=secondsPerScene===5?5:4;
 if(mustWrite){
  if(!brain||typeof brain.chat!=='function')
   throw Error('Нужна локальная Gemma для написания связного сценария, а не чтения описаний кадров.');
  if(typeof onProgress==='function')
   onProgress('🧠 NOVA пишет цельную историю для '+(lang==='uk'?'украинской':'русской')+' озвучки...');
  const manualMask=original.map(item=>item.manual&&readable(item.text)&&sourceLanguage===lang);
  let problem='';
  for(let attempt=0;attempt<2;attempt++){
   const rewrite=attempt===0?'':[
    'REWRITE THE WHOLE SCRIPT. Previous attempt rejected: '+problem+'.',
    'Stop listing objects, shadow movement, sound, weather, camera or gaze.',
    'Tell the protagonist fears, decisions, discoveries; each line must advance the plot.',
    'Different story events need different lines; never paraphrase the storyboard word for word.'
   ].join('\n');
   const result=await brain.chat({
    message:prompt(story,lang,seconds)+(rewrite?'\n'+rewrite:''),
    maxTokens:768
   });
   generated=parse(result?.answer,story.scenes.length);
   problem=scriptProblem(generated,story,manualMask);
   if(!problem)break;
   if(typeof onProgress==='function')onProgress('🧠 NOVA исправляет слишком описательный сценарий...');
  }
  if(problem)throw Error('NOVA отклонила неудачный сценарий: '+problem+
   '. Не разрешаю читать подписи к кадрам вместо истории.');
 }
 const lines=original.map((item,i)=>({
  // Never overwrite a valid author-written line in the matching language.
  text:item.manual&&readable(item.text)&&sourceLanguage===lang?item.text:generated[i],
  speaker:item.role,
  startSeconds:i*seconds,durationSeconds:seconds
 }));
 if(!lines.every(item=>readable(item.text)))throw Error('Озвучка не прошла языковую проверку.');
 return {language:lang,lines};
}
root.NovaComicNarrator=Object.freeze({prepare,prompt,parse,language,readable,scriptProblem});
if(typeof module==='object'&&module.exports)
 module.exports={prepare,prompt,parse,language,readable,scriptProblem};
})(typeof window!=='undefined'?window:globalThis);
