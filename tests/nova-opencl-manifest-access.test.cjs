const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const manifest=fs.readFileSync('android/app/src/main/AndroidManifest.xml','utf8');
const probe=fs.readFileSync('android/nova-image/src/main/cpp/nova_gpu_probe.cpp','utf8');
const cmake=fs.readFileSync('android/nova-image/src/main/cpp/CMakeLists.txt','utf8');
const gradle=fs.readFileSync('android/nova-image/build.gradle','utf8');

test('Android 12+ app explicitly requests optional vendor OpenCL access',()=>{
 assert.match(manifest,/<uses-native-library\s+android:name="libOpenCL\.so"\s+android:required="false"\s*\/>/);
 const applicationStart=manifest.indexOf('<application');
 const applicationEnd=manifest.indexOf('</application>');
 const declaration=manifest.indexOf('<uses-native-library');
 assert.ok(declaration>applicationStart&&declaration<applicationEnd);
 assert.doesNotMatch(manifest,/android:required="true"/);
});
test('GPU probe distinguishes inaccessible vendor library from missing runtime',()=>{
 assert.match(probe,/dlopen\("libOpenCL\.so", RTLD_NOW \| RTLD_LOCAL\)/);
 assert.match(probe,/dlerror\(\)/);
 assert.match(probe,/not accessible for the namespace/);
 assert.match(probe,/OpenCL не найден среди доступных приложению библиотек/);
 assert.match(probe,/clGetPlatformIDs/);
 assert.match(probe,/clGetDeviceIDs/);
 assert.match(probe,/clGetDeviceInfo/);
 assert.match(probe,/0x102B/);
});
test('Stable generation remains CPU and does not change image quality options',()=>{
 assert.match(cmake,/option\(NOVA_EXPERIMENTAL_OPENCL[^)]* OFF\)/);
 assert.match(cmake,/else\(\)\s*set\(SD_OPENCL OFF CACHE BOOL "" FORCE\)/);
 assert.match(gradle,/NOVA_EXPERIMENTAL_OPENCL'\) == '1'/);
 assert.doesNotMatch(manifest,/android:debuggable="true"/);
 assert.doesNotMatch(probe,/generate_image\(|new_sd_ctx\(/);
});
