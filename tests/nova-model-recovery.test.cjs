const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const read=path=>fs.readFileSync(path,'utf8');
const backup=read('android/app/src/main/java/ai/nova/director/NovaModelBackupPlugin.kt');
const installer=read('android/app/src/main/java/ai/nova/director/NovaImageModelsPlugin.kt');
const ui=read('src/index.html');
const app=read('android/app/src/main/java/ai/nova/director/MainActivity.java');
const gradle=read('android/app/build.gradle');
const workflow=read('.github/workflows/nova-stable-release.yml');

test('NOVA can restore a user-owned model from SAF without root or network',()=>{
 assert.match(backup,/Intent\.ACTION_OPEN_DOCUMENT/);
 assert.match(backup,/Intent\.CATEGORY_OPENABLE/);
 assert.match(backup,/Intent\.FLAG_GRANT_READ_URI_PERMISSION/);
 assert.match(backup,/@ActivityCallback/);
 assert.match(backup,/openInputStream\(uri\)/);
 assert.doesNotMatch(backup,/MANAGE_EXTERNAL_STORAGE|Android\/data|HttpURLConnection|https?:\/\//);
 assert.match(backup,/context\.contentResolver\.query/);
 assert.match(backup,/NovaRecommendedImageModel\.FILENAME/);
 assert.match(app,/registerPlugin\(NovaModelBackupPlugin\.class\)/);
});

test('copy never destroys original and only verified SHA-256 activates model',()=>{
 assert.match(backup,/MessageDigest\.getInstance\("SHA-256"\)/);
 assert.match(backup,/actual == NovaRecommendedImageModel\.SHA256/);
 assert.match(backup,/Files\.move\(temp\.toPath\(\), target\.toPath\(\), StandardCopyOption\.REPLACE_EXISTING\)/);
 assert.match(backup,/NovaRecommendedImageModel\.activate\(context, target\)/);
 assert.ok(backup.indexOf('actual == NovaRecommendedImageModel.SHA256') <
  backup.indexOf('Files.move(temp.toPath()'));
 assert.ok(backup.indexOf('Files.move(temp.toPath()') <
  backup.indexOf('NovaRecommendedImageModel.activate(context, target)'));
 assert.doesNotMatch(backup,/deleteDocument\(/);
 assert.doesNotMatch(backup,/uri\.toFile/);
 assert.match(backup,/temp\.delete\(\)/);
 assert.match(backup,/sourcePreserved/);
 assert.match(installer,/SHA256 = "[0-9a-f]{64}"/);
});

test('interface offers offline recovery from user backup with progress',()=>{
 const script=ui.match(/<script>([\s\S]*?)<\/script>/)?.[1];
 assert.ok(script);
 assert.doesNotThrow(()=>new vm.Script(script));
 assert.match(script,/NovaModelBackup/);
 assert.match(script,/restoreBackup\.onclick=async/);
 assert.match(script,/backup\.restoreRecommended\(\)/);
 assert.match(script,/novaModelRestoreProgress/);
 assert.match(script,/NOVA-Models/);
});

test('stable packages have one permanent identity and require signing secrets',()=>{
 assert.match(gradle,/applicationIdSuffix "\.stable"/);
 assert.match(gradle,/signingConfig signingConfigs\.novaStable/);
 assert.match(gradle,/NOVA_STABLE_VERSION_CODE/);
 assert.match(workflow,/workflow_dispatch/);
 assert.doesNotMatch(workflow,/\n\s*push:/);
 assert.match(workflow,/secrets\.NOVA_SIGNING_KEYSTORE_B64/);
 assert.match(workflow,/secrets\.NOVA_SIGNING_STORE_PASSWORD/);
 assert.match(workflow,/secrets\.NOVA_SIGNING_KEY_ALIAS/);
 assert.match(workflow,/secrets\.NOVA_SIGNING_KEY_PASSWORD/);
 assert.match(workflow,/100000 \+ GITHUB_RUN_NUMBER/);
 assert.match(workflow,/app-release\.apk/);
 assert.match(workflow,/apksigner.*verify/);
 assert.match(workflow,/ai\.nova\.director\.stable/);
 assert.match(workflow,/testDebugUnitTest assembleRelease/);
 assert.doesNotMatch(workflow,/storePassword\s+["'][^"']+["']/);
});
