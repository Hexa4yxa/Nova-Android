const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

class Element{
 constructor(tag){
  this.tagName=tag.toUpperCase();this.children=[];this.style={};this.attributes={};
  this.value='';this.textContent='';this.disabled=false;this.hidden=false;
 }
 appendChild(n){this.children.push(n);return n;}
 replaceChildren(...nodes){this.children=[];nodes.forEach(n=>this.appendChild(n));}
 setAttribute(key,value){this.attributes[key]=value;}
 focus(){}
}
function nodes(el){return [el,...el.children.flatMap(nodes)];}
function setup(){
 const data=new Map();
 const localStorage={
  getItem:key=>data.has(key)?data.get(key):null,
  setItem:(key,value)=>data.set(key,String(value))
 };
 const window={};
 const document={createElement:tag=>new Element(tag)};
 const answers=[];
 const context=vm.createContext({window,document,localStorage,confirm:()=>answers.shift()??false});
 for(const path of ['src/nova_story_director.js','src/nova_story_ui.js']){
  vm.runInContext(fs.readFileSync(path,'utf8'),context,{filename:path});
 }
 const director=window.NovaStoryDirector;
 const seed=n=>({
  id:'story-'+n,title:'История '+n,idea:'Сюжет '+n,hero:'hero '+n,style:'cinematic',
  createdAt:'2026-11-08T00:00:00Z',
  scenes:[{number:1,beat:'Сцена',prompt:'A character in a room',imageUri:'content://nova/'+n,renderSeconds:100}]
 });
 assert.equal(director.write([seed(1),seed(2),seed(3)]),true);
 const content=new Element('main');
 const api={content,brain:{},generate:()=>{},resumeStory:()=>{},isBusy:()=>false};
 function render(){window.NovaStoryUI.render(api);}
 function button(label){return nodes(content).find(n=>n.tagName==='BUTTON'&&n.textContent===label);}
 function namedInput(label){return nodes(content).find(n=>n.tagName==='INPUT'&&n.attributes['aria-label']===label);}
 return {director,content,answers,render,button,namedInput};
}

test('projects are compact and searchable without changing saved records',()=>{
 const x=setup();x.render();
 const cards=nodes(x.content).filter(n=>n.tagName==='SECTION'&&nodes(n).some(c=>c.tagName==='INPUT'&&c.type==='checkbox'));
 assert.equal(cards.length,3);
 assert.ok(cards.every(n=>nodes(n).some(c=>c.textContent==='▾ Сцены (1)')));
 assert.ok(cards.every(n=>nodes(n).some(c=>c.hidden===true)));
 const search=nodes(x.content).find(n=>n.tagName==='INPUT'&&n.type==='search');
 search.value='История 2';search.oninput();
 assert.equal(cards.filter(n=>n.style.display==='none').length,2);
 assert.equal(x.director.read().length,3);
});

test('renaming retains prompt, generated image reference and unrelated stories',()=>{
 const x=setup();x.render();
 x.button('✎ Название').onclick();
 const input=x.namedInput('Новое название проекта');
 input.value='Мой фильм';x.button('Сохранить').onclick();
 const stories=x.director.read();
 assert.equal(stories.length,3);
 assert.equal(stories.find(s=>s.id==='story-3').title,'Мой фильм');
 assert.equal(stories.find(s=>s.id==='story-3').scenes[0].imageUri,'content://nova/3');
 assert.equal(stories.find(s=>s.id==='story-1').title,'История 1');
});

test('individual delete asks confirmation; bulk delete affects only selected records',()=>{
 const x=setup();x.render();
 x.answers.push(false);
 x.button('🗑 Удалить').onclick();
 assert.equal(x.director.read().length,3);
 x.answers.push(true);
 x.button('🗑 Удалить').onclick();
 assert.deepEqual([...x.director.read().map(p=>p.id)],['story-1','story-2']);
 const picks=nodes(x.content).filter(n=>n.tagName==='INPUT'&&n.type==='checkbox');
 assert.equal(picks.length,2);
 picks[0].checked=true;picks[0].onchange();
 x.answers.push(true);
 x.button('🗑 Удалить выбранные').onclick();
 assert.deepEqual([...x.director.read().map(p=>p.id)],['story-1']);
 assert.equal(x.director.read()[0].scenes[0].imageUri,'content://nova/1');
});
