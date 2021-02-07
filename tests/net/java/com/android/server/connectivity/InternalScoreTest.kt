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

package com.android.server.connectivity

import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.server.connectivity.InternalScore.POLICY_ACCEPT_UNVALIDATED
import com.android.server.connectivity.InternalScore.POLICY_EVER_USER_SELECTED
import com.android.server.connectivity.InternalScore.POLICY_IS_VALIDATED
import com.android.server.connectivity.InternalScore.POLICY_IS_VPN
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.collections.minOfOrNull
import kotlin.collections.maxOfOrNull
import kotlin.reflect.full.staticProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@SmallTest
class InternalScoreTest {
    // Convenience methods
    fun InternalScore.withPolicies(
        validated: Boolean = false,
        vpn: Boolean = false,
        onceChosen: Boolean = false,
        acceptUnvalidated: Boolean = false
    ): InternalScore {
        val nac = NetworkAgentConfig.Builder().apply {
            setUnvalidatedConnectivityAcceptable(acceptUnvalidated)
            setExplicitlySelected(onceChosen)
        }.build()
        val nc = NetworkCapabilities.Builder().apply {
            if (vpn) addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            if (validated) addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.build()
        return mixInScore(nc, nac)
    }

    @Test
    fun testGetLegacyInt() {
        val ns = InternalScore(50, 0L /* policy */)
        assertEquals(10, ns.legacyInt) // -40 penalty for not being validated
        assertEquals(50, ns.legacyIntAsValidated)

        val vpnNs = InternalScore(101, 0L /* policy */).withPolicies(vpn = true)
        assertEquals(101, vpnNs.legacyInt) // VPNs are not subject to unvalidation penalty
        assertEquals(101, vpnNs.legacyIntAsValidated)
        assertEquals(101, vpnNs.withPolicies(validated = true).legacyInt)
        assertEquals(101, vpnNs.withPolicies(validated = true).legacyIntAsValidated)

        val validatedNs = ns.withPolicies(validated = true)
        assertEquals(50, validatedNs.legacyInt) // No penalty, this is validated
        assertEquals(50, validatedNs.legacyIntAsValidated)

        val chosenNs = ns.withPolicies(onceChosen = true)
        assertEquals(10, chosenNs.legacyInt)
        assertEquals(100, chosenNs.legacyIntAsValidated)
        assertEquals(10, chosenNs.withPolicies(acceptUnvalidated = true).legacyInt)
        assertEquals(50, chosenNs.withPolicies(acceptUnvalidated = true).legacyIntAsValidated)
    }

    @Test
    fun testToString() {
        val string = InternalScore(10, 0L /* policy */)
                .withPolicies(vpn = true, acceptUnvalidated = true).toString()
        assertTrue(string.contains("Score(10"), string)
        assertTrue(string.contains("ACCEPT_UNVALIDATED"), string)
        assertTrue(string.contains("IS_VPN"), string)
        assertFalse(string.contains("IS_VALIDATED"), string)
    }

    @Test
    fun testHasPolicy() {
        val ns = InternalScore(50, 0L /* policy */)
        assertFalse(ns.hasPolicy(POLICY_IS_VALIDATED))
        assertFalse(ns.hasPolicy(POLICY_IS_VPN))
        assertFalse(ns.hasPolicy(POLICY_EVER_USER_SELECTED))
        assertFalse(ns.hasPolicy(POLICY_ACCEPT_UNVALIDATED))
        assertTrue(ns.withPolicies(validated = true).hasPolicy(POLICY_IS_VALIDATED))
        assertTrue(ns.withPolicies(vpn = true).hasPolicy(POLICY_IS_VPN))
        assertTrue(ns.withPolicies(onceChosen = true).hasPolicy(POLICY_EVER_USER_SELECTED))
        assertTrue(ns.withPolicies(acceptUnvalidated = true).hasPolicy(POLICY_ACCEPT_UNVALIDATED))
    }

    @Test
    fun testMinMaxPolicyConstants() {
        val policyRegex = Regex("POLICY_.*")
        // CS-managed policies count from 63 downward
        val policies = InternalScore::class.staticProperties.filter {
            it.name.matches(policyRegex)
        }

        policies.forEach { policy ->
            assertTrue(policy.get() as Int >= InternalScore.MIN_CS_MANAGED_POLICY)
            assertTrue(policy.get() as Int <= InternalScore.MAX_CS_MANAGED_POLICY)
        }
        assertEquals(InternalScore.MIN_CS_MANAGED_POLICY,
                policies.minOfOrNull { it.get() as Int })
        assertEquals(InternalScore.MAX_CS_MANAGED_POLICY,
                policies.maxOfOrNull { it.get() as Int })
    }
}
