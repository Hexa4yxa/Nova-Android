const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');

const cmake=fs.readFileSync('android/nova-image/src/main/cpp/CMakeLists.txt','utf8');
const image=fs.readFileSync('android/nova-image/src/main/cpp/nova_image_jni.cpp','utf8');
const probe=fs.readFileSync('android/nova-image/src/main/cpp/nova_gpu_probe.cpp','utf8');
const gradle=fs.readFileSync('android/nova-image/build.gradle','utf8');
const bridge=fs.readFileSync('android/nova-image/src/main/java/ai/nova/image/NativeImageEngine.kt','utf8');
const manager=fs.readFileSync('android/app/src/main/java/ai/nova/director/NovaImagePlugin.kt','utf8');
const ui=fs.readFileSync('src/index.html','utf8');

test('Stable default remains on same CPU model, size, steps and sampler',()=>{
 assert.match(cmake,/option\(NOVA_EXPERIMENTAL_OPENCL[^)]* OFF\)/);
 assert.match(cmake,/else\(\)\s*set\(SD_OPENCL OFF CACHE BOOL "" FORCE\)/);
 assert.match(image,/ctx_params\.backend = "cpu"/);
 assert.match(image,/EULER_A_SAMPLE_METHOD/);
 assert.match(image,/DISCRETE_SCHEDULER/);
 assert.match(image,/image_params\.sample_params\.sample_steps = steps/);
 assert.match(image,/image_params\.width = size/);
 assert.match(gradle,/NOVA_EXPERIMENTAL_OPENCL'\) == '1'/);
});

test('GPU probing does not start rendering or allocate SD model',()=>{
 assert.match(probe,/dlopen\("libOpenCL\.so"/);
 assert.match(probe,/clGetPlatformIDs/);
 assert.match(probe,/clGetDeviceIDs/);
 assert.doesNotMatch(probe,/new_sd_ctx|generate_image|System\.loadLibrary/);
 assert.match(bridge,/fun gpuProbe\(\): String/);
 assert.match(bridge,/nativeGpuProbe/);
 assert.match(manager,/put\("gpuProbe", NativeImageEngine\.gpuProbe\(\)\)/);
 assert.match(ui,/resources\.gpuProbe/);
 assert.match(ui,/Пока генерация на CPU/);
});

test('OpenCL remains isolated as experimental GPU build without a new Android app ID',()=>{
 assert.match(cmake,/if\(NOVA_EXPERIMENTAL_OPENCL\)/);
 assert.match(cmake,/set\(SD_OPENCL ON CACHE BOOL "" FORCE\)/);
 assert.match(cmake,/GGML_OPENCL_EMBED_KERNELS/);
 assert.match(gradle,/OpenCL_INCLUDE_DIR/);
 assert.match(gradle,/OpenCL_LIBRARY/);
 assert.match(cmake,/nova_gpu_probe\.cpp/);
 assert.match(cmake,/target_link_libraries\(\$\{NOVA_IMAGE_TARGET\} PRIVATE stable-diffusion android log dl\)/);
});
