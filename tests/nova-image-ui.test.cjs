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
 assert.equal(re.test('Привет, Нова'),false);
 assert.equal(re.test('Создай видео'),false);
});
test('history stores only lightweight text, not base64 images',()=>{
 assert.match(js,/\.map\(m=>\(\{who:m\.who,text:m\.text\}\)\)/);
});

test('image studio has working preset choices and an explicit cancel action',()=>{
 assert.match(js,/const presets=\[/);
 assert.match(js,/Мистика/);
 assert.match(js,/Туманный лес/);
 assert.match(js,/Кадр без лица/);
 assert.match(js,/Anime|Аниме/);
 assert.match(js,/imageStop\.onclick/);
 assert.match(js,/NovaImage\?\.cancel\(\)/);
});

test('a user can open Local Dream without browsing Android app drawer',()=>{
 assert.match(js,/openLocal\.onclick=async/);
 assert.match(js,/plugin\.openLocalDream\(\)/);
 assert.match(html,/Открыть Local Dream/);
});
