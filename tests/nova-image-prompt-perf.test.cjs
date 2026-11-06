const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const guardSource=fs.readFileSync('src/nova_image_prompt_guard.js','utf8');
const context={window:{}};
vm.runInNewContext(guardSource,context,{filename:'nova_image_prompt_guard.js'});
const guard=context.window.NovaImagePromptGuard;
const ui=fs.readFileSync('src/index.html','utf8');
const js=ui.match(/<script>([\s\S]*?)<\/script>/)?.[1];
const native=fs.readFileSync('android/nova-image/src/main/cpp/nova_image_jni.cpp','utf8');
const bridge=fs.readFileSync('android/nova-image/src/main/java/ai/nova/image/NativeImageEngine.kt','utf8');
const android=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaImagePlugin.kt','utf8');

test('NOVA does not silently use Russian text for an English-centric SD1.5 image',()=>{
 assert.ok(guard);
 const dog=guard.prepare('собачки','photorealistic empty landscape and sand');
 assert.match(dog.prompt,/\bdog\b/i);
 assert.doesNotMatch(dog.prompt,/landscape and sand/i);
 assert.equal(dog.fallback,true);
 const dachshund=guard.prepare('собаки таксы','a landscape with hills');
 assert.match(dachshund.prompt,/\bdachshund dog\b/i);
 assert.match(dachshund.prompt,/short legs/i);
 assert.equal(dachshund.fallback,true);
 const translated=guard.prepare('собаки таксы','A dachshund dog standing in a garden, realistic photo');
 assert.equal(translated.fallback,false);
 assert.match(translated.prompt,/dachshund/i);
 assert.throws(()=>guard.prepare('космический корабль','русский текст'),/не смогла надёжно перевести/);
});

test('English subject prompts are preserved without forced translation',()=>{
 assert.equal(guard.prepare('A small dog in a park','A small dog in a park').prompt,'A small dog in a park');
 assert.equal(guard.prepare('A dog on a skateboard','A photo of an adorable dog on a skateboard').fallback,false);
 assert.match(guard.prepare('кошки','красивая кошка').prompt,/\bcat\b/i);
});

test('Image guard runs before freeing models or any costly native rendering',()=>{
 assert.ok(js);
 assert.doesNotThrow(()=>new vm.Script(js));
 assert.match(ui,/src="nova_image_prompt_guard\.js"/);
 const guardCall=js.indexOf('promptGuard.prepare(prompt,visualPrompt)');
 const freeMemory=js.indexOf('brain.releaseForImage()');
 const drawing=js.indexOf('bridge.generate({');
 assert.ok(guardCall>0 && guardCall<freeMemory && freeMemory<drawing);
 assert.match(js,/result\.promptUsed=visualPrompt/);
 assert.match(js,/result\.subject=prepared\.subject/);
});

test('Model loading and sampling are measured separately by the native library',()=>{
 assert.match(native,/latest_model_load_ms/);
 assert.match(native,/latest_sampling_ms/);
 assert.match(native,/model_load_started/);
 assert.match(native,/sample_started/);
 assert.match(native,/nativeTimings/);
 assert.match(bridge,/fun timings\(\): LongArray/);
 assert.match(bridge,/private external fun nativeTimings\(\): LongArray/);
 assert.match(android,/val timing = NativeImageEngine\.timings\(\)/);
 assert.match(android,/put\("modelLoadMs", timing\.getOrElse\(0\)/);
 assert.match(android,/put\("samplingMs", timing\.getOrElse\(1\)/);
 assert.match(js,/result\.samplingMs/);
});
