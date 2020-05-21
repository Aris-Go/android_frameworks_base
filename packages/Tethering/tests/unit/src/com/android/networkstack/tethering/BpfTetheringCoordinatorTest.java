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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStats.UID_TETHERING;
import static android.net.RouteInfo.RTN_UNICAST;
import static android.net.netstats.provider.NetworkStatsProvider.QUOTA_UNLIMITED;

import static com.android.networkstack.tethering.BpfTetheringCoordinator
        .DEFAULT_PERFORM_POLL_INTERVAL_MS;
import static com.android.networkstack.tethering.BpfTetheringCoordinator.StatsType;
import static com.android.networkstack.tethering.BpfTetheringCoordinator.StatsType.STATS_PER_IFACE;
import static com.android.networkstack.tethering.BpfTetheringCoordinator.StatsType.STATS_PER_UID;

import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.usage.NetworkStatsManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkStats;
import android.net.NetworkStats.Entry;
import android.net.RouteInfo;
import android.net.TetherStatsParcel;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.tethering.BpfTetheringCoordinator.Ipv6ForwardingRule;
import com.android.testutils.TestableNetworkStatsProviderCbBinder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BpfTetheringCoordinatorTest {
    @Mock private NetworkStatsManager mStatsManager;
    @Mock private INetd mNetd;
    @Mock private TetheringConfiguration mTetherConfig;
    // Late init since methods must be called by the thread that created this object.
    private TestableNetworkStatsProviderCbBinder mTetherStatsProviderCb;
    private HashMap<String, Integer> mInterfaceIndices = new HashMap<>();
    private BpfTetheringCoordinator.BpfTetherStatsProvider mTetherStatsProvider;
    private final ArgumentCaptor<ArrayList> mStringArrayCaptor =
            ArgumentCaptor.forClass(ArrayList.class);
    private final TestLooper mTestLooper = new TestLooper();
    private BpfTetheringCoordinator.Dependencies mDeps =
            new BpfTetheringCoordinator.Dependencies() {
            @Override
            int getPerformPollInterval() {
                return DEFAULT_PERFORM_POLL_INTERVAL_MS;
            }
            @Override
            int getInterfaceIndex(@NonNull String iface) {
                Integer ifIndex = mInterfaceIndices.get(iface);
                return (ifIndex != null) ? ifIndex : 0;
            }
            Handler getHandler() {
                return new Handler(mTestLooper.getLooper());
            }
            INetd getNetd() {
                return mNetd;
            }
            NetworkStatsManager getNetworkStatsManager() {
                return mStatsManager;
            }
            SharedLog getSharedLog() {
                return new SharedLog("test");
            }
            TetheringConfiguration getTetherConfig() {
                return mTetherConfig;
            }
    };

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mTetherConfig.isBpfOffloadEnabled()).thenReturn(true /* default value */);
    }

    private void waitForIdle() {
        mTestLooper.dispatchAll();
    }

    private void setInterfaceIndex(@NonNull String iface, int ifIndex) {
        mInterfaceIndices.put(iface, ifIndex);
    }

    private void setupFunctioningNetdInterface() throws Exception {
        when(mNetd.tetherOffloadGetStats()).thenReturn(new TetherStatsParcel[0]);
    }

    private BpfTetheringCoordinator makeBpfTetheringCoordinator() throws Exception {
        BpfTetheringCoordinator coordinator = new BpfTetheringCoordinator(mDeps);
        final ArgumentCaptor<BpfTetheringCoordinator.BpfTetherStatsProvider>
                tetherStatsProviderCaptor =
                ArgumentCaptor.forClass(BpfTetheringCoordinator.BpfTetherStatsProvider.class);
        verify(mStatsManager).registerNetworkStatsProvider(anyString(),
                tetherStatsProviderCaptor.capture());
        mTetherStatsProvider = tetherStatsProviderCaptor.getValue();
        assertNotNull(mTetherStatsProvider);
        mTetherStatsProviderCb = new TestableNetworkStatsProviderCbBinder();
        mTetherStatsProvider.setProviderCallbackBinder(mTetherStatsProviderCb);
        return coordinator;
    }

    @NonNull
    private static Entry buildTestEntry(@NonNull StatsType how,
            @NonNull String iface, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        return new Entry(iface, how == STATS_PER_IFACE ? UID_ALL : UID_TETHERING,
                SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, rxBytes,
                rxPackets, txBytes, txPackets, 0L);
    }

    @NonNull
    private static TetherStatsParcel buildTestTetherStatsParcel(@NonNull Integer ifIndex,
            long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final TetherStatsParcel parcel = new TetherStatsParcel();
        parcel.ifIndex = ifIndex;
        parcel.rxBytes = rxBytes;
        parcel.rxPackets = rxPackets;
        parcel.txBytes = txBytes;
        parcel.txPackets = txPackets;
        return parcel;
    }

    @Test
    public void testGetForwardedStats() throws Exception {
        setupFunctioningNetdInterface();

        final BpfTetheringCoordinator coordinator = makeBpfTetheringCoordinator();
        coordinator.start();

        final String wlanIface = "wlan0";
        final Integer wlanIfIndex = 100;
        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 101;

        // Add interface name to lookup table. In realistic case, the upstream interface name will
        // be added by IpServer when IpServer has been noticed with a new IPv6 upstream update
        // event.
        coordinator.addUpstreamNameToLookupTable(wlanIfIndex, wlanIface);
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // [1] Both interface stats are changed.
        // Setup the tether stats of wlan and mobile interface. Note that move forward the time of
        // the looper to make sure the new tether stats has been updated by polling update thread.
        TetherStatsParcel[] tetherStatsList = new TetherStatsParcel[] {
                buildTestTetherStatsParcel(wlanIfIndex, 1000, 100, 2000, 200),
                buildTestTetherStatsParcel(mobileIfIndex, 3000, 300, 4000, 400)};
        when(mNetd.tetherOffloadGetStats()).thenReturn(tetherStatsList);
        mTestLooper.moveTimeForward(DEFAULT_PERFORM_POLL_INTERVAL_MS);
        waitForIdle();

        final NetworkStats expectedIfaceStats = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, wlanIface, 1000, 100, 2000, 200))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 3000, 300, 4000, 400));

        final NetworkStats expectedUidStats = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, wlanIface, 1000, 100, 2000, 200))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 3000, 300, 4000, 400));

        // Force pushing stats update to verify the stats reported.
        // TODO: Perhaps make #expectNotifyStatsUpdated to use test TetherStatsParcel object for
        // verifying the notification.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(expectedIfaceStats, expectedUidStats);

        // [2] Only one interface stats is changed.
        // The tether stats of mobile interface is accumulated and The tether stats of wlan
        // interface is the same.
        TetherStatsParcel[] tetherStatsListAccu = new TetherStatsParcel[] {
                buildTestTetherStatsParcel(wlanIfIndex, 1000, 100, 2000, 200),
                buildTestTetherStatsParcel(mobileIfIndex, 3010, 320, 4030, 440)};
        when(mNetd.tetherOffloadGetStats()).thenReturn(tetherStatsListAccu);
        mTestLooper.moveTimeForward(DEFAULT_PERFORM_POLL_INTERVAL_MS);
        waitForIdle();

        final NetworkStats expectedIfaceStatsDiff = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_IFACE, wlanIface, 0, 0, 0, 0))
                .addEntry(buildTestEntry(STATS_PER_IFACE, mobileIface, 10, 20, 30, 40));

        final NetworkStats expectedUidStatsDiff = new NetworkStats(0L, 2)
                .addEntry(buildTestEntry(STATS_PER_UID, wlanIface, 0, 0, 0, 0))
                .addEntry(buildTestEntry(STATS_PER_UID, mobileIface, 10, 20, 30, 40));

        // Force pushing stats update to verify that only diff of stats is reported.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(expectedIfaceStatsDiff,
                expectedUidStatsDiff);

        // [3] Stop coordinator.
        // Shutdown the coordinator and clear the invocation history, especially the
        // tetherOffloadGetStats() calls.
        coordinator.stop();
        clearInvocations(mNetd);

        // Verify the polling update thread stopped.
        mTestLooper.moveTimeForward(DEFAULT_PERFORM_POLL_INTERVAL_MS);
        waitForIdle();
        verify(mNetd, never()).tetherOffloadGetStats();
    }

    @Test
    public void testOnSetAlert() throws Exception {
        setupFunctioningNetdInterface();

        final BpfTetheringCoordinator coordinator = makeBpfTetheringCoordinator();
        coordinator.start();

        final String mobileIface = "rmnet_data0";
        final Integer mobileIfIndex = 100;
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        // Verify that set quota to 0 will immediately triggers an callback.
        mTetherStatsProvider.onSetAlert(0);
        waitForIdle();
        mTetherStatsProviderCb.expectNotifyAlertReached();

        // Verify that notifyAlertReached never fired if quota is not yet reached.
        when(mNetd.tetherOffloadGetStats()).thenReturn(
                new TetherStatsParcel[] {buildTestTetherStatsParcel(mobileIfIndex, 0, 0, 0, 0)});
        mTetherStatsProvider.onSetAlert(100);
        mTestLooper.moveTimeForward(DEFAULT_PERFORM_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.assertNoCallback();

        // Verify that notifyAlertReached fired when quota is reached.
        when(mNetd.tetherOffloadGetStats()).thenReturn(
                new TetherStatsParcel[] {buildTestTetherStatsParcel(mobileIfIndex, 50, 0, 50, 0)});
        mTestLooper.moveTimeForward(DEFAULT_PERFORM_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.expectNotifyAlertReached();

        // Verify that set quota with UNLIMITED won't trigger any callback.
        mTetherStatsProvider.onSetAlert(QUOTA_UNLIMITED);
        mTestLooper.moveTimeForward(DEFAULT_PERFORM_POLL_INTERVAL_MS);
        waitForIdle();
        mTetherStatsProviderCb.assertNoCallback();
    }

    @NonNull
    private UpstreamNetworkState createDualStackUpstream(
            final int transportType, final String interfaceName) {
        final String testDnsServer = "2001:4860:4860::8888";
        final String testIpv6Address = "2001:db8::1/64";
        final String testIpv4Address = "192.168.100.1/24";

        final Network network = mock(Network.class);
        final NetworkCapabilities netCap =
                new NetworkCapabilities.Builder().addTransportType(transportType).build();
        final InetAddress dns = InetAddresses.parseNumericAddress(testDnsServer);
        final LinkProperties linkProp = new LinkProperties();
        linkProp.setInterfaceName(interfaceName);
        linkProp.addLinkAddress(new LinkAddress(testIpv6Address));
        linkProp.addLinkAddress(new LinkAddress(testIpv4Address));
        linkProp.addRoute(new RouteInfo(new IpPrefix("::/0"), null, interfaceName, RTN_UNICAST));
        linkProp.addRoute(new RouteInfo(new IpPrefix("0.0.0.0/0"), null, interfaceName,
                    RTN_UNICAST));
        linkProp.addDnsServer(dns);
        return new UpstreamNetworkState(linkProp, netCap, network);
    }

    @NonNull
    private static Ipv6ForwardingRule buildTestForwardingRule(
            int upstreamIfindex, @NonNull InetAddress address, @NonNull MacAddress dstMac) {
        final int downstreamIfindex = 1000;
        final MacAddress srcMac = MacAddress.ALL_ZEROS_ADDRESS;
        return new Ipv6ForwardingRule(upstreamIfindex, downstreamIfindex, (Inet6Address) address,
                srcMac, dstMac);
    }

    @Test
    public void testSetInterfaceQuota() throws Exception {
        setupFunctioningNetdInterface();

        final BpfTetheringCoordinator coordinator = makeBpfTetheringCoordinator();
        coordinator.start();

        final String ethernetIface = "eth1";
        final String mobileIface = "rmnet_data0";
        final Integer ethernetIfIndex = 100;
        final Integer mobileIfIndex = 101;
        final long ethernetLimit = 12345;
        final long mobileLimit = 12345678;

        final InetAddress neighA = InetAddresses.parseNumericAddress("2001:db8::1");
        final InetAddress neighB = InetAddresses.parseNumericAddress("2001:db8::2");
        final MacAddress macA = MacAddress.fromString("00:00:00:00:00:0a");
        final MacAddress macB = MacAddress.fromString("11:22:33:00:00:0b");

        setInterfaceIndex(ethernetIface, ethernetIfIndex);
        setInterfaceIndex(mobileIface, mobileIfIndex);
        coordinator.addUpstreamNameToLookupTable(ethernetIfIndex, ethernetIface);
        coordinator.addUpstreamNameToLookupTable(mobileIfIndex, mobileIface);

        final InOrder inOrder = inOrder(mNetd);
        when(mNetd.tetherOffloadGetAndClearStats(ethernetIfIndex))
                .thenReturn(buildTestTetherStatsParcel(ethernetIfIndex, 10, 20, 30, 40));

        // Update Ethernet as current upstream.
        final UpstreamNetworkState ethernetUpstream = createDualStackUpstream(
                TRANSPORT_ETHERNET, ethernetIface);
        coordinator.updateUpstreamNetworkState(ethernetUpstream);

        // Applying an interface quota to the current upstream does not take any immediate action.
        mTetherStatsProvider.onSetLimit(ethernetIface, ethernetLimit);
        waitForIdle();
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(anyInt(), anyLong());

        // Adding the first rule on current upstream immediately send the quota to netd.
        Ipv6ForwardingRule ethernetRuleA = buildTestForwardingRule(ethernetIfIndex, neighA, macA);
        coordinator.addForwardingRule(ethernetRuleA);
        waitForIdle();
        inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(ethernetIfIndex, ethernetLimit);
        inOrder.verifyNoMoreInteractions();

        // Adding the second rule on current upstream does not send the quota to netd.
        Ipv6ForwardingRule ethernetRuleB = buildTestForwardingRule(ethernetIfIndex, neighB, macB);
        coordinator.addForwardingRule(ethernetRuleB);
        waitForIdle();
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(anyInt(), anyLong());

        // Removing the second rule on current upstream does not send the quota to netd.
        coordinator.removeForwardingRule(ethernetRuleB);
        waitForIdle();
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(anyInt(), anyLong());

        // Removing the last rule on current upstream immediately send the cleanup stuff to netd.
        coordinator.removeForwardingRule(ethernetRuleA);
        waitForIdle();
        inOrder.verify(mNetd).tetherOffloadGetAndClearStats(ethernetIfIndex);
        inOrder.verifyNoMoreInteractions();

        // Force pushing stats update to verify that the last diff of stats is reported on
        // current upstream.
        mTetherStatsProvider.pushTetherStats();
        mTetherStatsProviderCb.expectNotifyStatsUpdated(
                new NetworkStats(0L, 1)
                .addEntry(buildTestEntry(STATS_PER_IFACE, ethernetIface, 10, 20, 30, 40)),
                new NetworkStats(0L, 1)
                .addEntry(buildTestEntry(STATS_PER_UID, ethernetIface, 10, 20, 30, 40)));

        // Applying an interface quota to another upstream, mobile, does not take any immediate
        // action.
        mTetherStatsProvider.onSetLimit(mobileIface, mobileLimit);
        waitForIdle();
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(mobileIfIndex, mobileLimit);

        // Switching to that upstream does not send the quota to netd.
        final UpstreamNetworkState mobileUpstream = createDualStackUpstream(
                TRANSPORT_CELLULAR, mobileIface);
        coordinator.updateUpstreamNetworkState(mobileUpstream);
        waitForIdle();
        inOrder.verify(mNetd, never()).tetherOffloadSetInterfaceQuota(mobileIfIndex, mobileLimit);

        // Adding the first rule on current upstream immediately send the quota to netd.
        Ipv6ForwardingRule mobileRuleA = buildTestForwardingRule(mobileIfIndex, neighA, macA);
        coordinator.addForwardingRule(mobileRuleA);
        waitForIdle();
        inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(mobileIfIndex, mobileLimit);
        inOrder.verifyNoMoreInteractions();

        // Applying the interface quota boundary {min, max, infinity} on current upstream which has
        // already added a rule immediately send the quota to netd
        for (final long quota : new long[] {0, Long.MAX_VALUE, QUOTA_UNLIMITED}) {
            mTetherStatsProvider.onSetLimit(mobileIface, quota);
            waitForIdle();
            inOrder.verify(mNetd).tetherOffloadSetInterfaceQuota(mobileIfIndex, quota);
            inOrder.verifyNoMoreInteractions();
        }
    }

    @Test
    public void testTetheringConfigDisableStart() throws Exception {
        setupFunctioningNetdInterface();
        when(mTetherConfig.isBpfOffloadEnabled()).thenReturn(false);

        final BpfTetheringCoordinator coordinator = makeBpfTetheringCoordinator();
        coordinator.start();

        // The tether stats polling task should not be scheduled.
        mTestLooper.moveTimeForward(DEFAULT_PERFORM_POLL_INTERVAL_MS);
        waitForIdle();
        verify(mNetd, never()).tetherOffloadGetStats();

        // The interface name lookup table can't be added.
        final String iface = "rmnet_data0";
        final Integer ifIndex = 100;
        coordinator.addUpstreamNameToLookupTable(ifIndex, iface);
        assertEquals(0, coordinator.mInterfaceNames.size());

        // The rule can't be added.
        final InetAddress neigh = InetAddresses.parseNumericAddress("2001:db8::1");
        final MacAddress mac = MacAddress.fromString("00:00:00:00:00:0a");
        Ipv6ForwardingRule rule = buildTestForwardingRule(ifIndex, neigh, mac);
        coordinator.addForwardingRule(rule);
        assertEquals(0, coordinator.mClientAddresses.size());

        // The rule can't be removed. This is not a realistic case because adding rule is not
        // allowed. That implies no rule could be removed. Verify this case just in case.
        HashSet<Inet6Address> clients = new HashSet<Inet6Address>();
        clients.add(rule.address);
        coordinator.mClientAddresses.put(ifIndex, clients);
        coordinator.removeForwardingRule(rule);
        assertEquals(1 /* can't be removed */, coordinator.mClientAddresses.size());
    }
}
