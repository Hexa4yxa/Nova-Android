/* NOVA Story UI. Uses the existing local Gemma, SD1.5 and Gallery. No external service. */
(function(root){
'use strict';
const state={creating:false,drawing:false,backupBusy:false,backupMessage:'',idea:'',councilBrief:'',error:'',notice:'',projectQuery:'',motionBusy:false,motionStoryId:'',motionStatus:'',motionStop:null,autoFinishStoryId:'',voiceChoices:null,artStyleId:'comic',seriesId:''};
const previews={};
function node(parent,tag,cls,copy) {
 const n=document.createElement(tag);
 if(cls)n.className=cls;
 if(copy!==undefined)n.textContent=copy;
 parent.appendChild(n);
 return n;
}
function prefillCouncil(task,finalPlan) {
  if(state.creating||state.drawing||state.motionBusy||state.backupBusy)
    throw Error('Дождись завершения текущей задачи в студии.');
  const idea=String(task||'').trim().replace(/\s+/g,' ').slice(0,380);
  if(idea.length<12)throw Error('Не удалось перенести короткую идею.');
  state.idea=idea;
  state.councilBrief=String(finalPlan||'').trim().replace(/\s+/g,' ').slice(0,900);
  state.error='';
  state.notice='✅ Замысел и решение совета перенесены. Проверь описание и нажми «Придумай 6 сцен». Картинки пока не создаются.';
  return true;
}
function render(api) {
 const {content,brain,generate,generateWithAnchor,resumeStory,isBusy}=api;
 const director=root.NovaStoryDirector;
 content.replaceChildren();
 const shell=node(content,'section','panel');
 node(shell,'div','council-heading','🎬 NOVA · Мои истории');
 node(shell,'p','small','Расскажи идею. Gemma создаст шесть последовательных сцен в два этапа по три и подготовит английские промпты. Старые проекты сохраняются. Каждый кадр запускается только с твоего разрешения.');

 const archiveTools=root.Capacitor?.Plugins?.NovaTools;
 const motion=root.Capacitor?.Plugins?.NovaMotion;
 const comic=root.Capacitor?.Plugins?.NovaComicSheet;
 const voice=root.Capacitor?.Plugins?.NovaVoice;
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
 // A simple per-project choice, not a model picker or a global Android setting.
 const styleRow=node(shell,'div','brain-row');
 node(styleRow,'p','small','🎨 Единый стиль всех шести кадров');
 const artPick=node(styleRow,'select','council-input');
 artPick.setAttribute('aria-label','Стиль рисования новой истории');
 for(const preset of director.artStyles()){
  const item=node(artPick,'option','',preset.label+' · '+preset.hint);
  item.value=preset.id;
 }
 artPick.value=state.artStyleId;
 artPick.disabled=state.creating||state.drawing||state.motionBusy||isBusy();
 artPick.onchange=()=>{state.artStyleId=artPick.value;};
 node(styleRow,'p','small','Стиль и внешность героя закрепляются в новой истории. Уже созданные кадры не меняются.');
 // One persistent cast library serves many episodes, not one-off prompts.
 if(root.NovaSeriesUI?.render)root.NovaSeriesUI.render(shell,{
  director,selectedSeriesId:state.seriesId,styleId:state.artStyleId,
  onSelect:id=>{
   state.seriesId=id;
   const series=id?root.NovaCharacterBible.getSeries(id):null;
   if(series)state.artStyleId=series.styleId;
   render(api);
  }
 });
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
   const seriesCast=state.seriesId?root.NovaCharacterBible.snapshot(state.seriesId):null;
   if(seriesCast&&!seriesCast.cast.length)
    throw Error('В выбранном сериале ещё нет персонажей. Добавь хотя бы одного.');
   const chosenStyle=seriesCast?.styleId||state.artStyleId;
   const question=director.ideaRequest(state.idea,3,state.councilBrief,chosenStyle,seriesCast);
   // Two small Gemma requests: 6 scenes in one 512-token answer would truncate.
   // No new models and no image generation until the plan is safely saved.
   // Share the asynchronous native loader; do not race "Initializing".
   if(root.NovaStoryChat?.readyBrain){
    const ready=await root.NovaStoryChat.readyBrain(brain,message=>{feedback.textContent=message;});
    if(!ready)throw Error('Запуск модели остановлен.');
   }else{
    const status=await brain.status();
    if(status.engine!=='ModelReady')await brain.prepare();
   }
   const response=await brain.chat({message:question,maxTokens:512});
   const first=director.parse(response?.answer,state.idea,chosenStyle);
   feedback.textContent='🧠 Первые три сцены готовы. NOVA продолжает историю (4–6)...';
   const second=await brain.chat({
    message:director.continuationRequest(state.idea,first,state.councilBrief,seriesCast),maxTokens:512
   });
   const story=director.appendContinuation(first,second?.answer);
   if(seriesCast)director.bindSeries(story,seriesCast);
    if(state.councilBrief)story.councilPlan=state.councilBrief;
   const saved=director.read();
   saved.push(story);
   if(!director.write(saved))throw Error('Нет места для сохранения плана истории на телефоне.');
   state.notice='✅ NOVA подготовила 6 сцен. Промпты готовы, картинки ещё не запущены.';
    state.councilBrief='';
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
  // Based on real native timings; older projects have no measurements.
  const measured=story.scenes.filter(scene=>Number(scene.modelLoadMs)>0||Number(scene.samplingMs)>0);
  if(measured.length){
   const loading=measured.reduce((sum,scene)=>sum+Math.max(0,Number(scene.modelLoadMs)||0),0);
   const sampling=measured.reduce((sum,scene)=>sum+Math.max(0,Number(scene.samplingMs)||0),0);
   node(storyBox,'p','small','⏱ Диагностика '+measured.length+' кадр.: загрузка SD1.5 '+(loading/60000).toFixed(1)+
    ' мин · рисование CPU '+(sampling/60000).toFixed(1)+' мин. Измерено на телефоне.');
  }
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
  // NOVA does the complete pipeline: missing SD1.5 images (owner-approved),
  // ONE saved physical comic PNG, then an optional panel-by-panel movie.
  // This remains compatible with older 1-3-scene projects and all saved PNGs.
  // Single deliberate approval, existing resumable comic -> MP4 + voice.
  const oneFilm=node(commands,'button','council-button','🎬 NOVA, сделай ролик');
  oneFilm.disabled=!canEdit()||!comic?.build||!motion?.render||
   (remaining.length>0&&typeof resumeStory!=='function');
  oneFilm.onclick=async()=>{
   if(!canEdit()||!comic?.build||!motion?.render)return;
   const missing=story.scenes.filter(x=>!x.imageUri).length;
   if(!confirm('NOVA нарисует '+missing+' недостающих кадров, соберёт комикс и MP4 с движением. '+
    'Если доступен офлайн-голос — добавит озвучку. Один кадр может создаваться десятки минут. '+
    'Готовые файлы сохраняются. Продолжить?'))return;
   state.autoFinishStoryId=story.id;
   if(comicReady){
    state.autoFinishStoryId='';
    await readComic.onclick({skipConfirm:true});
   }else await makeComic.onclick({skipConfirm:true});
  };
  const comicRow=node(storyBox,'div','brain-row');
  const makeComic=node(comicRow,'button','council-button',
   '📖 NOVA, создай комикс · '+story.scenes.length+' сцен');
  const sameSources=Array.isArray(story.comicFrameUris)&&
   story.comicFrameUris.length===story.scenes.length&&
   story.comicFrameUris.every((uri,index)=>uri===story.scenes[index].imageUri);
  const comicReady=!!(sameSources&&story.comicPageUri);
  makeComic.disabled=!canEdit()||!comic?.build||
   (remaining.length>0&&typeof resumeStory!=='function');
  const openComic=node(comicRow,'button','council-button','🖼 Комикс-лист');
  openComic.style.display=comicReady?'':'none';
  openComic.disabled=!comicReady||!comic?.openPage;
  openComic.onclick=async()=>{
   try{await comic.openPage({pageUri:story.comicPageUri});}
   catch(e){state.error='Не удалось открыть комикс: '+(e?.message||String(e));render(api);}
  };
  const readComic=node(comicRow,'button','council-button','🎬 Читать комикс в MP4');
  readComic.style.display=comicReady?'':'none';
  readComic.disabled=!comicReady||!canEdit()||!motion?.render;
  const comicProgress=node(storyBox,'p','small','');
  const comicStop=node(comicRow,'button','council-button','■ Остановить');
  comicStop.style.display='none';
  makeComic.onclick=async(options={})=>{
   if(!canEdit()||!comic?.build)return;
   const current=director.read().find(x=>x.id===story.id);
   if(!current)return;
   const missing=current.scenes.filter(x=>!x.imageUri).length;
   const warning=missing>0?
    'NOVA сама нарисует '+missing+' недостающих кадров и соберёт один PNG-комикс. '+
    'CPU-генерация каждого кадра может занять много времени; оставь приложение открытым. '+
    'Готовые кадры повторно не рисуются. Продолжить?':
    'NOVA соберёт один комикс-лист из '+current.scenes.length+
    ' готовых PNG и сохранит в Галерею. Исходные картинки не изменятся. Продолжить?';
   if(!options?.skipConfirm && !confirm(warning))return;
   state.motionBusy=true;state.motionStoryId=story.id;
   state.error='';makeComic.disabled=true;readComic.disabled=true;
   comicStop.style.display='';
   let cancelled=false;
   state.motionStop=()=>{
    cancelled=true;
    comicProgress.textContent='■ Останавливаю генерацию после текущего кадра...';
    Promise.resolve(root.Capacitor?.Plugins?.NovaImage?.cancel?.()).catch(()=>{});
   };
   comicStop.onclick=()=>state.motionStop?.();
   try{
    if(missing){
     const generation=await resumeStory(story.id,message=>{
      comicProgress.textContent=message;
     },()=>{},()=>cancelled);
     if(generation?.cancelled||cancelled)throw Error('Остановлено пользователем');
    }
    const fresh=director.read().find(x=>x.id===story.id);
    if(!fresh||fresh.scenes.some(x=>!/^content:\/\/media\/[^/]+\/images\/media\/\d+/i.test(x.imageUri||'')))
     throw Error('Не все изображения проекта сохранены в Галерею.');
    comicProgress.textContent='📖 Собираю единую страницу комикса...';
    const result=await comic.build({
     title:fresh.title,
     frames:fresh.scenes.map(x=>({imageUri:x.imageUri,beat:x.beat}))
    });
    if(!result?.saved||!result.pageUri)throw Error('Android не подтвердил сохранение комикса.');
    const items=director.read();
    const target=items.find(x=>x.id===story.id);
    if(!target)throw Error('PNG сохранён, но проект не найден.');
    target.comicPageUri=result.pageUri;
    target.comicFrameUris=fresh.scenes.map(x=>x.imageUri);
    target.comicPanels=fresh.scenes.length;
    if(!director.write(items))
     throw Error('Комикс сохранён в Галерею, но проект не смог запомнить путь к PNG.');
    state.notice='✅ NOVA создала один комикс-лист из '+fresh.scenes.length+
     ' панелей. Открой его или запусти чтение в MP4.';
   }catch(e){
    state.autoFinishStoryId='';
    state.error='Комикс: '+(e?.message||String(e))+
     '. Уже созданные кадры остаются в Галерее и проекте.';
   }finally{
    state.motionBusy=false;state.motionStoryId='';state.motionStop=null;
    comicStop.style.display='none';render(api);
   }
  };
  readComic.onclick=async(options={})=>{
   if(!canEdit()||!comicReady||!motion?.render)return;
   if(!options?.skipConfirm && !confirm('NOVA соберёт ролик из сохранённых кадров, напишет цельный сценарий и добавит чистый офлайн-голос. Без шипения, шумового дождя и автоматических звуков. Создать?'))return;
   state.motionBusy=true;state.motionStoryId=story.id;
   readComic.disabled=true;makeComic.disabled=true;
   comicStop.style.display='';
   comicProgress.textContent='🎬 Читаю комикс по панелям...';
   state.motionStop=()=>motion.cancel?.().catch(()=>{});
   comicStop.onclick=()=>state.motionStop?.();
   let listener=null;
   try{
    if(motion.addListener)listener=await motion.addListener('novaMotionProgress',progress=>{
     comicProgress.textContent='🎬 Панель '+Number(progress.done||0)+' из '+story.scenes.length;
    });
    const shots=story.scenes.map((scene,index)=>{
     // Image PROMPTS are not effects instructions: repeated "rain/storm" used
     // to trigger synthetic rain overlays on ALL scenes. Default is clean.
     const allowedEffects=new Set(['rain','torch','fog','glow']);
     const effect=allowedEffects.has(scene.videoEffect)?scene.videoEffect:'none';
     return {
      // Export original frame without comic titles/captions; gallery comic page stays intact.
      imageUri:scene.imageUri,
      plan:'wide',motion:index%2?'pan-left':'push',durationSeconds:story.scenes.length===6?5:4,effect
     };
    });
    const outcome=await motion.render({shots});
    if(!outcome?.saved||!outcome.videoUri)throw Error('Android не подтвердил MP4.');
    let finalMovie=outcome.videoUri;
    let voiced=false;
    let narrationDraft=[];
    let narrationLanguage='';
    let soundNote='Без озвучки: офлайн-голос Android не найден.';
    // Keep the original silent MP4 as fallback. A failed TTS or AAC step
    // must never erase the already-rendered comic or its source frames.
    if(voice?.dubMovie&&voice?.status){
     try{
      comicProgress.textContent='🎙 Проверяю локальные голоса Android...';
      const speech=await voice.status({engineId:story.voiceEngineId||''});
      if(speech?.available){
       const narrator=root.NovaComicNarrator;
       if(!narrator?.prepare)throw Error('Нет модуля подготовки понятного текста.');
       if(root.NovaStoryChat?.readyBrain&&brain?.status){
        await root.NovaStoryChat.readyBrain(brain,message=>{comicProgress.textContent=message;});
       }else if(brain?.status&&brain?.prepare){
        const ready=await brain.status();
        if(ready.engine!=='ModelReady')await brain.prepare();
       }
       const script=await narrator.prepare(story,speech,brain,message=>{
        comicProgress.textContent=message;
       },story.scenes.length===6?5:4);
       narrationDraft=script.lines.map(line=>line.text);
       narrationLanguage=script.language;
       comicProgress.textContent='🎙 NOVA озвучивает '+(script.language==='uk'?'украинский':'русский')+
        ' текст и собирает MP4...';
       // Video visual effects MUST NOT control audio. Most old scene prompts
       // contained "rain"/"storm" in every image, generating harsh hiss in all shots.
       // Only an explicit per-scene audioEffect is honored, default is dry voice.
       const allowedSounds=new Set(['rain','torch','fog','glow']);
       const lines=script.lines.map((line,index)=>({
        ...line,
        effect:story.enableAudioEffects===true&&
          allowedSounds.has(story.scenes[index]?.audioEffect)?
          story.scenes[index].audioEffect:'none'
       }));
       const dubbed=await voice.dubMovie({
        videoUri:outcome.videoUri,language:script.language,
         engineId:story.voiceEngineId||'',voiceId:story.voiceId||'',lines
       });
       if(dubbed?.saved&&dubbed.videoUri){
        finalMovie=dubbed.videoUri;
        voiced=true;
        soundNote='Чистая офлайн-озвучка без автоматического шума · голос '+(dubbed.voice||'Android');
       } else soundNote='Звук не подтверждён, сохранено немое видео.';
      }else soundNote=speech?.detail||soundNote;
     }catch(e){
      soundNote='Озвучка не удалась: '+(e?.message||String(e))+
       '. Немой MP4 сохранён.';
     }
    }else soundNote='Озвучка пока не подключена; немой MP4 сохранён.';
    const items=director.read(),current=items.find(x=>x.id===story.id);
    if(current){
     current.movieUri=finalMovie;
     current.movieSeconds=outcome.seconds;
     current.movieHasAudio=voiced;
     current.narrationDraft=narrationDraft;
     current.narrationLanguage=narrationLanguage;
     if(!director.write(items))
      throw Error('MP4 сохранён, но ссылка не записалась в проект.');
    }
    state.notice='✅ Видеокомикс создан. '+soundNote;
   }catch(e){state.error='Чтение комикса: '+(e?.message||String(e))+
    '. Исходные PNG и комикс-лист сохранены.';}
   finally{
    try{await listener?.remove();}catch(_){}
    state.motionStop=null;state.motionBusy=false;state.motionStoryId='';
    render(api);
   }
  };
  if(state.autoFinishStoryId===story.id && comicReady && !state.motionBusy){
   state.autoFinishStoryId='';
   Promise.resolve().then(()=>readComic.onclick({skipConfirm:true})).catch(e=>{
    state.error='Монтаж комикса: '+(e?.message||String(e));render(api);
   });
  }
  if(comicReady)node(storyBox,'p','small','📖 Единый комикс-лист готов: '+story.comicPanels+
   ' панелей · Pictures/NOVA.');
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
   '✅ Готовый фильм: '+(story.movieSeconds||'?')+' сек · '+
   (story.movieHasAudio===true?'с озвучкой':
    story.movieHasAudio===false?'без звука':'наличие звука не указано')+
   '. Галерея → Фильмы/NOVA.');
  if(Array.isArray(story.narrationDraft)&&story.narrationDraft.length){
   const narrationBox=node(storyBox,'details','nova-more');
   node(narrationBox,'summary','','🎙 Сценарий озвучки · '+story.narrationDraft.length+' реплик');
   node(narrationBox,'p','small','Текст готового рассказа. Если реплика плохая — её можно заменить в «Сцены → Озвучка и диалог».');
   story.narrationDraft.forEach((line,index)=>{
    node(narrationBox,'p','small',(index+1)+'. '+String(line).slice(0,120));
   });
  }
  const audioSettings=node(storyBox,'details','nova-more');
  node(audioSettings,'summary','','🔊 Звук: '+(story.enableAudioEffects?'эффекты по выбору':'чистый голос, без шипения'));
  node(audioSettings,'p','small','По умолчанию только голос. Эффекты не угадываются по промптам картинок. Экспериментальные звуки можно добавить вручную в каждой сцене.');

  const inspectVoices=node(audioSettings,'button','council-button','🎧 Проверить голоса телефона');
  inspectVoices.disabled=!voice?.status||!canEdit();
  inspectVoices.onclick=async()=>{
   try{
    const available=await voice.status({engineId:story.voiceEngineId||''});
    state.voiceChoices={...available,storyId:story.id,engineId:story.voiceEngineId||''};
    state.error='';
    state.notice='Движок: '+(available.engine||'Android')+
     ' · локальных голосов: '+Number(available.count||0)+'.';
   }catch(e){state.error='Голоса: '+(e?.message||String(e));}
   render(api);
  };
  if(state.voiceChoices?.storyId===story.id){
   const status=state.voiceChoices;
   node(audioSettings,'p','small','Движок: '+(status.engine||'Android')+' · '+status.count+' локальных ru/uk голосов.');
   const enginePick=node(audioSettings,'select','council-input');
   const systemOption=node(enginePick,'option','','Системный голос (как на телефоне)');
   systemOption.value='';
   for(const info of status.engines||[]){
    const entry=node(enginePick,'option','',info.label||info.id);
    entry.value=info.id;
   }
   enginePick.value=status.engineId||'';
   enginePick.onchange=async()=>{
    try{
     const engineId=enginePick.value;
     const found=await voice.status({engineId});
     state.voiceChoices={...found,storyId:story.id,engineId};
     state.error='';
     state.notice='Проверен движок '+(found.engine||'Android')+' · голосов: '+found.count;
    }catch(e){state.error='Движок TTS: '+(e?.message||String(e));}
    render(api);
   };
   if(!status.available){
    node(audioSettings,'p','small','Android пока не передал NOVA подходящие офлайн-голоса. Это не доказывает, что модель отсутствует в HayaiTTS.');
    if(typeof status.rawVoiceCount==='number'){
     const ru=status.ruSupported?'да':'нет',uk=status.ukSupported?'да':'нет';
     node(audioSettings,'p','small','Диагностика движка: всего голосов в API Android — '+status.rawVoiceCount+'; ru: '+ru+'; uk: '+uk+'.');
    }
   }
   const voicePick=node(audioSettings,'select','council-input');
   const autoVoice=node(voicePick,'option','','Автоматически — лучший по версии Android');
   autoVoice.value='';
   for(const item of status.voices||[]){
    const entry=node(voicePick,'option','',(item.label||item.id)+' ('+item.language+')');
    entry.value=item.id;
   }
   voicePick.value=enginePick.value===(story.voiceEngineId||'')?(story.voiceId||''):'';
   const saveVoice=node(audioSettings,'button','council-button','💾 Выбрать голос');
   saveVoice.disabled=!canEdit();
   saveVoice.onclick=()=>{
    const items=director.read(),current=items.find(x=>x.id===story.id);
    if(!current)return;
    current.voiceEngineId=enginePick.value;
    current.voiceId=voicePick.value;
    if(!director.write(items))state.error='Не получилось сохранить голос.';
    else{state.error='';state.notice='Голос выбран. Применится при следующем MP4.';}
    render(api);
   };
   const listenVoice=node(audioSettings,'button','council-button','▶ Послушать голос');
   listenVoice.disabled=!voice?.preview||!status.available;
   listenVoice.onclick=async()=>{
    try{
     const result=await voice.preview({
      voiceId:voicePick.value,
      engineId:enginePick.value,
      text:'В эту ночь она поняла: за дверью кто-то ждёт её ответа.'
     });
     state.notice='Звучит локальный голос: '+(result?.voice||voicePick.value||'Android');
     state.error='';
    }catch(e){state.error='Прослушивание: '+(e?.message||String(e));}
    render(api);
   };
  }

  node(audioSettings,'p','small','HayaiTTS: выбери Supertonic · speaker_0…speaker_9 (10 голосов). Они многоязычные: NOVA передаст русский или украинский язык при озвучке. Сначала проверь короткую фразу кнопкой «Послушать голос».');
  const ttsLink=node(audioSettings,'a','brain-caption','HayaiTTS — официальный APK для ARM64');
  ttsLink.href='https://github.com/HayaiApp/HayaiTTS/releases/download/v2.5.1/hayaitts-arm64-v8a-v2.5.1.apk';
  ttsLink.target='_blank';
  ttsLink.rel='noopener noreferrer';
  const enableSounds=node(audioSettings,'button','council-button',
   story.enableAudioEffects?'🔇 Отключить звуковые эффекты':'🔊 Разрешить выбранные звуки');
  enableSounds.disabled=!canEdit();
  enableSounds.onclick=()=>{
   const items=director.read(),current=items.find(x=>x.id===story.id);
   if(!current)return;
   current.enableAudioEffects=!current.enableAudioEffects;
   if(!director.write(items)){state.error='Не удалось сохранить настройки звука.';}
   else{state.error='';state.notice='Настройки звука сохранены. Они применятся при следующем экспорте.';}
   render(api);
  };
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
     current.movieHasAudio=false;
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
     current.movieHasAudio=false;
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
  // Put only the next action in front of the user, not 3 rival renderers.
  const advanced=node(storyBox,'details','nova-more');
  node(advanced,'summary','','⚙️ Дополнительные инструменты монтажа');
  if(comicReady){
   makeComic.textContent='♻️ Пересобрать комикс-лист';
   advanced.appendChild(makeComic);
   if(story.movieUri){
    comicRow.appendChild(openMovie);
    readComic.textContent='♻️ Пересоздать фильм с озвучкой';
    advanced.appendChild(readComic);
   }
  }
  advanced.appendChild(motionRow);
  advanced.appendChild(storyboardBar);
  advanced.appendChild(storyboardStatus);
  advanced.appendChild(storyboardPanel);
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
  node(scenesPanel,'p','brain-caption','Паспорт героя: '+story.hero+' · Стиль: '+
   (director.artStyles().find(x=>x.id===story.styleId)?.label||story.style));
  node(scenesPanel,'p','small','NOVA повторяет образ героя и художественный стиль в каждом промпте. Полное совпадение лиц SD1.5 пока не гарантируется.');
  if(story.seriesId)node(scenesPanel,'p','small','🎭 Сериал: '+story.seriesTitle+' · Персонажей: '+(story.cast?.length||0)+'.');
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
   if(Array.isArray(story.cast)&&story.cast.length){
    const castDetails=node(row,'details','nova-more');
    node(castDetails,'summary','','🎭 Кто присутствует в кадре');
    node(castDetails,'p','small','Выбери только нужных людей. Пустой список — кадр окружения. После генерации готовый PNG не меняем.');
    const checks=[];
    for(const actor of story.cast){
     const choice=node(castDetails,'label','small');
     const check=node(choice,'input');
     check.type='checkbox';
     check.checked=(scene.castIds||[]).includes(actor.id);
     check.disabled=!!scene.imageUri||!canEdit();
     choice.appendChild(document.createTextNode(' '+actor.name+' · '+actor.role));
     checks.push({id:actor.id,check});
    }
    const castSave=node(castDetails,'button','council-button','💾 Закрепить состав кадра');
    castSave.disabled=!!scene.imageUri||!canEdit();
    castSave.onclick=()=>{
     if(!canEdit()||scene.imageUri)return;
     try{
      director.setSceneCast(story.id,scene.number,checks.filter(x=>x.check.checked).map(x=>x.id));
      state.error='';state.notice='✅ Состав сцены '+scene.number+' сохранён без новой генерации.';
     }catch(e){state.error=e?.message||String(e);}
     render(api);
    };
    const actorId=(scene.castIds||[]).length===1?scene.castIds[0]:null;
    const liveActor=actorId?root.NovaCharacterBible?.getCast(story.seriesId)
     .find(x=>x.id===actorId):null;
    if(liveActor?.referenceImageUri){
     const anchorLabel=node(castDetails,'label','small');
     const anchor=node(anchorLabel,'input');
     anchor.type='checkbox';anchor.checked=!!scene.referenceEnabled;
     anchor.disabled=!!scene.imageUri||!canEdit();
     anchorLabel.appendChild(document.createTextNode(' 🔖 Использовать эталон этого героя (img2img, эксперимент)'));
     anchor.onchange=()=>{
      if(!canEdit()||scene.imageUri)return;
      const all=director.read(),current=all.find(x=>x.id===story.id);
      const target=current?.scenes.find(x=>x.number===scene.number);
      if(!target)return;
      target.referenceEnabled=anchor.checked;
      if(!director.write(all))state.error='Не удалось сохранить режим img2img.';
      else{state.error='';state.notice='Режим эталона сохранён. Рисование не запущено.';}
      render(api);
     };
     node(castDetails,'p','small','img2img повторяет часть композиции эталона, поэтому лучше использовать для похожих ракурсов. Полное совпадение лица не гарантировано.');
    }else{
     node(castDetails,'p','small','Чтобы включить реальный img2img, закрепи готовый PNG у одного персонажа в «Библии сериала».');
    }
   }
   const voiceDetails=node(row,'details','nova-more');
   node(voiceDetails,'summary','','🎙 Озвучка и диалог');
   node(voiceDetails,'p','small','По умолчанию NOVA сама напишет связный рассказ по всей истории. Здесь можно задать точную реплику или слова героя.');
   const voiceText=node(voiceDetails,'textarea','council-input');
   voiceText.maxLength=78;
   voiceText.value=scene.voiceText||'';
   voiceText.placeholder='Необязательно — NOVA напишет сама';
   voiceText.setAttribute('aria-label','Текст озвучки сцены '+scene.number);
   const voiceRole=node(voiceDetails,'select','council-input');
   const narratorOption=node(voiceRole,'option','','Рассказчик');
   narratorOption.value='narrator';
   const characterOption=node(voiceRole,'option','','Персонаж');
   characterOption.value='character';
   voiceRole.value=scene.voiceRole==='character'?'character':'narrator';
   const saveVoice=node(voiceDetails,'button','council-button','💾 Сохранить реплику');
   saveVoice.disabled=!canEdit();
   saveVoice.onclick=()=>{
    if(!canEdit())return;
    const text=voiceText.value.trim().slice(0,78);
    if(text && (!/[А-Яа-яЁёІіЇїЄєҐґ]/u.test(text)||/[A-Za-z]/.test(text)||text.length<5)){
     state.error='Нужна понятная реплика на русском или украинском (до 78 знаков).';
     render(api);return;
    }
    const saved=director.read();
    const current=saved.find(p=>p.id===story.id);
    const shot=current?.scenes.find(s=>s.number===scene.number);
    if(!shot){state.error='Сцена не найдена.';render(api);return;}
    shot.voiceText=text;
    shot.voiceRole=voiceRole.value==='character'?'character':'narrator';
    if(!director.write(saved)){state.error='Не удалось сохранить реплику.';}
    else{state.error='';state.notice=text?'✅ Реплика сцены '+scene.number+' сохранена.':
     '✅ Для сцены '+scene.number+' снова включён автоматический сценарий NOVA.';}
    render(api);
   };
   const vfxDetails=node(row,'details','nova-more');
   node(vfxDetails,'summary','','🎞 Необязательный визуальный эффект');
   const vfxSelect=node(vfxDetails,'select','council-input');
   for(const [value,label] of [['none','Без эффектов'],['rain','Дождь (эксперимент)'],
    ['torch','Мерцание огня (эксперимент)'],['fog','Туман (эксперимент)'],['glow','Сияние (эксперимент)']]){
    const item=node(vfxSelect,'option','',label);item.value=value;
   }
   vfxSelect.value=scene.videoEffect||'none';
   const saveVfx=node(vfxDetails,'button','council-button','💾 Сохранить визуальный эффект');
   saveVfx.disabled=!canEdit();
   saveVfx.onclick=()=>{
    const items=director.read(),current=items.find(x=>x.id===story.id);
    const target=current?.scenes.find(x=>x.number===scene.number);
    if(!target)return;
    target.videoEffect=vfxSelect.value;
    if(!director.write(items))state.error='Не удалось сохранить визуальный эффект.';
    else{state.error='';state.notice='Эффект изображения сохранён для сцены.';}
    render(api);
   };
   const soundDetails=node(row,'details','nova-more');
   node(soundDetails,'summary','','🔈 Необязательный звук сцены');
   const soundSelect=node(soundDetails,'select','council-input');
   for(const [value,label] of [['none','Без звука'],['rain','Дождь (эксперимент)'],
    ['torch','Огонь (эксперимент)'],['fog','Туман (эксперимент)'],['glow','Сияние (эксперимент)']]){
    const item=node(soundSelect,'option','',label);item.value=value;
   }
   soundSelect.value=scene.audioEffect||'none';
   const saveSound=node(soundDetails,'button','council-button','💾 Сохранить звук сцены');
   saveSound.disabled=!canEdit();
   saveSound.onclick=()=>{
    const items=director.read(),current=items.find(x=>x.id===story.id);
    const target=current?.scenes.find(x=>x.number===scene.number);
    if(!target)return;
    target.audioEffect=soundSelect.value;
    if(!director.write(items))state.error='Не удалось сохранить звук.';
    else{state.error='';state.notice='Звук сцены сохранён. Включи звуки в настройках проекта, если нужны.';}
    render(api);
   };
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
   if(scene.imageUri){
    node(row,'p','small','✅ Кадр сохранён в Галерею → Pictures/NOVA · '+scene.renderSeconds+' сек.');
    if(scene.modelLoadMs||scene.samplingMs)node(row,'p','small',
     '⏱ Загрузка модели: '+Math.round((scene.modelLoadMs||0)/1000)+' с · Семплинг: '+
      Math.round((scene.samplingMs||0)/1000)+' с'+(scene.referenceApplied?' · img2img':''));
   }
   const progress=node(row,'p','small','');
   const draw=node(row,'button','council-button','🎨 Создать кадр '+scene.number+' · 512×512');
   draw.disabled=!!scene.imageUri||state.creating||state.drawing||state.motionBusy||isBusy();
   draw.onclick=async()=>{
    if(scene.imageUri||state.drawing||state.creating||state.motionBusy||isBusy())return;
    if(!confirm('Нарисовать сцену '+scene.number+'? Сейчас на CPU это может занять около 20 минут. Качество и модель останутся прежними.'))return;
    state.drawing=true;draw.disabled=true;
    try{
     progress.textContent='🎬 Передаю готовый промпт генератору...';
     const renderFrame=scene.referenceEnabled?
      (typeof generateWithAnchor==='function'?generateWithAnchor:()=>Promise.reject(Error('Нужен модуль img2img'))):
      generate;
     const renderPrompt=scene.prompt;
     const renderReference=!!scene.referenceEnabled;
     const result=await renderFrame(renderPrompt,text=>{progress.textContent=text},scene,story);
     if(result?.saved!==true||typeof result.imageUri!=='string'||
        !/^content:\/\/media\/[^/?]+\/images\/media\/\d+$/i.test(result.imageUri))
      throw Error('Android не подтвердил сохранение PNG в Галерее. Кадр не отмечен готовым.');
     // A long CPU render must not commit an obsolete prompt or changed anchor.
     // Reload the current record: other project and voice edits must survive.
     const latest=director.read();
     const stored=latest.find(item=>item.id===story.id);
     const target=stored?.scenes.find(item=>item.number===scene.number);
     if(!target||target.imageUri)
      throw Error('Проект изменился во время рисования. PNG остаётся в Галерее; проверь сохранённые кадры.');
     if(target.prompt!==renderPrompt||!!target.referenceEnabled!==renderReference)
      throw Error('Описание или эталон кадра изменились во время рисования. Новый PNG остаётся в Галерее; прежний проект не перезаписан.');
     const update={imageUri:result.imageUri,
      renderSeconds:Number(result.elapsedSeconds)||0,
      modelLoadMs:Number(result.modelLoadMs)||0,
      samplingMs:Number(result.samplingMs)||0,
      referenceApplied:!!result.referenceApplied};
     Object.assign(target,update);
     if(!director.write(latest)) {
      throw Error('Кадр создан, но не удалось обновить историю. PNG сохранён в Галерее.');
     }
     Object.assign(scene,update);
     if(typeof result.preview==='string' && result.preview.startsWith('data:image/png;base64,')) previews[key]=result.preview;
     state.notice='✅ Кадр '+scene.number+' создан и сохранён в Галерее.';
    }catch(e){state.error=e?.message||String(e)}
    finally{state.drawing=false;render(api);}
   };
  }
 }
 filterCards();
}
root.NovaStoryUI=Object.freeze({render,prefillCouncil});
})(typeof window!=='undefined'?window:globalThis);
