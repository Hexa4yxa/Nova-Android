const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const read=x=>fs.readFileSync(x,'utf8');

const cmake=read('android/nova-image/src/main/cpp/CMakeLists.txt');
const jni=read('android/nova-image/src/main/cpp/nova_image_jni.cpp');
const gpu=read('android/nova-image/src/main/java/ai/nova/image/NativeGpuImageEngine.kt');
const cpu=read('android/nova-image/src/main/java/ai/nova/image/NativeImageEngine.kt');
const plugin=read('android/app/src/main/java/ai/nova/director/NovaImagePlugin.kt');
const html=read('src/index.html');
const build=read('scripts/build-nova-adreno.sh');

test('CPU is still an isolated native library and default in normal NOVA',()=>{
 assert.match(cmake,/add_library\(nova-image SHARED nova_image_jni.cpp nova_gpu_probe.cpp\)/);
 assert.match(cmake,/add_library\(nova-image-gpu SHARED nova_image_jni.cpp\)/);
 assert.match(cmake,/NOVA_GPU_IMAGE_BACKEND=1/);
 assert.match(cmake,/option\(NOVA_EXPERIMENTAL_OPENCL[^)]* OFF\)/);
 assert.match(jni,/ctx_params\.backend = "cpu"/);
 assert.match(cpu,/System\.loadLibrary\("nova-image"\)/);
 assert.match(plugin,/val rgb = NativeImageEngine\.generate\(/);
 assert.doesNotMatch(plugin,/putString\("selected_gpu"/);
});

test('GPU native library requires a real registered OpenCL device and never masquerades as CPU',()=>{
 assert.match(jni,/#define NOVA_JNI\(name\) Java_ai_nova_image_NativeGpuImageEngine_##name/);
 assert.match(jni,/sd_list_devices\(nullptr, 0\)/);
 assert.match(jni,/ctx_params\.backend = gpu_name\.c_str\(\)/);
 assert.match(jni,/if \(gpu_name\.empty\(\)\) throw/);
 assert.match(jni,/Java_ai_nova_image_NativeGpuImageEngine_nativeGpuDevice/);
 assert.match(gpu,/System\.loadLibrary\("nova-image-gpu"\)/);
 assert.match(gpu,/fun gpuDevice\(\): String/);
 assert.match(gpu,/fun enableForApprovedTest\(\): Boolean/);
 assert.match(plugin,/if \(!NativeGpuImageEngine\.enableForApprovedTest\(\)\)/);
 assert.match(html,/Драйвер GPU загрузится только после твоего подтверждения теста/);
 assert.doesNotMatch(gpu,/private val error: String\? = try/);
 assert.match(gpu,/require\(steps in 1\.\.30\)/);
 assert.match(plugin,/put\("gpuEngineDevice", NativeGpuImageEngine\.gpuDevice\(\)\)/);
});

test('Adreno quick test is opt-in, 256px, single step, same existing model',()=>{
 assert.match(plugin,/fun gpuQuickTest\(call: PluginCall\)/);
 assert.match(plugin,/call\.getBoolean\("approved", false\)/);
 assert.match(plugin,/NativeGpuImageEngine\.generate\(file\.absolutePath,/);
 assert.match(plugin,/256, 1, 4242L/);
 assert.match(plugin,/val unsafe = try \{ resourceWarning\(256\) \}/);
 assert.match(plugin,/NovaComputeGate\.beginImage\(\)/);
 assert.match(plugin,/NovaComputeGate\.finishImage\(\)/);
 assert.match(plugin,/put\("backend", "OpenCL GPU"\)/);
 assert.match(plugin,/call\.reject\("OpenCL тест не завершён/);
 assert.match(html,/await image\.gpuQuickTest\(\{approved:true\}\)/);
 assert.match(html,/await brain\.releaseForImage\(\)/);
 assert.match(html,/256×256 · 1 шаг/);
 assert.doesNotThrow(()=>new vm.Script(html.match(/<script>([\s\S]*?)<\/script>/)?.[1]));
});

test('GPU staging intentionally omits Khronos ICD loader and separate APKs',()=>{
 assert.match(build,/target nova-image-gpu --parallel 2/);
 assert.match(build,/STAGING=".*android\/app\/src\/main\/jniLibs\/arm64-v8a"/);
 assert.match(build,/grep -q 'libOpenCL\.so'/);
 assert.match(build,/cp -f "\$LIBRARY" "\$STAGING\/libnova-image-gpu\.so"/);
 assert.doesNotMatch(build,/cp.*libOpenCL\.so.*STAGING/);
 assert.doesNotMatch(build,/adb install|gh release|apksigner/);
});
