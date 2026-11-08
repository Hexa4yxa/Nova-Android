const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

class Element {
  constructor(tag){
    this.tagName=tag.toUpperCase();this.children=[];this.textContent='';
    this.style={};this.attributes={};this.hidden=false;this.disabled=false;this.value='';
  }
  appendChild(node){this.children.push(node);return node;}
  replaceChildren(...nodes){this.children=[];nodes.forEach(node=>this.appendChild(node));}
  setAttribute(key,value){this.attributes[key]=value;}
  focus(){}
}
const descend=n=>[n,...n.children.flatMap(descend)];
function environment(withMotion=true,uri='content://media/external/images/media/123'){
  const store=new Map();
  const localStorage={
    getItem:key=>store.get(key)||null,
    setItem:(key,value)=>store.set(key,String(value))
  };
  const calls={renders:[],opens:[]};
  const motion={
    render:async payload=>{
      calls.renders.push(payload);
      return {saved:true,videoUri:'content://media/external/video/media/42',seconds:4,frames:1,silent:true};
    },
    openVideo:async payload=>{calls.opens.push(payload);return {opened:true};},
    addListener:async()=>({remove:async()=>{}}),
    cancel:async()=>({requested:true})
  };
  const window={Capacitor:{Plugins:withMotion?{NovaMotion:motion}:{}}};
  const document={createElement:tag=>new Element(tag)};
  const context=vm.createContext({window,document,localStorage,confirm:()=>true,Set,Number,String});
  for(const path of ['src/nova_story_director.js','src/nova_story_ui.js']){
    vm.runInContext(fs.readFileSync(path,'utf8'),context,{filename:path});
  }
  const project={id:'story-1',title:'Зеркало',hero:'young man',style:'dark',idea:'Зеркало',
    scenes:[{number:1,beat:'Герой',prompt:'man at mirror',imageUri:uri,renderSeconds:120}]};
  const director=window.NovaStoryDirector;
  director.write([project]);
  const content=new Element('main');
  const api={content,brain:{},isBusy:()=>false,generate:async()=>{},resumeStory:async()=>{}};
  const render=()=>window.NovaStoryUI.render(api);
  const button=label=>descend(content).find(n=>n.tagName==='BUTTON'&&n.textContent.includes(label));
  return {calls,director,content,render,button};
}

test('ready PNGs offer real motion comic export, not automatic image generation',async()=>{
  const x=environment();
  x.render();
  const exportBtn=x.button('Создать видеокомикс');
  assert.equal(exportBtn.disabled,false);
  await exportBtn.onclick();
  assert.equal(x.calls.renders.length,1);
  assert.equal(x.calls.renders[0].secondsPerFrame,4);
  assert.deepEqual(Array.from(x.calls.renders[0].frames),
    ['content://media/external/images/media/123']);
  const p=x.director.read()[0];
  assert.equal(p.movieUri,'content://media/external/video/media/42');
  assert.equal(p.scenes[0].imageUri,'content://media/external/images/media/123');
  assert.equal(p.scenes[0].prompt,'man at mirror');
  await x.button('Смотреть MP4').onclick();
  assert.equal(x.calls.opens[0].videoUri,p.movieUri);
});

test('no native renderer: export stays disabled and existing story untouched',()=>{
 const x=environment(false);
 x.render();
 assert.equal(x.button('Создать видеокомикс').disabled,true);
 assert.equal(x.director.read()[0].movieUri,undefined);
});

test('missing gallery URI or legacy saved marker never sent as rendered frame',()=>{
 const x=environment(true,'saved');
 x.render();
 assert.equal(x.button('Создать видеокомикс').disabled,true);
 assert.equal(x.director.read().length,1);
});

test('native plugin uses Android H.264 + OpenGL + MediaStore and deletes partial video',()=>{
 const native=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaMotionPlugin.kt','utf8');
 const entry=fs.readFileSync('android/app/src/main/java/ai/nova/director/MainActivity.java','utf8');
 assert.match(entry,/registerPlugin\(NovaMotionPlugin\.class\)/);
 assert.match(native,/MediaCodec\.createEncoderByType\(MediaFormat\.MIMETYPE_VIDEO_AVC\)/);
 assert.match(native,/MediaMuxer\(/);
 assert.match(native,/eglPresentationTimeANDROID/);
 assert.match(native,/MediaStore\.Video\.Media\.IS_PENDING/);
 assert.match(native,/context\.contentResolver\.delete\(uri, null, null\)/);
 assert.match(native,/stopRequested/);
});

test('video capability is truthful for both available and missing native bridge',async()=>{
 const registry=require('../src/nova_tool_registry.js');
 const route={kind:'capability',tool:'video'};
 const withMotion=await registry.describeCapability(route,{
  NovaImage:{status:async()=>({ready:true})},
  NovaMotion:{render:async()=>{}}
 });
 assert.match(withMotion,/видеокомикс MP4/);
 assert.match(withMotion,/Озвучка/);
 const without=await registry.describeCapability(route,{});
 assert.match(without,/ещё не подключён/);
});
