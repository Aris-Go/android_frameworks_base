/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_NATIVEBRIDGE_H
#define ANDROID_NATIVEBRIDGE_H

#include <jni.h>
#include <utils/Mutex.h>

namespace android {

// Native-bridge interfaces to native-activity
typedef struct vm_itf_t vm_itf_t;
typedef struct {
    // Initialize native-bridge. Native-bridge's internal implementation must ensure MT safety
    // and that native-bridge is initialized only once. OK to call this interface for already
    // initialized native-bridge.
    //
    // Parameters:
    //   vm_itf [IN] the pointer to vm_itf_t callbacks
    // Returns:
    //   TRUE for initialization success, FALSE for initialization fail.
    bool  (*initialize   )(vm_itf_t* vm_itf);
    // Load a shared library that is supported by the native-bridge.
    //
    // Parameters:
    //   libpath [IN] path to the shared library
    //   flag [IN] the stardard RTLD_XXX defined in bionic dlfcn.h
    // Returns:
    //   The opaque handle of shared library if sucessful, otherwise NULL
    void* (*loadLibrary  )(const char* libpath, int flag);
    // Get a native-bridge trampoline for specified native method. The trampoline has same
    // sigature as the native method.
    //
    // Parameters:
    //   handle [IN] the handle returned from loadLibrary
    //   shorty [IN] short descriptor of native method
    //   len [IN] length of shorty
    // Returns:
    //   address of trampoline of successful, otherwise NULL
    void* (*getTrampoline)(void* handle, const char* name, const char* shorty, uint32_t len);
    // Check whether native library is valid and is for an ABI that is supported by native-bridge.
    //
    // Parameters:
    //   libpath [IN] path to the shared library
    // Returns:
    //   TRUE if library is supported by native-bridge, FALSE otherwise
    bool  (*isSupported  )(const char* libpath);
} nb_itf_t;

// Class that wraps the native-bridge interfaces
class NativeBridge {
public:
    static void* loadLibrary(const char* libpath, int flag);
    static void* getTrampoline(void* handle, const char* name, const char* shorty, uint32_t len);
    static bool  isSupported(const char* libpath);

private:
    static bool  init();
    static bool  initialized_;
    static nb_itf_t* nb_itf_;
    static Mutex lock_;
};

// Default library name for native-brdige
#define DEFAULT_NATIVE_BRIDGE "libnativebridge.so"
// Property that defines the library name of native-bridge
#define PROP_NATIVE_BRIDGE "persist.native.bridge"
// Property that enables native-bridge
#define PROP_ENABLE_NAIVE_BRIDGE "persist.enable.native.bridge"
// The symbol name exposed by native-bridge with the type of nb_itf_t
#define NATIVE_BRIDGE_ITF "NativeBridgeItf"

};  // namespace android

#endif  // ANDROID_NATIVEBRIDGE_H
