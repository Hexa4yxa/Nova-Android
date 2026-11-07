const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const read=name=>fs.readFileSync(name,'utf8');
const html=read('src/index.html');
const js=html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
const kotlin=read('android/app/src/main/java/ai/nova/director/NovaImagePlugin.kt');
const installer=read('android/app/src/main/java/ai/nova/director/NovaImageModelsPlugin.kt');
const native=read('android/nova-image/src/main/cpp/nova_image_jni.cpp');
const cmake=read('android/nova-image/src/main/cpp/CMakeLists.txt');

test('NOVA single-app UI is syntactically valid and has no required external image app',()=>{
 assert.ok(js);
 assert.doesNotThrow(()=>new vm.Script(js));
 assert.match(js,/image\.importImageModel\(/);
 assert.match(js,/downloads\.downloadRecommended\(\{approved:true,allowMeteredWifi:/);
 assert.match(js,/brain\.releaseForImage\(\)/);
 assert.match(js,/bridge\.generate\(/);
 assert.match(js,/\uD83E\uDDEA Проверить PNG/);
 assert.match(js,/executeImageGeneration\(prompt,message=>/);
 assert.match(js,/imageTestPreview\.replaceChildren\(\)/);
 assert.doesNotMatch(js,/image\.openLocalDream\(/);
 assert.doesNotMatch(js,/innerHTML\s*=/);
});

test('native library does real diffusion with memory-safe PNG handoff',()=>{
 assert.match(native,/new_sd_ctx\(/);
 assert.match(native,/generate_image\(/);
 assert.match(native,/free_sd_image_generation_result\(/);
 assert.match(native,/free_sd_ctx\(/);
 assert.match(native,/copy_rgb\(/);
 assert.match(native,/nativeCancel/);
 assert.match(native,/nativeProgress/);
 assert.match(cmake,/add_library\(nova-image SHARED/);
 assert.match(cmake,/stable-diffusion/);
 assert.match(kotlin,/NativeImageEngine\.generate\(/);
 assert.match(kotlin,/last_success_model_id/);
 assert.match(kotlin,/last_error_model_id/);
 assert.match(kotlin,/freeMemoryMib/);
 assert.match(js,/item\.testedOnDevice/);
 assert.match(js,/item\.lastError/);
 assert.match(js,/response\.testedOnDevice/);
 assert.match(kotlin,/MediaStore\.Images\.Media\.IS_PENDING/);
 assert.doesNotMatch(kotlin,/127\.0\.0\.1/);
});

test('model is chosen and downloaded only after approval, integrity verified',()=>{
 assert.match(installer,/call\.getBoolean\("approved", false\)/);
 assert.match(installer,/AlertDialog\.Builder\(activity\)/);
 assert.match(installer,/setPositiveButton\("Разрешить загрузку"/);
 assert.match(installer,/private fun enqueueRecommended\(allowMeteredWifi: Boolean\)/);
 assert.match(installer,/\.commit\(\)/);
 assert.match(installer,/https:\/\/huggingface\.co\//);
 assert.match(installer,/SHA256 = "[0-9a-f]{64}"/);
 assert.match(installer,/MessageDigest\.getInstance\("SHA-256"\)/);
 assert.match(installer,/actual == NovaRecommendedImageModel\.SHA256/);
 assert.match(installer,/setAllowedOverMetered\(allowMeteredWifi\)/);
 assert.match(installer,/eligibleMeteredWifi\(\)/);
 assert.match(installer,/getBoolean\("allowMeteredWifi", false\)/);
 assert.match(installer,/putBoolean\("allow_metered_wifi", allowMeteredWifi\)/);
 assert.match(installer,/if \(allowHotspot && !eligibleMeteredWifi\(\)\)/);
 assert.match(js,/downloads\.downloadRecommended\(\{approved:true,allowMeteredWifi:true\}\)/);
 assert.match(installer,/PAUSED_WAITING_FOR_NETWORK/);
 assert.match(installer,/PAUSED_QUEUED_FOR_WIFI/);
 assert.match(installer,/PAUSED_WAITING_TO_RETRY/);
 assert.match(installer,/NET_CAPABILITY_NOT_METERED/);
 assert.match(installer,/put\("pauseExplanation", pausedExplanation\(reason\)\)/);
 assert.match(js,/installed\.pauseExplanation/);
 const manifest=read('android/app/src/main/AndroidManifest.xml');
 assert.match(manifest,/android\.permission\.ACCESS_NETWORK_STATE/);
 assert.match(installer,/setDestinationInExternalFilesDir/);
 assert.match(installer,/putString\("selected_id", ID\)/);
 assert.match(installer,/STATUS_FAILED/);
 assert.match(installer,/cancelRecommended/);
 assert.match(kotlin,/StatFs\(dir\(\)\.absolutePath\)\.availableBytes/);
});

test('the new bridge and image module remain isolated from working text GGUF',()=>{
 const main=read('android/app/src/main/java/ai/nova/director/MainActivity.java');
 const gradle=read('android/app/build.gradle');
 assert.match(main,/registerPlugin\(NovaImageModelsPlugin\.class\)/);
 assert.match(gradle,/project\(':nova-image'\)/);
 assert.match(read('android/app/src/main/java/ai/nova/director/NovaBrainPlugin.kt'),/releaseForImage/);
 assert.match(read('android/nova-image/src/main/java/ai/nova/image/NativeImageEngine.kt'),/loadLibrary\("nova-image"\)/);
});

test('native image preflight uses RAM and thermal checks before starting diffusion',()=>{
 const safety=read('android/app/src/main/java/ai/nova/director/NovaImageSafety.kt');
 const tests=read('android/app/src/test/java/ai/nova/director/NovaImageSafetyTest.kt');
 assert.match(safety,/MIN_FREE_MIB_256/);
 assert.match(safety,/MIN_FREE_MIB_512/);
 assert.match(safety,/thermalSevere/);
 assert.match(safety,/systemLowMemory/);
 assert.match(kotlin,/PowerManager\.THERMAL_STATUS_SEVERE/);
 assert.match(kotlin,/private fun resourceWarning\(size: Int\)/);
 const guardPos=kotlin.indexOf('val unsafe = try { resourceWarning(size) }');
 const inferencePos=kotlin.indexOf('val rgb = NativeImageEngine.generate(');
 assert.ok(guardPos > 0 && inferencePos > guardPos,
   'preflight must run before native model allocation');
 assert.match(kotlin,/NovaComputeGate\.finishImage\(\)\s*call\.reject\(unsafe\)/);
 assert.match(js,/resources\.resourceWarning/);
 assert.match(js,/response\.resourceWarning/);
 assert.match(tests,/fun overheatingBlocksBothImageSizes/);
 assert.match(tests,/fun qualityModeRequiresMoreRamThanQuickMode/);
});
