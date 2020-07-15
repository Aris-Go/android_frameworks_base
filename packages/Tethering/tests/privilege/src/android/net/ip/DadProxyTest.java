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

package android.net.ip;

import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.IPPROTO_TCP;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;

import android.app.Instrumentation;
import android.content.Context;
import android.net.InetAddresses;
import android.net.MacAddress;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.util.InterfaceParams;
import android.net.util.IpUtils;
import android.net.util.TetheringUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;
import com.android.testutils.TapPacketReader;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import android.util.Log;


import android.os.IBinder;
import android.net.INetd;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class DadProxyTest {
    private static final int DATA_BUFFER_LEN = 4096;
    private static final int PACKET_TIMEOUT_MS = 5_000;

    TestNetworkInterface mUpstreamTestIface, mTetheredTestIface;
    private String mUpstreamIfaceName, mTetheredIfaceName;
    private HandlerThread mHandlerThread;
    private Handler mUpstreamHandler, mTetheredHandler, mHandler;
    private TapPacketReader mUpstreamPacketReader, mTetheredPacketReader;
    private FileDescriptor mUpstreamTapFd, mTetheredTapFd;
    private byte[] mUpstreamMac, mTetheredMac;

    @Mock private TetheringUtils.Native mMockNative;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        System.loadLibrary("tetherutilsjni");

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        setupTapInterfaces();

        // Looper must be prepared here since AndroidJUnitRunner runs tests on separate threads.
        if (Looper.myLooper() == null) Looper.prepare();
    }

    @After
    public void tearDown() throws Exception {
        if (mHandlerThread != null) {
            mUpstreamHandler.post(mUpstreamPacketReader::stop); // Also closes the socket
            mTetheredHandler.post(mTetheredPacketReader::stop); // Also closes the socket
            mUpstreamTapFd = null;
            mTetheredTapFd = null;
        }

        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
    }

    private TestNetworkInterface setupTapInterface() {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        // Adopt the shell permission identity to create a test TAP interface.
        inst.getUiAutomation().adoptShellPermissionIdentity();

        AtomicReference<TestNetworkInterface> iface = new AtomicReference<>();
        final TestNetworkManager tnm = (TestNetworkManager) inst.getContext().getSystemService(
            Context.TEST_NETWORK_SERVICE);
        iface.set(tnm.createTapInterface());
        inst.getUiAutomation().dropShellPermissionIdentity();

        return iface.get();
    }

    private void setupTapInterfaces() {
        // Create upstream test iface.
        mUpstreamTestIface = setupTapInterface();
        mUpstreamIfaceName = mUpstreamTestIface.getInterfaceName();
        mUpstreamHandler = mHandlerThread.getThreadHandler();
        mUpstreamTapFd = mUpstreamTestIface.getFileDescriptor().getFileDescriptor();
        mUpstreamPacketReader = new TapPacketReader(mUpstreamHandler, mUpstreamTapFd,
                                                    DATA_BUFFER_LEN);
        mUpstreamHandler.post(mUpstreamPacketReader::start);

        // Create tethered test iface.
        mTetheredTestIface = setupTapInterface();
        mTetheredIfaceName = mTetheredTestIface.getInterfaceName();
        mTetheredHandler = mHandlerThread.getThreadHandler();
        mTetheredTapFd = mTetheredTestIface.getFileDescriptor().getFileDescriptor();
        mTetheredPacketReader = new TapPacketReader(mTetheredHandler, mTetheredTapFd,
                                                    DATA_BUFFER_LEN);
        mTetheredHandler.post(mTetheredPacketReader::start);
    }

    private static final int IPV6_HEADER_LEN = 40;
    private static final int ETH_HEADER_LEN = 14;
    private static final int ICMPV6_HEADER_LEN = 24;
    private static final int LL_TARGET_OPTION_LEN = 8;
    private static final int ICMPV6_CHECKSUM_OFFSET = 2;

    // TODO: move the IpUtils code to frameworks/lib/net and link it statically.
    private static int checksumFold(int sum) {
        while (sum > 0xffff) {
            sum = (sum >> 16) + (sum & 0xffff);
        }
        return sum;
    }

    private static short checksumAdjust(short checksum, short oldWord, short newWord) {
        checksum = (short) ~checksum;
        int tempSum = checksumFold(uint16(checksum) + uint16(newWord) + 0xffff - uint16(oldWord));
        return (short) ~tempSum;
    }

    public static int uint16(short s) {
        return s & 0xffff;
    }

    private static short icmpv6Checksum(ByteBuffer buf, int ipOffset, int transportOffset,
            int transportLen) {
        // The ICMPv6 checksum is the same as the TCP checksum, except the pseudo-header uses
        // 58 (ICMPv6) instead of 6 (TCP). Calculate the TCP checksum, and then do an incremental
        // checksum adjustment  for the change in the next header byte.
        short checksum = IpUtils.tcpChecksum(buf, ipOffset, transportOffset, transportLen);
        return checksumAdjust(checksum, (short) IPPROTO_TCP, (short) IPPROTO_ICMPV6);
    }

    // Move function to Util location?
    private static ByteBuffer createIcmpV6Packet(int type) {
        // Refer to buildArpPacket()
        int icmpLen = ICMPV6_HEADER_LEN +
            (type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT ?
             LL_TARGET_OPTION_LEN : 0);
        final ByteBuffer buf = ByteBuffer.allocate(ETH_HEADER_LEN + icmpLen + IPV6_HEADER_LEN);

        final InetAddress target = InetAddresses.parseNumericAddress("ff02::1:5566:7788");
        final MacAddress targetMac = MacAddress.fromString("01:02:03:04:05:06");
        final MacAddress solicitedMac = MacAddress.fromString("33:33:01:66:77:88");
        final MacAddress srcMac = MacAddress.fromString("01:02:03:04:05:06");

        // IPv6 header
        byte[] version = {(byte) 0x60, 0x00, 0x00, 0x00};
        buf.put(version);                                           // Version
        buf.putShort((byte) icmpLen);                               // Length
        buf.put((byte) IPPROTO_ICMPV6);                             // Next header
        buf.put((byte) 0xff);                                       // Hop limit

        final byte[] src = InetAddresses.parseNumericAddress("::").getAddress();
        buf.put(src);                                               // Src
        final byte[] dst = target.getAddress();
        buf.put(dst);                                               // Dst

        // ICMPv6 Header
        buf.put((byte) type);                                       // Type
        buf.put((byte) 0x00);                                       // Code
        buf.putShort((short) 0);                                    // Checksum
        buf.putInt(0);                                              // Reserved
        buf.put(target.getAddress());

        if(type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT) {
            //NA packet has LL target address
            //ICMPv6 Option
            buf.put((byte) 0x02);                                   // Type
            buf.put((byte) 0x01);                                   // Length
            byte[] ll_target = targetMac.toByteArray();
            buf.put(ll_target);
        }

        // Populate checksum field
        final int transportOffset = IPV6_HEADER_LEN;
        final short checksum = icmpv6Checksum(buf, 0, transportOffset, icmpLen);
        buf.putShort(transportOffset + ICMPV6_CHECKSUM_OFFSET, checksum);

        buf.flip();

        // Prepend Ethernet header.
        ByteBuffer ethPacket = ByteBuffer.allocate(buf.capacity());
        ethPacket.put(solicitedMac.toByteArray());
        ethPacket.put(targetMac.toByteArray());
        ethPacket.putShort((short) 0x86dd);  // TODO: use ETHER_TYPE_IPV6
        ethPacket.put(buf);
        ethPacket.flip();

        return ethPacket;
    }

    private DadProxy setupProxy() {
        if (Looper.myLooper() == null) Looper.prepare();
        final InterfaceParams tetheredParams = InterfaceParams.getByName(mTetheredIfaceName);
        assertNotNull(tetheredParams);
        DadProxy proxy = new DadProxy(new Handler(Looper.myLooper()), tetheredParams); // mMockNative
        assertNotNull(proxy);
        final InterfaceParams upstreamParams = InterfaceParams.getByName(mUpstreamIfaceName);
        assertNotNull(upstreamParams);
        proxy.setUpstreamIface(upstreamParams);
        return proxy;
    }

    private boolean waitForPacket(ByteBuffer packet, TapPacketReader reader) {
        byte[] p;
        int i = -1;
        while((p = reader.popPacket(PACKET_TIMEOUT_MS)) != null) {
            final ByteBuffer buffer = ByteBuffer.wrap(p, 14, p.length - 14);
            i = buffer.compareTo(packet);

            if(i ==0)
                return true;
        }
        return false;
    }

    @Test
    public void testDadProxy() {
        setupProxy();
    }

    private INetd mNetd;

    // Might remove need for sleep below... test
    private void disableIpv6ProvisioningDelays() throws Exception {
        // Speed up the test by disabling DAD and removing router_solicitation_delay.
        // We don't need to restore the default value because the interface is removed in tearDown.
        // TODO: speed up further by not waiting for RS but keying off first IPv6 packet.
        mNetd.setProcSysNet(INetd.IPV6, INetd.CONF, mUpstreamIfaceName, "router_solicitation_delay", "0");
        mNetd.setProcSysNet(INetd.IPV6, INetd.CONF, mUpstreamIfaceName, "dad_transmits", "0");
        mNetd.setProcSysNet(INetd.IPV6, INetd.CONF, mTetheredIfaceName, "router_solicitation_delay", "0");
        mNetd.setProcSysNet(INetd.IPV6, INetd.CONF, mTetheredIfaceName, "dad_transmits", "0");
    }

    @Test
    public void testIface() {
        DadProxy proxy = setupProxy();

        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final IBinder netdIBinder =
                (IBinder) inst.getContext().getSystemService(Context.NETD_SERVICE);
        mNetd = INetd.Stub.asInterface(netdIBinder);
        //mNetd = mServices.getNetd();

        // Need to wait before sending any packets out newly created sockets.
        try {
            disableIpv6ProvisioningDelays();

            mNetd.networkAddInterface(INetd.LOCAL_NET_ID, mUpstreamIfaceName);
            mNetd.networkAddInterface(INetd.LOCAL_NET_ID, mTetheredIfaceName);

            Thread.sleep(3000);
        } catch (Exception e) {} //InterruptedException

        ByteBuffer ns_packet = createIcmpV6Packet(NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION);

        Log.d("DadProxyTest", HexDump.toHexString(ns_packet.array()));
        try {
            mTetheredPacketReader.sendResponse(ns_packet);
        } catch (IOException e) {
            //TODO: handle exception
            Log.e("testIface", "exception " + e);
        }
        ns_packet.rewind();

        waitForPacket(ns_packet, mTetheredPacketReader);
        assertTrue(waitForPacket(ns_packet, mUpstreamPacketReader));
    }

    @Test
    public void testNsPacket() {
        DadProxy proxy = setupProxy();

        // Need to wait before sending any packets out newly created sockets.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {}

        ByteBuffer ns_packet = createIcmpV6Packet(NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION);
        byte[] arr = new byte[ns_packet.remaining()];
        ns_packet.get(arr);
        proxy.nsForwarder.handlePacket(arr, arr.length);
        ns_packet.rewind();

        assertTrue(waitForPacket(ns_packet, mUpstreamPacketReader));
    }

    @Test
    public void testNaPacket() {
        DadProxy proxy = setupProxy();

        // Need to wait before sending any packets out newly created sockets.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {}

        ByteBuffer na_packet = createIcmpV6Packet(
            NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT);
        byte[] arr = new byte[na_packet.remaining()];
        na_packet.get(arr);
        proxy.naForwarder.handlePacket(arr, arr.length);
        na_packet.rewind();

        assertTrue(waitForPacket(na_packet, mTetheredPacketReader));
    }
}
