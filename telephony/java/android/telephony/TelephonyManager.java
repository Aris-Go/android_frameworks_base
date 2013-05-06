/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.telephony;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telephony.Rlog;

import com.android.internal.telephony.IPhoneSubInfo;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides access to information about the telephony services on
 * the device. Applications can use the methods in this class to
 * determine telephony services and states, as well as to access some
 * types of subscriber information. Applications can also register
 * a listener to receive notification of telephony state changes.
 * <p>
 * You do not instantiate this class directly; instead, you retrieve
 * a reference to an instance through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.TELEPHONY_SERVICE)}.
 * <p>
 * Note that access to some telephony information is
 * permission-protected. Your application cannot access the protected
 * information unless it has the appropriate permissions declared in
 * its manifest file. Where permissions apply, they are noted in the
 * the methods through which you access the protected information.
 */
public class TelephonyManager {
    private static final String TAG = "TelephonyManager";

    private static Context sContext;
    private static ITelephonyRegistry sRegistry;

    /** @hide */
    public TelephonyManager(Context context) {
        if (sContext == null) {
            Context appContext = context.getApplicationContext();
            if (appContext != null) {
                sContext = appContext;
            } else {
                sContext = context;
            }

            sRegistry = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
        }
    }

    /** @hide */
    private TelephonyManager() {
    }

    private static TelephonyManager sInstance = new TelephonyManager();

    /** @hide
    /* @deprecated - use getSystemService as described above */
    public static TelephonyManager getDefault() {
        return sInstance;
    }

    /** {@hide} */
    public static TelephonyManager from(Context context) {
        return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    //
    // Broadcast Intent actions
    //

    /**
     * Broadcast intent action indicating that the call state (cellular)
     * on the device has changed.
     *
     * <p>
     * The {@link #EXTRA_STATE} extra indicates the new call state.
     * If the new state is RINGING, a second extra
     * {@link #EXTRA_INCOMING_NUMBER} provides the incoming phone number as
     * a String.
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">
     * This was a {@link android.content.Context#sendStickyBroadcast sticky}
     * broadcast in version 1.0, but it is no longer sticky.
     * Instead, use {@link #getCallState} to synchronously query the current call state.
     *
     * @see #EXTRA_STATE
     * @see #EXTRA_INCOMING_NUMBER
     * @see #getCallState
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PHONE_STATE_CHANGED =
            "android.intent.action.PHONE_STATE";

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the new call state.
     *
     * @see #EXTRA_STATE_IDLE
     * @see #EXTRA_STATE_RINGING
     * @see #EXTRA_STATE_OFFHOOK
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_STATE = PhoneConstants.STATE_KEY;

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_IDLE}.
     */
    public static final String EXTRA_STATE_IDLE = PhoneConstants.State.IDLE.toString();

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_RINGING}.
     */
    public static final String EXTRA_STATE_RINGING = PhoneConstants.State.RINGING.toString();

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_OFFHOOK}.
     */
    public static final String EXTRA_STATE_OFFHOOK = PhoneConstants.State.OFFHOOK.toString();

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the incoming phone number.
     * Only valid when the new call state is RINGING.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";

    /**
     * Broadcast intent action indicating that a precise call state 
     * (cellular) on the device has changed.
     *
     * <p>
     * The {@link #EXTRA_RC_STATE} extra indicates the ringing call state.
     * The {@link #EXTRA_FC_STATE} extra indicates the foreground call state.
     * The {@link #EXTRA_BC_STATE} extra indicates the background call state.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_RC_STATE
     * @see #EXTRA_FC_STATE
     * @see #EXTRA_BC_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_CALL_STATE_CHANGED =
            "android.intent.action.PRECISE_CALL_STATE";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the new ringing call state.
     *
     * @see TelephonyManager#PRECISE_CALL_STATE_IDLE
     * @see TelephonyManager#PRECISE_CALL_STATE_ACTIVE
     * @see TelephonyManager#PRECISE_CALL_STATE_HOLDING
     * @see TelephonyManager#PRECISE_CALL_STATE_DIALING
     * @see TelephonyManager#PRECISE_CALL_STATE_ALERTING
     * @see TelephonyManager#PRECISE_CALL_STATE_INCOMING
     * @see TelephonyManager#PRECISE_CALL_STATE_WAITING
     * @see TelephonyManager#PRECISE_CALL_STATE_DISCONNECTED
     * @see TelephonyManager#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     */
    public static final String EXTRA_RC_STATE = "ringing_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the new foreground call state.
     *
     * @see TelephonyManager#PRECISE_CALL_STATE_IDLE
     * @see TelephonyManager#PRECISE_CALL_STATE_ACTIVE
     * @see TelephonyManager#PRECISE_CALL_STATE_HOLDING
     * @see TelephonyManager#PRECISE_CALL_STATE_DIALING
     * @see TelephonyManager#PRECISE_CALL_STATE_ALERTING
     * @see TelephonyManager#PRECISE_CALL_STATE_INCOMING
     * @see TelephonyManager#PRECISE_CALL_STATE_WAITING
     * @see TelephonyManager#PRECISE_CALL_STATE_DISCONNECTED
     * @see TelephonyManager#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     */
    public static final String EXTRA_FC_STATE = "foreground_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the new background call state.
     *
     * @see TelephonyManager#PRECISE_CALL_STATE_IDLE
     * @see TelephonyManager#PRECISE_CALL_STATE_ACTIVE
     * @see TelephonyManager#PRECISE_CALL_STATE_HOLDING
     * @see TelephonyManager#PRECISE_CALL_STATE_DIALING
     * @see TelephonyManager#PRECISE_CALL_STATE_ALERTING
     * @see TelephonyManager#PRECISE_CALL_STATE_INCOMING
     * @see TelephonyManager#PRECISE_CALL_STATE_WAITING
     * @see TelephonyManager#PRECISE_CALL_STATE_DISCONNECTED
     * @see TelephonyManager#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     */
    public static final String EXTRA_BC_STATE = "background_state";

    /**
     * Broadcast intent action indicating that a phone call (cellular)
     * have disconnected.
     *
     * <p>
     * The {@link #EXTRA_CALL_DISCONNECT_CAUSE} extra indicates the disconnect cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_CALL_DISCONNECT_CAUSE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CALL_DISCONNECT =
            "android.intent.action.CALL_DISCONNECT";

    /**
     * The lookup key used with the {@link #ACTION_CALL_DISCONNECT} broadcast
     * for an integer containing the disconnect cause.
     *
     * @see TelephonyManager#DISCONNECT_CAUSE_NOT_DISCONNECTED
     * @see TelephonyManager#DISCONNECT_CAUSE_INCOMING_MISSED
     * @see TelephonyManager#DISCONNECT_CAUSE_NORMAL
     * @see TelephonyManager#DISCONNECT_CAUSE_LOCAL
     * @see TelephonyManager#DISCONNECT_CAUSE_BUSY
     * @see TelephonyManager#DISCONNECT_CAUSE_CONGESTION
     * @see TelephonyManager#DISCONNECT_CAUSE_MMI
     * @see TelephonyManager#DISCONNECT_CAUSE_INVALID_NUMBER
     * @see TelephonyManager#DISCONNECT_CAUSE_NUMBER_UNREACHABLE
     * @see TelephonyManager#DISCONNECT_CAUSE_SERVER_UNREACHABLE
     * @see TelephonyManager#DISCONNECT_CAUSE_INVALID_CREDENTIALS
     * @see TelephonyManager#DISCONNECT_CAUSE_OUT_OF_NETWORK
     * @see TelephonyManager#DISCONNECT_CAUSE_SERVER_ERROR
     * @see TelephonyManager#DISCONNECT_CAUSE_TIMED_OUT
     * @see TelephonyManager#DISCONNECT_CAUSE_LOST_SIGNAL
     * @see TelephonyManager#DISCONNECT_CAUSE_LIMIT_EXCEEDED
     * @see TelephonyManager#DISCONNECT_CAUSE_INCOMING_REJECTED
     * @see TelephonyManager#DISCONNECT_CAUSE_POWER_OFF
     * @see TelephonyManager#DISCONNECT_CAUSE_OUT_OF_SERVICE
     * @see TelephonyManager#DISCONNECT_CAUSE_ICC_ERROR
     * @see TelephonyManager#DISCONNECT_CAUSE_CALL_BARRED
     * @see TelephonyManager#DISCONNECT_CAUSE_FDN_BLOCKED
     * @see TelephonyManager#DISCONNECT_CAUSE_CS_RESTRICTED
     * @see TelephonyManager#DISCONNECT_CAUSE_CS_RESTRICTED_NORMAL
     * @see TelephonyManager#DISCONNECT_CAUSE_CS_RESTRICTED_EMERGENCY
     * @see TelephonyManager#DISCONNECT_CAUSE_UNOBTAINABLE_NUMBER
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_LOCKED_UNTIL_POWER_CYCLE
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_DROP
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_INTERCEPT
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_REORDER
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_SO_REJECT
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_RETRY_ORDER
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_ACCESS_FAILURE
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_PREEMPTED
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_NOT_EMERGENCY
     * @see TelephonyManager#DISCONNECT_CAUSE_CDMA_ACCESS_BLOCKED
     * @see TelephonyManager#DISCONNECT_CAUSE_ERROR_UNSPECIFIED
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     */
    public static final String EXTRA_CALL_DISCONNECT_CAUSE = "disconnect_cause";

    /**
     * Broadcast intent action indicating that a phone call (cellular)
     * have disconnected.
     *
     * <p>
     * The {@link #EXTRA_PRECISE_CALL_DISCONNECT_CAUSE} extra indicates the disconnect cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_CALL_DISCONNECT_CAUSE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_CALL_DISCONNECT =
            "android.intent.action.PRECISE_CALL_DISCONNECT";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_DISCONNECT} broadcast
     * for an integer containing the disconnect cause.
     *
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_NO_DISCONNECT_CAUSE_AVAILABLE
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_UNOBTAINABLE_NUMBER
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_NORMAL
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_BUSY
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_NUMBER_CHANGED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_STATUS_ENQUIRY
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_NORMAL_UNSPECIFIED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_NO_CIRCUIT_AVAIL
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_TEMPORARY_FAILURE
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_SWITCHING_CONGESTION
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CHANNEL_NOT_AVAIL
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_QOS_NOT_AVAIL
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_BEARER_NOT_AVAIL
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_ACM_LIMIT_EXCEEDED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CALL_BARRED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_FDN_BLOCKED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_IMSI_UNKNOWN_IN_VLR
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_IMEI_NOT_ACCEPTED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_LOCKED_UNTIL_POWER_CYCLE
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_DROP
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_INTERCEPT
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_REORDER
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_SO_REJECT
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_RETRY_ORDER
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_ACCESS_FAILURE
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_PREEMPTED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_NOT_EMERGENCY
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_CDMA_ACCESS_BLOCKED
     * @see TelephonyManager#PRECISE_DISCONNECT_CAUSE_ERROR_UNSPECIFIED
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     */
    public static final String EXTRA_PRECISE_CALL_DISCONNECT_CAUSE = "precise_disconnect_cause";

    /**
     * Broadcast intent action indicating a data connection has changed,
     * providing precise information about the connection.
     *
     * <p>
     * The {@link #EXTRA_DATA_STATE} extra indicates the connection state.
     * The {@link #EXTRA_DATA_NETWORK_TYPE} extra indicates the connection network type.
     * The {@link #EXTRA_DATA_APN_TYPE} extra indicates the APN type.
     * The {@link #EXTRA_DATA_APN} extra indicates the APN.
     * The {@link #EXTRA_DATA_CHANGE_REASON} extra indicates the connection change reason.
     * The {@link #EXTRA_DATA_IFACE} extra indicates the connection interface.
     * The {@link #EXTRA_DATA_LINK} extra indicates the link capabilities.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_DATA_STATE
     * @see #EXTRA_DATA_NETWORK_TYPE
     * @see #EXTRA_DATA_APN_TYPE
     * @see #EXTRA_DATA_APN
     * @see #EXTRA_DATA_CHANGE_REASON
     * @see #EXTRA_DATA_IFACE
     * @see #EXTRA_DATA_LINK
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED =
            "android.intent.action.PRECISE_DATA_CONNECTION_STATE_CHANGED";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an integer containing the new data connection state.
     *
     * @see TelephonyManager#DATA_DISCONNECTED
     * @see TelephonyManager#DATA_CONNECTING
     * @see TelephonyManager#DATA_CONNECTED
     * @see TelephonyManager#DATA_SUSPENDED
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     */
    public static final String EXTRA_DATA_STATE = PhoneConstants.STATE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an integer containing the network type.
     *
     * @see TelephonyManager#NETWORK_TYPE_UNKNOWN
     * @see TelephonyManager#NETWORK_TYPE_GPRS
     * @see TelephonyManager#NETWORK_TYPE_EDGE
     * @see TelephonyManager#NETWORK_TYPE_UMTS
     * @see TelephonyManager#NETWORK_TYPE_CDMA
     * @see TelephonyManager#NETWORK_TYPE_EVDO_0
     * @see TelephonyManager#NETWORK_TYPE_EVDO_A
     * @see TelephonyManager#NETWORK_TYPE_1xRTT
     * @see TelephonyManager#NETWORK_TYPE_HSDPA
     * @see TelephonyManager#NETWORK_TYPE_HSUPA
     * @see TelephonyManager#NETWORK_TYPE_HSPA
     * @see TelephonyManager#NETWORK_TYPE_IDEN
     * @see TelephonyManager#NETWORK_TYPE_EVDO_B
     * @see TelephonyManager#NETWORK_TYPE_LTE
     * @see TelephonyManager#NETWORK_TYPE_EHRPD
     * @see TelephonyManager#NETWORK_TYPE_HSPAP
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     */
    public static final String EXTRA_DATA_NETWORK_TYPE = PhoneConstants.DATA_NETWORK_TYPE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED},
     * {@link #ACTION_DATA_CONNECTION_FAILED} and {@link #ACTION_PRECISE_DATA_CONNECTION_FAILED}
     * broadcasts for an String containing the data APN type.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     */
    public static final String EXTRA_DATA_APN_TYPE = PhoneConstants.DATA_APN_TYPE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} and
     * {@link #ACTION_PRECISE_DATA_CONNECTION_FAILED} broadcasts for an String containing
     * the data APN.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     */
    public static final String EXTRA_DATA_APN = PhoneConstants.DATA_APN_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String representation of the change reason.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     */
    public static final String EXTRA_DATA_CHANGE_REASON = PhoneConstants.STATE_CHANGE_REASON_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String representation of the data interface.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     */
    public static final String EXTRA_DATA_IFACE = PhoneConstants.DATA_IFACE_NAME_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String representation of the link capabilities.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     */
    public static final String EXTRA_DATA_LINK = PhoneConstants.DATA_LINK_CAPABILITIES_KEY;

    /**
     * Broadcast intent action indicating a failure on the data connection.
     *
     * <p>
     * The {@link #EXTRA_FAILURE_REASON} extra indicates the failure reason.
     * The {@link #EXTRA_DATA_APN_TYPE} extra indicates the apn type that has failed.
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * @see #EXTRA_FAILURE_REASON
     * @see #EXTRA_DATA_APN_TYPE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DATA_CONNECTION_FAILED =
            "android.intent.action.DATA_CONNECTION_FAILED";

    /**
     * Broadcast intent action indicating a precise failure on the data connection.
     *
     * <p>
     * The {@link #EXTRA_FAILURE_REASON} extra indicates the failure reason.
     * The {@link #EXTRA_DATA_APN_TYPE} extra indicates the apn type that has failed.
     * The {@link #EXTRA_DATA_APN} extra indicates the apn that has failed.
     * The {@link #EXTRA_DATA_FAILURE_CAUSE} extra indicates the connection fail cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_FAILURE_REASON
     * @see #EXTRA_DATA_APN_TYPE
     * @see #EXTRA_DATA_APN
     * @see #EXTRA_DATA_FAILURE_CAUSE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_DATA_CONNECTION_FAILED =
            "android.intent.action.PRECISE_DATA_CONNECTION_FAILED";

    /**
     * The lookup key used with the {@link #ACTION_DATA_CONNECTION_FAILED} and
     * broadcast {@link #ACTION_PRECISE_DATA_CONNECTION_FAILED} for an String
     * representation of the failure reason.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     */
    public static final String EXTRA_FAILURE_REASON = PhoneConstants.FAILURE_REASON_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_FAILED} broadcast
     * for the connection fail cause.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     */
    public static final String EXTRA_DATA_FAILURE_CAUSE = PhoneConstants.DATA_FAILURE_CAUSE_KEY;

    //
    //
    // Device Info
    //
    //

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getDeviceSoftwareVersion() {
        try {
            return getSubscriberInfo().getDeviceSvn();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the unique device ID, for example, the IMEI for GSM and the MEID
     * or ESN for CDMA phones. Return null if device ID is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getDeviceId() {
        try {
            return getSubscriberInfo().getDeviceId();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the current location of the device.
     * Return null if current location is not available.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION}.
     */
    public CellLocation getCellLocation() {
        try {
            Bundle bundle = getITelephony().getCellLocation();
            if (bundle.isEmpty()) return null;
            CellLocation cl = CellLocation.newFromBundle(bundle);
            if (cl.isEmpty())
                return null;
            return cl;
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Enables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @hide
     */
    public void enableLocationUpdates() {
        try {
            getITelephony().enableLocationUpdates();
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Disables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @hide
     */
    public void disableLocationUpdates() {
        try {
            getITelephony().disableLocationUpdates();
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the neighboring cell information of the device.
     *
     * @return List of NeighboringCellInfo or null if info unavailable.
     *
     * <p>Requires Permission:
     * (@link android.Manifest.permission#ACCESS_COARSE_UPDATES}
     */
    public List<NeighboringCellInfo> getNeighboringCellInfo() {
        try {
            return getITelephony().getNeighboringCellInfo();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** No phone radio. */
    public static final int PHONE_TYPE_NONE = PhoneConstants.PHONE_TYPE_NONE;
    /** Phone radio is GSM. */
    public static final int PHONE_TYPE_GSM = PhoneConstants.PHONE_TYPE_GSM;
    /** Phone radio is CDMA. */
    public static final int PHONE_TYPE_CDMA = PhoneConstants.PHONE_TYPE_CDMA;
    /** Phone is via SIP. */
    public static final int PHONE_TYPE_SIP = PhoneConstants.PHONE_TYPE_SIP;

    /**
     * Returns the current phone type.
     * TODO: This is a last minute change and hence hidden.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     * @see #PHONE_TYPE_SIP
     *
     * {@hide}
     */
    public int getCurrentPhoneType() {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getActivePhoneType();
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return getPhoneTypeFromProperty();
            }
        } catch (RemoteException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty();
        } catch (NullPointerException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty();
        }
    }

    /**
     * Returns a constant indicating the device phone type.  This
     * indicates the type of radio used to transmit voice calls.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     * @see #PHONE_TYPE_SIP
     */
    public int getPhoneType() {
        if (!isVoiceCapable()) {
            return PHONE_TYPE_NONE;
        }
        return getCurrentPhoneType();
    }

    private int getPhoneTypeFromProperty() {
        int type =
            SystemProperties.getInt(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                    getPhoneTypeFromNetworkType());
        return type;
    }

    private int getPhoneTypeFromNetworkType() {
        // When the system property CURRENT_ACTIVE_PHONE, has not been set,
        // use the system property for default network type.
        // This is a fail safe, and can only happen at first boot.
        int mode = SystemProperties.getInt("ro.telephony.default_network", -1);
        if (mode == -1)
            return PHONE_TYPE_NONE;
        return getPhoneType(mode);
    }

    /**
     * This function returns the type of the phone, depending
     * on the network mode.
     *
     * @param network mode
     * @return Phone Type
     *
     * @hide
     */
    public static int getPhoneType(int networkMode) {
        switch(networkMode) {
        case RILConstants.NETWORK_MODE_CDMA:
        case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
        case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
            return PhoneConstants.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_WCDMA_PREF:
        case RILConstants.NETWORK_MODE_GSM_ONLY:
        case RILConstants.NETWORK_MODE_WCDMA_ONLY:
        case RILConstants.NETWORK_MODE_GSM_UMTS:
        case RILConstants.NETWORK_MODE_LTE_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_WCDMA:
            return PhoneConstants.PHONE_TYPE_GSM;

        // Use CDMA Phone for the global mode including CDMA
        case RILConstants.NETWORK_MODE_GLOBAL:
        case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
        case RILConstants.NETWORK_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            return PhoneConstants.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_LTE_ONLY:
            if (getLteOnCdmaModeStatic() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                return PhoneConstants.PHONE_TYPE_CDMA;
            } else {
                return PhoneConstants.PHONE_TYPE_GSM;
            }
        default:
            return PhoneConstants.PHONE_TYPE_GSM;
        }
    }

    /**
     * The contents of the /proc/cmdline file
     */
    private static String getProcCmdLine()
    {
        String cmdline = "";
        FileInputStream is = null;
        try {
            is = new FileInputStream("/proc/cmdline");
            byte [] buffer = new byte[2048];
            int count = is.read(buffer);
            if (count > 0) {
                cmdline = new String(buffer, 0, count);
            }
        } catch (IOException e) {
            Rlog.d(TAG, "No /proc/cmdline exception=" + e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
        }
        Rlog.d(TAG, "/proc/cmdline=" + cmdline);
        return cmdline;
    }

    /** Kernel command line */
    private static final String sKernelCmdLine = getProcCmdLine();

    /** Pattern for selecting the product type from the kernel command line */
    private static final Pattern sProductTypePattern =
        Pattern.compile("\\sproduct_type\\s*=\\s*(\\w+)");

    /** The ProductType used for LTE on CDMA devices */
    private static final String sLteOnCdmaProductType =
        SystemProperties.get(TelephonyProperties.PROPERTY_LTE_ON_CDMA_PRODUCT_TYPE, "");

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     * @hide
     */
    public static int getLteOnCdmaModeStatic() {
        int retVal;
        int curVal;
        String productType = "";

        curVal = SystemProperties.getInt(TelephonyProperties.PROPERTY_LTE_ON_CDMA_DEVICE,
                    PhoneConstants.LTE_ON_CDMA_UNKNOWN);
        retVal = curVal;
        if (retVal == PhoneConstants.LTE_ON_CDMA_UNKNOWN) {
            Matcher matcher = sProductTypePattern.matcher(sKernelCmdLine);
            if (matcher.find()) {
                productType = matcher.group(1);
                if (sLteOnCdmaProductType.equals(productType)) {
                    retVal = PhoneConstants.LTE_ON_CDMA_TRUE;
                } else {
                    retVal = PhoneConstants.LTE_ON_CDMA_FALSE;
                }
            } else {
                retVal = PhoneConstants.LTE_ON_CDMA_FALSE;
            }
        }

        Rlog.d(TAG, "getLteOnCdmaMode=" + retVal + " curVal=" + curVal +
                " product_type='" + productType +
                "' lteOnCdmaProductType='" + sLteOnCdmaProductType + "'");
        return retVal;
    }

    //
    //
    // Current Network
    //
    //

    /**
     * Returns the alphabetic name of current registered operator.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkOperatorName() {
        return SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_ALPHA);
    }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkOperator() {
        return SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC);
    }

    /**
     * Returns true if the device is considered roaming on the current
     * network, for GSM purposes.
     * <p>
     * Availability: Only when user registered to a network.
     */
    public boolean isNetworkRoaming() {
        return "true".equals(SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING));
    }

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkCountryIso() {
        return SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY);
    }

    /** Network type is unknown */
    public static final int NETWORK_TYPE_UNKNOWN = 0;
    /** Current network is GPRS */
    public static final int NETWORK_TYPE_GPRS = 1;
    /** Current network is EDGE */
    public static final int NETWORK_TYPE_EDGE = 2;
    /** Current network is UMTS */
    public static final int NETWORK_TYPE_UMTS = 3;
    /** Current network is CDMA: Either IS95A or IS95B*/
    public static final int NETWORK_TYPE_CDMA = 4;
    /** Current network is EVDO revision 0*/
    public static final int NETWORK_TYPE_EVDO_0 = 5;
    /** Current network is EVDO revision A*/
    public static final int NETWORK_TYPE_EVDO_A = 6;
    /** Current network is 1xRTT*/
    public static final int NETWORK_TYPE_1xRTT = 7;
    /** Current network is HSDPA */
    public static final int NETWORK_TYPE_HSDPA = 8;
    /** Current network is HSUPA */
    public static final int NETWORK_TYPE_HSUPA = 9;
    /** Current network is HSPA */
    public static final int NETWORK_TYPE_HSPA = 10;
    /** Current network is iDen */
    public static final int NETWORK_TYPE_IDEN = 11;
    /** Current network is EVDO revision B*/
    public static final int NETWORK_TYPE_EVDO_B = 12;
    /** Current network is LTE */
    public static final int NETWORK_TYPE_LTE = 13;
    /** Current network is eHRPD */
    public static final int NETWORK_TYPE_EHRPD = 14;
    /** Current network is HSPA+ */
    public static final int NETWORK_TYPE_HSPAP = 15;

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for data transmission.
     * @return the network type
     *
     * @see #NETWORK_TYPE_UNKNOWN
     * @see #NETWORK_TYPE_GPRS
     * @see #NETWORK_TYPE_EDGE
     * @see #NETWORK_TYPE_UMTS
     * @see #NETWORK_TYPE_HSDPA
     * @see #NETWORK_TYPE_HSUPA
     * @see #NETWORK_TYPE_HSPA
     * @see #NETWORK_TYPE_CDMA
     * @see #NETWORK_TYPE_EVDO_0
     * @see #NETWORK_TYPE_EVDO_A
     * @see #NETWORK_TYPE_EVDO_B
     * @see #NETWORK_TYPE_1xRTT
     * @see #NETWORK_TYPE_IDEN
     * @see #NETWORK_TYPE_LTE
     * @see #NETWORK_TYPE_EHRPD
     * @see #NETWORK_TYPE_HSPAP
     */
    public int getNetworkType() {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getNetworkType();
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_UNKNOWN;
            }
        } catch(RemoteException ex) {
            // This shouldn't happen in the normal case
            return NETWORK_TYPE_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return NETWORK_TYPE_UNKNOWN;
        }
    }

    /** Unknown network class. {@hide} */
    public static final int NETWORK_CLASS_UNKNOWN = 0;
    /** Class of broadly defined "2G" networks. {@hide} */
    public static final int NETWORK_CLASS_2_G = 1;
    /** Class of broadly defined "3G" networks. {@hide} */
    public static final int NETWORK_CLASS_3_G = 2;
    /** Class of broadly defined "4G" networks. {@hide} */
    public static final int NETWORK_CLASS_4_G = 3;

    /**
     * Return general class of network type, such as "3G" or "4G". In cases
     * where classification is contentious, this method is conservative.
     *
     * @hide
     */
    public static int getNetworkClass(int networkType) {
        switch (networkType) {
            case NETWORK_TYPE_GPRS:
            case NETWORK_TYPE_EDGE:
            case NETWORK_TYPE_CDMA:
            case NETWORK_TYPE_1xRTT:
            case NETWORK_TYPE_IDEN:
                return NETWORK_CLASS_2_G;
            case NETWORK_TYPE_UMTS:
            case NETWORK_TYPE_EVDO_0:
            case NETWORK_TYPE_EVDO_A:
            case NETWORK_TYPE_HSDPA:
            case NETWORK_TYPE_HSUPA:
            case NETWORK_TYPE_HSPA:
            case NETWORK_TYPE_EVDO_B:
            case NETWORK_TYPE_EHRPD:
            case NETWORK_TYPE_HSPAP:
                return NETWORK_CLASS_3_G;
            case NETWORK_TYPE_LTE:
                return NETWORK_CLASS_4_G;
            default:
                return NETWORK_CLASS_UNKNOWN;
        }
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @return the name of the radio technology
     *
     * @hide pending API council review
     */
    public String getNetworkTypeName() {
        return getNetworkTypeName(getNetworkType());
    }

    /** {@hide} */
    public static String getNetworkTypeName(int type) {
        switch (type) {
            case NETWORK_TYPE_GPRS:
                return "GPRS";
            case NETWORK_TYPE_EDGE:
                return "EDGE";
            case NETWORK_TYPE_UMTS:
                return "UMTS";
            case NETWORK_TYPE_HSDPA:
                return "HSDPA";
            case NETWORK_TYPE_HSUPA:
                return "HSUPA";
            case NETWORK_TYPE_HSPA:
                return "HSPA";
            case NETWORK_TYPE_CDMA:
                return "CDMA";
            case NETWORK_TYPE_EVDO_0:
                return "CDMA - EvDo rev. 0";
            case NETWORK_TYPE_EVDO_A:
                return "CDMA - EvDo rev. A";
            case NETWORK_TYPE_EVDO_B:
                return "CDMA - EvDo rev. B";
            case NETWORK_TYPE_1xRTT:
                return "CDMA - 1xRTT";
            case NETWORK_TYPE_LTE:
                return "LTE";
            case NETWORK_TYPE_EHRPD:
                return "CDMA - eHRPD";
            case NETWORK_TYPE_IDEN:
                return "iDEN";
            case NETWORK_TYPE_HSPAP:
                return "HSPA+";
            default:
                return "UNKNOWN";
        }
    }

    //
    //
    // SIM Card
    //
    //

    /** SIM card state: Unknown. Signifies that the SIM is in transition
     *  between states. For example, when the user inputs the SIM pin
     *  under PIN_REQUIRED state, a query for sim status returns
     *  this state before turning to SIM_STATE_READY. */
    public static final int SIM_STATE_UNKNOWN = 0;
    /** SIM card state: no SIM card is available in the device */
    public static final int SIM_STATE_ABSENT = 1;
    /** SIM card state: Locked: requires the user's SIM PIN to unlock */
    public static final int SIM_STATE_PIN_REQUIRED = 2;
    /** SIM card state: Locked: requires the user's SIM PUK to unlock */
    public static final int SIM_STATE_PUK_REQUIRED = 3;
    /** SIM card state: Locked: requries a network PIN to unlock */
    public static final int SIM_STATE_NETWORK_LOCKED = 4;
    /** SIM card state: Ready */
    public static final int SIM_STATE_READY = 5;

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        try {
            return getITelephony().hasIccCard();
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return false;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return false;
        }
    }

    /**
     * Returns a constant indicating the state of the
     * device SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_READY
     */
    public int getSimState() {
        String prop = SystemProperties.get(TelephonyProperties.PROPERTY_SIM_STATE);
        if ("ABSENT".equals(prop)) {
            return SIM_STATE_ABSENT;
        }
        else if ("PIN_REQUIRED".equals(prop)) {
            return SIM_STATE_PIN_REQUIRED;
        }
        else if ("PUK_REQUIRED".equals(prop)) {
            return SIM_STATE_PUK_REQUIRED;
        }
        else if ("NETWORK_LOCKED".equals(prop)) {
            return SIM_STATE_NETWORK_LOCKED;
        }
        else if ("READY".equals(prop)) {
            return SIM_STATE_READY;
        }
        else {
            return SIM_STATE_UNKNOWN;
        }
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     */
    public String getSimOperator() {
        return SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC);
    }

    /**
     * Returns the Service Provider Name (SPN).
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     */
    public String getSimOperatorName() {
        return SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA);
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     */
    public String getSimCountryIso() {
        return SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY);
    }

    /**
     * Returns the serial number of the SIM, if applicable. Return null if it is
     * unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getSimSerialNumber() {
        try {
            return getSubscriberInfo().getIccSerialNumber();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link Phone#LTE_ON_CDMA_TRUE}
     *
     * @hide
     */
    public int getLteOnCdmaMode() {
        try {
            return getITelephony().getLteOnCdmaMode();
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        }
    }

    //
    //
    // Subscriber Info
    //
    //

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getSubscriberId() {
        try {
            return getSubscriberInfo().getSubscriberId();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getLine1Number() {
        try {
            return getSubscriberInfo().getLine1Number();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     * nobody seems to call this.
     */
    public String getLine1AlphaTag() {
        try {
            return getSubscriberInfo().getLine1AlphaTag();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @hide
     */
    public String getMsisdn() {
        try {
            return getSubscriberInfo().getMsisdn();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getVoiceMailNumber() {
        try {
            return getSubscriberInfo().getVoiceMailNumber();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#CALL_PRIVILEGED CALL_PRIVILEGED}
     *
     * @hide
     */
    public String getCompleteVoiceMailNumber() {
        try {
            return getSubscriberInfo().getCompleteVoiceMailNumber();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the voice mail count. Return 0 if unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public int getVoiceMessageCount() {
        try {
            return getITelephony().getVoiceMessageCount();
        } catch (RemoteException ex) {
            return 0;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return 0;
        }
    }

    /**
     * Retrieves the alphabetic identifier associated with the voice
     * mail number.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getVoiceMailAlphaTag() {
        try {
            return getSubscriberInfo().getVoiceMailAlphaTag();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS private user identity (IMPI) that was loaded from the ISIM.
     * @return the IMPI, or null if not present or not loaded
     * @hide
     */
    public String getIsimImpi() {
        try {
            return getSubscriberInfo().getIsimImpi();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS home network domain name that was loaded from the ISIM.
     * @return the IMS domain name, or null if not present or not loaded
     * @hide
     */
    public String getIsimDomain() {
        try {
            return getSubscriberInfo().getIsimDomain();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS public user identities (IMPU) that were loaded from the ISIM.
     * @return an array of IMPU strings, with one IMPU per string, or null if
     *      not present or not loaded
     * @hide
     */
    public String[] getIsimImpu() {
        try {
            return getSubscriberInfo().getIsimImpu();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    private IPhoneSubInfo getSubscriberInfo() {
        // get it each time because that process crashes a lot
        return IPhoneSubInfo.Stub.asInterface(ServiceManager.getService("iphonesubinfo"));
    }


    /** Device call state: No activity. */
    public static final int CALL_STATE_IDLE = 0;
    /** Device call state: Ringing. A new call arrived and is
     *  ringing or waiting. In the latter case, another call is
     *  already active. */
    public static final int CALL_STATE_RINGING = 1;
    /** Device call state: Off-hook. At least one call exists
      * that is dialing, active, or on hold, and no calls are ringing
      * or waiting. */
    public static final int CALL_STATE_OFFHOOK = 2;

    /**
     * Returns a constant indicating the call state (cellular) on the device.
     */
    public int getCallState() {
        try {
            return getITelephony().getCallState();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return CALL_STATE_IDLE;
        } catch (NullPointerException ex) {
          // the phone process is restarting.
          return CALL_STATE_IDLE;
      }
    }

    /** Data connection activity: No traffic. */
    public static final int DATA_ACTIVITY_NONE = 0x00000000;
    /** Data connection activity: Currently receiving IP PPP traffic. */
    public static final int DATA_ACTIVITY_IN = 0x00000001;
    /** Data connection activity: Currently sending IP PPP traffic. */
    public static final int DATA_ACTIVITY_OUT = 0x00000002;
    /** Data connection activity: Currently both sending and receiving
     *  IP PPP traffic. */
    public static final int DATA_ACTIVITY_INOUT = DATA_ACTIVITY_IN | DATA_ACTIVITY_OUT;
    /**
     * Data connection is active, but physical link is down
     */
    public static final int DATA_ACTIVITY_DORMANT = 0x00000004;

    /**
     * Returns a constant indicating the type of activity on a data connection
     * (cellular).
     *
     * @see #DATA_ACTIVITY_NONE
     * @see #DATA_ACTIVITY_IN
     * @see #DATA_ACTIVITY_OUT
     * @see #DATA_ACTIVITY_INOUT
     * @see #DATA_ACTIVITY_DORMANT
     */
    public int getDataActivity() {
        try {
            return getITelephony().getDataActivity();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_ACTIVITY_NONE;
        } catch (NullPointerException ex) {
          // the phone process is restarting.
          return DATA_ACTIVITY_NONE;
      }
    }

    /** Data connection state: Unknown.  Used before we know the state.
     * @hide
     */
    public static final int DATA_UNKNOWN        = -1;
    /** Data connection state: Disconnected. IP traffic not available. */
    public static final int DATA_DISCONNECTED   = 0;
    /** Data connection state: Currently setting up a data connection. */
    public static final int DATA_CONNECTING     = 1;
    /** Data connection state: Connected. IP traffic should be available. */
    public static final int DATA_CONNECTED      = 2;
    /** Data connection state: Suspended. The connection is up, but IP
     * traffic is temporarily unavailable. For example, in a 2G network,
     * data activity may be suspended when a voice call arrives. */
    public static final int DATA_SUSPENDED      = 3;

    /**
     * Returns a constant indicating the current data connection state
     * (cellular).
     *
     * @see #DATA_DISCONNECTED
     * @see #DATA_CONNECTING
     * @see #DATA_CONNECTED
     * @see #DATA_SUSPENDED
     */
    public int getDataState() {
        try {
            return getITelephony().getDataState();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_DISCONNECTED;
        } catch (NullPointerException ex) {
            return DATA_DISCONNECTED;
        }
    }

    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE));
    }

    //
    //
    // PhoneStateListener
    //
    //

    /**
     * Registers a listener object to receive notification of changes
     * in specified telephony states.
     * <p>
     * To register a listener, pass a {@link PhoneStateListener}
     * and specify at least one telephony state of interest in
     * the events argument.
     *
     * At registration, and when a specified telephony state
     * changes, the telephony manager invokes the appropriate
     * callback method on the listener object and passes the
     * current (udpated) values.
     * <p>
     * To unregister a listener, pass the listener object and set the
     * events argument to
     * {@link PhoneStateListener#LISTEN_NONE LISTEN_NONE} (0).
     *
     * @param listener The {@link PhoneStateListener} object to register
     *                 (or unregister)
     * @param events The telephony state(s) of interest to the listener,
     *               as a bitwise-OR combination of {@link PhoneStateListener}
     *               LISTEN_ flags.
     */
    public void listen(PhoneStateListener listener, int events) {
        String pkgForDebug = sContext != null ? sContext.getPackageName() : "<unknown>";
        try {
            Boolean notifyNow = (getITelephony() != null);
            sRegistry.listen(pkgForDebug, listener.callback, events, notifyNow);
        } catch (RemoteException ex) {
            // system process dead
        } catch (NullPointerException ex) {
            // system process dead
        }
    }

    /**
     * Returns the CDMA ERI icon index to display
     *
     * @hide
     */
    public int getCdmaEriIconIndex() {
        try {
            return getITelephony().getCdmaEriIconIndex();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return -1;
        } catch (NullPointerException ex) {
            return -1;
        }
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     *
     * @hide
     */
    public int getCdmaEriIconMode() {
        try {
            return getITelephony().getCdmaEriIconMode();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return -1;
        } catch (NullPointerException ex) {
            return -1;
        }
    }

    /**
     * Returns the CDMA ERI text,
     *
     * @hide
     */
    public String getCdmaEriText() {
        try {
            return getITelephony().getCdmaEriText();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * @return true if the current device is "voice capable".
     * <p>
     * "Voice capable" means that this device supports circuit-switched
     * (i.e. voice) phone calls over the telephony network, and is allowed
     * to display the in-call UI while a cellular voice call is active.
     * This will be false on "data only" devices which can't make voice
     * calls and don't support any in-call UI.
     * <p>
     * Note: the meaning of this flag is subtly different from the
     * PackageManager.FEATURE_TELEPHONY system feature, which is available
     * on any device with a telephony radio, even if the device is
     * data-only.
     *
     * @hide pending API review
     */
    public boolean isVoiceCapable() {
        if (sContext == null) return true;
        return sContext.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
    }

    /**
     * @return true if the current device supports sms service.
     * <p>
     * If true, this means that the device supports both sending and
     * receiving sms via the telephony network.
     * <p>
     * Note: Voicemail waiting sms, cell broadcasting sms, and MMS are
     *       disabled when device doesn't support sms.
     *
     * @hide pending API review
     */
    public boolean isSmsCapable() {
        if (sContext == null) return true;
        return sContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sms_capable);
    }

    /**
     * Returns all observed cell information of the device.
     *
     * @return List of CellInfo or null if info unavailable.
     *
     * <p>Requires Permission:
     * (@link android.Manifest.permission#ACCESS_COARSE_UPDATES}
     */
    public List<CellInfo> getAllCellInfo() {
        try {
            return getITelephony().getAllCellInfo();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** Call state: No activity. */
    public static final int PRECISE_CALL_STATE_IDLE =           0;
    /** Call state: Active. */
    public static final int PRECISE_CALL_STATE_ACTIVE =         1;
    /** Call state: On hold. */
    public static final int PRECISE_CALL_STATE_HOLDING =        2;
    /** Call state: Dialing. */
    public static final int PRECISE_CALL_STATE_DIALING =        3;
    /** Call state: Alerting. */
    public static final int PRECISE_CALL_STATE_ALERTING =       4;
    /** Call state: Incoming. */
    public static final int PRECISE_CALL_STATE_INCOMING =       5;
    /** Call state: Waiting. */
    public static final int PRECISE_CALL_STATE_WAITING =        6;
    /** Call state: Disconnected. */
    public static final int PRECISE_CALL_STATE_DISCONNECTED =   7;
    /** Call state: Disconnecting. */
    public static final int PRECISE_CALL_STATE_DISCONNECTING =  8;

    /** Has not yet disconnected */
    public static final int DISCONNECT_CAUSE_NOT_DISCONNECTED               = 0;
    /** An incoming call that was missed and never answered */
    public static final int DISCONNECT_CAUSE_INCOMING_MISSED                = 1;
    /** Normal; Remote hangup*/
    public static final int DISCONNECT_CAUSE_NORMAL                         = 2;
    /** Normal; Local hangup */
    public static final int DISCONNECT_CAUSE_LOCAL                          = 3;
    /** Outgoing call to busy line */
    public static final int DISCONNECT_CAUSE_BUSY                           = 4;
    /** Outgoing call to congested network */
    public static final int DISCONNECT_CAUSE_CONGESTION                     = 5;
    /** Not presently used */
    public static final int DISCONNECT_CAUSE_MMI                            = 6;
    /** Invalid dial string */
    public static final int DISCONNECT_CAUSE_INVALID_NUMBER                 = 7;
    /** Cannot reach the peer */
    public static final int DISCONNECT_CAUSE_NUMBER_UNREACHABLE             = 8;
    /** Cannot reach the server */
    public static final int DISCONNECT_CAUSE_SERVER_UNREACHABLE             = 9;
    /** Invalid credentials */
    public static final int DISCONNECT_CAUSE_INVALID_CREDENTIALS            = 10;
    /** Calling from out of network is not allowed */
    public static final int DISCONNECT_CAUSE_OUT_OF_NETWORK                 = 11;
    /** Server error */
    public static final int DISCONNECT_CAUSE_SERVER_ERROR                   = 12;
    /** Client timed out */
    public static final int DISCONNECT_CAUSE_TIMED_OUT                      = 13;
    /** Client went out of network range */
    public static final int DISCONNECT_CAUSE_LOST_SIGNAL                    = 14;
    /** GSM or CDMA ACM limit exceeded */
    public static final int DISCONNECT_CAUSE_LIMIT_EXCEEDED                 = 15;
    /** An incoming call that was rejected */
    public static final int DISCONNECT_CAUSE_INCOMING_REJECTED              = 16;
    /** Radio is turned off explicitly */
    public static final int DISCONNECT_CAUSE_POWER_OFF                      = 17;
    /** Out of service */
    public static final int DISCONNECT_CAUSE_OUT_OF_SERVICE                 = 18;
    /** No ICC, ICC locked, or other ICC error */
    public static final int DISCONNECT_CAUSE_ICC_ERROR                      = 19;
    /** Call was blocked by call barring */
    public static final int DISCONNECT_CAUSE_CALL_BARRED                    = 20;
    /** Call was blocked by fixed dial number */
    public static final int DISCONNECT_CAUSE_FDN_BLOCKED                    = 21;
    /** Call was blocked by restricted all voice access */
    public static final int DISCONNECT_CAUSE_CS_RESTRICTED                  = 22;
    /** Call was blocked by restricted normal voice access */
    public static final int DISCONNECT_CAUSE_CS_RESTRICTED_NORMAL           = 23;
    /** Call was blocked by restricted emergency voice access */
    public static final int DISCONNECT_CAUSE_CS_RESTRICTED_EMERGENCY        = 24;
    /** Unassigned number */
    public static final int DISCONNECT_CAUSE_UNOBTAINABLE_NUMBER            = 25;
    /** MS is locked until next power cycle */
    public static final int DISCONNECT_CAUSE_CDMA_LOCKED_UNTIL_POWER_CYCLE  = 26;
    /** */
    public static final int DISCONNECT_CAUSE_CDMA_DROP                      = 27;
    /** INTERCEPT order received, MS state idle entered */
    public static final int DISCONNECT_CAUSE_CDMA_INTERCEPT                 = 28;
    /** MS has been redirected, call is cancelled */
    public static final int DISCONNECT_CAUSE_CDMA_REORDER                   = 29;
    /** Service option rejection */
    public static final int DISCONNECT_CAUSE_CDMA_SO_REJECT                 = 30;
    /** Requested service is rejected, retry delay is set */
    public static final int DISCONNECT_CAUSE_CDMA_RETRY_ORDER               = 31;
    /** */
    public static final int DISCONNECT_CAUSE_CDMA_ACCESS_FAILURE            = 32;
    /** */
    public static final int DISCONNECT_CAUSE_CDMA_PREEMPTED                 = 33;
    /** Not an emergency call */
    public static final int DISCONNECT_CAUSE_CDMA_NOT_EMERGENCY             = 34;
    /** Access Blocked by CDMA network */
    public static final int DISCONNECT_CAUSE_CDMA_ACCESS_BLOCKED            = 35;
    /** Unknown error or not specified */
    public static final int DISCONNECT_CAUSE_ERROR_UNSPECIFIED              = 36;

    /* Codes obtained from ril.h RIL_LastCallFailCause and CallFailCause */
    /** No disconnect cause provided. Generally a local disconnect or an incoming missed call */
    public static final int PRECISE_DISCONNECT_CAUSE_NO_DISCONNECT_CAUSE_AVAILABLE  = 0;
    public static final int PRECISE_DISCONNECT_CAUSE_UNOBTAINABLE_NUMBER            = 1;
    public static final int PRECISE_DISCONNECT_CAUSE_NORMAL                         = 16;
    public static final int PRECISE_DISCONNECT_CAUSE_BUSY                           = 17;
    public static final int PRECISE_DISCONNECT_CAUSE_NUMBER_CHANGED                 = 22;
    public static final int PRECISE_DISCONNECT_CAUSE_STATUS_ENQUIRY                 = 30;
    public static final int PRECISE_DISCONNECT_CAUSE_NORMAL_UNSPECIFIED             = 31;
    public static final int PRECISE_DISCONNECT_CAUSE_NO_CIRCUIT_AVAIL               = 34;
    public static final int PRECISE_DISCONNECT_CAUSE_TEMPORARY_FAILURE              = 41;
    public static final int PRECISE_DISCONNECT_CAUSE_SWITCHING_CONGESTION           = 42;
    public static final int PRECISE_DISCONNECT_CAUSE_CHANNEL_NOT_AVAIL              = 44;
    public static final int PRECISE_DISCONNECT_CAUSE_QOS_NOT_AVAIL                  = 49;
    public static final int PRECISE_DISCONNECT_CAUSE_BEARER_NOT_AVAIL               = 58;
    public static final int PRECISE_DISCONNECT_CAUSE_ACM_LIMIT_EXCEEDED             = 68;
    public static final int PRECISE_DISCONNECT_CAUSE_CALL_BARRED                    = 240;
    public static final int PRECISE_DISCONNECT_CAUSE_FDN_BLOCKED                    = 241;
    public static final int PRECISE_DISCONNECT_CAUSE_IMSI_UNKNOWN_IN_VLR            = 242;
    public static final int PRECISE_DISCONNECT_CAUSE_IMEI_NOT_ACCEPTED              = 243;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_LOCKED_UNTIL_POWER_CYCLE  = 1000;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_DROP                      = 1001;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_INTERCEPT                 = 1002;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_REORDER                   = 1003;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_SO_REJECT                 = 1004;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_RETRY_ORDER               = 1005;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_ACCESS_FAILURE            = 1006;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_PREEMPTED                 = 1007;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_NOT_EMERGENCY             = 1008;
    public static final int PRECISE_DISCONNECT_CAUSE_CDMA_ACCESS_BLOCKED            = 1009;
    public static final int PRECISE_DISCONNECT_CAUSE_ERROR_UNSPECIFIED              = 0xffff;
}
