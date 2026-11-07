const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const brain = fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaBrainPlugin.kt', 'utf8');
const html = fs.readFileSync('src/index.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];

test('Bad saved GGUF is visible but not automatically activated', () => {
  assert.match(brain, /private fun isValidGguf\(file: File\)/);
  assert.match(brain, /if \(id != null && usable\(id\)\) return id/);
  assert.match(brain, /if \(usable\("legacy"\)\) return "legacy"/);
  assert.match(brain, /put\("valid", isValidGguf\(file\)\)/);
  assert.match(script, /item\.valid!==false/);
  assert.match(script, /Этот файл NOVA не запустит/);
  assert.match(script, /if\(!valid\)\{[\s\S]*?continue;/);
});

test('Text model export is an explicit copy using Android system picker', () => {
  assert.match(brain, /fun exportSavedModel\(call: PluginCall\)/);
  assert.match(brain, /Intent\.ACTION_CREATE_DOCUMENT/);
  assert.match(brain, /modelExportSelected/);
  assert.match(brain, /context\.contentResolver\.openOutputStream\(uri, "w"\)/);
  assert.match(brain, /input\.copyTo\(output, 1024 \* 1024\)/);
  assert.match(brain, /put\("sourcePreserved", true\)/);
  assert.match(script, /brain\.exportSavedModel\(\{id:item\.id\}\)/);
});

test('Legacy import validates before committing and preserves previous model', () => {
  const start = brain.indexOf('private fun modelSelected(');
  const end = brain.indexOf('@PluginMethod\n    fun loadModel(', start);
  const callback = brain.slice(start, end);
  assert.ok(start >= 0 && end > start);
  assert.match(callback, /checkGguf\(temporary!!\)/);
  assert.match(callback, /renameTo\(destination\)/);
  assert.ok(callback.indexOf('checkGguf(temporary!!)') < callback.indexOf('renameTo(destination)'));
  assert.doesNotMatch(callback, /File\(context\.filesDir, "nova-model\.gguf"\)/);
});

test('Faster rendering remains selectable with original high-quality option', () => {
  assert.ok(script);
  assert.doesNotThrow(() => new vm.Script(script));
  assert.match(script, /size===256\?8:12/);
  assert.match(script, /stepsSelect\.value='12'/);
  assert.match(script, /\['8','⚡ Быстро/);
  assert.match(script, /\['24','💎 Качество/);
  assert.match(script, /Number\(stepsSelect\.value\)/);
  assert.match(script, /result\.elapsedSeconds=/);
  assert.match(script, /await brain\.releaseForImage\(\)/);
});
