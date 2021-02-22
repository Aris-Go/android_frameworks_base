/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.vcn.persistablebundleutils;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static org.junit.Assert.assertEquals;

import android.net.InetAddresses;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.SaProposal;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TunnelModeChildSessionParamsUtilsTest {
    private static final ChildSaProposal SA_PROPOSAL;
    private static final IkeTrafficSelector TS_INBOUND;
    private static final IkeTrafficSelector TS_OUTBOUND;
    private static final int HARD_LIFETIME = (int) TimeUnit.HOURS.toSeconds(3L);
    private static final int SOFT_LIFETIME = (int) TimeUnit.HOURS.toSeconds(1L);

    static {
        SA_PROPOSAL =
                new ChildSaProposal.Builder()
                        .addEncryptionAlgorithm(
                                SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12,
                                SaProposal.KEY_LEN_AES_128)
                        .build();
        TS_INBOUND =
                new IkeTrafficSelector(
                        16,
                        65520,
                        InetAddresses.parseNumericAddress("192.0.2.100"),
                        InetAddresses.parseNumericAddress("192.0.2.101"));
        TS_OUTBOUND =
                new IkeTrafficSelector(
                        32,
                        256,
                        InetAddresses.parseNumericAddress("192.0.2.200"),
                        InetAddresses.parseNumericAddress("192.0.2.255"));
    }

    private TunnelModeChildSessionParams.Builder createBuilderMinimum() {
        return new TunnelModeChildSessionParams.Builder().addSaProposal(SA_PROPOSAL);
    }

    private static void verifyPersistableBundleEncodeDecodeIsLossless(
            TunnelModeChildSessionParams params) {
        PersistableBundle bundle = TunnelModeChildSessionParamsUtils.toPersistableBundle(params);
        TunnelModeChildSessionParams result =
                TunnelModeChildSessionParamsUtils.fromPersistableBundle(bundle);

        assertEquals(params, result);
    }

    @Test
    public void testMinimumParamsEncodeDecodeIsLossless() throws Exception {
        TunnelModeChildSessionParams sessionParams = createBuilderMinimum().build();
        verifyPersistableBundleEncodeDecodeIsLossless(sessionParams);
    }

    @Test
    public void testSetTsEncodeDecodeIsLossless() throws Exception {
        TunnelModeChildSessionParams sessionParams =
                createBuilderMinimum()
                        .addInboundTrafficSelectors(TS_INBOUND)
                        .addOutboundTrafficSelectors(TS_OUTBOUND)
                        .build();
        verifyPersistableBundleEncodeDecodeIsLossless(sessionParams);
    }

    @Test
    public void testSetLifetimesEncodeDecodeIsLossless() throws Exception {
        TunnelModeChildSessionParams sessionParams =
                createBuilderMinimum().setLifetimeSeconds(HARD_LIFETIME, SOFT_LIFETIME).build();
        verifyPersistableBundleEncodeDecodeIsLossless(sessionParams);
    }

    @Test
    public void testSetConfigRequestsEncodeDecodeIsLossless() throws Exception {
        final int ipv6PrefixLen = 64;
        final Inet4Address ipv4Address =
                (Inet4Address) InetAddresses.parseNumericAddress("192.0.2.100");
        final Inet6Address ipv6Address =
                (Inet6Address) InetAddresses.parseNumericAddress("2001:db8::1");

        TunnelModeChildSessionParams sessionParams =
                createBuilderMinimum()
                        .addInternalAddressRequest(AF_INET)
                        .addInternalAddressRequest(AF_INET6)
                        .addInternalAddressRequest(ipv4Address)
                        .addInternalAddressRequest(ipv6Address, ipv6PrefixLen)
                        .addInternalDnsServerRequest(AF_INET)
                        .addInternalDnsServerRequest(AF_INET6)
                        .addInternalDhcpServerRequest(AF_INET)
                        .build();
        verifyPersistableBundleEncodeDecodeIsLossless(sessionParams);
    }
}
