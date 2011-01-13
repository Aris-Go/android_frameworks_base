/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.Intent;
import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import com.android.internal.telephony.IPhoneStateListener;

interface ITelephonyRegistry {
    void listen(String pkg, IPhoneStateListener callback, int events, boolean notifyNow);
    void listenOnSubscription(String pkg, IPhoneStateListener callback, int events, boolean notifyNow, int subscription);

    void notifyCallState(int state, String incomingNumber);
    void notifyCallStateOnSubscription(int state, String incomingNumber, in int subscription);

    void notifyServiceState(in ServiceState state);
    void notifyServiceStateOnSubscription(in ServiceState state, in int subscription);

    void notifySignalStrength(in SignalStrength signalStrength);
    void notifySignalStrengthOnSubscription(in SignalStrength signalStrength, in int subscription);

    void notifyMessageWaitingChanged(boolean mwi);
    void notifyMessageWaitingChangedOnSubscription(boolean mwi, in int subscription);

    void notifyCallForwardingChanged(boolean cfi);
    void notifyCallForwardingChangedOnSubscription(boolean cfi, in int subscription);

    void notifyDataActivity(int state);
    void notifyDataConnection(int state, boolean isDataConnectivityPossible,
            String reason, String apn, in String[] apnTypes, String interfaceName, int networkType,
            String gateway);
    void notifyDataConnectionFailed(String reason);

    void notifyCellLocation(in Bundle cellLocation);
    void notifyCellLocationOnSubscription(in Bundle cellLocation, in int subscription);
}
