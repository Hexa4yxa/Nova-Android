const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('src/index.html','utf8');
const code=html.match(/<script>([\s\S]*?)<\/script>/)?.[1];

test('brains tab and JavaScript syntax',()=>{
 assert.ok(code);
 assert.doesNotThrow(()=>new vm.Script(code,{filename:'src/index.html'}));
 assert.match(html,/data-tab="brains"/);
 assert.match(code,/function showBrainManager\(/);
});
test('brain manager connects to native APIs, not model-generated shell commands',()=>{
 assert.match(code,/brain\.listModels\(\)/);
 assert.match(code,/brain\.activateSavedModel\(/);
 assert.match(code,/brain\.importAdditionalModel\(/);
 assert.match(code,/image\.imageModels\(\)/);
 assert.match(code,/image\.selectImageModel\(/);
 assert.match(code,/image\.openLocalDream\(\)/);
 assert.doesNotMatch(code,/innerHTML\s*=/);
 assert.doesNotMatch(code,/\beval\s*\(/);
});
test('old director council and image tool remain connected',()=>{
 assert.match(code,/brain\.directorCouncil\(/);
 assert.match(code,/bridge\.generate\(/);
 assert.match(code,/tools\.requestDownload\(/);
});
