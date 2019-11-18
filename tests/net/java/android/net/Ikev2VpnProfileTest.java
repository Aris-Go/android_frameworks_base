/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import android.test.mock.MockContext;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.net.VpnProfile;
import com.android.org.bouncycastle.x509.X509V1CertificateGenerator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

/** Unit tests for {@link Ikev2VpnProfile.Builder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class Ikev2VpnProfileTest {
    private static final String SESSION_NAME_STRING = "testSession";
    private static final String SERVER_ADDR_STRING = "1.2.3.4";
    private static final String IDENTITY_STRING = "Identity";
    private static final String USERNAME_STRING = "username";
    private static final String PASSWORD_STRING = "pa55w0rd";
    private static final byte[] PSK_BYTES = "preSharedKey".getBytes();
    private static final int TEST_MTU = 1300;

    private MockContext mMockContext =
            new MockContext() {
                @Override
                public String getOpPackageName() {
                    return "fooPackage";
                }
            };
    private X509Certificate mUserCert;
    private X509Certificate mServerRootCa;
    private PrivateKey mPrivateKey;
    private final ProxyInfo mProxy = mock(ProxyInfo.class);

    @Before
    public void setUp() throws Exception {
        mServerRootCa = buildCertAndKeyPair().cert;

        CertificateAndKey userCertKey = buildCertAndKeyPair();
        mUserCert = userCertKey.cert;
        mPrivateKey = userCertKey.key;
    }

    private Ikev2VpnProfile.Builder getBuilderWithDefaultOptions() {
        Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(SERVER_ADDR_STRING, IDENTITY_STRING);

        builder.setSessionName(SESSION_NAME_STRING);
        builder.setBypassable(true);
        builder.setProxy(mProxy);
        builder.setMaxMtu(TEST_MTU);
        builder.setMetered(true);

        return builder;
    }

    @Test
    public void testBuildValidProfileWithOptions() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        // Check non-auth parameters correctly stored
        assertEquals(SESSION_NAME_STRING, profile.getSessionName());
        assertEquals(SERVER_ADDR_STRING, profile.getServerAddr());
        assertEquals(IDENTITY_STRING, profile.getUserIdentity());
        assertEquals(mProxy, profile.getProxyInfo());
        assertTrue(profile.isBypassable());
        assertTrue(profile.isMetered());
        assertEquals(TEST_MTU, profile.getMaxMtu());
    }

    @Test
    public void testBuildUsernamePasswordProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        assertEquals(USERNAME_STRING, profile.getUsername());
        assertEquals(PASSWORD_STRING, profile.getPassword());
        assertEquals(mServerRootCa, profile.getServerRootCaCert());
    }

    @Test
    public void testBuildDigitalSignatureProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthDigitalSignature(mUserCert, mPrivateKey, mServerRootCa);
        Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        assertEquals(profile.getUserCert(), mUserCert);
        assertEquals(mPrivateKey, profile.getRsaPrivateKey());
        assertEquals(profile.getServerRootCaCert(), mServerRootCa);
    }

    @Test
    public void testBuildPresharedKeyProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthPsk(PSK_BYTES);
        Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        assertArrayEquals(PSK_BYTES, profile.getPresharedKey());
    }

    @Test
    public void testBuildNoAuthMethodSet() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        try {
            builder.build();
            fail("Expected exception due to lack of auth method");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testBuildInvalidMtu() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        try {
            builder.setMaxMtu(500);
            fail("Expected exception due to too-small MTU");
        } catch (IllegalArgumentException expected) {
        }
    }

    private void verifyVpnProfileCommon(VpnProfile profile) {
        assertEquals(SESSION_NAME_STRING, profile.name);
        assertEquals(SERVER_ADDR_STRING, profile.server);
        assertEquals(IDENTITY_STRING, profile.ipsecIdentifier);
        assertEquals(mProxy, profile.proxy);
        assertTrue(profile.isBypassable);
        assertTrue(profile.isMetered);
        assertEquals(TEST_MTU, profile.maxMtu);
    }

    @Test
    public void testPskConvertToVpnProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthPsk(PSK_BYTES);
        VpnProfile profile = builder.build().toVpnProfile();

        verifyVpnProfileCommon(profile);
        assertEquals(Ikev2VpnProfile.encodeForIpsecSecret(PSK_BYTES), profile.ipsecSecret);
    }

    @Test
    public void testUsernamePasswordConvertToVpnProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        VpnProfile profile = builder.build().toVpnProfile();

        verifyVpnProfileCommon(profile);
        assertEquals(USERNAME_STRING, profile.username);
        assertEquals(PASSWORD_STRING, profile.password);
        assertEquals(Ikev2VpnProfile.certificateToPemString(mServerRootCa), profile.ipsecCaCert);
    }

    @Test
    public void testRsaConvertToVpnProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthDigitalSignature(mUserCert, mPrivateKey, mServerRootCa);
        VpnProfile profile = builder.build().toVpnProfile();

        verifyVpnProfileCommon(profile);
        assertEquals(Ikev2VpnProfile.certificateToPemString(mUserCert), profile.ipsecUserCert);
        assertEquals(
                Ikev2VpnProfile.encodeForIpsecSecret(mPrivateKey.getEncoded()),
                profile.ipsecSecret);
        assertEquals(Ikev2VpnProfile.certificateToPemString(mServerRootCa), profile.ipsecCaCert);
    }

    @Test
    public void testPskToFromVpnProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthPsk(PSK_BYTES);
        Ikev2VpnProfile ikeProfile = builder.build();
        VpnProfile profile = ikeProfile.toVpnProfile();

        assertEquals(ikeProfile, Ikev2VpnProfile.fromVpnProfile(profile));
    }

    @Test
    public void testUsernamePasswordToFromVpnProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        Ikev2VpnProfile ikeProfile = builder.build();
        VpnProfile profile = ikeProfile.toVpnProfile();

        assertEquals(ikeProfile, Ikev2VpnProfile.fromVpnProfile(profile));
    }

    @Test
    public void testRsaToFromVpnProfile() throws Exception {
        Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthDigitalSignature(mUserCert, mPrivateKey, mServerRootCa);
        Ikev2VpnProfile ikeProfile = builder.build();
        VpnProfile profile = ikeProfile.toVpnProfile();

        assertEquals(ikeProfile, Ikev2VpnProfile.fromVpnProfile(profile));
    }

    private static class CertificateAndKey {
        public final X509Certificate cert;
        public final PrivateKey key;

        CertificateAndKey(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }
    }

    private static CertificateAndKey buildCertAndKeyPair() throws Exception {
        Date validityBeginDate = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1L));
        Date validityEndDate = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1L));

        // Generate a keypair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(512);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        X500Principal dnName = new X500Principal("CN=test.android.com");
        X509V1CertificateGenerator certGen = new X509V1CertificateGenerator();
        certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
        certGen.setSubjectDN(dnName);
        certGen.setIssuerDN(dnName);
        certGen.setNotBefore(validityBeginDate);
        certGen.setNotAfter(validityEndDate);
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

        X509Certificate cert = certGen.generate(keyPair.getPrivate(), "AndroidOpenSSL");
        return new CertificateAndKey(cert, keyPair.getPrivate());
    }
}
