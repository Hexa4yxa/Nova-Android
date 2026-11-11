const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

class Element{
 constructor(tag){
  this.tagName=tag.toUpperCase();this.children=[];this.textContent='';
  this.style={};this.hidden=false;this.disabled=false;this.attributes={};
  this.value='';
 }
 appendChild(node){this.children.push(node);return node;}
 replaceChildren(...nodes){this.children=[];nodes.forEach(node=>this.appendChild(node));}
 setAttribute(k,v){this.attributes[k]=v;}
 focus(){}
}
const flatten=x=>[x,...x.children.flatMap(flatten)];
const imageUris=[1,2,3].map(n=>'content://media/external/images/media/'+n);
const comicPage='content://media/external/images/media/999';
const silentVideo='content://media/external/video/media/1000';
const voicedVideo='content://media/external/video/media/1001';
function setup(voices=true){
 const store=new Map();
 const localStorage={getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,String(v))};
 const calls={voice:[],motion:[]};
 const nativeVoice={
  status:async()=>({available:voices,count:voices?2:0,voices:voices?[{id:'ru-offline',language:'ru'},{id:'ru-offline-2',language:'ru'}]:[],detail:voices?'local':'No installed offline voices'}),
  dubMovie:async x=>{calls.voice.push(x);return {saved:true,videoUri:voicedVideo,distinctCharacterVoice:true,audio:true};}
 };
 const nativeMotion={
  render:async x=>{calls.motion.push(x);return {saved:true,videoUri:silentVideo,seconds:12};},
  addListener:async()=>({remove:async()=>{}}),cancel:async()=>({requested:true}),
  openVideo:async()=>({opened:true})
 };
 const window={Capacitor:{Plugins:{NovaMotion:nativeMotion,NovaVoice:nativeVoice}}};
 const document={createElement:t=>new Element(t)};
 const context=vm.createContext({window,document,localStorage,confirm:()=>true,console});
 for(const path of ['src/nova_story_director.js','src/nova_comic_narrator.js','src/nova_story_ui.js']){
  vm.runInContext(fs.readFileSync(path,'utf8'),context,{filename:path});
 }
 const director=window.NovaStoryDirector;
 const project={
  id:'offline-voice-story',title:'Маяк',idea:'Парень спускается под маяк',
  hero:'young man',style:'comic',
  scenes:imageUris.map((uri,index)=>({
   number:index+1,beat:['Сильный дождь у маяка','«Кто там?» — спросил герой','Загорелся факел'][index],
   prompt:'A young man and a dark mysterious lighthouse',
   imageUri:uri,renderSeconds:1
  })),
  comicPageUri:comicPage,comicFrameUris:imageUris,comicPanels:3
 };
 director.write([project]);
 const content=new Element('main');
 const brain={
  status:async()=>({engine:'ModelReady'}),
  chat:async()=>({answer:JSON.stringify({lines:[
   'Шторм заглушал шаги за его спиной.',
   'В темноте он услышал знакомый голос.',
   'И понял: это говорил он сам.'
  ]})})
 };
 const api={content,brain,isBusy:()=>false,generate:async()=>{},resumeStory:async()=>{}};
 const render=()=>window.NovaStoryUI.render(api);
 const button=text=>flatten(content).find(n=>n.tagName==='BUTTON'&&n.textContent.includes(text));
 return {director,content,render,button,calls};
}

test('NOVA creates voiced MP4 after the existing panel-by-panel motion render',async()=>{
 const x=setup();x.render();
 await x.button('Читать комикс в MP4').onclick();
 assert.equal(x.calls.motion.length,1);
 assert.equal(x.calls.voice.length,1);
 assert.equal(x.calls.voice[0].videoUri,silentVideo);
 const lines=x.calls.voice[0].lines;
 assert.equal(lines.length,3);
 assert.equal(lines[0].speaker,'narrator');
 assert.equal(lines[0].effect,'none'); // The visual rain is never converted to hiss.
 assert.equal(lines[1].speaker,'narrator');
 assert.equal(lines[1].text,'В темноте он услышал знакомый голос.');
 assert.equal(lines[2].effect,'none');
 const story=x.director.read()[0];
 assert.equal(story.movieUri,voicedVideo);
 assert.equal(story.movieHasAudio,true);
 assert.deepEqual(Array.from(story.narrationDraft),[
  'Шторм заглушал шаги за его спиной.',
  'В темноте он услышал знакомый голос.',
  'И понял: это говорил он сам.'
 ]);
 assert.ok(flatten(x.content).some(n=>n.tagName==='SUMMARY'&&
  n.textContent.includes('Сценарий озвучки')));
 assert.deepEqual(Array.from(story.scenes.map(s=>s.imageUri)),imageUris);
 assert.equal(story.comicPageUri,comicPage);
});

test('audio settings default to dry narration and never infer rain from repeated prompt words',async()=>{
 const x=setup();x.render();
 await x.button('Читать комикс в MP4').onclick();
 assert.equal(x.calls.voice.length,1);
 assert.ok(x.calls.voice[0].lines.every(line=>line.effect==='none'));
 const project=x.director.read()[0];
 assert.notEqual(project.enableAudioEffects,true);
 const ui=fs.readFileSync('src/nova_story_ui.js','utf8');
 assert.match(ui,/story\.enableAudioEffects===true/);
 assert.match(ui,/story\.scenes\[index\]\?\.audioEffect/);
 assert.doesNotMatch(ui,/effect:shots\[index\]\.effect/);
});
test('missing offline voice does not remove the finished silent MP4',async()=>{
 const x=setup(false);x.render();
 await x.button('Читать комикс в MP4').onclick();
 assert.equal(x.calls.voice.length,0);
 assert.equal(x.director.read()[0].movieUri,silentVideo);
 assert.equal(x.director.read()[0].movieHasAudio,false);
});

test('user can keep an optional character line without changing storyboard',()=>{
 const x=setup();x.render();
 const details=flatten(x.content).find(n=>n.tagName==='SUMMARY'&&n.textContent==='🎙 Озвучка и диалог');
 assert.ok(details);
 const input=flatten(x.content).find(n=>n.tagName==='TEXTAREA'&&n.attributes['aria-label']==='Текст озвучки сцены 1');
 assert.ok(input);
 assert.equal(input.value,''); // Do NOT prefill with an inaccurate scene description.
 input.value='Нам нужно вернуться!';
 const save=x.button('Сохранить реплику');
 save.onclick();
 const after=x.director.read()[0];
 assert.equal(after.scenes[0].voiceText,'Нам нужно вернуться!');
 assert.equal(after.scenes[0].imageUri,imageUris[0]);
 x.render();
 const updated=flatten(x.content).find(n=>n.tagName==='TEXTAREA'&&
  n.attributes['aria-label']==='Текст озвучки сцены 1');
 assert.equal(updated.value,'Нам нужно вернуться!');
 updated.value='';
 x.button('Сохранить реплику').onclick();
 assert.equal(x.director.read()[0].scenes[0].voiceText,'');
 assert.equal(x.director.read()[0].scenes[0].imageUri,imageUris[0]);
});

test('Android native speech is explicitly offline and muxes AAC with untouched H264 video',()=>{
 const text=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaVoicePlugin.kt','utf8');
 const activity=fs.readFileSync('android/app/src/main/java/ai/nova/director/MainActivity.java','utf8');
 assert.match(text,/!it\.isNetworkConnectionRequired/);
 assert.match(text,/synthesizeToFile\(/);
 assert.match(text,/MediaFormat\.MIMETYPE_AUDIO_AAC/);
 assert.match(text,/MediaMuxer\(/);
 assert.match(text,/MediaExtractor\(/);
 assert.match(text,/MediaStore\.Video\.Media\.IS_PENDING/);
 assert.match(text,/rain/);
 assert.match(text,/torch/);
 assert.match(text,/fog/);
 assert.match(activity,/registerPlugin\(NovaVoicePlugin\.class\)/);
});
