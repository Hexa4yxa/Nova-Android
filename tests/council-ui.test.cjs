const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('src/index.html','utf8');
const js=html.match(/<script>([\s\S]*?)<\/script>/);
test('the bundled NOVA interface has valid JavaScript',()=>{
  assert.ok(js,'inline script exists');
  assert.doesNotThrow(()=>new vm.Script(js[1],{filename:'src/index.html'}));
});
test('both existing chat and new council remain reachable',()=>{
  assert.match(html,/data-tab="chat"/);
  assert.match(html,/data-tab="council"/);
  assert.match(js[1],/brain\.chat\(\{message:preparedMessage,requestId\}\)/);
  assert.match(js[1],/brain\.directorCouncil\(/);
  assert.match(js[1],/councilToken/);
  assert.match(js[1],/brain\.cancelChat\(\)/);
});
test('generated model text is not rendered as HTML',()=>{
  assert.match(js[1],/node\.textContent=councilState\[stage\]/);
  assert.doesNotMatch(js[1],/innerHTML\s*=/);
});
