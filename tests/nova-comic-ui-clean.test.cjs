const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
class Element {
 constructor(tag){this.tagName=tag.toUpperCase();this.children=[];this.parent=null;this.style={};this.attributes={};this.textContent='';this.value='';this.hidden=false;this.disabled=false;}
 appendChild(x){if(x.parent){x.parent.children=x.parent.children.filter(y=>y!==x);}this.children.push(x);x.parent=this;return x;}
 replaceChildren(...items){this.children.forEach(x=>x.parent=null);this.children=[];items.forEach(x=>this.appendChild(x));}
 setAttribute(k,v){this.attributes[k]=v;}
 focus(){}
}
const walk=n=>[n,...n.children.flatMap(walk)];
function setup(finished) {
 const store=new Map();
 const localStorage={getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,String(v))};
 const document={createElement:tag=>new Element(tag)};
 const window={Capacitor:{Plugins:{NovaMotion:{render:async()=>({saved:true,videoUri:'content://media/external/video/media/2000',seconds:12}),openVideo:async()=>({opened:true})},NovaComicSheet:{build:async()=>({saved:true,pageUri:'content://media/external/images/media/999'}),openPage:async()=>({opened:true})}}}};
 const context=vm.createContext({window,document,localStorage,confirm:()=>true,console});
 for(const path of ['src/nova_story_director.js','src/nova_story_ui.js'])vm.runInContext(fs.readFileSync(path,'utf8'),context,{filename:path});
 const scenes=[1,2,3].map((i)=>({number:i,beat:'Маяк и ангел',prompt:'lighthouse',imageUri:'content://media/external/images/media/'+i,renderSeconds:5}));
 const story={id:'test',title:'Маяк',idea:'Маяк и ангел',hero:'man',style:'comic',scenes,
  comicPageUri:'content://media/external/images/media/999',comicFrameUris:scenes.map(s=>s.imageUri),comicPanels:3};
 if(finished){story.movieUri='content://media/external/video/media/123';story.movieSeconds=12;story.movieHasAudio=true;}
 window.NovaStoryDirector.write([story]);
 const content=new Element('main');
 window.NovaStoryUI.render({content,brain:{},isBusy:()=>false,generate:async()=>{},resumeStory:async()=>{}});
 return {content};
}

test('completed comic surfaces watch movie + view comic, hides 3 competing renderers',()=>{
 const {content}=setup(true);
 const btn=t=>walk(content).find(x=>x.tagName==='BUTTON'&&x.textContent.includes(t));
 const view=btn('Комикс-лист');
 const watch=btn('Смотреть MP4');
 const advanced=walk(content).find(x=>x.tagName==='DETAILS'&&x.children.some(y=>y.textContent.includes('Дополнительные инструменты')));
 assert.ok(view&&watch&&advanced);
 assert.equal(view.parent,watch.parent);
 assert.notEqual(advanced,watch.parent);
 assert.equal(btn('Создать видеокомикс').parent,advanced);
 assert.equal(btn('Режиссёрский монтаж').parent,advanced);
 assert.equal(btn('Пересоздать фильм с озвучкой').parent,advanced);
 assert.equal(btn('Пересобрать комикс-лист').parent,advanced);
 assert.ok(walk(content).some(x=>x.textContent.includes('12 сек')&&x.textContent.includes('с озвучкой')));
});

test('first-time comic exposes reader without forcing advanced controls',()=>{
 const {content}=setup(false);
 const btn=t=>walk(content).find(x=>x.tagName==='BUTTON'&&x.textContent.includes(t));
 const view=btn('Комикс-лист');
 const create=btn('Читать комикс в MP4');
 const advanced=walk(content).find(x=>x.tagName==='DETAILS'&&x.children.some(y=>y.textContent.includes('Дополнительные инструменты')));
 assert.ok(view&&create&&advanced);
 assert.equal(view.parent,create.parent);
 assert.notEqual(create.parent,advanced);
 assert.equal(btn('Создать видеокомикс').parent,advanced);
});
