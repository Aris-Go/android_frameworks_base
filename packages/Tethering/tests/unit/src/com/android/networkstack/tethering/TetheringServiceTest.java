/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.UiAutomation;
import android.content.Intent;
import android.net.IIntResultListener;
import android.net.ITetheringConnector;
import android.net.ITetheringEventCallback;
import android.net.TetheringRequestParcel;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.tethering.MockTetheringService.MockTetheringConnector;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class TetheringServiceTest {
    private static final String TEST_IFACE_NAME = "test_wlan0";
    private static final String TEST_CALLER_PKG = "test_pkg";
    private static final String TEST_ATTRIBUTION_TAG = null;
    @Mock private ITetheringEventCallback mITetheringEventCallback;
    @Rule public ServiceTestRule mServiceTestRule;
    private Tethering mTethering;
    private Intent mMockServiceIntent;
    private ITetheringConnector mTetheringConnector;
    private UiAutomation mUiAutomation;

    private class TestTetheringResult extends IIntResultListener.Stub {
        private int mResult = -1; // Default value that does not match any result code.
        @Override
        public void onResult(final int resultCode) {
            mResult = resultCode;
        }

        public void assertResult(final int expected) {
            assertEquals(expected, mResult);
        }
    }

    private class MyResultReceiver extends ResultReceiver {
        MyResultReceiver(Handler handler) {
            super(handler);
        }
        private int mResult = -1; // Default value that does not match any result code.
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mResult = resultCode;
        }

        public void assertResult(int expected) {
            assertEquals(expected, mResult);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mServiceTestRule = new ServiceTestRule();
        mMockServiceIntent = new Intent(
                InstrumentationRegistry.getTargetContext(),
                MockTetheringService.class);
        final MockTetheringConnector mockConnector =
                (MockTetheringConnector) mServiceTestRule.bindService(mMockServiceIntent);
        mTetheringConnector = mockConnector.getTetheringConnector();
        final MockTetheringService service = mockConnector.getService();
        mTethering = service.getTethering();
        when(mTethering.isTetheringSupported()).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        mServiceTestRule.unbindService();
        mUiAutomation.dropShellPermissionIdentity();
    }

    private void assertCheckAndNotifyCommonPermissionFail(final TestTetheringResult result) {
        verify(mTethering).isTetherProvisioningRequired();
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
    }

    @Test
    public void testTether() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.tether(TEST_IFACE_NAME, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result);
        assertCheckAndNotifyCommonPermissionFail(result);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        when(mTethering.tether(TEST_IFACE_NAME)).thenReturn(TETHER_ERROR_NO_ERROR);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.tether(TEST_IFACE_NAME, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG,
                result2);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).tether(TEST_IFACE_NAME);
        verifyNoMoreInteractions(mTethering);
        result2.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testUntether() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.untether(TEST_IFACE_NAME, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG,
                result);
        assertCheckAndNotifyCommonPermissionFail(result);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        when(mTethering.untether(TEST_IFACE_NAME)).thenReturn(TETHER_ERROR_NO_ERROR);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.untether(TEST_IFACE_NAME, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG,
                result2);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).untether(TEST_IFACE_NAME);
        verifyNoMoreInteractions(mTethering);
        result2.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testSetUsbTethering() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.setUsbTethering(true /* enable */, TEST_CALLER_PKG,
                TEST_ATTRIBUTION_TAG, result);
        assertCheckAndNotifyCommonPermissionFail(result);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        when(mTethering.setUsbTethering(true /* enable */)).thenReturn(TETHER_ERROR_NO_ERROR);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.setUsbTethering(true /* enable */, TEST_CALLER_PKG,
                TEST_ATTRIBUTION_TAG, result2);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).setUsbTethering(true /* enable */);
        verifyNoMoreInteractions(mTethering);
        result2.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testStartTethering() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        final TetheringRequestParcel request = new TetheringRequestParcel();
        request.tetheringType = TETHERING_WIFI;
        mTetheringConnector.startTethering(request, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result);
        assertCheckAndNotifyCommonPermissionFail(result);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.startTethering(request, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result2);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).startTethering(eq(request), eq(result2));
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testStartTetheringWithExemptFromEntitlementCheck() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        final TetheringRequestParcel request = new TetheringRequestParcel();
        request.tetheringType = TETHERING_WIFI;
        request.exemptFromEntitlementCheck = true;
        mTetheringConnector.startTethering(request, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result);
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.startTethering(request, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result2);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).startTethering(eq(request), eq(result2));
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testStopTethering() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.stopTethering(TETHERING_WIFI, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG,
                result);
        assertCheckAndNotifyCommonPermissionFail(result);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.stopTethering(TETHERING_WIFI, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG,
                result2);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).stopTethering(TETHERING_WIFI);
        verifyNoMoreInteractions(mTethering);
        result2.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testRequestLatestTetheringEntitlementResult() throws Exception {
        final MyResultReceiver result = new MyResultReceiver(null);
        mTetheringConnector.requestLatestTetheringEntitlementResult(TETHERING_WIFI, result,
                true /* showEntitlementUi */, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG);
        verify(mTethering).isTetherProvisioningRequired();
        verifyNoMoreInteractions(mTethering);
        result.assertResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        final MyResultReceiver result2 = new MyResultReceiver(null);
        mTetheringConnector.requestLatestTetheringEntitlementResult(TETHERING_WIFI, result2,
                true /* showEntitlementUi */, TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).requestLatestTetheringEntitlementResult(eq(TETHERING_WIFI),
                eq(result2), eq(true) /* showEntitlementUi */);
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testRegisterTetheringEventCallback() throws Exception {
        mTetheringConnector.registerTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verify(mITetheringEventCallback).onCallbackStopped(
                TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);
        verifyNoMoreInteractions(mTethering);

        mUiAutomation.adoptShellPermissionIdentity(ACCESS_NETWORK_STATE);
        mTetheringConnector.registerTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verify(mTethering).registerTetheringEventCallback(eq(mITetheringEventCallback));
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testUnregisterTetheringEventCallback() throws Exception {
        mTetheringConnector.unregisterTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verifyNoMoreInteractions(mTethering);
        verify(mITetheringEventCallback).onCallbackStopped(
                TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);

        mUiAutomation.adoptShellPermissionIdentity(ACCESS_NETWORK_STATE);
        mTetheringConnector.unregisterTetheringEventCallback(mITetheringEventCallback,
                TEST_CALLER_PKG);
        verify(mTethering).unregisterTetheringEventCallback(
                eq(mITetheringEventCallback));
        verifyNoMoreInteractions(mTethering);
    }

    @Test
    public void testStopAllTethering() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.stopAllTethering(TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result);
        assertCheckAndNotifyCommonPermissionFail(result);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.stopAllTethering(TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result2);
        verify(mTethering).isTetheringSupported();
        verify(mTethering).untetherAll();
        verifyNoMoreInteractions(mTethering);
        result2.assertResult(TETHER_ERROR_NO_ERROR);
    }

    @Test
    public void testIsTetheringSupported() throws Exception {
        final TestTetheringResult result = new TestTetheringResult();
        mTetheringConnector.isTetheringSupported(TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result);
        assertCheckAndNotifyCommonPermissionFail(result);

        mUiAutomation.adoptShellPermissionIdentity(TETHER_PRIVILEGED);
        final TestTetheringResult result2 = new TestTetheringResult();
        mTetheringConnector.isTetheringSupported(TEST_CALLER_PKG, TEST_ATTRIBUTION_TAG, result2);
        verify(mTethering).isTetheringSupported();
        verifyNoMoreInteractions(mTethering);
        result2.assertResult(TETHER_ERROR_NO_ERROR);
    }
}
