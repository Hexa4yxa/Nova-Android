const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const initiative=require('../src/nova_director_initiative.js');
function store(){
 const map=new Map();
 return {getItem:k=>map.get(k)||null,setItem:(k,v)=>map.set(k,String(v))};
}
test('proactive suggestions are local, daily, opt-out and never fake completed work',()=>{
 const s=store(),day=new Date('2026-11-10T10:00:00');
 assert.equal(initiative.shouldOffer(day,s),true);
 assert.match(initiative.prompt([]),/Не выдавай предположения за просмотренное видео/);
 initiative.recordIdea('Мистический маяк',day,s);
 assert.equal(initiative.shouldOffer(day,s),false);
 assert.equal(initiative.shouldOffer(new Date('2026-11-11T10:00:00'),s),true);
 initiative.setInitiative(false,s);
 assert.equal(initiative.shouldOffer(new Date('2026-11-11T10:00:00'),s),false);
});
test('context comes only from saved project, does not claim rendered footage',()=>{
 const p={title:'Маяк',idea:'герой слышит голос из света',scenes:[{imageUri:'uri1'},{imageUri:''}]};
 const details=initiative.projectContext([p]);
 assert.match(details,/Маяк/);
 assert.match(details,/1 из 2/);
 assert.match(details,/MP4.*нет/);
 assert.match(initiative.prompt([p]),/герой слышит голос/);
 assert.doesNotMatch(details,/просмотрела|оценила качество/);
});
test('corrupt settings gracefully recover and empty suggestions are rejected',()=>{
 const s=store();s.setItem('nova_director_preferences_v1','[broken');
 assert.deepEqual(initiative.preferences(s),{initiative:true});
 assert.throws(()=>initiative.recordIdea('',new Date(),s));
});
test('simple interface retains real chat, projects, tools, council and brain manager',()=>{
 const html=fs.readFileSync('src/index.html','utf8');
 assert.match(html,/src="nova_director_initiative\.js"/);
 for(const tab of ['chat','projects','tools','more','council','brains'])
  assert.match(html,new RegExp('data-tab="'+tab+'"'));
 assert.match(html,/async function generateNovaIdea/);
 assert.match(html,/brain\.chat\(\{message:prompt,maxTokens:180,requestId\}\)/);
 assert.match(html,/function showNovaMore/);
 assert.match(html,/function showCouncil/);
 assert.match(html,/function showBrainManager/);
 assert.match(html,/window\.NovaStoryUI\.render/);
});
test('native personality enforces finished result and truthful tool claims',()=>{
 const engine=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaEngine.kt','utf8');
 const council=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaCouncilPrompts.kt','utf8');
 assert.match(engine,/Завершай начатое/);
 assert.match(engine,/Не выдавай намерения за выполненные действия/);
 assert.match(council,/бесконечных доработок/);
 assert.match(engine,/Не говори её вместо приветствия/);
 assert.match(council,/Твоя собственная критика может ошибаться/);
});


test('NOVA remembers the owners explicit idea rating, without changing model weights',()=>{
 const s=store(),day=new Date('2026-11-10T12:00:00');
 initiative.recordIdea('Хоррор про отражение',day,s);
 initiative.rateIdea(false,s);
 assert.equal(initiative.lastReaction(s).liked,false);
 assert.match(initiative.prompt([],s),/НЕ ПОНРАВИЛОСЬ: Хоррор про отражение/);
 initiative.rateIdea(true,s);
 assert.equal(initiative.lastReaction(s).liked,true);
 assert.equal(initiative.reactions(s).length,1);
});

test('automatic idea is a visible invitation, never an unrequested heavy LLM run',()=>{
 const html=fs.readFileSync('src/index.html','utf8');
 assert.match(html,/ideaButton\.onclick=\(\)=>generateNovaIdea\(\)/);
 assert.match(html,/NOVA обдумывает идею для следующего ролика/);
 assert.doesNotMatch(html,/generateNovaIdea\(true\)|generateNovaIdea\(false\)/);
 assert.doesNotMatch(html,/setTimeout\(\(\)=>generateNovaIdea/);
 assert.doesNotMatch(html,/function generateNovaIdea\(automatic/);
 assert.match(html,/maxTokens:180,requestId/);
 assert.match(html,/NovaDirectorInitiative\?\.shouldOffer\(\)/);
});
