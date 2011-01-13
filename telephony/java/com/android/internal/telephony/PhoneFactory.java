/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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

import android.content.Context;
import android.net.LocalServerSocket;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.telephony.TelephonyManager;
import android.provider.Settings.SettingNotFoundException;
import android.content.Intent;

import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.ProxyManager;
import com.android.internal.telephony.sip.SipPhone;
import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.internal.telephony.UiccManager;

/**
 * {@hide}
 */
public class PhoneFactory {
    static final String LOG_TAG = "PHONE";
    static final int SOCKET_OPEN_RETRY_MILLIS = 2 * 1000;
    static final int SOCKET_OPEN_MAX_RETRY = 3;
    //***** Class Variables

    static private Phone sProxyPhone[] = null;
    static private CommandsInterface sCommandsInterface[] = null;
    static private UiccManager mUiccManager = null;
    static private ProxyManager mProxyManager = null;

    static private boolean sMadeDefaults = false;
    static private PhoneNotifier sPhoneNotifier;
    static private Looper sLooper;
    static private Context sContext;
    static private int numPhones = 1;

    static final int preferredNetworkMode = RILConstants.PREFERRED_NETWORK_MODE;

    static final int preferredCdmaSubscription = RILConstants.PREFERRED_CDMA_SUBSCRIPTION;

    static final String SUBSCRIPTION_KEY = "phone_subscription";

    //***** Class Methods

    public static void makeDefaultPhones(Context context) {
        makeDefaultPhone(context);
    }

    /**
     * FIXME replace this with some other way of making these
     * instances
     */
    public static void makeDefaultPhone(Context context) {
        synchronized(Phone.class) {
            if (!sMadeDefaults) {
                sLooper = Looper.myLooper();
                sContext = context;

                if (sLooper == null) {
                    throw new RuntimeException(
                        "PhoneFactory.makeDefaultPhone must be called from Looper thread");
                }

                int retryCount = 0;
                for(;;) {
                    boolean hasException = false;
                    retryCount ++;

                    try {
                        // use UNIX domain socket to
                        // prevent subsequent initialization
                        new LocalServerSocket("com.android.internal.telephony");
                    } catch (java.io.IOException ex) {
                        hasException = true;
                    }

                    if ( !hasException ) {
                        break;
                    } else if (retryCount > SOCKET_OPEN_MAX_RETRY) {
                        throw new RuntimeException("PhoneFactory probably already running");
                    } else {
                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }
                    }
                }
                /* In case of multi SIM mode two instances of PhoneProxy,RIL are created,
                   where as in single SIM mode only instance. isMultiSimEnabled() function checks
                   whether it is single SIM or multi SIM mode */

                numPhones = TelephonyManager.getPhoneCount();
                sPhoneNotifier = new DefaultPhoneNotifier();

                //Get preferredNetworkMode from Settings.System
                int cdmaSubscription = Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.PREFERRED_CDMA_SUBSCRIPTION, preferredCdmaSubscription);
                Log.i(LOG_TAG, "Cdma Subscription set to " + Integer.toString(cdmaSubscription));
                sProxyPhone = new PhoneProxy[numPhones];
                sCommandsInterface = new RIL[numPhones];

                int[] networkMode = new int[numPhones];
                for (int i = 0; i < numPhones; i++) {
                    //Get preferredNetworkMode from Settings.System
                    try {
                        networkMode[i]  = Settings.Secure.getIntAtIndex(context.getContentResolver(),
                            Settings.Secure.PREFERRED_NETWORK_MODE, i);
                    } catch (SettingNotFoundException snfe) {
                        Log.e(LOG_TAG, "Exception on Reading Preferred network mode", snfe);
                        networkMode[i] = preferredNetworkMode;
                    }
                    Log.i(LOG_TAG, "Network Mode set to " + networkMode[i]);
                    sCommandsInterface[i] = new RIL(context, networkMode[i], cdmaSubscription, i);
                }

                mUiccManager = UiccManager.getInstance(context, sCommandsInterface);

                for (int i = 0; i < numPhones; i++) {
                    int phoneType = getPhoneType(networkMode[i]);
                    Log.i(LOG_TAG, "get Phone Type:"+ phoneType);

                    if (phoneType == Phone.PHONE_TYPE_GSM) {
                        Log.i(LOG_TAG, "Creating GSMPhone");
                        sProxyPhone[i] = new PhoneProxy(new GSMPhone(context,
                                sCommandsInterface[i], sPhoneNotifier, false));
                    } else if (phoneType == Phone.PHONE_TYPE_CDMA) {
                        Log.i(LOG_TAG, "Creating CDMAPhone");
                        sProxyPhone[i] = new PhoneProxy(new CDMAPhone(context,
                                sCommandsInterface[i], sPhoneNotifier, false));
                    }
                }

                sMadeDefaults = true;
                mProxyManager = ProxyManager.getInstance(context, sProxyPhone, mUiccManager, sCommandsInterface);
            }
        }
    }

    /*
     * This function returns the type of the phone, depending
     * on the network mode.
     *
     * @param network mode
     * @return Phone Type
     */
    public static int getPhoneType(int networkMode) {
        switch(networkMode) {
        case RILConstants.NETWORK_MODE_CDMA:
        case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
        case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
        case RILConstants.NETWORK_MODE_CDMA_AND_LTE_EVDO:
            return Phone.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_WCDMA_PREF:
        case RILConstants.NETWORK_MODE_GSM_ONLY:
        case RILConstants.NETWORK_MODE_WCDMA_ONLY:
        case RILConstants.NETWORK_MODE_GSM_UMTS:
        case RILConstants.NETWORK_MODE_GSM_WCDMA_LTE:
            return Phone.PHONE_TYPE_GSM;

        case RILConstants.NETWORK_MODE_GLOBAL:
        case RILConstants.NETWORK_MODE_GLOBAL_LTE:
        case RILConstants.NETWORK_MODE_LTE_ONLY:
            return Phone.PHONE_TYPE_CDMA;
        default:
            return Phone.PHONE_TYPE_GSM;
        }
    }

    public static Phone getDefaultPhone() {
        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }

        if (!sMadeDefaults) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }
       return sProxyPhone[getDefaultSubscription()];
    }

    public static Phone getPhone(int subscription) {
        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getPhone must be called from Looper thread");
        }
        if (!sMadeDefaults) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }
       return sProxyPhone[subscription];
    }

    public static Phone[] getPhones() {
        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }
        if (!sMadeDefaults) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }
       return sProxyPhone;
    }

    public static Phone getCdmaPhone() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = new CDMAPhone(sContext, sCommandsInterface[0], sPhoneNotifier);
            return phone;
        }
    }

    /* Gets CDMA phone attached with proper CommandInterface */
    public static Phone getCdmaPhone(int subscription) {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = new CDMAPhone(sContext, sCommandsInterface[subscription], sPhoneNotifier, false);
            return phone;
        }
    }

    public static Phone getGsmPhone() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = new GSMPhone(sContext, sCommandsInterface[0], sPhoneNotifier);
            return phone;
        }
    }

    /* Gets GSM phone attached with proper CommandInterface */
    public static Phone getGsmPhone(int subscription) {
        Log.d(LOG_TAG,"getGsmPhone on sub :" + subscription);
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = new GSMPhone(sContext, sCommandsInterface[subscription], sPhoneNotifier, false);
            return phone;
        }
    }

    /* Gets the default subscription */
    public static int getDefaultSubscription() {
        int subscription = 0;

        try {
            subscription = Settings.System.getInt(sContext.getContentResolver(),
                    Settings.System.DEFAULT_SUBSCRIPTION);
        } catch (SettingNotFoundException snfe) {
            Log.e(LOG_TAG, "Settings Exception Reading Default Subscription", snfe);
        }

        return subscription;
    }

    /* Sets the default subscription. If only one phone instance is active that
     * that subscription is set as default subscription. If both phone isntances
     * are active the first instance "0" is set as default subscription
     */
    public static void setDefaultSubscription(int subscription) {
        Settings.System.putInt(sContext.getContentResolver(),
                                       Settings.System.DEFAULT_SUBSCRIPTION, subscription);
        // Broadcast an Intent for default sub change
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(SUBSCRIPTION_KEY, subscription);
        Log.d(LOG_TAG, "setDefaultSubscription : " + subscription
                + " Broadcasting Default Subscription Changed...");
        sContext.sendStickyBroadcast(intent);
    }

    /* Gets User preferred Voice subscription setting*/
    public static int getVoiceSubscription() {
        int subscription = 0;

        try {
            subscription = Settings.System.getInt(sContext.getContentResolver(),
                    Settings.System.MULTI_SIM_VOICE_CALL);
        } catch (SettingNotFoundException snfe) {
            Log.e(LOG_TAG, "Settings Exception Reading Dual Sim Voice Call Values", snfe);
        }

        return subscription;
    }

    static public void setVoiceSubscription(int subscription) {
        Settings.System.putInt(sContext.getContentResolver(),
                Settings.System.MULTI_SIM_VOICE_CALL, subscription);
        Log.d(LOG_TAG, "setVoiceSubscription : " + subscription);
    }

    /* Gets User preferred Data subscription setting*/
    public static int getDataSubscription() {
        int subscription = 0;

        try {
            subscription = Settings.System.getInt(sContext.getContentResolver(),
                    Settings.System.MULTI_SIM_DATA_CALL);
        } catch (SettingNotFoundException snfe) {
            Log.e(LOG_TAG, "Settings Exception Reading Dual Sim Data Call Values", snfe);
        }

        return subscription;
    }

    static public void setDataSubscription(int subscription) {
        Settings.System.putInt(sContext.getContentResolver(),
                Settings.System.MULTI_SIM_DATA_CALL, subscription);
        Log.d(LOG_TAG, "setDataSubscription: " + subscription);
    }

    /* Gets User preferred SMS subscription setting*/
    public static int getSMSSubscription() {
        int subscription = 0;
        try {
            subscription = Settings.System.getInt(sContext.getContentResolver(),
                    Settings.System.MULTI_SIM_SMS);
        } catch (SettingNotFoundException snfe) {
            Log.e(LOG_TAG, "Settings Exception Reading Dual Sim SMS Values", snfe);
        }

        return subscription;
    }

    static public void setSMSSubscription(int subscription) {
        Settings.System.putInt(sContext.getContentResolver(),
                Settings.System.MULTI_SIM_SMS, subscription);

        Intent intent = new Intent("com.android.mms.transaction.SEND_MESSAGE");
        sContext.sendBroadcast(intent);
        Log.d(LOG_TAG, "setSMSSubscription : " + subscription);
    }

    /**
     * Makes a {@link SipPhone} object.
     * @param sipUri the local SIP URI the phone runs on
     * @return the {@code SipPhone} object or null if the SIP URI is not valid
     */
    public static SipPhone makeSipPhone(String sipUri) {
        return SipPhoneFactory.makePhone(sipUri, sContext, sPhoneNotifier);
    }
}
