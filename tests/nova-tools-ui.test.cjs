const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('src/index.html','utf8');
const code=html.match(/<script>([\s\S]*?)<\/script>/)?.[1];

test('NOVA tools screen retains valid JavaScript',()=>{
  assert.ok(code,'inline script must exist');
  assert.doesNotThrow(()=>new vm.Script(code));
});

test('downloads require explicit native confirmation rather than model free-text',()=>{
  assert.match(html,/data-tab="tools"/);
  assert.match(code,/function showNovaTools\(/);
  assert.match(code,/tools\.requestDownload\(/);
  assert.match(code,/tools\.verifySha256\(/);
  assert.match(code,/tools\.exportDownload\(/);
  assert.doesNotMatch(code,/innerHTML\s*=/);
  assert.doesNotMatch(code,/\beval\s*\(/);
});

test('chat and council remain wired alongside tools',()=>{
  assert.match(code,/brain\.directorCouncil\(/);
  assert.match(code,/brain\.chat\(\{message:text,requestId\}\)/);
});
