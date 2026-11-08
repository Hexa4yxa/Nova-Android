/* NOVA Story UI. Uses the existing local Gemma, SD1.5 and Gallery. No external service. */
(function(root){
'use strict';
const state={creating:false,drawing:false,backupBusy:false,backupMessage:'',idea:'',error:'',notice:'',projectQuery:''};
const previews={};
function node(parent,tag,cls,copy) {
 const n=document.createElement(tag);
 if(cls)n.className=cls;
 if(copy!==undefined)n.textContent=copy;
 parent.appendChild(n);
 return n;
}
function render(api) {
 const {content,brain,generate,resumeStory,isBusy}=api;
 const director=root.NovaStoryDirector;
 content.replaceChildren();
 const shell=node(content,'section','panel');
 node(shell,'div','council-heading','🎬 NOVA · Мои истории');
 node(shell,'p','small','Расскажи идею. Gemma сама создаст раскадровку и английские промпты. Истории сохраняются на телефоне. Каждый кадр запускается только с твоего разрешения.');

 const archiveTools=root.Capacitor?.Plugins?.NovaTools;
 const archiveBar=node(shell,'div','brain-row');
 const exportBtn=node(archiveBar,'button','council-button','💾 Сохранить копию историй');
 const importBtn=node(archiveBar,'button','council-button','♻️ Восстановить истории из копии');
 const backupFeedback=node(archiveBar,'p','small',
   state.backupMessage||'Копия сохраняет истории и промпты. Файлы PNG в Галерее остаются отдельно.');
 const archiveDisabled=state.creating||state.drawing||state.backupBusy||isBusy();
 exportBtn.disabled=archiveDisabled||!archiveTools||director.read().length===0;
 importBtn.disabled=archiveDisabled||!archiveTools;
 if(!archiveTools)backupFeedback.textContent='Архивирование недоступно в этой сборке NOVA.';
 exportBtn.onclick=async()=>{
  if(state.backupBusy||state.creating||state.drawing||isBusy()||!archiveTools)return;
  state.backupBusy=true;exportBtn.disabled=true;importBtn.disabled=true;
  backupFeedback.textContent='💾 Выбери место для файла резервной копии...';
  try{
   const result=await archiveTools.exportStoryProjects({payload:director.exportBackup()});
   if(!result?.saved)throw Error('Android не подтвердил запись');
   state.backupMessage='✅ Копия сохранена. Все истории в NOVA остались без изменений.';
  }catch(e){state.backupMessage='⚠ Копия не сохранена: '+(e?.message||String(e));}
  finally{state.backupBusy=false;render(api);}
 };
 importBtn.onclick=async()=>{
  if(state.backupBusy||state.creating||state.drawing||isBusy()||!archiveTools)return;
  if(!confirm('Выбрать резервную копию историй? NOVA добавит недостающие истории, не удаляя и не заменяя существующие.'))return;
  state.backupBusy=true;exportBtn.disabled=true;importBtn.disabled=true;
  backupFeedback.textContent='♻️ Выбери NOVA-stories-backup.json через Android...';
  try{
   const result=await archiveTools.importStoryProjects();
   const merged=director.mergeBackup(result?.payload);
   state.backupMessage='✅ Восстановлено: '+merged.added+
     '. Уже были: '+merged.skipped+'. Всего историй: '+merged.total+'.';
  }catch(e){state.backupMessage='⚠ Восстановление не выполнено: '+(e?.message||String(e));}
  finally{state.backupBusy=false;render(api);}
 };
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
   const response=await brain.chat({message:question,maxTokens:512});
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
 const manager=node(content,'section','panel');
 node(manager,'div','council-heading','📁 Проекты ('+saved.length+')');
 node(manager,'p','small','Поиск, переименование и удаление записей. Картинки в Галерее не затрагиваются.');
 const search=node(manager,'input','council-input');
 search.type='search';
 search.placeholder='🔎 Найти проект по названию или идее';
 search.setAttribute('aria-label','Поиск проектов NOVA');
 search.value=state.projectQuery;
 const bulk=node(manager,'div','brain-row');
 const selectedCount=node(bulk,'span','small','Выбрано: 0');
 const removeSelected=node(bulk,'button','council-button','🗑 Удалить выбранные');
 const selectedIds=new Set();
 const cards=[];
 const canEdit=()=>!state.creating&&!state.drawing&&!state.backupBusy&&!isBusy();
 function showSelection() {
  selectedCount.textContent='Выбрано: '+selectedIds.size;
  removeSelected.disabled=!canEdit()||selectedIds.size===0;
 }
 function filterCards(){
  const needle=state.projectQuery.trim().toLocaleLowerCase();
  for(const item of cards){
   const text=[item.story.title,item.story.idea,item.story.hero].join(' ').toLocaleLowerCase();
   item.box.style.display=needle&&!text.includes(needle)?'none':'';
  }
 }
 function erase(ids){
  if(!canEdit()||!ids.size)return;
  const all=director.read();
  const removing=all.filter(item=>ids.has(item.id));
  if(!removing.length){state.error='Проекты уже отсутствуют. Обнови список.';render(api);return;}
  if(!confirm('Удалить '+removing.length+' проект(ов) из NOVA? Это удалит планы и промпты из списка. PNG в Галерее останутся. Восстановить планы можно из ранее сохранённой копии.'))return;
  if(!director.write(all.filter(item=>!ids.has(item.id)))){
   state.error='Не удалось сохранить удаление. Старые записи не изменены.';
   render(api);return;
  }
  for(const id of ids){
   for(const key of Object.keys(previews))if(key.startsWith(id+'/'))delete previews[key];
  }
  state.error='';
  state.notice='✅ Удалено проектов: '+removing.length+'. PNG в Галерее сохранены.';
  render(api);
 }
 search.oninput=()=>{state.projectQuery=search.value;filterCards();};
 removeSelected.onclick=()=>erase(selectedIds);
 showSelection();
 for(const story of saved) {
  const storyBox=node(content,'section','panel');
  cards.push({story,box:storyBox});
  const heading=node(storyBox,'div','brain-row');
  const pick=node(heading,'input','');
  pick.type='checkbox';
  pick.style.cssText='flex:none;width:22px;height:22px;accent-color:#9394ff';
  pick.setAttribute('aria-label','Выбрать проект '+story.title);
  pick.disabled=!canEdit();
  pick.onchange=()=>{
   if(pick.checked)selectedIds.add(story.id);
   else selectedIds.delete(story.id);
   showSelection();
  };
  node(heading,'div','council-heading',story.title);
  const remaining=story.scenes.filter(scene=>!scene.imageUri);
  node(storyBox,'p','small','Кадры: '+(story.scenes.length-remaining.length)+' из '+story.scenes.length+
   ' · Идея: '+(story.idea||'').slice(0,115)+((story.idea||'').length>115?'…':''));
  const commands=node(storyBox,'div','brain-row');
  const show=node(commands,'button','council-button','▾ Сцены ('+story.scenes.length+')');
  const rename=node(commands,'button','council-button','✎ Название');
  const eraseOne=node(commands,'button','council-button','🗑 Удалить');
  rename.disabled=!canEdit();
  eraseOne.disabled=!canEdit();
  eraseOne.onclick=()=>erase(new Set([story.id]));
  const editRow=node(storyBox,'div','brain-row');
  editRow.style.display='none';
  const nameInput=node(editRow,'input','council-input');
  nameInput.type='text';nameInput.maxLength=100;nameInput.value=story.title;
  nameInput.setAttribute('aria-label','Новое название проекта');
  const saveName=node(editRow,'button','council-button','Сохранить');
  const cancelName=node(editRow,'button','council-button','Отмена');
  rename.onclick=()=>{
   if(!canEdit())return;
   editRow.style.display=editRow.style.display==='none'?'':'none';
   if(editRow.style.display!=='none')nameInput.focus();
  };
  cancelName.onclick=()=>{editRow.style.display='none';};
  saveName.onclick=()=>{
   if(!canEdit())return;
   const title=nameInput.value.trim();
   if(!title){state.error='Название проекта не может быть пустым.';render(api);return;}
   const all=director.read();
   const target=all.find(item=>item.id===story.id);
   if(!target){state.error='Проект не найден. Обнови список.';render(api);return;}
   target.title=title;
   if(!director.write(all)){
    state.error='Не получилось сохранить новое название. Проекты не изменены.';
    render(api);return;
   }
   state.error='';state.notice='✅ Название проекта изменено.';
   render(api);
  };
  const scenesPanel=node(storyBox,'div','');
  scenesPanel.hidden=true;
  show.onclick=()=>{
   scenesPanel.hidden=!scenesPanel.hidden;
   show.textContent=scenesPanel.hidden?'▾ Сцены ('+story.scenes.length+')':'▴ Скрыть сцены';
  };
  node(scenesPanel,'p','brain-caption','Герой: '+story.hero+' · Стиль: '+story.style);
  if(remaining.length){
   const resumePanel=node(scenesPanel,'div','brain-row');
   node(resumePanel,'p','small','Готово: '+(story.scenes.length-remaining.length)+' из '+story.scenes.length+
    '. Незавершённые кадры не будут повторять уже сохранённые.');
   const resumeButton=node(resumePanel,'button','council-button',
    '▶ Продолжить с кадра '+remaining[0].number);
   const stopButton=node(resumePanel,'button','council-button','■ Остановить продолжение');
   const progress=node(resumePanel,'p','small','');
   resumeButton.disabled=state.creating||state.drawing||isBusy()||typeof resumeStory!=='function';
   stopButton.disabled=true;
   resumeButton.onclick=async()=>{
    if(state.drawing||state.creating||isBusy()||typeof resumeStory!=='function')return;
    if(!confirm('Продолжить историю с кадра '+remaining[0].number+
     '? Генерация на CPU может занять много времени. Готовые кадры сохранятся.'))return;
    state.drawing=true;
    state.error='';
    resumeButton.disabled=true;
    stopButton.disabled=false;
    let cancelled=false;
    stopButton.onclick=()=>{
     cancelled=true;
     stopButton.disabled=true;
     progress.textContent='■ Останавливаю создание изображения...';
     Promise.resolve(root.Capacitor?.Plugins?.NovaImage?.cancel?.()).catch(e=>
      console.warn('NOVA: не удалось остановить генератор',e));
    };
    try{
     const result=await resumeStory(story.id,message=>{progress.textContent=message;},
      ({scene,result})=>{
       const key=story.id+'/'+scene.number;
       if(typeof result.preview==='string' && result.preview.startsWith('data:image/png;base64,'))
        previews[key]=result.preview;
      },()=>cancelled);
     state.notice=result.cancelled?
      '■ Генерация остановлена. Готовые кадры остаются в Галерее.':
      '✅ Все '+result.completed+' кадра истории сохранены.';
    }catch(e){
     state.error='Не удалось продолжить: '+(e?.message||String(e))+
      '. Сохранённые кадры не удалены.';
    }finally{
     state.drawing=false;
     render(api);
    }
   };
  }
  for(const scene of story.scenes) {
   const row=node(scenesPanel,'div','brain-row');
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
 filterCards();
}
root.NovaStoryUI=Object.freeze({render});
})(typeof window!=='undefined'?window:globalThis);
