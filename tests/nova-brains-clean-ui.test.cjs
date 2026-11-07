const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/index.html','utf8');
const script = source.match(/<script>([\s\S]*?)<\/script>/)?.[1];
const start = script?.indexOf('async function showBrainManager()') ?? -1;
const end = script?.indexOf('// Explicit-approval resource tools.', start) ?? -1;
const brains = start >= 0 && end > start ? script.slice(start,end) : '';

test('single active text brain and image brain remain visible',()=>{
 assert.ok(script && brains);
 assert.doesNotThrow(()=>new vm.Script(script));
 assert.match(brains,/const primary=models\.find\(item=>item\.active&&item\.valid!==false\)/);
 assert.match(brains,/main\?modelsBox:otherModelsBox/);
 assert.match(brains,/const primary=models\.find\(item=>item\.selected\)\|\|models\[0\]/);
 assert.match(brains,/main\?imagesBox:imageOtherModelsBox/);
});

test('old models, duplicate image copies and recovery actions use expandable advanced sections',()=>{
 assert.match(brains,/const llmMore=add\(llm,'details','nova-more'\)/);
 assert.match(brains,/const imageMore=add\(imagePanel,'details','nova-more'\)/);
 assert.match(brains,/const importBtn=add\(llmMore,'button'/);
 assert.match(brains,/const restoreBackup=add\(imageMore,'button'/);
 assert.match(brains,/const imageTestStatus=add\(imageMore,'div'/);
 assert.match(brains,/const quickTest=add\(imageToolsBox,'button'/);
 assert.match(brains,/if\(state==='installed' && models\.length\)/);
 assert.match(brains,/const row=add\(downloadsBox,'div','brain-row'\)/);
});

test('advanced actions do not delete imported brain files',()=>{
 assert.match(brains,/brain\.exportSavedModel\(\{id:item\.id\}\)/);
 assert.match(brains,/brain\.importAdditionalModel\(\)/);
 assert.match(brains,/image\.importImageModel\(\)/);
 assert.match(brains,/backup\.restoreRecommended\(\)/);
 assert.match(brains,/image\.selectImageModel\(\{id:item\.id\}\)/);
 assert.doesNotMatch(brains,/\.delete\(|deleteFile\(/);
});
