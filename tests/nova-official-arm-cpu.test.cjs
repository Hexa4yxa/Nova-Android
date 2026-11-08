const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const read=path=>fs.readFileSync(path,'utf8');

test('arm64 optimization uses verified native CPU kernels, no GPU or data migration',()=>{
 const cmake=read('android/nova-image/src/main/cpp/CMakeLists.txt');
 const gradle=read('android/nova-image/build.gradle');
 const native=read('android/nova-image/src/main/cpp/nova_image_jni.cpp');
 assert.match(cmake,/option\(NOVA_CPU_ARM_FP16_DOTPROD .* OFF\)/);
 assert.match(cmake,/set\(GGML_CPU_ARM_ARCH "armv8\.2-a\+fp16\+dotprod"/);
 assert.match(cmake,/if\(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a"\)/);
 assert.match(gradle,/System\.getenv\('NOVA_CPU_ARM_TUNED'\) == '1'/);
 assert.match(gradle,/-DNOVA_CPU_ARM_FP16_DOTPROD=ON/);
 assert.match(native,/ctx_params\.backend = "cpu";/);
 assert.match(native,/ctx_params\.enable_mmap = true;/);
 assert.match(native,/image_params\.sample_params\.sample_steps = steps;/);
 assert.match(native,/image_params\.sample_params\.sample_method = EULER_A_SAMPLE_METHOD;/);
 assert.match(native,/image_params\.sample_params\.scheduler = DISCRETE_SCHEDULER;/);
});
test('stable package and owner signature remain identical',()=>{
 const gradle=read('android/app/build.gradle');
 const workflow=read('.github/workflows/nova-one-stable.yml');
 assert.match(gradle,/applicationIdSuffix "\.stable"/);
 assert.match(gradle,/signingConfig signingConfigs\.novaStable/);
 assert.match(workflow,/NOVA_SIGNING_KEYSTORE_B64/);
 assert.match(workflow,/NOVA_STABLE_CERT_SHA256\.txt/);
 assert.match(workflow,/libnova-image-gpu\.so/);
 assert.match(workflow,/Light release must not package the experimental Adreno GPU renderer/);
});
test('storyboard project checkpoint and full-quality 512x512 12 steps remain unchanged',()=>{
 const js=read('src/index.html');
 const story=read('src/nova_story_chat.js');
 const director=read('src/nova_story_director.js');
 assert.match(js,/executeImageGeneration\(prompt,onProgress,512,12\)/);
 assert.match(story,/resumeExisting/);
 assert.match(director,/const KEY='nova_story_projects_v1';/);
 assert.match(director,/const existing=read\(\);/);
});
