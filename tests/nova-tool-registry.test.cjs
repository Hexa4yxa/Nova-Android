const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const registry=require('../src/nova_tool_registry.js');
const html=fs.readFileSync('src/index.html','utf8');
const inline=html.match(/<script>([\s\S]*?)<\/script>/)?.[1]||'';
const mockReady={NovaImage:{status:async()=>({
  ready:true,testedOnDevice:true,detail:'Готово: изображение уже создано'
})},NovaBrain:{status:async()=>({engine:'ModelReady'}),directorCouncil:async()=>({})},NovaTools:{}};

test('user screenshot: NOVA knows image tool after Gemma chat',async()=>{
 const route=registry.classifyCapability('А картинку создать ?',[
  {who:'user',text:'Нова, привет! Расскажи, что ты умеешь.'},
  {who:'nova',text:'Я локальный помощник.'}
 ]);
 assert.deepEqual(route,{kind:'capability',tool:'image'});
 const answer=await registry.describeCapability(route,mockReady);
 assert.match(answer,/Да!/);
 assert.match(answer,/PNG/);
 assert.match(answer,/Нарисуй/);
 assert.doesNotMatch(answer,/не умею|не использую инструменты/u);
});

test('follow-up retains the image tool instead of asking Gemma again',async()=>{
 const route=registry.classifyCapability('А если с помощью инструментов',[
  {who:'user',text:'А картинку создать ?'},
  {who:'nova',text:'Я генерирую текст. Картинки создавать не умею.'}
 ]);
 assert.deepEqual(route,{kind:'capability',tool:'image'});
 const answer=await registry.describeCapability(route,mockReady);
 assert.match(answer,/нарисовать/i);
});

test('a real art instruction is NOT rewritten as a capability question',()=>{
 assert.equal(registry.classifyCapability('Нарисуй картинку: лес в тумане',[]),null);
 assert.equal(registry.classifyCapability('Создай фото мрачного леса',[]),null);
 assert.equal(registry.classifyCapability('Привет, Нова',[]),null);
 assert.equal(registry.classifyCapability('Создай видео',[]),null);
});

test('feature reporting comes from actual Android plugin state',async()=>{
 const route={kind:'capability',tool:'image'};
 const noPlugin=await registry.describeCapability(route,{NovaBrain:{}});
 assert.match(noPlugin,/недоступно/);
 const noWeights=await registry.describeCapability(route,{
   NovaImage:{status:async()=>({ready:false,detail:'Модель не установлена'})}
 });
 assert.match(noWeights,/ещё не готова/);
 const untested=await registry.describeCapability(route,{
   NovaImage:{status:async()=>({ready:true,testedOnDevice:false})}
 });
 assert.match(untested,/ещё нужно проверить/);
 assert.doesNotMatch(untested,/уже создавала PNG/);
});

test('generic skills menu and video status are honest',async()=>{
 const all=registry.classifyCapability('Расскажи, что ты умеешь',[]);
 assert.deepEqual(all,{kind:'capability',tool:'all'});
 const answer=await registry.describeCapability(all,mockReady);
 assert.match(answer,/изображени|PNG|рисов/u);
 assert.match(answer,/модуль сборки MP4/i);
 const video=registry.classifyCapability('Можешь делать видеомонтаж?',[]);
 assert.equal(video.tool,'video');
 assert.match(await registry.describeCapability(video,mockReady),/недоступен/);
});

test('NOVA webview includes registry before main script and routes BEFORE Gemma',()=>{
 assert.match(html,/<script src="nova_tool_registry\.js"><\/script>\s*<script src="nova_image_prompt_guard\.js"><\/script>\s*<script src="nova_story_director\.js"><\/script>\s*<script src="nova_comic_narrator\.js"><\/script>\s*<script src="nova_story_ui\.js"><\/script>\s*<script src="nova_story_chat\.js"><\/script>\s*<script src="nova_director_initiative\.js"><\/script>\s*<script>/);
 assert.doesNotThrow(()=>new vm.Script(inline));
 const capability=inline.indexOf('const capability=window.NovaToolRegistry?.classifyCapability(text,history)');
 const gemma=inline.indexOf('const answer=await brain.chat({message:text,requestId})');
 assert.ok(capability>=0&&gemma>capability);
 assert.match(inline,/handleCapabilityMessage\(text,capability\)/);
 assert.match(inline,/route\.tool==='image'/);
 assert.match(inline,/await image\.status\(\)/);
 assert.match(inline,/history\.slice\(-6\)/);
 assert.match(inline,/\{kind:'capability',tool:'image'\}/);

});

test('NOVA accurately reports offline voice only when the Android local voice is ready',async()=>{
 const video={kind:'capability',tool:'video'};
 const motion={render:async()=>({})};
 const ready={NovaMotion:motion,NovaVoice:{status:async()=>({available:true}),dubMovie:async()=>({})}};
 const answer=await registry.describeCapability(video,ready);
 assert.match(answer,/озвучить через установленный локальный голос Android/);
 assert.doesNotMatch(answer,/немой|ещё не подключен/);
 const unavailable={NovaMotion:motion,NovaVoice:{status:async()=>({available:false}),dubMovie:async()=>({})}};
 const notReady=await registry.describeCapability(video,unavailable);
 assert.match(notReady,/готовность такого голоса не подтверждена/);
 const broken={NovaMotion:motion,NovaVoice:{status:async()=>{throw Error('TTS failed')},dubMovie:async()=>({})}};
 assert.match(await registry.describeCapability(video,broken),/не подтверждена/);
});
