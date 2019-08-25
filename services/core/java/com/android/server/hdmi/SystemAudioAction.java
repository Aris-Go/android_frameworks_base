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

package com.android.server.hdmi;

import android.annotation.Nullable;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.RemoteException;
import android.util.Slog;
import java.util.Arrays;
import java.util.List;

/**
 * Base feature action class for SystemAudioActionFromTv and SystemAudioActionFromAvr.
 */
abstract class SystemAudioAction extends HdmiCecFeatureAction {
    private static final String TAG = "SystemAudioAction";

    public static final int AUDIO_FORMAT_MASK = 0xF8;
    public static final int AUDIO_FORMAT_DDP = 0x50;

    // Transient state to differentiate with STATE_NONE where the on-finished callback
    // will not be called.
    private static final int STATE_CHECK_ROUTING_IN_PRGRESS = 1;

    // State in which waits for <SetSystemAudioMode>.
    private static final int STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE = 2;

    // State that waits for <ReportAudioStatus>.
    private static final int STATE_WAIT_FOR_REPORT_SHORT_AUDIO_DESCRIPTOR = 3;

    // State that start AudioStatusAction.
    private static final int STATE_START_AUDIOSTATUSACTION = 4;

    private static final int MAX_SEND_RETRY_COUNT = 2;

    private static final int ON_TIMEOUT_MS = 5000;
    private static final int OFF_TIMEOUT_MS = HdmiConfig.TIMEOUT_MS;

    // Logical address of AV Receiver.
    protected final int mAvrLogicalAddress;

    // The target audio status of the action, whether to enable the system audio mode or not.
    protected boolean mTargetAudioStatus;

    // The audio format to be confirmed with AVR.
    protected int[] mSad = new int[] { (int)Constants.AUD_FMT_CODE_DOLBY_DIGITAL }; // EAC3

    @Nullable private final IHdmiControlCallback mCallback;

    private int mSendRetryCount = 0;

    /**
     * Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param avrAddress logical address of AVR device
     * @param targetStatus Whether to enable the system audio mode or not
     * @param callback callback interface to be notified when it's done
     * @throw IllegalArugmentException if device type of sourceAddress and avrAddress is invalid
     */
    SystemAudioAction(HdmiCecLocalDevice source, int avrAddress, boolean targetStatus,
            IHdmiControlCallback callback) {
        super(source);
        HdmiUtils.verifyAddressType(avrAddress, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mAvrLogicalAddress = avrAddress;
        mTargetAudioStatus = targetStatus;
        mCallback = callback;
    }

    // Seq #27
    protected void sendSystemAudioModeRequest() {
        List<RoutingControlAction> routingActions = getActions(RoutingControlAction.class);
        if (!routingActions.isEmpty()) {
            mState = STATE_CHECK_ROUTING_IN_PRGRESS;
            // Should have only one Routing Control Action
            RoutingControlAction routingAction = routingActions.get(0);
            routingAction.addOnFinishedCallback(this, new Runnable() {
                @Override
                public void run() {
                    sendSystemAudioModeRequestInternal();
                }
            });
            return;
        }
        sendSystemAudioModeRequestInternal();
    }

    private void sendSystemAudioModeRequestInternal() {
        HdmiCecMessage command = HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                getSourceAddress(),
                mAvrLogicalAddress, getSystemAudioModeRequestParam(), mTargetAudioStatus);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    HdmiLogger.debug("Failed to send <System Audio Mode Request>:" + error);
                    setSystemAudioMode(false);
                    finishWithCallback(HdmiControlManager.RESULT_COMMUNICATION_FAILED);
                }
            }
        });
        mState = STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE;
        addTimer(mState, mTargetAudioStatus ? ON_TIMEOUT_MS : OFF_TIMEOUT_MS);
    }

    protected void sendRequestShortAudioDescriptor() {
        sendCommand(HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                        getSourceAddress(), mAvrLogicalAddress, mSad),
                        new HdmiControlService.SendMessageCallback() {
                            @Override
                            public void onSendCompleted(int error) {
                                if (error != SendMessageResult.SUCCESS) {
                                    HdmiLogger.debug(
                                        "Failed to send <Request Short Audio Descriptor>:"
                                        + error);
                                    handleSendRequestShortAudioDescriptorFailure();
                                }
                            }
                        });
        mState = STATE_WAIT_FOR_REPORT_SHORT_AUDIO_DESCRIPTOR;
        addTimer(mState, mTargetAudioStatus ? ON_TIMEOUT_MS : OFF_TIMEOUT_MS);
    }

    private void handleSendRequestShortAudioDescriptorFailure() {
        // Still return SUCCESS to callback.
        finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
    }

    private void handleSendRequestShortAudioDescriptorTimeout() {
        if (!mTargetAudioStatus  // Don't retry for Off case.
                || mSendRetryCount++ >= MAX_SEND_RETRY_COUNT) {
            HdmiLogger.debug("Wait for <Report Short Audio Descriptor> timeout");
            mState = STATE_START_AUDIOSTATUSACTION;
            return;
        }
        sendRequestShortAudioDescriptor();
    }

    private int getSystemAudioModeRequestParam() {
        // <System Audio Mode Request> takes the physical address of the source device
        // as a parameter. Get it from following candidates, in the order listed below:
        // 1) physical address of the active source
        // 2) active routing path
        // 3) physical address of TV
        if (tv().getActiveSource().isValid()) {
            return tv().getActiveSource().physicalAddress;
        }
        int param = tv().getActivePath();
        return param != Constants.INVALID_PHYSICAL_ADDRESS
                ? param : Constants.PATH_INTERNAL;
    }

    private void handleSendSystemAudioModeRequestTimeout() {
        if (!mTargetAudioStatus  // Don't retry for Off case.
                || mSendRetryCount++ >= MAX_SEND_RETRY_COUNT) {
            HdmiLogger.debug("[T]:wait for <Set System Audio Mode>.");
            setSystemAudioMode(false);
            finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
            return;
        }
        sendSystemAudioModeRequest();
    }

    protected void setSystemAudioMode(boolean mode) {
        tv().setSystemAudioMode(mode);
    }

    @Override
    final boolean processCommand(HdmiCecMessage cmd) {
        if (cmd.getSource() != mAvrLogicalAddress) {
            return false;
        }
        switch (mState) {
            case STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE:
                if (cmd.getOpcode() == Constants.MESSAGE_FEATURE_ABORT
                        && (cmd.getParams()[0] & 0xFF)
                                == Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST) {
                    HdmiLogger.debug("Failed to start system audio mode request.");
                    setSystemAudioMode(false);
                    finishWithCallback(HdmiControlManager.RESULT_EXCEPTION);
                    return true;
                }
                if (cmd.getOpcode() != Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE
                        || !HdmiUtils.checkCommandSource(cmd, mAvrLogicalAddress, TAG)) {
                    return false;
                }
                boolean receivedStatus = HdmiUtils.parseCommandParamSystemAudioStatus(cmd);
                if (receivedStatus == mTargetAudioStatus) {
                    setSystemAudioMode(receivedStatus);
                    if (receivedStatus) {
                        Slog.i(TAG, "Send RequestShortAudioDescriptor");
                        HdmiCecLocalDeviceTv tv = tv();
                        HdmiDeviceInfo avr = tv.getAvrDeviceInfo();
                        if (tv.isConnectedToArcPort(avr.getPhysicalAddress())
                                && tv.isDirectConnectAddress(avr.getPhysicalAddress())) {
                            sendRequestShortAudioDescriptor();
                        }
                     }
                    return true;
                } else {
                    HdmiLogger.debug("Unexpected system audio mode request:" + receivedStatus);
                    // Unexpected response, consider the request is newly initiated by AVR.
                    // To return 'false' will initiate new SystemAudioActionFromAvr by the control
                    // service.
                    finishWithCallback(HdmiControlManager.RESULT_EXCEPTION);
                    return false;
                }
            case STATE_WAIT_FOR_REPORT_SHORT_AUDIO_DESCRIPTOR:
                // <Feature Abort>[]
                if (cmd.getOpcode() == Constants.MESSAGE_FEATURE_ABORT
                    && (cmd.getParams()[0] & 0xFF)
                            == Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR) {
                    HdmiLogger.debug("AVR not support SAD- feature abort");
                    mState = STATE_START_AUDIOSTATUSACTION;
                    startAudioStatusAction();
                    return true;
                 }
                // msg not belong to this action
                if (cmd.getOpcode() != Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR
                              || !HdmiUtils.checkCommandSource(cmd, mAvrLogicalAddress, TAG)) {
                    return false;
                }
                setShortAudioDescriptor(cmd.getParams());
                mState = STATE_START_AUDIOSTATUSACTION;
                startAudioStatusAction();
                return true;
            default:
                return false;
        }
    }

    protected void setShortAudioDescriptor(byte[] params) {
        Slog.w(TAG, "setShortAudioDescriptor");
        int size = params.length;
        int num = size/3;
        if (num < 1 || (params.length%3)!=0) {
            return;
        }
        for (int i=0; i<size; i++) {
            Slog.i(TAG, "SAD[" + i + "]:" + params[i]);
            tv().mAvrSupporedFormats.add(params[i]);
            if(i%3==0) {
                switch (params[i]&AUDIO_FORMAT_MASK) {
                    case AUDIO_FORMAT_DDP:
                        Slog.i(TAG, "AVR support DDP");
                        tv().setAtmosSuppotedEnabled(params[2]%2);
                        break;
                    default:
                        break;
                 }
             }
        }
    }

    protected void startAudioStatusAction() {
        addAndStartAction(new SystemAudioStatusAction(tv(), mAvrLogicalAddress, mCallback));
        finish();
    }

    protected void removeSystemAudioActionInProgress() {
        removeActionExcept(SystemAudioActionFromTv.class, this);
        removeActionExcept(SystemAudioActionFromAvr.class, this);
    }

    @Override
    final void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }
        switch (mState) {
            case STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE:
                handleSendSystemAudioModeRequestTimeout();
                return;
            case STATE_WAIT_FOR_REPORT_SHORT_AUDIO_DESCRIPTOR:
                handleSendRequestShortAudioDescriptorTimeout();
                return;
        }
    }

    // TODO: if IHdmiControlCallback is general to other FeatureAction,
    //       move it into FeatureAction.
    protected void finishWithCallback(int returnCode) {
        if (mCallback != null) {
            try {
                mCallback.onComplete(returnCode);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to invoke callback.", e);
            }
        }
        finish();
    }
}
