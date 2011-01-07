/*
 * Copyright (c) 2010 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.RILConstants;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.provider.Settings;
import android.util.Log;

/**
 * Class that handles the CDMA subscription source changed events from RIL
 */
public class CdmaSubscriptionSourceManager extends Handler {
    static final String LOG_TAG = "CDMA";
    private static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 1;
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_SOURCE     = 2;
    private static final int EVENT_RADIO_ON                         = 3;

    // ***** Instance Variables
    private static CdmaSubscriptionSourceManager sInstance;
    private CommandsInterface mCM;
    private Context mContext;
    private RegistrantList mCdmaSubscriptionSourceChangedRegistrants = new RegistrantList();
    private int mRef = 0;

    // Type of CDMA subscription source
    private int mCdmaSubscriptionSource = RILConstants.SUBSCRIPTION_FROM_NV;

    // Constructor
    private CdmaSubscriptionSourceManager(Context context, CommandsInterface ci) {
        mContext = context;
        mCM = ci;
        mCM.registerForCdmaSubscriptionSourceChanged(this, EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mCM.registerForOn(this, EVENT_RADIO_ON, null);
        getDefaultCdmaSubscriptionSource();
    }

    /**
     * This function creates a single instance of this class
     *
     * @return object of type CdmaSubscriptionSourceManager
     */
    public synchronized static CdmaSubscriptionSourceManager getInstance(Context context,
           CommandsInterface ci, Handler h, int what, Object obj) {
        if (null == sInstance) {
            sInstance = new CdmaSubscriptionSourceManager(context, ci);
        }
        sInstance.mRef++;
        sInstance.registerForCdmaSubscriptionSourceChanged(h, what, obj);
        return sInstance;
    }

    /**
     * Unregisters for the registered event with RIL
     */
    public void dispose(Handler h) {
        mCdmaSubscriptionSourceChangedRegistrants.remove(h);
        mRef--;
        if (mRef <= 0) {
            mCM.unregisterForCdmaSubscriptionSourceChanged(this);
            mCM.unregisterForOn(this);
        }
    }

    /*
     * (non-Javadoc)
     * @see android.os.Handler#handleMessage(android.os.Message)
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        switch (msg.what) {
            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED: {
                Log.d(LOG_TAG, "EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED");
                mCM.getCdmaSubscriptionSource(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_SOURCE));
            }
            break;
            case EVENT_GET_CDMA_SUBSCRIPTION_SOURCE: {
                ar = (AsyncResult)msg.obj;
                handleGetCdmaSubscriptionSource(ar);
            }
            break;
            case EVENT_RADIO_ON: {
                mCM.getCdmaSubscriptionSource(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_SOURCE));
            }
            break;
            default:
                super.handleMessage(msg);
        }
    }

    /**
     * Returns the current CDMA subscription source value
     * @return CDMA subscription source value
     */
    public int getCdmaSubscriptionSource() {
        return mCdmaSubscriptionSource;
    }

    /**
     * Gets the default CDMA subscription source
     *
     * @param cr
     * @return
     */
    private int getDefaultCdmaSubscriptionSource() {
        // Get the default value from the Settings
        mCdmaSubscriptionSource = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.CDMA_SUBSCRIPTION_MODE, RILConstants.PREFERRED_CDMA_SUBSCRIPTION);
        return mCdmaSubscriptionSource;
    }

    /**
     * Allows clients to register for CDMA subscription source changed event
     */
    private void registerForCdmaSubscriptionSourceChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mCdmaSubscriptionSourceChangedRegistrants.add(r);
    }

    /**
     * Handles the call to get the subscription source
     *
     * @param ar AsyncResult object that contains the result of get CDMA
     *            subscription source call
     */
    private void handleGetCdmaSubscriptionSource(AsyncResult ar) {
        if ((ar.exception == null) && (ar.result != null)) {
            int newSubscriptionSource = ((int[]) ar.result)[0];

            if (newSubscriptionSource != mCdmaSubscriptionSource) {
                Log.v(LOG_TAG, "Subscription Source Changed : " + mCdmaSubscriptionSource + " >> "
                        + newSubscriptionSource);
                mCdmaSubscriptionSource = newSubscriptionSource;

                // Notify registrants of the new CDMA subscription source
                mCdmaSubscriptionSourceChangedRegistrants.notifyRegistrants(new AsyncResult(null,
                        null, null));
            }
        } else {
            // GET_CDMA_SUBSCRIPTION is returning Failure. Probably
            // because modem created GSM Phone. If modem created
            // GSMPhone, then PhoneProxy will trigger a change in
            // Phone objects and this object will be destroyed.
            Log.w(LOG_TAG, "Unable to get CDMA Subscription Source, Exception: " + ar.exception
                    + ", result: " + ar.result);
        }
    }
}
