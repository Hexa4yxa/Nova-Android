const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const narrator=require('../src/nova_comic_narrator.js');

test('NOVA asks Gemma for a coherent, scene-filling narration without nonsense filler',()=>{
 const prompt=narrator.prompt({
  title:'Тревожный маяк',
  idea:'Человек слышит голос из маяка',
  hero:'Человек',
  scenes:Array.from({length:6},(_,i)=>({beat:'Событие '+i}))
 },'ru',5);
 assert.match(prompt,/8 to 11 SHORT words/);
 assert.match(prompt,/aim for 55-75 characters/);
 assert.match(prompt,/one coherent narrative/i);
 assert.match(prompt,/never add filler or clip a sentence/);
 assert.match(prompt,/MAXIMUM 78 characters/);
});

test('NOVA can voice a useful longer Russian sentence and still bounds script length',()=>{
 const valid='Он остановился, потому что из пустого маяка прозвучало знакомое имя.';
 assert.ok(valid.length>56);
 assert.ok(valid.length<=78);
 assert.equal(narrator.readable(valid),true);
 assert.equal(narrator.readable('Ё'.repeat(79)),false);
 assert.equal(narrator.readable('Звуки от Google TTS'),false);
});

test('real measured WAV durations determine placement and words are never chopped per frame',()=>{
 const native=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaVoicePlugin.kt','utf8');
 const timeline=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaVoiceTimeline.kt','utf8');
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 assert.match(native,/NovaVoiceTimeline\.plan\(/);
 assert.match(native,/clips\.map \{ voiceFrames\(it\) \}/);
 assert.match(native,/addVoice\(samples,starts\[i\],clip\)/);
 assert.doesNotMatch(native,/addVoice\(samples,line,readWav\(wav\)\)/);
 assert.match(timeline,/BREATH_FRAMES/);
 assert.match(timeline,/EARLY_FRAMES/);
 assert.match(timeline,/Текст озвучки не помещается в фильм без обрезания слов/);
 assert.match(ui,/voiceText\.maxLength=78/);
 assert.match(ui,/voiceText\.value\.trim\(\)\.slice\(0,78\)/);
});
