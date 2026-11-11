/* NOVA local narrator: NEVER feed English image prompts to Ukrainian/Russian TTS.
 * Prepare short understandable voice lines with the already-connected offline Gemma.
 * Nothing gets synthesized unless the script passes deterministic language checks. */
(function(root){
'use strict';
const CYRILLIC=/[А-Яа-яЁёІіЇїЄєҐґ]/u;
const LATIN=/[A-Za-z]/;
const UK_MARKERS=/[ІіЇїЄєҐґ]/u;
const MAX_CHARS=56;
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
function prompt(story, lang){
 const name=lang==='uk'?'UKRAINIAN (українською)':'RUSSIAN (по-русски)';
 const scenes=story.scenes.map((s,i)=>String(i+1)+'. '+tidy(s.beat).slice(0,180)).join('\n');
 return [
  'You are NOVA, a professional short-film narrator and translator.',
  'Write exactly '+story.scenes.length+' spoken lines IN '+name+'.',
  'The scene summaries below may be in English. TRANSLATE their meaning into natural speech, do not pronounce English words.',
  'Each line: 3 to 6 SHORT words, MAXIMUM '+MAX_CHARS+' characters, suitable for speaking in under 4 seconds.',
  'Keep the story and character roles coherent. No Latin letters, English words, phonetic nonsense, hashtags or stage directions.',
  'Do not describe camera angles; tell the story. Do not make up unrelated events.',
  'Return ONLY a JSON object: {"lines":["...","..."]}. No markdown, no explanation.',
  'Story idea: '+tidy(story.idea).slice(0,240),
  'Scenes:\n'+scenes
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
 const original=story.scenes.map(scene=>{
  const raw=tidy(scene.voiceText||scene.beat);
  const quoted=quote(raw);
  return {text:quoted,role:scene.voiceRole==='character'||quoted!==raw?'character':'narrator'};
 });
 const allReadable=original.every(s=>readable(s.text));
 const idea=tidy(story.idea||'');
 const sourceLanguage=UK_MARKERS.test(idea)?'uk':'ru';
 // Even existing Cyrillic lines must be translated if the only available
 // local TTS voice speaks the OTHER language.
 const mustTranslate=!allReadable||sourceLanguage!==lang;
 let generated=null;
 if(mustTranslate){
  if(!brain||typeof brain.chat!=='function')
   throw Error('Нужна локальная Gemma для перевода описаний сцен.');
  if(typeof onProgress==='function')
   onProgress('🧠 NOVA переводит историю для понятной '+(lang==='uk'?'украинской':'русской')+' озвучки...');
  const result=await brain.chat({message:prompt(story,lang),maxTokens:512});
  generated=parse(result?.answer,story.scenes.length);
 }
 const seconds=secondsPerScene===5?5:4;
 const lines=original.map((item,i)=>({
  text:mustTranslate?generated[i]:item.text,
  speaker:item.role,
  startSeconds:i*seconds,durationSeconds:seconds
 }));
 if(!lines.every(item=>readable(item.text)))throw Error('Озвучка не прошла языковую проверку.');
 return {language:lang,lines};
}
root.NovaComicNarrator=Object.freeze({prepare,prompt,parse,language,readable});
if(typeof module==='object'&&module.exports)
 module.exports={prepare,prompt,parse,language,readable};
})(typeof window!=='undefined'?window:globalThis);
