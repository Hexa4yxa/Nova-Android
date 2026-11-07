// Cheap, non-invasive OpenCL driver probe. No image model is allocated or changed.
// Probe != acceleration. Android vendor linker namespaces may hide an otherwise present driver.
#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <string>
#include <vector>

namespace {
std::string probe() {
    void* library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return "OpenCL runtime недоступен приложению";
    using GetPlatformIds = int (*)(unsigned int, void**, unsigned int*);
    using GetDeviceIds = int (*)(void*, uint64_t, unsigned int, void**, unsigned int*);
    const auto platforms = reinterpret_cast<GetPlatformIds>(dlsym(library, "clGetPlatformIDs"));
    const auto devices = reinterpret_cast<GetDeviceIds>(dlsym(library, "clGetDeviceIDs"));
    std::string message = "OpenCL: не удалось проверить GPU";
    if (platforms && devices) {
        unsigned int count = 0;
        if (platforms(0, nullptr, &count) == 0 && count > 0 && count < 32) {
            std::vector<void*> list(count);
            if (platforms(count,list.data(),nullptr)==0) {
                for(void* platform:list) {
                    unsigned int gpuCount=0;
                    // CL_DEVICE_TYPE_GPU = 1 << 2; not a device handle.
                    if (devices(platform,4ULL,0,nullptr,&gpuCount)==0 && gpuCount>0) {
                        message = "OpenCL GPU обнаружен (генератор пока использует CPU)";
                        break;
                    }
                }
                if (message=="OpenCL: не удалось проверить GPU") message="OpenCL есть, GPU для вычислений не подтверждён";
            }
        } else message="OpenCL runtime есть, платформы недоступны";
    }
    dlclose(library);
    return message;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_nova_image_NativeImageEngine_nativeGpuProbe(JNIEnv* env,jobject /*self*/) {
    try { return env->NewStringUTF(probe().c_str()); }
    catch (...) { return env->NewStringUTF("Не удалось проверить OpenCL без изменения генератора"); }
}
