/* NOVA Story UI. Uses the existing local Gemma, SD1.5 and Gallery. No external service. */
(function(root){
'use strict';
const state={creating:false,drawing:false,idea:'',error:'',notice:''};
const previews={};
function node(parent,tag,cls,copy) {
 const n=document.createElement(tag);
 if(cls)n.className=cls;
 if(copy!==undefined)n.textContent=copy;
 parent.appendChild(n);
 return n;
}
function render(api) {
 const {content,brain,generate,isBusy}=api;
 const director=root.NovaStoryDirector;
 content.replaceChildren();
 const shell=node(content,'section','panel');
 node(shell,'div','council-heading','🎬 NOVA · Мои истории');
 node(shell,'p','small','Расскажи идею. Gemma сама создаст раскадровку и английские промпты. Истории сохраняются на телефоне. Каждый кадр запускается только с твоего разрешения.');
 const editor=node(shell,'textarea','council-input');
 editor.placeholder='Например: герой возвращается домой, а отражение в старом зеркале начинает жить своей жизнью...';
 editor.maxLength=380;
 editor.value=state.idea;
 editor.oninput=()=>{state.idea=editor.value};
 const button=node(shell,'button','council-button','🧠 NOVA, придумай три сцены');
 const feedback=node(shell,'p','small',state.error?'⚠ '+state.error:state.notice);
 button.disabled=state.creating||state.drawing||isBusy()||!brain;
 if(!brain) feedback.textContent='Нет доступа к локальной Gemma.';
 button.onclick=async()=>{
  if(state.creating||state.drawing||isBusy())return;
  state.creating=true;
  button.disabled=true;
  state.error='';
  feedback.textContent='🧠 Gemma готовит последовательность сцен и собственные промпты...';
  try{
   const question=director.ideaRequest(state.idea,3);
   // The text model can be unloaded by SD1.5 to save memory.
   const status=await brain.status();
   if(status.engine!=='ModelReady')await brain.prepare();
   const response=await brain.chat({message:question});
   const story=director.parse(response?.answer,state.idea);
   const saved=director.read();
   saved.push(story);
   if(!director.write(saved))throw Error('Нет места для сохранения плана истории на телефоне.');
   state.notice='✅ NOVA подготовила историю. Промпты готовы, картинки ещё не запущены.';
  }catch(e){state.error=e?.message||String(e);}
  finally{state.creating=false;render(api);}
 };
 const saved=director.read().slice().reverse();
 if(!saved.length) {
  node(content,'section','panel','📚 Пока нет историй. Начни с идеи выше.');
  return;
 }
 for(const story of saved) {
  const storyBox=node(content,'section','panel');
  node(storyBox,'div','council-heading',story.title);
  node(storyBox,'p','small','Идея: '+story.idea);
  node(storyBox,'p','brain-caption','Герой: '+story.hero+' · Стиль: '+story.style);
  for(const scene of story.scenes) {
   const row=node(storyBox,'div','brain-row');
   node(row,'div','council-heading','Сцена '+scene.number);
   node(row,'p','small',scene.beat);
   const details=node(row,'details','nova-more');
   node(details,'summary','','📝 Промпт NOVA для SD 1.5');
   node(details,'p','small',scene.prompt);
   const key=story.id+'/'+scene.number;
   const shot=previews[key];
   if(shot) {
    const photo=node(row,'img','nova-image-preview');
    photo.src=shot;
    photo.alt='Кадр NOVA — сцена '+scene.number;
   }
   if(scene.imageUri)node(row,'p','small','✅ Кадр сохранён в Галерею → Pictures/NOVA · '+scene.renderSeconds+' сек.');
   const progress=node(row,'p','small','');
   const draw=node(row,'button','council-button','🎨 Создать кадр '+scene.number+' · 512×512');
   draw.disabled=state.creating||state.drawing||isBusy();
   draw.onclick=async()=>{
    if(state.drawing||state.creating||isBusy())return;
    if(!confirm('Нарисовать сцену '+scene.number+'? Сейчас на CPU это может занять около 20 минут. Качество и модель останутся прежними.'))return;
    state.drawing=true;draw.disabled=true;
    try{
     progress.textContent='🎬 Передаю готовый промпт генератору...';
     const result=await generate(scene.prompt,text=>{progress.textContent=text});
     scene.imageUri=result.imageUri||'saved';
     scene.renderSeconds=Number(result.elapsedSeconds)||0;
     if(typeof result.preview==='string' && result.preview.startsWith('data:image/png;base64,')) previews[key]=result.preview;
     if(!director.write(director.read().map(item=>item.id===story.id?story:item))) {
      throw Error('Кадр создан, но не удалось обновить историю. PNG сохранён в Галерее.');
     }
     state.notice='✅ Кадр '+scene.number+' создан и сохранён в Галерее.';
    }catch(e){state.error=e?.message||String(e)}
    finally{state.drawing=false;render(api);}
   };
  }
 }
}
root.NovaStoryUI=Object.freeze({render});
})(typeof window!=='undefined'?window:globalThis);
