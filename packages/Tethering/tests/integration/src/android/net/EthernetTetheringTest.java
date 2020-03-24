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

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.net.EthernetManager.TetheredInterfaceCallback;
import android.net.EthernetManager.TetheredInterfaceRequest;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.HandlerUtilsKt;
import com.android.testutils.TapPacketReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class EthernetTetheringTest {

    private static final String TAG = EthernetTetheringTest.class.getSimpleName();
    private static final int TIMEOUT_MS = 1000;
    private static final int PACKET_READ_TIMEOUT_MS = 100;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };
    private static final String DHCP_HOSTNAME = "testhostname";

    private final Context mContext = InstrumentationRegistry.getContext();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final TetheringManager mTm = mContext.getSystemService(TetheringManager.class);

    private TestNetworkInterface mTestIface;
    private NetworkInterface mIface;
    private byte[] mIfaceMacAddress;
    private FileDescriptor mTapFd;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mTapPacketReader;

    private boolean mSetEthernetIncludeTestInterfaces = false;
    private TetheredInterfaceRequest mTetheredInterfaceRequest;
    private volatile TetheringEventCallback mTetheringEventCallback;
    private volatile CountDownLatch mTetheredInterfaceRequestLatch;
    private volatile String mTetheredIface;

    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    @Before
    public void setUp() throws Exception {
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @After
    public void tearDown() throws Exception {
        if (mTetheringEventCallback != null) {
            mTm.unregisterTetheringEventCallback(mTetheringEventCallback);
            mTetheringEventCallback = null;
        }
        mTm.stopTethering(TetheringManager.TETHERING_ETHERNET);
        if (mTapPacketReader != null) {
            mHandler.post(() -> mTapPacketReader.stop());
        }
        mHandlerThread.quitSafely();
        maybeReleaseTetheredInterfaceRequest();
        maybeClearIncludeTestInterfaces();
        maybeDeleteTestInterface();
        mUiAutomation.dropShellPermissionIdentity();
    }

    public void assertLinkAddressMatches(LinkAddress l1, LinkAddress l2) {
        // Check all fields except the deprecation and expiry times.
        String msg = String.format("LinkAddresses do not match. expected: %s actual: %s", l1, l2);
        assertTrue(msg, l1.isSameAddressAs(l2));
        assertEquals("LinkAddress flags do not match", l1.getFlags(), l2.getFlags());
        assertEquals("LinkAddress scope does not match", l1.getScope(), l2.getScope());
    }

    @Test
    public void testVirtualEthernetAlreadyExists() throws Exception {
        assumeFalse(mEm.isAvailable());

        // NETWORK_SETTINGS is needed to create a TestNetworkInterface, to call
        // requestTetheredInterface, and to receive tethered client callbacks.
        mUiAutomation.adoptShellPermissionIdentity();

        createTestInterface();
        mIface = NetworkInterface.getByName(mTestIface.getInterfaceName());
        assertNotNull(mIface);
        mIfaceMacAddress = getMacAddress(mIface);
        Log.d(TAG, "Created test interface " + mIface.getName()
                + " with MAC " + MacAddress.fromBytes(mIfaceMacAddress));

        Log.d(TAG, "Including test interfaces");
        setIncludeTestInterfaces(true);
        Log.d(TAG, "Requesting tethered interfaces");
        requestTetheredInterface();

        checkVirtualEthernet();
    }

    @Test
    public void testVirtualEthernet() throws Exception {
        assumeFalse(mEm.isAvailable());

        mUiAutomation.adoptShellPermissionIdentity();

        requestTetheredInterface();
        setIncludeTestInterfaces(true);
        createTestInterface();
        mIface = NetworkInterface.getByName(mTestIface.getInterfaceName());
        assertNotNull(mIface);
        mIfaceMacAddress = getMacAddress(mIface);

        checkVirtualEthernet();
    }

    private void checkVirtualEthernet() throws Exception {
        mTapFd = mTestIface.getFileDescriptor().getFileDescriptor();
        mTapPacketReader = new TapPacketReader(mHandler, mTapFd,  mIface.getMTU());
        mHandler.post(() -> mTapPacketReader.start());
        HandlerUtilsKt.waitForIdle(mHandler, TIMEOUT_MS);

        assertNotNull("Can't get NetworkInterface object for " + mTestIface.getInterfaceName(),
                mIface);
        assertNotNull("Can't find MAC address for " + mTestIface.getInterfaceName());
        getTetheredInterface();

        final CountDownLatch tetheringStartedLatch = new CountDownLatch(1);
        final CountDownLatch clientConnectedLatch = new CountDownLatch(1);
        final AtomicReference<Collection<TetheredClient>> clientRef = new AtomicReference<>();

        mTetheringEventCallback = new TetheringEventCallback() {
            @Override
            public void onTetheredInterfacesChanged(List<String> interfaces) {
                // Ignore stale callbacks registered by previous test cases.
                if (mTetheringEventCallback != this) return;
                if (interfaces.contains(mTetheredIface)) {
                    Log.d(TAG, "Tethering started: " + interfaces);
                    tetheringStartedLatch.countDown();
                }
            }

            @Override
            public void onError(String ifName, int error) {
                fail("TetheringEventCallback got error:" + error + " on iface " + ifName);
            }

            @Override
            public void onClientsChanged(Collection<TetheredClient> clients) {
                // Ignore stale callbacks registered by previous test cases.
                if (mTetheringEventCallback != this) return;
                Log.d(TAG, "Got clients changed: " + clients);
                if (clients.size() > 0) {
                    clientRef.set(clients);
                    clientConnectedLatch.countDown();
                }
            }
        };
        mTm.registerTetheringEventCallback(mHandler::post, mTetheringEventCallback);

        StartTetheringCallback startTetheringCallback = new StartTetheringCallback() {
            @Override
            public void onTetheringStarted() {
                // Do nothing.
            }
            @Override
            public void onTetheringFailed(int resultCode) {
                fail("Unexpectedly got onTetheringFailed");
            }
        };
        Log.d(TAG, "Starting Ethernet tethering");
        mTm.startTethering(TetheringManager.TETHERING_ETHERNET, mHandler::post,
                startTetheringCallback);
        assertTrue("Tethering not started on " + mTetheredIface + " after " + TIMEOUT_MS + "ms",
                tetheringStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        // We have to retransmit DHCP requests because IpServer declares itself to be ready before
        // its DhcpServer is actually started. TODO: fix this race and remove this loop.
        DhcpPacket offerPacket = null;
        for (int i = 0; i < DHCP_DISCOVER_ATTEMPTS; i++) {
            Log.d(TAG, "Sending DHCP discover");
            sendDhcpDiscover(clientMacAddr);
            offerPacket = getNextDhcpPacket();
            if (offerPacket instanceof DhcpOfferPacket) break;
        }
        assertTrue("No DHCPOFFER received on interface within timeout",
                offerPacket instanceof DhcpOfferPacket);

        sendDhcpRequest(offerPacket, clientMacAddr);
        DhcpPacket ackPacket = getNextDhcpPacket();
        assertTrue(ackPacket instanceof DhcpAckPacket);
        assertNotNull("No DHCPACK received on interface within timeout", ackPacket);

        assertTrue("Did not receive client connected callback after " + TIMEOUT_MS + "ms",
                clientConnectedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        final Collection<TetheredClient> clients = clientRef.get();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.toArray(new TetheredClient[0])[0];

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(TetheringManager.TETHERING_ETHERNET, client.getTetheringType());

        // Check the hostname.
        assertEquals(1, client.getAddresses().size());
        TetheredClient.AddressInfo info = client.getAddresses().get(0);
        assertEquals(DHCP_HOSTNAME, info.getHostname());

        // Check the address is the one that was handed out in the DHCP ACK.
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertLinkAddressMatches(dhcpResults.ipAddress, info.getAddress());

        // Check that the lifetime is correct +/- 10s.
        final long now = SystemClock.elapsedRealtime();
        final long actualLeaseDuration = (info.getAddress().getExpirationTime() - now) / 1000;
        final String msg = String.format("IP address should have lifetime of %d, got %d",
                dhcpResults.leaseDuration, actualLeaseDuration);
        assertTrue(msg, Math.abs(dhcpResults.leaseDuration - actualLeaseDuration) < 10);
    }

    private DhcpPacket getNextDhcpPacket() throws ParseException {
        byte[] packet;
        while ((packet = mTapPacketReader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            try {
                return DhcpPacket.decodeFullPacket(packet, packet.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet. Continue.
            }
        }
        return null;
    }

    @Test
    public void testPhysicalEthernet() throws Exception {
        assumeTrue(mEm.isAvailable());
    }

    private void requestTetheredInterface() throws Exception {
        assertNull("BUG: more than one tethered interface request", mTetheredInterfaceRequest);

        mTetheredInterfaceRequestLatch = new CountDownLatch(1);

        final TetheredInterfaceCallback callback = new TetheredInterfaceCallback() {
            @Override
            public void onAvailable(String iface) {
                if (mTetheredInterfaceRequestLatch == null) return;

                Log.d(TAG, "Ethernet interface available: " + iface);
                mTetheredIface = iface;
                mTetheredInterfaceRequestLatch.countDown();
            }
            @Override
            public void onUnavailable() { }
        };

        mEm.requestTetheredInterface(mHandler::post, callback);
    }

    private void getTetheredInterface() throws Exception {
        assertTrue("No tethered interface available after " + TIMEOUT_MS + "ms",
                mTetheredInterfaceRequestLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals("TetheredInterfaceCallback for unexpected interface", mIface.getName(),
                mTetheredIface);
        mTetheredInterfaceRequestLatch = null;
    }

    private void sendDhcpDiscover(byte[] macAddress) throws Exception {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(DhcpPacket.ENCAP_L2,
                new Random().nextInt() /* transactionId */, (short) 0 /* secs */,
                macAddress,  false /* unicast */, DHCP_REQUESTED_PARAMS,
                false /* rapid commit */,  DHCP_HOSTNAME);
        sendPacket(packet);
    }

    private void sendDhcpRequest(DhcpPacket offerPacket, byte[] macAddress) throws Exception {
        DhcpResults results = offerPacket.toDhcpResults();
        Inet4Address clientIp = (Inet4Address) results.ipAddress.getAddress();
        Inet4Address serverIdentifier = results.serverAddress;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(DhcpPacket.ENCAP_L2,
                0 /* transactionId */, (short) 0 /* secs */, DhcpPacket.INADDR_ANY /* clientIp */,
                false /* broadcast */, macAddress, clientIp /* requestedIpAddress */,
                serverIdentifier, DHCP_REQUESTED_PARAMS, DHCP_HOSTNAME);
        sendPacket(packet);
    }

    private void sendPacket(ByteBuffer packet) throws Exception {
        assertNotNull("Only tests on virtual interfaces can send packets", mTapFd);
        Os.write(mTapFd, packet);
    }

    // Unprivileged apps can't get MAC addresses from interfaces. Get it from the modified EUI-64
    // link-local address instead.
    private byte[] getMacAddress(NetworkInterface iface) throws Exception {
        for (InterfaceAddress interfaceAddress : iface.getInterfaceAddresses()) {
            InetAddress addr = interfaceAddress.getAddress();
            if (addr instanceof Inet6Address && addr.isLinkLocalAddress()) {
                byte[] addrBytes = addr.getAddress();
                byte[] macAddrBytes = new byte[6];
                System.arraycopy(addrBytes, 8, macAddrBytes, 0, 3);
                System.arraycopy(addrBytes, 13, macAddrBytes, 3, 3);
                macAddrBytes[0] ^=  (byte) 0x02;
                return macAddrBytes;
            }
        }
        return null;
    }

    private void createTestInterface() throws Exception {
        TestNetworkManager tnm = mContext.getSystemService(TestNetworkManager.class);
        mTestIface = tnm.createTapInterface();
    }

    private void setIncludeTestInterfaces(boolean include) throws Exception {
        mEm.setIncludeTestInterfaces(include);
        mSetEthernetIncludeTestInterfaces = include;
    }

    private void maybeClearIncludeTestInterfaces() throws Exception {
        if (mSetEthernetIncludeTestInterfaces) {
            setIncludeTestInterfaces(false);
        }
    }

    private void maybeDeleteTestInterface() throws Exception {
        if (mTestIface != null) {
            mTestIface.getFileDescriptor().close();
        }
    }

    private void maybeReleaseTetheredInterfaceRequest() throws Exception {
        if (mTetheredInterfaceRequest != null) {
            mTetheredInterfaceRequest.release();
        }
    }
}
