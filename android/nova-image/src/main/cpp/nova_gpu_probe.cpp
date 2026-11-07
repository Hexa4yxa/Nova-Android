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
    if (!library) {
        const char* error = dlerror();
        const std::string detail = error ? error : "dlopen вернул ошибку без описания";
        if (detail.find("not accessible for the namespace") != std::string::npos) {
            return "OpenCL заблокирован пространством библиотек Android (namespace)";
        }
        if (detail.find("not found") != std::string::npos ||
            detail.find("No such file") != std::string::npos) {
            return "OpenCL не найден среди доступных приложению библиотек";
        }
        return "OpenCL пока недоступен: " + detail.substr(0, 170);
    }
    using GetPlatformIds = int (*)(unsigned int, void**, unsigned int*);
    using GetDeviceIds = int (*)(void*, uint64_t, unsigned int, void**, unsigned int*);
    const auto platforms = reinterpret_cast<GetPlatformIds>(dlsym(library, "clGetPlatformIDs"));
    const auto devices = reinterpret_cast<GetDeviceIds>(dlsym(library, "clGetDeviceIDs"));
    using GetDeviceInfo = int (*)(void*, unsigned int, size_t, void*, size_t*);
    const auto deviceInfo = reinterpret_cast<GetDeviceInfo>(dlsym(library, "clGetDeviceInfo"));
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
                        if (deviceInfo) {
                            void* firstDevice = nullptr;
                            if (devices(platform, 4ULL, 1, &firstDevice, nullptr) == 0 && firstDevice) {
                                char deviceName[160] = {0};
                                // CL_DEVICE_NAME = 0x102B, querying metadata does not run GPU kernels.
                                if (deviceInfo(firstDevice, 0x102B, sizeof(deviceName) - 1, deviceName, nullptr) == 0) {
                                    message = std::string("GPU доступен: ") + deviceName + " (NOVA всё ещё рисует на CPU)";
                                }
                            }
                        }
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
