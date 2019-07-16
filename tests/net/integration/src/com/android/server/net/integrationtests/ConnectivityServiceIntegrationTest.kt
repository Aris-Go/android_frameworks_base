/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.net.integrationtests

import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Context.BIND_IMPORTANT
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.IDnsResolver
import android.net.InetAddresses
import android.net.INetd
import android.net.INetworkPolicyManager
import android.net.INetworkStatsService
import android.net.LinkProperties
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkRequest
import android.net.NetworkStackClient
import android.net.TestNetworkStackClient
import android.net.metrics.IpConnectivityLog
import android.net.shared.PrivateDnsConfig
import android.networkstack.util.TYPE_ADDRCONFIG
import android.os.ConditionVariable
import android.os.IBinder
import android.os.INetworkManagementService
import android.os.Looper
import android.testing.TestableContext
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.ConnectivityService
import com.android.server.LocalServices
import com.android.server.TestNetIdManager
import com.android.server.WrappedNetworkAgent
import com.android.server.connectivity.DnsManager
import com.android.server.connectivity.DefaultNetworkMetrics
import com.android.server.connectivity.IpConnectivityMetrics
import com.android.server.connectivity.MockableSystemProperties
import com.android.server.connectivity.ProxyTracker
import com.android.server.connectivity.Tethering
import com.android.server.net.NetworkPolicyManagerInternal
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.RecorderCallback.CallbackRecord
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Spy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

const val TEST_TIMEOUT_MS = 5_000L
const val TEST_PRIVATE_DNS_HOST_NAME = "test.dns.android"

@RunWith(AndroidJUnit4::class)
class ConnectivityServiceIntegrationTest {
    // lateinit used here for mocks as they need to be reinitialized between each test and the test
    // should crash if they are used before being initialized.
    @Mock
    private lateinit var netManager: INetworkManagementService
    @Mock
    private lateinit var statsService: INetworkStatsService
    @Mock
    private lateinit var policyManager: INetworkPolicyManager
    @Mock
    private lateinit var log: IpConnectivityLog
    @Mock
    private lateinit var netd: INetd
    @Mock
    private lateinit var dnsResolver: IDnsResolver
    @Mock
    private lateinit var metricsLogger: IpConnectivityMetrics.Logger
    @Mock
    private lateinit var defaultMetrics: DefaultNetworkMetrics
    @Mock
    private lateinit var dnsManager: DnsManager
    @Spy
    private var context = TestableContext(sRealContext)

    private val privateDnsConfig = PrivateDnsConfig(TEST_PRIVATE_DNS_HOST_NAME,
            arrayOf(InetAddresses.parseNumericAddress("8.8.8.8"),
                    InetAddresses.parseNumericAddress("2001:db8::1")))
    private val noPrivateDnsConfig = PrivateDnsConfig(false)

    // lateinit for these three classes under test, as they should be reset to a different instance
    // for every test but should always be initialized before use (or the test should crash).
    private lateinit var networkStackClient: TestNetworkStackClient
    private lateinit var service: ConnectivityService
    private lateinit var cm: ConnectivityManager

    companion object {
        private val sRealContext = InstrumentationRegistry.getInstrumentation().getContext()
        // lateinit for this binder token, as it must be initialized before any test code is run
        // and use of it before init should crash the test.
        private lateinit var nsInstrumentation: INetworkStackInstrumentation
        private val sBindingCondition = ConditionVariable(false)

        private class InstrumentationServiceConnection : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i("TestNetworkStack", "Service connected")
                try {
                    if (service == null) fail("Error binding to NetworkStack instrumentation")
                    nsInstrumentation = INetworkStackInstrumentation.Stub.asInterface(service)
                } finally {
                    sBindingCondition.open()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val intent = Intent(sRealContext, TestNetworkStackService::class.java)
            intent.action = TestNetworkStackService.INSTRUMENTATION_ACTION
            assertTrue(sRealContext.bindService(intent, InstrumentationServiceConnection(),
                    BIND_AUTO_CREATE or BIND_IMPORTANT),
                    "Error binding to instrumentation service")
            assertTrue(sBindingCondition.block(TEST_TIMEOUT_MS),
                    "Timed out binding to instrumentation service after $TEST_TIMEOUT_MS ms")
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doReturn(defaultMetrics).`when`(metricsLogger).defaultNetworkMetrics()
        doNothing().`when`(context).sendStickyBroadcastAsUser(any(), any(), any())

        networkStackClient = spy(TestNetworkStackClient(sRealContext))
        networkStackClient.init()
        networkStackClient.start(sRealContext)

        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal::class.java)
        LocalServices.addService(NetworkPolicyManagerInternal::class.java,
                mock(NetworkPolicyManagerInternal::class.java))

        // A looper is required for DataConnectionStats -> PhoneStateListener
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        service = ConnectivityService(context, netManager, statsService, policyManager,
                dnsResolver, log, netd, makeDependencies())
        cm = ConnectivityManager(context, service)
        context.addMockSystemService(Context.CONNECTIVITY_SERVICE, cm)

        service.systemReady()
    }

    private fun makeDependencies() : ConnectivityService.Dependencies {
        val deps = spy(ConnectivityService.Dependencies())
        doReturn(networkStackClient).`when`(deps).networkStack
        doReturn(metricsLogger).`when`(deps).metricsLogger
        doReturn(mock(Tethering::class.java)).`when`(deps).makeTethering(
                any(), any(), any(), any(), any())
        doReturn(mock(ProxyTracker::class.java)).`when`(deps).makeProxyTracker(any(), any())
        doReturn(mock(MockableSystemProperties::class.java)).`when`(deps).systemProperties
        doReturn(TestNetIdManager()).`when`(deps).makeNetIdManager()
        doReturn(dnsManager).`when`(deps).makeDnsManager(any(), any(), any())
        doReturn(noPrivateDnsConfig).`when`(dnsManager).getPrivateDnsConfig()
        return deps
    }

    @After
    fun tearDown() {
        nsInstrumentation.clearAllState()
    }

    private fun addValidatedMockDnsResponses() {
        nsInstrumentation.addDnsResponse(
                DnsResponse("test.android.com", "192.168.0.1", TYPE_ADDRCONFIG))
        nsInstrumentation.addDnsResponse(
                DnsResponse("secure.test.android.com", "192.168.0.2", TYPE_ADDRCONFIG))
    }

    private fun addValidatedMockResponses() {
        addValidatedMockDnsResponses()

        nsInstrumentation.addHttpResponse(HttpResponse(
                "http://test.android.com",
                responseCode = 204, contentLength = 42, redirectUrl = null))
        nsInstrumentation.addHttpResponse(HttpResponse(
                "https://secure.test.android.com",
                responseCode = 204, contentLength = 42, redirectUrl = null))
    }

    private fun getTestableNetworkCallback(): TestableNetworkCallback {
        val request = NetworkRequest.Builder()
                .clearCapabilities()
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val testableNetworkCallback = TestableNetworkCallback(TEST_TIMEOUT_MS)
        cm.registerNetworkCallback(request, testableNetworkCallback)
        return testableNetworkCallback
    }

    @Test
    fun validationTest() {
        val callback = getTestableNetworkCallback()

        // Verify valiation without private dns config
        val na = WrappedNetworkAgent(TRANSPORT_CELLULAR, LinkProperties(), context)
        networkStackClient.verifyNetworkMonitorCreated(na.network, TEST_TIMEOUT_MS)

        addValidatedMockResponses()
        na.addCapability(NET_CAPABILITY_INTERNET)
        na.connect()

        callback.expectAvailableThenValidatedCallbacks(na.network)
        assertEquals(2, nsInstrumentation.getRequestUrls().size)

        na.disconnect()
        callback.expectCallback(CallbackRecord.LOST, na)

        // Set private dns config and verify validation again
        doReturn(privateDnsConfig).`when`(dnsManager).getPrivateDnsConfig()

        val na2 = WrappedNetworkAgent(TRANSPORT_CELLULAR, LinkProperties(), context)
        networkStackClient.verifyNetworkMonitorCreated(na2.network, TEST_TIMEOUT_MS)

        addValidatedMockResponses()
        na2.addCapability(NET_CAPABILITY_INTERNET)
        na2.connect()

        callback.expectAvailableThenValidatedCallbacks(na2.network)
        assertEquals(4, nsInstrumentation.getRequestUrls().size)

        na2.disconnect()
        callback.expectCallback(CallbackRecord.LOST, na2)
    }
}