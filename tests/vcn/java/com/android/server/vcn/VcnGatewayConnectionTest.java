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

package com.android.server.vcn;


import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.net.NetworkCapabilities;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.telephony.SubscriptionInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tests for TelephonySubscriptionTracker */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionTest extends VcnGatewayConnectionTestBase {
    private static final ParcelUuid TEST_PARCEL_UUID = new ParcelUuid(UUID.randomUUID());
    private static final int TEST_SIM_SLOT_INDEX = 1;
    private static final int TEST_SUBSCRIPTION_ID_1 = 2;
    private static final SubscriptionInfo TEST_SUBINFO_1 = mock(SubscriptionInfo.class);
    private static final int TEST_SUBSCRIPTION_ID_2 = 3;
    private static final SubscriptionInfo TEST_SUBINFO_2 = mock(SubscriptionInfo.class);
    private static final Map<Integer, ParcelUuid> TEST_SUBID_TO_GROUP_MAP;

    static {
        final Map<Integer, ParcelUuid> subIdToGroupMap = new HashMap<>();
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_1, TEST_PARCEL_UUID);
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_2, TEST_PARCEL_UUID);
        TEST_SUBID_TO_GROUP_MAP = Collections.unmodifiableMap(subIdToGroupMap);
    }

    @Test
    public void testBuildNetworkCapabilities() throws Exception {
        final NetworkCapabilities caps =
                VcnGatewayConnection.buildNetworkCapabilities(
                        VcnGatewayConnectionConfigTest.buildTestConfig());

        for (int exposedCapability : VcnGatewayConnectionConfigTest.EXPOSED_CAPS) {
            assertTrue(caps.hasCapability(exposedCapability));
        }
    }

    @Test
    public void testSubscriptionSnapshotUpdateNotifiesUnderlyingNetworkTracker() {
        final TelephonySubscriptionSnapshot updatedSnapshot =
                mock(TelephonySubscriptionSnapshot.class);
        mGatewayConnection.updateSubscriptionSnapshot(updatedSnapshot);

        verify(mUnderlyingNetworkTracker).updateSubscriptionSnapshot(eq(updatedSnapshot));
        verifyWakeLockAcquired();

        mTestLooper.dispatchAll();

        verifyWakeLockReleased();
    }
}
