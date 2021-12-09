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
package android.net.vcn;

import static android.net.vcn.VcnUnderlyingNetworkPriorityRule.NETWORK_QUALITY_ANY;
import static android.net.vcn.VcnUnderlyingNetworkPriorityRule.NETWORK_QUALITY_OK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class VcnCellUnderlyingNetworkPriorityRuleTest {
    private static final Set<String> ALLOWED_PLMN_IDS = new HashSet<>();
    private static final Set<Integer> ALLOWED_CARRIER_IDS = new HashSet<>();

    // Package private for use in VcnGatewayConnectionConfigTest
    static VcnCellUnderlyingNetworkPriorityRule getTestNetworkPriority() {
        return new VcnCellUnderlyingNetworkPriorityRule.Builder()
                .setNetworkQuality(NETWORK_QUALITY_OK)
                .setMatchesMetered(true /* allowMetered */)
                .setMatchingOperatorPlmnIds(ALLOWED_PLMN_IDS)
                .setMatchingSpecificCarrierIds(ALLOWED_CARRIER_IDS)
                .setMatchesRoaming(true /* allowRoaming */)
                .setRequireOpportunistic(true /* requireOpportunistic */)
                .build();
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnCellUnderlyingNetworkPriorityRule networkPriority = getTestNetworkPriority();
        assertEquals(NETWORK_QUALITY_OK, networkPriority.getNetworkQuality());
        assertTrue(networkPriority.matchesMetered());
        assertEquals(ALLOWED_PLMN_IDS, networkPriority.getMatchingOperatorPlmnIds());
        assertEquals(ALLOWED_CARRIER_IDS, networkPriority.getMatchingSpecificCarrierIds());
        assertTrue(networkPriority.allowRoaming());
        assertTrue(networkPriority.requireOpportunistic());
    }

    @Test
    public void testBuilderAndGettersForDefaultValues() {
        final VcnCellUnderlyingNetworkPriorityRule networkPriority =
                new VcnCellUnderlyingNetworkPriorityRule.Builder().build();
        assertEquals(NETWORK_QUALITY_ANY, networkPriority.getNetworkQuality());
        assertFalse(networkPriority.matchesMetered());
        assertEquals(new HashSet<String>(), networkPriority.getMatchingOperatorPlmnIds());
        assertEquals(new HashSet<Integer>(), networkPriority.getMatchingSpecificCarrierIds());
        assertFalse(networkPriority.allowRoaming());
        assertFalse(networkPriority.requireOpportunistic());
    }

    @Test
    public void testPersistableBundle() {
        final VcnCellUnderlyingNetworkPriorityRule networkPriority = getTestNetworkPriority();
        assertEquals(
                networkPriority,
                VcnUnderlyingNetworkPriorityRule.fromPersistableBundle(
                        networkPriority.toPersistableBundle()));
    }
}
