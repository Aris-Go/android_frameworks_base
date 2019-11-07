/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_TAG "WirelessDebuggingManager-JNI"

#define LOG_NDEBUG 0

#include <crypto/key_store.h>
#include "pairing/pairing_auth.h"

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include <utils/Log.h>

#include <string>
#include <vector>

namespace android {

// ----------------------------------------------------------------------------
namespace {

template <class T, class N>
class JSmartWrapper {
public:
    JSmartWrapper(JNIEnv* env, T* jData) :
        mEnv(env),
        mJData(jData) {
    }

    virtual ~JSmartWrapper() = default;

    const N* data() const {
        return mRawData;
    }

    jsize length() const {
        return mLength;
    }

protected:
    N* mRawData = nullptr;
    JNIEnv* mEnv = nullptr;
    T* mJData = nullptr;
    jsize mLength = 0;
};  // JSmartWrapper

class JStringUTFWrapper : public JSmartWrapper<jstring, const char> {
public:
    explicit JStringUTFWrapper(JNIEnv* env, jstring* str)
        : JSmartWrapper(env, str) {
        mRawData = env->GetStringUTFChars(*str, NULL);
        mLength = env->GetStringUTFLength(*str);
    }

    virtual ~JStringUTFWrapper() {
        if (data()) {
            mEnv->ReleaseStringUTFChars(*mJData, mRawData);
        }
    }
}; // JStringUTFWrapper

class JByteArrayWrapper : public JSmartWrapper<jbyteArray, jbyte> {
public:
    explicit JByteArrayWrapper(JNIEnv* env, jbyteArray* arr)
        : JSmartWrapper(env, arr) {
        mRawData = env->GetByteArrayElements(*arr, NULL);
        mLength = env->GetArrayLength(*arr);
    }

    virtual ~JByteArrayWrapper() {
        if (data()) {
            mEnv->ReleaseByteArrayElements(*mJData, mRawData, JNI_ABORT);
        }
    }
}; // JByteArrayWrapper

const char kKeystorePath[] = "/data/misc/adb";
std::unique_ptr<void, void (*)(void*)> createKeyStoreCtx() {
    return std::unique_ptr<void, void (*)(void*)>(
            keystore_new_ctx(kKeystorePath),
            keystore_delete_ctx);
}
PairingAuthCtx sPairingCtx = nullptr;

}  // namespace

static jboolean native_keystore_init(JNIEnv* env,
                                     jclass /* clazz */) {
    // Create and destroy a KeyStoreCtx to generate the device guid and the key
    // pair.
    auto keyStoreCtx = createKeyStoreCtx();
    if (keyStoreCtx == nullptr) {
        ALOGE("Unable to get keystore");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean native_pairing_init(JNIEnv* env,
                                    jclass /* clazz */,
                                    jstring password) {
    if (sPairingCtx != nullptr) {
        ALOGE("Already created a pairing context.");
        return JNI_FALSE;
    }

    // Create a new PairingAuthCtx.
    JStringUTFWrapper passwordWrapper(env, &password);
    sPairingCtx = pairing_auth_new_ctx(PairingRole::Server,
                                       reinterpret_cast<const uint8_t*>(passwordWrapper.data()),
                                       passwordWrapper.length());
    if (!sPairingCtx) {
        ALOGE("Unable to create a pairing context.");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static void native_pairing_destroy(JNIEnv* /* env */,
                                   jclass /* clazz */) {
    if (sPairingCtx == nullptr) {
        ALOGW("Attempted to destroy a non-existent pairing context.");
        return;
    }

    pairing_auth_delete_ctx(sPairingCtx);
}

static jbyteArray native_pairing_our_public_key(JNIEnv* env,
                                                jclass /* clazz */) {
    jbyteArray jkey = nullptr;
    if (sPairingCtx == nullptr) {
        ALOGE("Pairing context is null. Cannot get our public key.");
        return nullptr;
    }

    uint8_t key[pairing_auth_max_key_size(sPairingCtx)];
    int size = pairing_auth_our_public_key(sPairingCtx, key);
    if (size > 0) {
        jkey = env->NewByteArray(size);
        env->SetByteArrayRegion(jkey, 0, size, reinterpret_cast<jbyte*>(key));
    }

    return jkey;
}

// Parse a pairing request from the client. On success, return a pairing request
// of our own for the client to store our public certificate. If we failed do
// store it, return an empty byte array.
static jbyteArray native_pairing_parse_request(JNIEnv* env,
                                               jclass /* clazz */,
                                               jbyteArray pairing_request) {
    if (!sPairingCtx) {
        ALOGE("Pairing context is null. Cannot register their key.");
        return nullptr;
    }

    auto keyStoreCtx = createKeyStoreCtx();
    if (keyStoreCtx == nullptr) {
        ALOGE("Unable to get keystore");
        return nullptr;
    }

    JByteArrayWrapper request_wrapper(env, &pairing_request);
    if (request_wrapper.data() == NULL) {
        return nullptr;
    }

    PublicKeyHeader header;
    std::vector<char> public_key(keystore_max_certificate_size(keyStoreCtx.get()), '\0');
    bool valid = pairing_auth_parse_request(
                         sPairingCtx,
                         reinterpret_cast<const uint8_t*>(request_wrapper.data()),
                         request_wrapper.length(),
                         &header,
                         public_key.data());

    if (!valid) {
        ALOGW("Unable to parse pairing request. Rejecting the pairing.");
        return nullptr;
    }

    // The certificate may not be null-terminated. Ensure that it is.
    public_key.resize(header.payload + 1);
    public_key[header.payload] = '\0';

    // We got a valid pairing! Let's add it to the keystore and send our pairing
    // reuqest to the client.
    bool stored = keystore_store_public_key(keyStoreCtx.get(),
                                            &header,
                                            public_key.data());
    if (!stored) {
        ALOGE("Unable to store the client's public certificate.");
        return nullptr;
    }

    // Get the system's PublicKeyHeader and public key from the keystore
    // to build a pairing request.
    PublicKeyHeader system_key_header;
    keystore_public_key_header(keyStoreCtx.get(), &system_key_header);
    std::vector<char> system_key(keystore_max_certificate_size(keyStoreCtx.get()));
    uint32_t system_key_sz = keystore_system_public_key(keyStoreCtx.get(), system_key.data());
    if (system_key_sz == 0) {
        ALOGE("Unable to retrieve the system's public certificate.");
        return nullptr;
    }
    system_key.resize(system_key_sz);
    uint32_t pktSize = pairing_auth_request_max_size();
    std::vector<uint8_t> pkt(pktSize);
    if (!pairing_auth_create_request(sPairingCtx,
                                     &system_key_header,
                                     system_key.data(),
                                     pkt.data(),
                                     &pktSize)) {
        ALOGE("Unable to create pairing request packet.");
        return nullptr;
    }
    pkt.resize(pktSize);

    jbyteArray jrequest = nullptr;
    jrequest = env->NewByteArray(pktSize);
    env->SetByteArrayRegion(jrequest, 0, pktSize, reinterpret_cast<jbyte*>(pkt.data()));
    return jrequest;
}

//static jboolean native_pairing_register_their_key(JNIEnv* env,
//                                                  jclass /* clazz */,
//                                                  jbyteArray theirKey) {
//    if (!sPairingCtx) {
//        ALOGE("Pairing context is null. Cannot register their key.");
//        return JNI_FALSE;
//    }
//
//    JByteArrayWrapper theirKeyWrapper(env, &theirKey);
//    if (theirKeyWrapper.data() == NULL) {
//        return JNI_FALSE;
//    }
//
//    bool valid = pairing_auth_register_their_key(
//                         sPairingCtx,
//                         reinterpret_cast<const uint8_t*>(theirKeyWrapper.data()),
//                         theirKeyWrapper.length());
//    return valid ? JNI_TRUE : JNI_FALSE;
//}

//static jbyteArray native_pairing_decrypt(JNIEnv* env,
//                                         jclass /* clazz */,
//                                         jbyteArray data) {
//    jbyteArray ret = nullptr;
//    if (!sPairingCtx) {
//        ALOGE("Pairing context is null. Cannot decrypt message.");
//        return nullptr;
//    }
//
//    JByteArrayWrapper dataWrapper(env, &data);
//    if (dataWrapper.data() == NULL) {
//        return nullptr;
//    }
//
//    uint64_t sizeNeeded = pairing_auth_decrypted_size(sPairingCtx,
//                                                      reinterpret_cast<const uint8_t*>(dataWrapper.data()),
//                                                      dataWrapper.length());
//    std::vector<uint8_t> decrypted(sizeNeeded);
//    uint64_t decryptedSize = 0;
//    bool valid = pairing_auth_decrypt(
//                         sPairingCtx,
//                         reinterpret_cast<const uint8_t*>(dataWrapper.data()),
//                         dataWrapper.length(),
//                         decrypted.data(),
//                         &decryptedSize);
//
//    if (valid) {
//        ret = env->NewByteArray(decryptedSize);
//        env->SetByteArrayRegion(ret, 0, decryptedSize, reinterpret_cast<jbyte*>(decrypted.data()));
//    }
//
//    return ret;
//}

//static jbyteArray native_pairing_encrypt(JNIEnv* env,
//                                         jclass /* clazz */,
//                                         jbyteArray data) {
//    jbyteArray ret = nullptr;
//    if (!sPairingCtx) {
//        ALOGE("Pairing context is null. Cannot encrypt message.");
//        return nullptr;
//    }
//
//    JByteArrayWrapper dataWrapper(env, &data);
//    if (dataWrapper.data() == NULL) {
//        return nullptr;
//    }
//
//    uint64_t sizeNeeded = pairing_auth_encrypted_size(sPairingCtx,
//                                                      dataWrapper.length());
//    std::vector<uint8_t> encrypted(sizeNeeded);
//    uint64_t encryptedSize = 0;
//    bool valid = pairing_auth_encrypt(
//                         sPairingCtx,
//                         reinterpret_cast<const uint8_t*>(dataWrapper.data()),
//                         dataWrapper.length(),
//                         encrypted.data(),
//                         &encryptedSize);
//
//    if (valid) {
//        ret = env->NewByteArray(encryptedSize);
//        env->SetByteArrayRegion(ret, 0, encryptedSize, reinterpret_cast<jbyte*>(encrypted.data()));
//    }
//
//    return ret;
//}


// ----------------------------------------------------------------------------

static const JNINativeMethod gWirelessDebuggingManagerMethods[] = {
    /* name, signature, funcPtr */
    { "native_keystore_init", "()Z",
            (void*) native_keystore_init },
    { "native_pairing_init", "(Ljava/lang/String;)Z",
            (void*) native_pairing_init },
    { "native_pairing_destroy", "()V",
            (void*) native_pairing_destroy },
    { "native_pairing_parse_request", "([B)[B",
            (void*) native_pairing_parse_request },
    { "native_pairing_our_public_key", "()[B",
            (void*) native_pairing_our_public_key },
//    { "native_pairing_register_their_key", "([B)Z",
//            (void*) native_pairing_register_their_key },
//    { "native_pairing_decrypt", "([B)[B",
//            (void*) native_pairing_decrypt },
//    { "native_pairing_encrypt", "([B)[B",
//            (void*) native_pairing_encrypt },
};

int register_android_server_WirelessDebuggingManager(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/adb/WirelessDebuggingManager",
            gWirelessDebuggingManagerMethods, NELEM(gWirelessDebuggingManagerMethods));
    (void) res;  // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} /* namespace android */
