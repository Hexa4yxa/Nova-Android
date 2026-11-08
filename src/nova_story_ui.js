/* NOVA Story UI. Uses the existing local Gemma, SD1.5 and Gallery. No external service. */
(function(root){
'use strict';
const state={creating:false,drawing:false,backupBusy:false,backupMessage:'',idea:'',error:'',notice:'',projectQuery:'',motionBusy:false,motionStoryId:'',motionStatus:'',motionStop:null};
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
 node(shell,'p','small','Расскажи идею. Gemma создаст шесть последовательных сцен в два этапа по три и подготовит английские промпты. Старые проекты сохраняются. Каждый кадр запускается только с твоего разрешения.');

 const archiveTools=root.Capacitor?.Plugins?.NovaTools;
 const motion=root.Capacitor?.Plugins?.NovaMotion;
 const archiveBar=node(shell,'div','brain-row');
 const exportBtn=node(archiveBar,'button','council-button','💾 Сохранить копию историй');
 const importBtn=node(archiveBar,'button','council-button','♻️ Восстановить истории из копии');
 const backupFeedback=node(archiveBar,'p','small',
   state.backupMessage||'Копия сохраняет истории и промпты. Файлы PNG в Галерее остаются отдельно.');
 const archiveDisabled=state.creating||state.drawing||state.backupBusy||state.motionBusy||isBusy();
 exportBtn.disabled=archiveDisabled||!archiveTools||director.read().length===0;
 importBtn.disabled=archiveDisabled||!archiveTools;
 if(!archiveTools)backupFeedback.textContent='Архивирование недоступно в этой сборке NOVA.';
 if(state.motionBusy){
  const activeBar=node(shell,'div','brain-row');
  node(activeBar,'p','small',state.motionStatus||'🎞 NOVA создаёт видеокомикс...');
  const stop=node(activeBar,'button','council-button','■ Остановить монтаж');
  stop.onclick=()=>{if(state.motionStop)state.motionStop();};
 }
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
 const button=node(shell,'button','council-button','🧠 NOVA, придумай 6 сцен');
 const feedback=node(shell,'p','small',state.error?'⚠ '+state.error:state.notice);
 button.disabled=state.creating||state.drawing||state.motionBusy||isBusy()||!brain;
 if(!brain) feedback.textContent='Нет доступа к локальной Gemma.';
 button.onclick=async()=>{
  if(state.creating||state.drawing||state.motionBusy||isBusy())return;
  state.creating=true;
  button.disabled=true;
  state.error='';
  feedback.textContent='🧠 Gemma готовит последовательность сцен и собственные промпты...';
  try{
   const question=director.ideaRequest(state.idea,3);
   // Two small Gemma requests: 6 scenes in one 512-token answer would truncate.
   // No new models and no image generation until the plan is safely saved.
   const status=await brain.status();
   if(status.engine!=='ModelReady')await brain.prepare();
   const response=await brain.chat({message:question,maxTokens:512});
   const first=director.parse(response?.answer,state.idea);
   feedback.textContent='🧠 Первые три сцены готовы. NOVA продолжает историю (4–6)...';
   const second=await brain.chat({
    message:director.continuationRequest(state.idea,first),maxTokens:512
   });
   const story=director.appendContinuation(first,second?.answer);
   const saved=director.read();
   saved.push(story);
   if(!director.write(saved))throw Error('Нет места для сохранения плана истории на телефоне.');
   state.notice='✅ NOVA подготовила 6 сцен. Промпты готовы, картинки ещё не запущены.';
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
 const canEdit=()=>!state.creating&&!state.drawing&&!state.backupBusy&&!state.motionBusy&&!isBusy();
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

  // Render existing Gallery PNGs only. No fake video, cloud service or new
  // image generation. Keep the project and original frames unchanged.
  const readyFrames=story.scenes
   .map(scene=>typeof scene.imageUri==='string'?scene.imageUri:'')
   .filter(uri=>/^content:\/\/media\/[^/]+\/images\/media\/\d+/i.test(uri));
  const motionRow=node(storyBox,'div','brain-row');
  const movie=node(motionRow,'button','council-button',
   '🎞 Создать видеокомикс · '+readyFrames.length+' кадр(ов)');
  movie.disabled=!canEdit()||!motion?.render||readyFrames.length===0;
  const openMovie=node(motionRow,'button','council-button','▶ Смотреть MP4');
  openMovie.disabled=!motion?.openVideo||!story.movieUri||state.motionBusy;
  openMovie.style.display=story.movieUri?'':'none';
  openMovie.onclick=async()=>{
   try{await motion.openVideo({videoUri:story.movieUri});}
   catch(e){state.error='Не удалось открыть видео: '+(e?.message||String(e));render(api);}
  };
  if(state.motionBusy&&state.motionStoryId===story.id){
   node(storyBox,'p','small',state.motionStatus||'🎞 NOVA создаёт MP4...');
  }
  if(story.movieUri)node(storyBox,'p','small',
   '✅ MP4 сохранён в Галерею → Фильмы/NOVA ('+(story.movieSeconds||'?')+' сек, без звука).');
  movie.onclick=async()=>{
   if(!canEdit()||!motion?.render||!readyFrames.length)return;
   if(!confirm('Сделать видео из '+readyFrames.length+' готовых кадров? NOVA проявит каждый кадр из темноты, добавит плавное движение и сохранит MP4. В этой первой версии видео БЕЗ звука. PNG и история останутся без изменений.'))return;
   state.motionBusy=true;
   state.motionStoryId=story.id;
   state.motionStatus='🎞 Создаю видео локально: 0 из '+readyFrames.length+' кадров';
   movie.disabled=true;
   let listener=null;
   state.motionStop=()=>{state.motionStatus='■ Останавливаю монтаж...';motion.cancel?.().catch(()=>{});};
   try{
    if(motion.addListener){
     listener=await motion.addListener('novaMotionProgress',event=>{
      state.motionStatus='🎞 Смонтировано '+Number(event.done||0)+' из '+readyFrames.length+' кадров';
     });
    }
    const outcome=await motion.render({frames:readyFrames,secondsPerFrame:4});
    if(!outcome?.saved||typeof outcome.videoUri!=='string')throw Error('Android не подтвердил сохранение видео.');
    const projects=director.read();
    const current=projects.find(entry=>entry.id===story.id);
    if(current){
     current.movieUri=outcome.videoUri;
     current.movieSeconds=outcome.seconds;
     if(!director.write(projects)){
      throw Error('MP4 уже сохранён в Фильмы/NOVA, но ссылку не удалось записать в проект.');
     }
    }
    state.error='';
    state.notice='✅ NOVA сохранила MP4 в Галерею → Фильмы/NOVA. Пока без звука.';
   }catch(e){
    state.error='Монтаж не завершён: '+(e?.message||String(e))+
     '. PNG и история не удалены.';
   }finally{
    try{await listener?.remove();}catch(_){}
    state.motionStop=null;
    state.motionBusy=false;
    state.motionStoryId='';
    state.motionStatus='';
    render(api);
   }
  };
  // Director edit uses exactly the same existing PNGs, never regenerates them.
  const storyboardShots=root.NovaStoryboard?.plan(story)||[];
  const storyboardBar=node(storyBox,'div','brain-row');
  const storyboardPreview=node(storyboardBar,'button','council-button',
   '📋 Планы ('+storyboardShots.length+')');
  storyboardPreview.disabled=storyboardShots.length===0;
  const storyboardExport=node(storyboardBar,'button','council-button',
   '🎬 Режиссёрский монтаж · '+storyboardShots.length+' планов');
  storyboardExport.disabled=!canEdit()||!motion?.render||storyboardShots.length===0;
  const storyboardStop=node(storyboardBar,'button','council-button','■ Остановить монтаж');
  storyboardStop.style.display='none';
  const storyboardStatus=node(storyBox,'p','small','');
  const storyboardPanel=node(storyBox,'div','');
  storyboardPanel.hidden=true;
  node(storyboardPanel,'p','small',
   'Раскадровка видна только в редакторе. В MP4 один кадр проявляется из темноты за другим.');
  storyboardShots.forEach((shot,index)=>{
   node(storyboardPanel,'p','small',String(index+1)+'. Сцена '+shot.sceneNumber+
    ' · '+shot.planLabel+' · '+shot.motionLabel+' · '+shot.durationSeconds+
    ' с'+(shot.accent?' ★':''));
  });
  storyboardPreview.onclick=()=>{storyboardPanel.hidden=!storyboardPanel.hidden;};
  storyboardStop.onclick=()=>{
   storyboardStatus.textContent='■ Останавливаю монтаж...';
   state.motionStop?.();
  };
  storyboardExport.onclick=async()=>{
   if(!canEdit()||!motion?.render||!storyboardShots.length)return;
   if(!confirm('Сделать режиссёрский видеокомикс: '+readyFrames.length+
    ' изображений → '+storyboardShots.length+
    ' планов? Всё офлайн, пока БЕЗ звука. Исходные картинки и истории не изменяются.'))return;
   state.motionBusy=true;
   state.motionStoryId=story.id;
   state.motionStatus='🎞 Смонтировано 0 из '+storyboardShots.length+' планов';
   storyboardStatus.textContent=state.motionStatus;
   storyboardExport.disabled=true;
   movie.disabled=true;
   storyboardStop.style.display='';
   state.motionStop=()=>{
    state.motionStatus='■ Останавливаю монтаж...';
    motion.cancel?.().catch(()=>{});
   };
   let listener=null;
   try{
    if(motion.addListener)listener=await motion.addListener('novaMotionProgress',progress=>{
     state.motionStatus='🎞 Смонтировано '+Number(progress.done||0)+' из '+
      storyboardShots.length+' планов';
     storyboardStatus.textContent=state.motionStatus;
    });
    const result=await motion.render({shots:storyboardShots});
    if(!result?.saved||typeof result.videoUri!=='string')
     throw Error('Android не подтвердил сохранение MP4.');
    const projects=director.read();
    const current=projects.find(p=>p.id===story.id);
    if(current){
     current.movieUri=result.videoUri;
     current.movieSeconds=result.seconds;
     if(!director.write(projects))
      throw Error('MP4 уже сохранён в Галерею, но не записался в проект.');
    }
    state.error='';
    state.notice='✅ Режиссёрский MP4 сохранён в Галерею → Фильмы/NOVA. Пока без звука.';
   }catch(error){
    state.error='Монтаж не завершён: '+(error?.message||String(error))+
     '. Исходные PNG и проект не удалены.';
   }finally{
    try{await listener?.remove();}catch(_){}
    state.motionStop=null;
    state.motionBusy=false;
    state.motionStoryId='';
    state.motionStatus='';
    render(api);
   }
  };
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
   resumeButton.disabled=state.creating||state.drawing||state.motionBusy||isBusy()||typeof resumeStory!=='function';
   stopButton.disabled=true;
   resumeButton.onclick=async()=>{
    if(state.drawing||state.creating||state.motionBusy||isBusy()||typeof resumeStory!=='function')return;
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
   draw.disabled=state.creating||state.drawing||state.motionBusy||isBusy();
   draw.onclick=async()=>{
    if(state.drawing||state.creating||state.motionBusy||isBusy())return;
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
