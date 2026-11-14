/* NOVA SERIES UI: offline story world, cast roster and Gallery frame anchors.
 * Dedicated view so one story screen does not accumulate hundreds of controls.
 * PNGs stay in Gallery; only safe MediaStore URIs enter the library.
 */
(function(root){
'use strict';
function el(parent,tag,label='',cls=''){
 const n=document.createElement(tag);
 if(cls)n.className=cls;
 if(label)n.textContent=label;
 parent.appendChild(n);
 return n;
}
function opt(select,value,label){
 const n=el(select,'option',label);n.value=value;return n;
}
function render(parent,{director,selectedSeriesId='',styleId='comic',onSelect=()=>{}}){
 const bible=root.NovaCharacterBible;
 if(!bible){el(parent,'p','⚠ Библия сериалов недоступна.','small');return;}
 const db=bible.read();
 const panel=el(parent,'details','','nova-more');
 const header=el(panel,'summary','🎭 Библия сериала · персонажи и антигерои');
 header.setAttribute('aria-label','Открыть библиотеку сериала');
 el(panel,'p','Общие герои для разных эпизодов. Имена, роли, внешний вид и выбранный эталон сохраняются на этом телефоне.','small');
 const seriesRow=el(panel,'div','','brain-row');
 const seriesPick=el(seriesRow,'select','','council-input');
 seriesPick.setAttribute('aria-label','Выбрать сериал');
 opt(seriesPick,'','Отдельная история (без библии)');
 db.series.forEach(s=>opt(seriesPick,s.id,s.title+' · '+s.styleId));
 seriesPick.value=db.series.some(s=>s.id===selectedSeriesId)?selectedSeriesId:'';
 seriesPick.onchange=()=>onSelect(seriesPick.value);
 const createBox=el(panel,'details','','nova-more');
 el(createBox,'summary','➕ Новый сериал');
 const seriesInput=el(createBox,'input','','council-input');
 seriesInput.placeholder='Название сериала';seriesInput.maxLength=90;
 seriesInput.setAttribute('aria-label','Название нового сериала');
 const makeSeries=el(createBox,'button','Создать сериал с текущим стилем','council-button');
 const status=el(panel,'p','','small');
 makeSeries.onclick=()=>{
  try{
   const item=bible.createSeries(seriesInput.value,styleId);
   onSelect(item.id);
  }catch(e){status.textContent='⚠ '+(e?.message||String(e));}
 };
 if(!seriesPick.value){
  el(panel,'p','Создай или выбери сериал, затем добавь героев. Текущие самостоятельные истории сохраняются.','small');
  return;
 }
 const actors=bible.getCast(seriesPick.value);
 const characters=el(panel,'div','','brain-row');
 el(characters,'p','Действующие лица: '+actors.length,'brain-caption');
 const makeCharacter=el(panel,'details','','nova-more');
 el(makeCharacter,'summary','➕ Добавить героя или антигероя');
 const name=el(makeCharacter,'input','','council-input');
 name.placeholder='Имя (например, Марк)';name.maxLength=60;
 name.setAttribute('aria-label','Имя персонажа');
 const role=el(makeCharacter,'select','','council-input');
 for(const [value,label] of [['hero','Главный герой'],['antagonist','Антигерой / антагонист'],['support','Второстепенный герой']])
  opt(role,value,label);
 const appearance=el(makeCharacter,'textarea','','council-input');
 appearance.placeholder='English visual passport: short black hair, oval face, navy coat, red scarf…';
 appearance.maxLength=300;
 appearance.setAttribute('aria-label','Внешность персонажа на английском для SD1.5');
 const stories=director.read();
 const samples=stories.filter(x=>typeof x.hero==='string'&&/[a-z]/i.test(x.hero))
  .slice().reverse().slice(0,30);
 if(samples.length){
  const source=el(makeCharacter,'select','','council-input');
  opt(source,'','Взять паспорт из готовой истории...');
  samples.forEach(s=>opt(source,s.id,s.title+' — '+s.hero.slice(0,42)));
  source.onchange=()=>{
   const selected=samples.find(x=>x.id===source.value);
   if(selected)appearance.value=selected.hero.slice(0,300);
  };
 }
 const add=el(makeCharacter,'button','💾 Сохранить персонажа','council-button');
 add.onclick=()=>{
  try{
   bible.addCharacter(seriesPick.value,{name:name.value,role:role.value,appearance:appearance.value});
   onSelect(seriesPick.value);
  }catch(e){status.textContent='⚠ '+(e?.message||String(e));}
 };
 const pictures=stories.flatMap(s=>s.scenes.filter(x=>
  /^content:\/\/media\/[^/?]+\/images\/media\/\d+$/i.test(x.imageUri||''))
  .map(x=>({uri:x.imageUri,title:s.title,number:x.number}))).slice(-120);
 const uniqueImages=Array.from(new Map(pictures.map(x=>[x.uri,x])).values());
 for(const actor of actors){
  const card=el(characters,'details','','nova-more');
  el(card,'summary',actor.name+' · '+({hero:'Главный',antagonist:'Антагонист',support:'Второстепенный'}[actor.role]||'Герой'));
  el(card,'p',actor.appearance,'small');
  const ref=el(card,'select','','council-input');
  ref.setAttribute('aria-label','Эталон персонажа '+actor.name);
  opt(ref,'','Без эталонной картинки');
  uniqueImages.forEach(pic=>opt(ref,pic.uri,pic.title+' · кадр '+pic.number));
  ref.value=actor.referenceImageUri||'';
  const save=el(card,'button','🔖 Закрепить кадр как эталон','council-button');
  save.onclick=()=>{
   try{
    bible.setReference(actor.id,ref.value,uniqueImages.map(x=>x.uri));
    onSelect(seriesPick.value);
   }catch(e){status.textContent='⚠ '+(e?.message||String(e));}
  };
  if(actor.referenceImageUri)el(card,'p','✅ Кадр-эталон сохранён в паспорте героя.','small');
 }
 el(panel,'p','Паспорт и привязка эталонного PNG уже сохраняются. Передача картинки в SD1.5 (img2img) требует отдельной проверки и пока не включена.','small');
}
root.NovaSeriesUI=Object.freeze({render});
})(typeof window!=='undefined'?window:globalThis);
