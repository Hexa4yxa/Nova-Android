/* NOVA Storyboard Director: deterministic local camera edit, no new models.
 * One rendered image can produce multiple distinct cinematic shots. The viewer
 * never sees a complete comic page. Original PNGs/projects are read-only. */
(function(root){
'use strict';
const VALID_URI=/^content:\/\/media\/[^/?]+\/images\/media\/\d+$/i;
const MOTIONS=Object.freeze(['push','pan-right','tilt-up','pull','pan-left','tilt-down']);
const LABELS=Object.freeze({
 'push':'Наезд камеры','pull':'Отъезд камеры',
 'pan-left':'Панорама влево','pan-right':'Панорама вправо',
 'tilt-up':'Камера вверх','tilt-down':'Камера вниз',
 'hold':'Неподвижный план'
});
const PLAN_LABELS=Object.freeze({wide:'ОБЩИЙ',medium:'СРЕДНИЙ',close:'КРУПНЫЙ'});
function plan(story){
 if(!story||!Array.isArray(story.scenes))return [];
 const ready=story.scenes.filter(x=>x&&typeof x.imageUri==='string'&&VALID_URI.test(x.imageUri)).slice(0,6);
 const shots=[];
 ready.forEach((scene,index)=>{
  const total=ready.length;
  // Two shots per source image, plus one reveal/accent at the beginning,
  // midpoint and ending: 6 key images -> 15 cinematic plans.
  const motion=MOTIONS[index%MOTIONS.length];
  const sceneNumber=Number(scene.number)||index+1;
  const add=(type,move,seconds,accent=false)=>{
   shots.push({
    sceneNumber,imageUri:scene.imageUri,beat:String(scene.beat||'').slice(0,180),
    plan:type,planLabel:PLAN_LABELS[type],
    motion:move,motionLabel:LABELS[move],durationSeconds:seconds,accent
   });
  };
  add(index===0?'wide':'medium',motion,3,index===0);
  add(index%2===0?'medium':'close',index%2===0?'push':'pull',2,index===total-1);
  if(index===0||index===Math.floor(total/2)||index===total-1){
   add(index===total-1?'close':'wide',index===total-1?'hold':'pull',2,true);
  }
 });
 return shots.slice(0,16);
}
root.NovaStoryboard=Object.freeze({plan});
if(typeof module==='object'&&module.exports)module.exports={plan};
})(typeof window!=='undefined'?window:globalThis);
