/*
 * Copyright (C) ST-Ericsson SA 2010
 * Copyright 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Authors: johan.xj.palmaeus@stericsson.com
 *          stuart.macdonald@stericsson.com
 *          for ST-Ericsson
 */

/*
 * Native part of the generic RX FmRadio inteface
 */

#define LOG_TAG "FmReceiverServiceNative"

#define LOG_NDEBUG 1

#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <termios.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <stdarg.h>
#include <signal.h>
#include <pthread.h>
#include <math.h>


#include "jni.h"
#include "JNIHelp.h"
#include "android_fmradio.h"
#include "utils/Log.h"


/* *INDENT-OFF* */
namespace android {


// state machine

static const ValidEventsForStates_t IsValidRxEventForState = {
  /* this table defines valid transitions. (turn off indent, we want this easy readable) */
             /* FMRADIO_STATE_ IDLE,STARTING,STARTED,PAUSED,SCANNING,E_C,RESETTING */

   /* FMRADIO_EVENT_START */         {true ,false,false,false,false,false,false},
   /* FMRADIO_EVENT_START_ASYNC */   {true ,false,false,false,false,false,false},
   /* FMRADIO_EVENT_PAUSE */         {false,false,true, true, false,false,false},
   /* FMRADIO_EVENT_RESUME */        {false,false,true, true, false,false,false},
   /* FMRADIO_EVENT_RESET */         {true, true, true, true, true, true, false},
   /* FMRADIO_EVENT_FORCED_PAUSE */  {true, true, true, true, true, true, false},
   /* FMRADIO_EVENT_GET_PARAMETER */ {false,false,true, true, false,false,false},
   /* FMRADIO_EVENT_SET_PARAMETER */ {false,false,true, true, false,false,false},
   /* FMRADIO_EVENT_STOP_SCAN */     {true, true, true, true, true, true, false},
   /* FMRADIO_EVENT_EXTRA_COMMAND */ {true, true, true, true, true, true, false},
   /* Rx Only */
   /* FMRADIO_EVENT_GET_SIGNAL_STRENGTH */{false,false,true,false,false,false,false},
   /* FMRADIO_EVENT_SCAN */          {false,false,true, true, false,false,false},
   /* FMRADIO_EVENT_FULL_SCAN */     {false,false,true, true, false,false,false},
   // Tx Only - never allowed
   /* FMRADIO_EVENT_BLOCK_SCAN */    {false,false,false,false,false,false, false},
};
/*  *INDENT-ON*  */

/* Callbacks to java layer */

static void androidFmRadioRxCallbackOnStateChanged(int newState,
                                                   int oldState);
static void androidFmRadioRxCallbackOnError(void);

static void androidFmRadioRxCallbackOnStarted(void);

static void androidFmRadioRxCallbackOnScan(int foundFreq,
                                           int signalStrength,
                                           bool aborted);
static void androidFmRadioRxCallbackOnFullScan(int noItems,
                                               int *frequencies,
                                               int *sigStrengths,
                                               bool aborted);
static void androidFmRadioRxCallbackOnForcedReset(enum fmradio_reset_reason_t reason);

static void androidFmRadioRxCallbackOnVendorForcedReset(enum fmradio_reset_reason_t reason);

static void androidFmRadioRxCallbackOnSignalStrengthChanged(int newLevel);

static void androidFmRadioRxCallbackOnRDSDataFound(struct
                                                   fmradio_rds_bundle_t
                                                   *t, int frequency);

static void androidFmRadioRxCallbackOnPlayingInStereo(int
                                                      isPlayingInStereo);

static void androidFmRadioRxCallbackOnExtraCommand(char* command,
                                                   struct
                                                   fmradio_extra_command_ret_item_t
                                                   *retItem);

static void androidFmRadioRxCallbackOnAutomaticSwitch(int newFrequency, enum fmradio_switch_reason_t reason);

static const FmRadioCallbacks_t FmRadioRxCallbacks = {
    androidFmRadioRxCallbackOnStateChanged,
    androidFmRadioRxCallbackOnError,
    androidFmRadioRxCallbackOnStarted,
    androidFmRadioRxCallbackOnScan,
    androidFmRadioRxCallbackOnFullScan,
    NULL,
    androidFmRadioRxCallbackOnForcedReset,
    androidFmRadioRxCallbackOnExtraCommand,
};

/* callbacks from vendor layer */

static const fmradio_vendor_callbacks_t FmRadioRxVendorCallbacks = {
    androidFmRadioRxCallbackOnPlayingInStereo,
    androidFmRadioRxCallbackOnRDSDataFound,
    androidFmRadioRxCallbackOnSignalStrengthChanged,
    androidFmRadioRxCallbackOnAutomaticSwitch,
    androidFmRadioRxCallbackOnVendorForcedReset
};

extern struct FmSession_t fmTransmitterSession;

struct FmSession_t fmReceiverSession = {
    NULL,
    false,
    FMRADIO_STATE_IDLE,
    NULL,
    &IsValidRxEventForState,
    &FmRadioRxCallbacks,
    NULL,
    NULL,
    &fmTransmitterSession,
    NULL,
    FMRADIO_STATE_IDLE,
    false,
    false,
    false,
    &rx_tx_common_mutex,
    NULL,
};

// make sure we don't refer the TransmitterSession anymore from here
#define fmTransmitterSession ERRORDONOTUSERECEIVERSESSIONINTRANSMITTER

/*
* Implementation of callbacks from within service layer. For these the
*  mutex lock is always held on entry and need to be released before doing
*  calls to java layer (env->Call*Method)  becasue these might trigger new
*  calls from java and a deadlock would occure if lock was still held.
*/

static void androidFmRadioRxCallbackOnStateChanged(int newState,
                                                   int oldState)
{
    jmethodID notifyOnStateChangedMethod;
    JNIEnv *env;
    jclass clazz;
    bool reAttached = false;

    LOGI("androidFmRadioRxCallbackOnStateChanged: New state %d, old state %d\n", newState, oldState);

    /* since we might be both in main thread and subthread both test getenv
     * and attach */
    if (fmReceiverSession.jvm_p->GetEnv((void **) &env, JNI_VERSION_1_4) !=
        JNI_OK) {
        reAttached = true;
        if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
            LOGE("Error, can't attch current thread\n");
            return;
        }
    }

    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);

    notifyOnStateChangedMethod =
        env->GetMethodID(clazz, "notifyOnStateChanged", "(II)V");
    if (notifyOnStateChangedMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj,
                            notifyOnStateChangedMethod, newState,
                            oldState);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    } else {
        LOGE("ERROR - JNI can't find java notifyOnStateChanged method\n");
    }

    if (reAttached) {
        fmReceiverSession.jvm_p->DetachCurrentThread();
    }
}

static void androidFmRadioRxCallbackOnError(void)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;

    LOGI("androidFmRadioRxCallbackOnError\n");

    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        return;
    }

    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);
    notifyMethod = env->GetMethodID(clazz, "notifyOnError", "()V");

    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    } else {
        LOGE("ERROR - JNI can't find java notifyOnError method\n");
    }

    fmReceiverSession.jvm_p->DetachCurrentThread();
}

static void androidFmRadioRxCallbackOnStarted(void)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;

    LOGI("androidFmRadioRxCallbackOnStarted\n");

    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        return;
    }

    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);
    notifyMethod = env->GetMethodID(clazz, "notifyOnStarted", "()V");

    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    } else {
        LOGE("ERROR - JNI can't find java notifyOnStarted method\n");
    }

    fmReceiverSession.jvm_p->DetachCurrentThread();
}


static void androidFmRadioRxCallbackOnScan(int foundFreq,
                                           int signalStrength,
                                           bool aborted)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;

    LOGI("androidFmRadioRxCallbackOnScan: Callback foundFreq %d, signalStrength %d, aborted %u\n", foundFreq, signalStrength, aborted);

    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        return;
    }

    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);

    notifyMethod = env->GetMethodID(clazz, "notifyOnScan", "(IIZ)V");

    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod,
                            foundFreq, signalStrength, aborted);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    } else {
        LOGE("ERROR - JNI can't find java notifyOnScan method\n");
    }

    fmReceiverSession.jvm_p->DetachCurrentThread();
}

static void androidFmRadioRxCallbackOnFullScan(int noItems,
                                               int *frequencies,
                                               int *sigStrengths,
                                               bool aborted)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;
    jintArray jFreqs;
    jintArray jSigStrengths;

    int d;

    LOGI("androidFmRadioRxCallbackOnFullScan: No items %d, aborted %d\n",
         noItems, aborted);

    for (d = 0; d < noItems; d++) {
        LOGI("%d -> %d\n", frequencies[d], sigStrengths[d]);
    }

    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        return;
    }

    jFreqs = env->NewIntArray(noItems);
    jSigStrengths = env->NewIntArray(noItems);
    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);

    env->SetIntArrayRegion(jFreqs, 0, noItems, frequencies);
    env->SetIntArrayRegion(jSigStrengths, 0, noItems, sigStrengths);


    notifyMethod = env->GetMethodID(clazz, "notifyOnFullScan", "([I[IZ)V");

    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

        env->CallVoidMethod(jobj, notifyMethod,
                            jFreqs, jSigStrengths, aborted);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    } else {
        LOGE("ERROR - JNI can't find java notifyOnFullScan method\n");
    }

    fmReceiverSession.jvm_p->DetachCurrentThread();
}

static void androidFmRadioRxCallbackOnForcedReset(enum fmradio_reset_reason_t reason)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;
    bool reAttached = false;

    LOGI("androidFmRadioRxCallbackOnForcedReset\n");

    if (fmReceiverSession.jvm_p->GetEnv((void **) &env, JNI_VERSION_1_4) !=
        JNI_OK) {
        reAttached = true;
        if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
            LOGE("Error, can't attch current thread\n");
            return;
        }
    }

    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);

    notifyMethod = env->GetMethodID(clazz, "notifyOnForcedReset", "(I)V");
    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod,
                            reason);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    }

    if (reAttached) {
        fmReceiverSession.jvm_p->DetachCurrentThread();
    }
}

static void androidFmRadioRxCallbackOnVendorForcedReset(enum fmradio_reset_reason_t reason)
{

    LOGI("androidFmRadioRxCallbackOnVendorForcedReset\n");
    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    if (fmReceiverSession.state != FMRADIO_STATE_IDLE) {
        FMRADIO_SET_STATE(&fmReceiverSession, FMRADIO_STATE_IDLE);
    }
    fmReceiverSession.callbacks_p->onForcedReset(reason);
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}


static void androidFmRadioRxCallbackOnExtraCommand(char* command,
                                                   struct
                                                   fmradio_extra_command_ret_item_t
                                                   *retList)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;

    struct bundle_descriptor_offsets_t *bundle_p =
        fmReceiverSession.bundleOffsets_p;

    LOGI("androidFmRadioRxCallbackOnSendExtraCommand\n");

    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        return;
    }

    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);
    jobject retBundle = extraCommandRetList2Bundle(env, bundle_p, retList);
    jstring jcommand = env->NewStringUTF(command);

    notifyMethod =
        env->GetMethodID(clazz, "notifyOnExtraCommand",
                         "(Ljava/lang/String;Landroid/os/Bundle;)V");
    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

        env->CallVoidMethod(jobj, notifyMethod, jcommand, retBundle);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    }

    fmReceiverSession.jvm_p->DetachCurrentThread();
}

/*
* Implementation of callbacks from vendor layer. For these the  mutex lock
* is NOT held on entry and need to be taken and released before doing
*  calls to java layer (env->Call*Method)  becasue these might trigger new
*  calls from java and a deadlock would occure
*/

static void
androidFmRadioRxCallbackOnRDSDataFound(struct fmradio_rds_bundle_t *t,
                                       int frequency)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;
    jobject bundle;
    jshortArray jsArr;

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    struct bundle_descriptor_offsets_t *bundle_p =
        fmReceiverSession.bundleOffsets_p;

    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        goto drop_lock;
    }
    bundle = env->NewObject(bundle_p->mClass,
                                    bundle_p->mConstructor);
    /* note, these calls are to predefined methods, no need to release lock */
    env->CallVoidMethod(bundle, bundle_p->mPutShort,
                        env->NewStringUTF("PI"), t->pi);
    env->CallVoidMethod(bundle, bundle_p->mPutShort,
                        env->NewStringUTF("TP"), t->tp);
    env->CallVoidMethod(bundle, bundle_p->mPutShort,
                        env->NewStringUTF("PTY"), t->pty);
    env->CallVoidMethod(bundle, bundle_p->mPutShort,
                        env->NewStringUTF("TA"), t->ta);
    env->CallVoidMethod(bundle, bundle_p->mPutShort,
                        env->NewStringUTF("M/S"), t->ms);

    if (t->num_afs > 0 && t->num_afs < RDS_MAX_AFS) {
        jintArray jArr = env->NewIntArray(t->num_afs);
        env->SetIntArrayRegion(jArr, 0, t->num_afs, t->af);
        env->CallVoidMethod(bundle, bundle_p->mPutIntArray,
                            env->NewStringUTF("AF"), jArr);
    }
    env->CallVoidMethod(bundle, bundle_p->mPutString,
                        env->NewStringUTF("PSN"),
                        env->NewStringUTF(t->psn));
    env->CallVoidMethod(bundle, bundle_p->mPutString,
                        env->NewStringUTF("RT"),
                        env->NewStringUTF(t->rt));
    env->CallVoidMethod(bundle, bundle_p->mPutString,
                        env->NewStringUTF("CT"),
                        env->NewStringUTF(t->ct));
    env->CallVoidMethod(bundle, bundle_p->mPutString,
                        env->NewStringUTF("PTYN"),
                        env->NewStringUTF(t->ptyn));

    jsArr = env->NewShortArray(3);

    env->SetShortArrayRegion(jsArr, 0, 3, t->tmc);
    env->CallVoidMethod(bundle, bundle_p->mPutShortArray,
                        env->NewStringUTF("TMC"), jsArr);

    env->CallVoidMethod(bundle, bundle_p->mPutInt,
                        env->NewStringUTF("TAF"), t->taf);

    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);

    notifyMethod =
        env->GetMethodID(clazz, "notifyOnRDSDataFound",
                         "(Landroid/os/Bundle;I)V");
    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod,
                            bundle, frequency);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    }
    fmReceiverSession.jvm_p->DetachCurrentThread();

 drop_lock:
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

static void androidFmRadioRxCallbackOnSignalStrengthChanged(int newLevel)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        goto drop_lock;
    }
    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);
    notifyMethod =
        env->GetMethodID(clazz, "notifyOnSignalStrengthChanged", "(I)V");
    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod,
                            newLevel);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    }

    fmReceiverSession.jvm_p->DetachCurrentThread();
 drop_lock:
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

static void androidFmRadioRxCallbackOnPlayingInStereo(int
                                                      isPlayingInStereo)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;

    LOGI("androidFmRadioRxCallbackOnPlayingInStereo (%d)\n",
         isPlayingInStereo);

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        goto drop_lock;
    }
    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);
    notifyMethod =
        env->GetMethodID(clazz, "notifyOnPlayingInStereo", "(Z)V");
    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod,
                            (bool) isPlayingInStereo);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    }

    fmReceiverSession.jvm_p->DetachCurrentThread();
 drop_lock:
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

/*
 * currently frequency changed event is not supported by interface, to be
 * implemented quite soon...
 */

static void androidFmRadioRxCallbackOnAutomaticSwitch(int newFrequency, enum fmradio_switch_reason_t reason)
{
    jmethodID notifyMethod;
    JNIEnv *env;
    jclass clazz;

    LOGI("androidFmRadioRxCallbackOnAutomaticSwitch: new frequency %d, reason %d\n",
         newFrequency, (int) reason);

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    if (fmReceiverSession.jvm_p->AttachCurrentThread(&env, NULL) != JNI_OK) {
        LOGE("Error, can't attch current thread\n");
        goto drop_lock;
    }
    clazz = env->GetObjectClass(*fmReceiverSession.jobj_p);
    notifyMethod =
        env->GetMethodID(clazz, "notifyOnAutomaticSwitching", "(II)V");
    if (notifyMethod != NULL) {
        jobject jobj = *fmReceiverSession.jobj_p;
        pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
        env->CallVoidMethod(jobj, notifyMethod, (jint)newFrequency,
                            (jint)reason);
        pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    }


    fmReceiverSession.jvm_p->DetachCurrentThread();
 drop_lock:
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

/*
 *  function calls from java layer.
 */

static jint androidFmRadioRxGetState(JNIEnv * env, jobject obj)
{
    FmRadioState_t state;

    LOGI("androidFmRadioRxGetState, state\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    state = fmReceiverSession.state;
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
    return state;
}

/* common ones with tx, just forward to the generic androidFmRadioxxxxx version */

static void
androidFmRadioRxStart(JNIEnv * env, jobject obj, int lowFreq,
                      int highFreq, int defaultFreq, int grid)
{
    LOGI("androidFmRadioRxStart. LowFreq %d, HighFreq %d, DefaultFreq %d, grid %d.\n", lowFreq, highFreq, defaultFreq, grid);
    if (fmReceiverSession.jobj_p == NULL)
        fmReceiverSession.jobj_p =
            (jobject *) malloc(sizeof(*fmReceiverSession.jobj_p));
    *fmReceiverSession.jobj_p = obj;
    (void) androidFmRadioStart(&fmReceiverSession, FMRADIO_RX,
                               &FmRadioRxVendorCallbacks, false, lowFreq,
                               highFreq, defaultFreq, grid);
}


static void
androidFmRadioRxStartAsync(JNIEnv * env, jobject obj, int lowFreq,
                           int highFreq, int defaultFreq, int grid)
{
    LOGI("androidFmRadioRxStartAsync...\n");
    if (fmReceiverSession.jobj_p == NULL)
        fmReceiverSession.jobj_p =
            (jobject *) malloc(sizeof(*fmReceiverSession.jobj_p));
    *fmReceiverSession.jobj_p = obj;
    (void) androidFmRadioStart(&fmReceiverSession, FMRADIO_RX,
                               &FmRadioRxVendorCallbacks, true, lowFreq,
                               highFreq, defaultFreq, grid);
}

static void androidFmRadioRxPause(JNIEnv * env, jobject obj)
{
    LOGI("androidFmRadioRxPause\n");

    androidFmRadioPause(&fmReceiverSession);
}

static void androidFmRadioRxResume(JNIEnv * env, jobject obj)
{
    LOGI("androidFmRadioRxResume\n");
    androidFmRadioResume(&fmReceiverSession);
}

static jint androidFmRadioRxReset(JNIEnv * env, jobject obj)
{
    int retval = 0;

    LOGI("androidFmRadioRxReset\n");
    retval = androidFmRadioReset(&fmReceiverSession);

    return retval;
}

static void
androidFmRadioRxSetFrequency(JNIEnv * env, jobject obj, jint frequency)
{
    LOGI("androidFmRadioRxSetFrequency tuneTo:%d\n", (int) frequency);
    return androidFmRadioSetFrequency(&fmReceiverSession, (int) frequency);
}

static jint androidFmRadioRxGetFrequency(JNIEnv * env, jobject obj)
{
    LOGI("androidFmRadioRxGetFrequency:\n");
    return androidFmRadioGetFrequency(&fmReceiverSession);
}

static jint androidFmRadioRxForcedPause(JNIEnv * env, jobject obj)
{
    LOGI("FMRadioRxForcedPaused\n");
    return androidFmRadioForcedPause(&fmReceiverSession);
}

static void androidFmRadioRxStopScan(JNIEnv * env, jobject obj)
{
    LOGI("androidFmRadioRxStopScan\n");
    androidFmRadioStopScan(&fmReceiverSession);
}

/* the rest of the calls are specific for RX */

static jint androidFmRadioRxGetSignalStrength(JNIEnv * env, jobject obj)
{
    int retval = -1;

    LOGI("androidFmRadioRxGetSignalStrength\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_GET_SIGNAL_STRENGTH)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }

    if (fmReceiverSession.vendorMethods_p->get_signal_strength) {
        /* if in pause state temporary resume */
        androidFmRadioTempResumeIfPaused(&fmReceiverSession);

        retval =
            fmReceiverSession.vendorMethods_p->
            get_signal_strength(&fmReceiverSession.vendorData_p);

        androidFmRadioPauseIfTempResumed(&fmReceiverSession);
    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }

  drop_lock:
    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    return retval;
}

static jboolean
androidFmRadioRxIsSignalStrengthSupported(JNIEnv * env, jobject obj)
{
    bool retval;

    LOGI("androidFmRadioRxIsSignalStrengthSupported:\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    /* if we haven't register we don't know yet */
    if (!fmReceiverSession.isRegistered) {
        retval = false;
        goto drop_lock;
    }
    // valid in all states
    if (fmReceiverSession.vendorMethods_p->get_signal_strength != NULL) {
        retval = true;
    } else {
        retval = false;
    }

  drop_lock:
    /* no exceptions, just return false if unregistred */

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    return retval;
}

static jboolean
androidFmRadioRxIsPlayingInStereo(JNIEnv * env, jobject obj)
{
    bool retval;

    LOGI("androidFmRadioRxIsPlayingInStereo:\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    /* if we haven't register we don't know yet */
    if (!fmReceiverSession.isRegistered) {
        retval = false;
        goto drop_lock;
    }
    // valid in all states
    if (fmReceiverSession.vendorMethods_p->is_playing_in_stereo != NULL) {
        retval =
            fmReceiverSession.vendorMethods_p->
            is_playing_in_stereo(&fmReceiverSession.vendorData_p);
    } else {
        retval = false;
    }

  drop_lock:

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    return retval;
}

static jboolean
androidFmRadioRxIsRDSDataSupported(JNIEnv * env, jobject obj)
{
    bool retval;

    LOGI("androidFmRadioRxIsRDSDataSupported:\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    /* if we haven't register we don't know yet */
    if (!fmReceiverSession.isRegistered) {
        retval = false;
        goto drop_lock;
    }
    // valid in all states
    if (fmReceiverSession.vendorMethods_p->is_rds_data_supported != NULL) {
        retval =
            fmReceiverSession.vendorMethods_p->
            is_rds_data_supported(&fmReceiverSession.vendorData_p);
    } else {
        retval = false;
    }

  drop_lock:

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
    return retval;
}

static jboolean
androidFmRadioRxIsTunedToValidChannel(JNIEnv * env, jobject obj)
{
    bool retval;

    LOGI("androidFmRadioRxIsTunedToValidChannel:\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    /* if we haven't register we don't know yet */
    if (!fmReceiverSession.isRegistered) {
        retval = false;
        goto drop_lock;
    }
    // valid in all states
    if (fmReceiverSession.vendorMethods_p->is_tuned_to_valid_channel != NULL) {
        retval =
            fmReceiverSession.vendorMethods_p->
            is_tuned_to_valid_channel(&fmReceiverSession.vendorData_p);
    } else {
        retval = false;
    }

  drop_lock:

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
    return retval;
}

static void *execute_androidFmRadioRxScan(void *args)
{
    enum fmradio_seek_direction_t seekDirection =
        *(enum fmradio_seek_direction_t *) args;
    int signalStrength = -1;
    int retval;
    enum FmRadioState_t oldState;

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    // we should still be in SCANNING mode, but we can't be 100.00 % sure since main thread released lock
    // before we could run

    if (fmReceiverSession.state != FMRADIO_STATE_SCANNING) {
        LOGE("execute_androidFmRadioRxScan - warning, state not scanning\n");
    }

    oldState = fmReceiverSession.oldState;

    // temporary resume chip if sleeping
    if (oldState == FMRADIO_STATE_PAUSED) {
        (void) fmReceiverSession.
            vendorMethods_p->resume(&fmReceiverSession.vendorData_p);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    retval =
        fmReceiverSession.vendorMethods_p->scan(&fmReceiverSession.
                                                vendorData_p,
                                                seekDirection);


    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    /*
     * if state has changed we should keep it, probably a forced reset
     */
    if (fmReceiverSession.state != FMRADIO_STATE_SCANNING) {
        LOGI("State changed while scanning (state now %d), keeping\n",
             fmReceiverSession.state);
        retval = -1;
    } else {
        // put back to sleep if we did a temporary wake-up
        if ((oldState == FMRADIO_STATE_PAUSED
             || fmReceiverSession.pendingPause))
            (void) fmReceiverSession.
                vendorMethods_p->pause(&fmReceiverSession.vendorData_p);
        if (fmReceiverSession.pendingPause) {
            FMRADIO_SET_STATE(&fmReceiverSession, FMRADIO_STATE_PAUSED);
        } else {
            FMRADIO_SET_STATE(&fmReceiverSession, oldState);
        }

        if (retval >= 0) {
            // also getsignal strength (if supported)

            if (fmReceiverSession.vendorMethods_p->get_signal_strength)
                signalStrength =
                    fmReceiverSession.vendorMethods_p->
                    get_signal_strength(&fmReceiverSession.vendorData_p);
        }
    }

    fmReceiverSession.pendingPause = false;

    if (retval >= 0) {
        fmReceiverSession.callbacks_p->onScan(retval,
                                              signalStrength,
                                              fmReceiverSession.
                                              lastScanAborted);
    } else {
        fmReceiverSession.callbacks_p->onError();
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    pthread_exit(NULL);
    return NULL;
}


static void androidFmRadioRxScan(enum fmradio_seek_direction_t seekDirection)
{
    int retval = 0;

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_SCAN)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }

    if (fmReceiverSession.vendorMethods_p->scan) {
        enum fmradio_seek_direction_t *seekDirectionParam_p =
            (enum fmradio_seek_direction_t *)
            malloc(sizeof(*seekDirectionParam_p));

        pthread_t execute_thread;

        // we need to create a new thread actually executing the command

        fmReceiverSession.oldState = fmReceiverSession.state;
        FMRADIO_SET_STATE(&fmReceiverSession, FMRADIO_STATE_SCANNING);
        *seekDirectionParam_p = seekDirection;

        fmReceiverSession.lastScanAborted = false;

        if (pthread_create
            (&execute_thread, NULL, execute_androidFmRadioRxScan,
             (void *) seekDirectionParam_p) != 0) {

            LOGE("pthread_create failure...\n");
            FMRADIO_SET_STATE(&fmReceiverSession, FMRADIO_STATE_IDLE);
            retval = FMRADIO_IO_ERROR;
        }

    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }

  drop_lock:
    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    if (retval < 0) {
        LOGE("androidFmRadioRxScan failed\n");
    }
}

static void
androidFmRadioRxScanUp(JNIEnv * env, jobject obj, jlong * frequency)
{
    LOGI("androidFmRadioRxScanUp\n");

    androidFmRadioRxScan(FMRADIO_SEEK_UP);
}

static void
androidFmRadioRxScanDown(JNIEnv * env, jobject obj, jlong * frequency)
{
    LOGI("androidFmRadioRxScanDown\n");

    androidFmRadioRxScan(FMRADIO_SEEK_DOWN);
}

static void *execute_androidFmRadioRxFullScan(void *args)
{
    int retval;
    enum FmRadioState_t oldState = fmReceiverSession.oldState;
    int *frequencies_p = NULL;
    int *rssi_p = NULL;

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    // we should still be in SCANNING mode, but we can't be 100.00 % sure since main thread released lock
    // before we could run

    if (fmReceiverSession.state != FMRADIO_STATE_SCANNING) {
        LOGE("execute_androidFmRadioRxScan - warning, state not scanning\n");
    }
    // temporary resume chip if sleeping
    if (oldState == FMRADIO_STATE_PAUSED) {
        (void) fmReceiverSession.
            vendorMethods_p->resume(&fmReceiverSession.vendorData_p);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    retval =
        fmReceiverSession.vendorMethods_p->full_scan(&fmReceiverSession.
                                                    vendorData_p,
                                                    &frequencies_p,
                                                    &rssi_p);

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    /*
     * if state has changed we should keep it, probably a forced pause or
     * forced reset
     */
    if (fmReceiverSession.state != FMRADIO_STATE_SCANNING) {
        LOGI("State changed while scanning (state now %d), keeping\n",
             fmReceiverSession.state);
        retval = -1;
    } else {
        if (fmReceiverSession.pendingPause) {
            FMRADIO_SET_STATE(&fmReceiverSession, FMRADIO_STATE_PAUSED);
        } else {
            FMRADIO_SET_STATE(&fmReceiverSession, oldState);
        }

        fmReceiverSession.pendingPause = false;
    }

    if (retval >= 0) {
        fmReceiverSession.callbacks_p->onFullScan(retval,
                                                  frequencies_p,
                                                  rssi_p,
                                                  fmReceiverSession.
                                                  lastScanAborted);
    } else {
        fmReceiverSession.callbacks_p->onError();
    }

    if (frequencies_p != NULL) {
        free(frequencies_p);
    }

    if (rssi_p != NULL) {
        free(rssi_p);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    pthread_exit(NULL);
    return NULL;
}

static void androidFmRadioRxStartFullScan(JNIEnv * env, jobject obj)
{
    LOGI("androidFmRadioRxStartFullScan\n");
    int retval = 0;

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_FULL_SCAN)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }


    if (fmReceiverSession.vendorMethods_p->full_scan) {
        pthread_t execute_thread;

        fmReceiverSession.oldState = fmReceiverSession.state;
        FMRADIO_SET_STATE(&fmReceiverSession, FMRADIO_STATE_SCANNING);
        fmReceiverSession.lastScanAborted = false;

        if (pthread_create
            (&execute_thread, NULL, execute_androidFmRadioRxFullScan,
             NULL) != 0) {

            LOGE("pthread_create failure...\n");
            FMRADIO_SET_STATE(&fmReceiverSession, FMRADIO_STATE_IDLE);
            retval = FMRADIO_IO_ERROR;
        }
    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }

  drop_lock:
    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

static void androidFmRadioRxSetAutomaticAFSwitching(JNIEnv * env,
                                                  jobject obj,
                                                  jboolean automatic)
{
    int retval = -1;

    LOGI("androidFmRadioRxSetAutomaticAFSwitching\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_SET_PARAMETER)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }


    if (fmReceiverSession.vendorMethods_p->set_automatic_af_switching) {
        retval =
            fmReceiverSession.vendorMethods_p->
            set_automatic_af_switching(&fmReceiverSession.vendorData_p, automatic);
    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }

  drop_lock:
    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

static void androidFmRadioRxSetAutomaticTASwitching(JNIEnv * env, jobject obj,
                                                    jboolean automatic)
{
    int retval = -1;

    LOGI("androidFmRadioRxSetAutomaticTASwitching\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_SET_PARAMETER)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }


    if (fmReceiverSession.vendorMethods_p->set_automatic_ta_switching) {
        retval =
            fmReceiverSession.vendorMethods_p->
            set_automatic_ta_switching(&fmReceiverSession.vendorData_p, automatic);
    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }

  drop_lock:
    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

static void androidFmRadioRxSetForceMono(JNIEnv * env, jobject obj,
                                         jboolean forceMono)
{
    int retval = -1;

    LOGI("androidFmRadioRxSetForceMono\n");

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_SET_PARAMETER)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }


    if (fmReceiverSession.vendorMethods_p->set_force_mono) {
        /* if in pause state temporary resume */
        androidFmRadioTempResumeIfPaused(&fmReceiverSession);

        retval =
            fmReceiverSession.vendorMethods_p->
            set_force_mono(&fmReceiverSession.vendorData_p, forceMono);

        androidFmRadioPauseIfTempResumed(&fmReceiverSession);
    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }

  drop_lock:
    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }

    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

static void
androidFmRadioRxSetThreshold(JNIEnv * env, jobject obj, jint threshold)
{
    int retval;

    LOGI("androidFmRadioRxSetThreshold threshold:%d\n", (int) threshold);

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_SET_PARAMETER)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }


    if (fmReceiverSession.vendorMethods_p->set_threshold) {
        /* if in pause state temporary resume */
        androidFmRadioTempResumeIfPaused(&fmReceiverSession);

        retval =
            fmReceiverSession.
            vendorMethods_p->set_threshold(&fmReceiverSession.vendorData_p,
                                          threshold);
        /* if in pause state temporary resume */
        androidFmRadioPauseIfTempResumed(&fmReceiverSession);
    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }

    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }

  drop_lock:
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
}

static jint androidFmRadioRxGetThreshold(JNIEnv * env, jobject obj)
{
    int retval;

    LOGI("androidFmRadioRxGetThreshold\n");
    pthread_mutex_lock(fmReceiverSession.dataMutex_p);

    if (!androidFmRadioIsValidEventForState
        (&fmReceiverSession, FMRADIO_EVENT_GET_PARAMETER)) {
        retval = FMRADIO_INVALID_STATE;
        goto drop_lock;
    }

    if (fmReceiverSession.vendorMethods_p->get_threshold) {
        /* if in pause state temporary resume */
        androidFmRadioTempResumeIfPaused(&fmReceiverSession);
        retval =
            fmReceiverSession.
            vendorMethods_p->get_threshold(&fmReceiverSession.vendorData_p);
        androidFmRadioPauseIfTempResumed(&fmReceiverSession);
    } else {
        retval = FMRADIO_UNSUPPORTED_OPERATION;
    }
  drop_lock:

    if (retval == FMRADIO_INVALID_STATE) {
        THROW_INVALID_STATE(&fmReceiverSession);
    } else if (retval < 0) {
        THROW_IO_ERROR(&fmReceiverSession);
    }
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);

    return retval;
}

static jboolean androidFmRadioRxSendExtraCommand(JNIEnv * env, jobject obj,
                                                 jstring command,
                                                 jobjectArray parameters)
{
    LOGI("androidFmRadioRxSendExtraCommand\n");

/* we need to set jobj since this might be called before start */
    if (fmReceiverSession.jobj_p == NULL) {
        fmReceiverSession.jobj_p =
            (jobject *) malloc(sizeof(*fmReceiverSession.jobj_p));
    }
    *fmReceiverSession.jobj_p = obj;

    androidFmRadioSendExtraCommand(&fmReceiverSession, env, command,
                                   parameters);

    return true;
}


static JNINativeMethod gMethods[] = {
    {"_fm_receiver_getState", "()I",
     (void *) androidFmRadioRxGetState},
    {"_fm_receiver_start", "(IIII)V", (void *) androidFmRadioRxStart},
    {"_fm_receiver_startAsync", "(IIII)V",
     (void *) androidFmRadioRxStartAsync},
    {"_fm_receiver_pause", "()V", (void *) androidFmRadioRxPause},
    {"_fm_receiver_resume", "()V", (void *) androidFmRadioRxResume},
    {"_fm_receiver_reset", "()I", (void *) androidFmRadioRxReset},
    {"_fm_receiver_setFrequency", "(I)V",
     (void *) androidFmRadioRxSetFrequency},
    {"_fm_receiver_getFrequency", "()I",
     (void *) androidFmRadioRxGetFrequency},
    {"_fm_receiver_forcedPause", "()I",
     (void *) androidFmRadioRxForcedPause},
    {"_fm_receiver_getSignalStrength", "()I",
     (void *) androidFmRadioRxGetSignalStrength},
    {"_fm_receiver_isSignalStrengthSupported", "()Z",
     (void *) androidFmRadioRxIsSignalStrengthSupported},
    {"_fm_receiver_scanUp", "()V", (void *) androidFmRadioRxScanUp},
    {"_fm_receiver_scanDown", "()V",
     (void *) androidFmRadioRxScanDown},
    {"_fm_receiver_startFullScan", "()V",
     (void *) androidFmRadioRxStartFullScan},
    {"_fm_receiver_isPlayingInStereo", "()Z",
     (void *) androidFmRadioRxIsPlayingInStereo},
    {"_fm_receiver_isRDSDataSupported", "()Z",
     (void *) androidFmRadioRxIsRDSDataSupported},
    {"_fm_receiver_isTunedToValidChannel", "()Z",
     (void *) androidFmRadioRxIsTunedToValidChannel},
    {"_fm_receiver_stopScan", "()V",
     (void *) androidFmRadioRxStopScan},
    {"_fm_receiver_setAutomaticAFSwitching", "(Z)V",
     (void *) androidFmRadioRxSetAutomaticAFSwitching},
    {"_fm_receiver_setAutomaticTASwitching", "(Z)V",
     (void *) androidFmRadioRxSetAutomaticTASwitching},
    {"_fm_receiver_setForceMono", "(Z)V",
     (void *) androidFmRadioRxSetForceMono},
    {"_fm_receiver_sendExtraCommand",
     "(Ljava/lang/String;[Ljava/lang/String;)Z",
     (void *) androidFmRadioRxSendExtraCommand},
    {"_fm_receiver_getThreshold", "()I",
     (void *) androidFmRadioRxGetThreshold},
    {"_fm_receiver_setThreshold", "(I)V",
     (void *) androidFmRadioRxSetThreshold},
};




int registerAndroidFmRadioReceiver(JavaVM * vm, JNIEnv * env)
{
    LOGI("registerAndroidFmRadioReceiver\n");
    jclass clazz;

    pthread_mutex_lock(fmReceiverSession.dataMutex_p);
    fmReceiverSession.jvm_p = vm;
    /*
     * if we haven't registred the library yet do it, but no panic if we
     * fail, libraries can be moved during runtime and new attempts will be
     * done in all start/startAsync commands.
     */

    if (!fmReceiverSession.isRegistered) {
        fmReceiverSession.vendorMethods_p = (fmradio_vendor_methods_t *)
            malloc(sizeof(*fmReceiverSession.vendorMethods_p));
        if (fmReceiverSession.vendorMethods_p == NULL) {
            LOGE("malloc failed\n");
        } else if (androidFmRadioLoadFmLibrary
                   (&fmReceiverSession, FMRADIO_RX)) {
            fmReceiverSession.isRegistered = true;
        } else {
            LOGE("vendor registration failed\n");
            free(fmReceiverSession.vendorMethods_p);
        }
    }

    struct bundle_descriptor_offsets_t *bundle_p =
        (struct bundle_descriptor_offsets_t *)
        malloc(sizeof(struct bundle_descriptor_offsets_t));

    clazz = env->FindClass("android/os/Bundle");
    bundle_p->mClass = (jclass) env->NewGlobalRef(clazz);
    bundle_p->mConstructor = env->GetMethodID(clazz, "<init>", "()V");
    bundle_p->mPutInt =
        env->GetMethodID(clazz, "putInt", "(Ljava/lang/String;I)V");
    bundle_p->mPutShort =
        env->GetMethodID(clazz, "putShort", "(Ljava/lang/String;S)V");
    bundle_p->mPutIntArray =
        env->GetMethodID(clazz, "putIntArray", "(Ljava/lang/String;[I)V");
    bundle_p->mPutShortArray =
        env->GetMethodID(clazz, "putShortArray",
                         "(Ljava/lang/String;[S)V");
    bundle_p->mPutString =
        env->GetMethodID(clazz, "putString",
                         "(Ljava/lang/String;Ljava/lang/String;)V");

    fmReceiverSession.bundleOffsets_p = bundle_p;
    pthread_mutex_unlock(fmReceiverSession.dataMutex_p);
    return jniRegisterNativeMethods(env,
                                    "android/fm/FmReceiverService",
                                    gMethods, NELEM(gMethods));
}

/* *INDENT-OFF* */
};                              // namespace android
