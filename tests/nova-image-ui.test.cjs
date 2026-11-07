const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('src/index.html','utf8');
const js=html.match(/<script>([\s\S]*?)<\/script>/)?.[1];

test('NOVA retains syntactically valid chat and image tools',()=>{
 assert.ok(js);
 assert.doesNotThrow(()=>new vm.Script(js));
 assert.match(js,/brain\.directorCouncil\(/);
 assert.match(js,/tools\.requestDownload\(/);
 assert.match(js,/bridge\.generate\(/);
 assert.match(js,/await handleImageMessage\(/);
 assert.match(js,/img\.src=preview/);
 assert.doesNotMatch(js,/innerHTML\s*=/);
});
test('image command does not route a normal chat message',()=>{
 const pattern=js.match(/^const imageCommand=(.+);$/m)?.[1];
 assert.ok(pattern,'router regex');
 const re=vm.runInNewContext(pattern);
 assert.ok(re.test('Создай картинку'));
 assert.ok(re.test('Нарисуй изображение: туманный лес'));
 assert.ok(re.test('Зроби зображення туманний ліс'));
 assert.ok(re.test('Нова, сделай страшную картинку с зеркалом'));
 assert.ok(re.test('Сделай мне красивую картинку'));
 assert.ok(re.test('Возьми и создай мне картинку солдата ВСУ'));
 assert.ok(re.test('Візьми и зроби зображення лісу'));

 assert.equal(re.test('Привет, Нова'),false);
 assert.equal(re.test('Создай видео'),false);
 assert.equal(re.test('Не создавай картинку'),false);
 assert.equal(re.test('Пожалуйста, найди картинку'),false);
});
test('history stores only lightweight text, not base64 images',()=>{
 assert.match(js,/\.map\(m=>\(\{who:m\.who,text:m\.text\}\)\)/);
});

test('local drawing calls are explicit and diagnostics are available',()=>{
 assert.match(js,/const drawCommand=/);
 assert.match(js,/importImageModel/);
 assert.match(js,/releaseForImage/);
 assert.match(js,/check\.onclick\(\)/);
 assert.match(js,/Number\(sizeSelect\.value\)/);
 assert.match(js,/Зеркало без лица/);
 assert.match(js,/Туманный лес/);
 assert.match(js,/Аниме/);
 assert.match(js,/img\.src=result\.preview/);
});

test('quality path uses the actual offline image engine and optional LLM scene planner',()=>{
 assert.match(js,/brain\.planImagePrompt\(\{prompt:/);
 assert.match(js,/await brain\.releaseForImage\(\)/);
 assert.ok(js.indexOf("brain.planImagePrompt({prompt:")<js.indexOf("brain.releaseForImage()"),
   'scene must be planned before unloading the text model');
 assert.match(js,/steps:size===256\?16:24/);
 assert.match(js,/size=512\)/);
 assert.match(js,/sizeSelect\.value='512'/);
 assert.match(js,/imageNegativePrompt/);
 assert.match(js,/imageEnhancedPrompt/);
 assert.match(js,/imageLastResult\?\.saved/);
 assert.match(js,/Вот результат/);
});


test('combined story and illustration request routes to native image engine',()=>{
 const routeSource=js.match(/function wantsStoryImages\(text\)\{[\s\S]*?\n\}/)?.[0];
 const sceneSource=js.match(/function storyFramePrompts\(story\)\{[\s\S]*?\n\}/)?.[0];
 assert.ok(routeSource&&sceneSource,'story route helpers exist');
 const wants=vm.runInNewContext(routeSource+'; wantsStoryImages');
 const scenes=vm.runInNewContext(sceneSource+'; storyFramePrompts');
 assert.equal(wants('Придумай историю веселую. И сделай к ним картинки'),true);
 assert.equal(wants('Сочини сказку и нарисуй иллюстрации'),true);
 assert.equal(wants('Вигадай історію та зроби малюнки'),true);
 assert.equal(wants('Привет, как дела?'),false);
 assert.equal(wants('Нарисуй картинку'),false);
 const shots=scenes('Весёлая история\nСцена 1: Профессор в космосе\nСцена 2: Робот танцует\nСцена 3: Космический пончик');
 assert.equal(shots.length,3);
 assert.equal(shots[0],'Профессор в космосе');
 assert.deepEqual(Array.from(scenes('[Создаю изображение: робот в космосе]')),['робот в космосе']);
 assert.match(js,/const storyImages=wantsStoryImages\(text\)/);
 assert.match(js,/await brain\.chat\(\{message:storyRequest,requestId\}\)/);
 assert.match(js,/await generateStoryFrames\(waiting\.text\)/);
 assert.match(js,/await executeImageGeneration\(prepared\[index\]/);
 assert.match(js,/const prepared=\[\]/);
 assert.match(js,/await image\?\.cancel\?\.\(\)/);
});
