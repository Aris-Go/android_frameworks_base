/*
 * Copyright 2017 The Android Open Source Project
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

/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.conscrypt;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Benchmark comparing handshake performance of various engine implementations to conscrypt.
 */
@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class EngineHandshakePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Provider for the test configuration
     */
    private class Config {
        BufferType a_bufferType;
        EngineFactory b_engineFactory;
        boolean c_useAlpn;
        String d_cipher;
        PerfTestProtocol e_protocol;
        int f_rttMillis;
        Config(BufferType bufferType,
            EngineFactory engineFactory,
            boolean useAlpn,
            String cipher,
            PerfTestProtocol protocol,
            int rttMillis) {
          a_bufferType = bufferType;
          b_engineFactory = engineFactory;
          c_useAlpn = useAlpn;
          d_cipher = cipher;
          e_protocol = protocol;
          f_rttMillis = rttMillis;
        }
        public EndpointFactory bufferType() {
            return a_bufferType;
        }

        public EndpointFactory engineFactory() {
            return b_engineFactory;
        }

        public boolean useAlpn() {
            return c_useAlpn;
        }

        public String cipher() {
            return d_cipher;
        }

        public PerfTestProtocol protocol() {
            return e_protocol;
        }

        public int rttMillis() {
            return f_rttMillis;
        }
    }

    private Object[] getParams() {
        return new Object[][] {
            new Object[] {new Config(
                              BufferType.DIRECT,
                              EngineFactory.CONSCRYPT,
                              false,
                              "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                              PerfTestProtocol.TLSv13,
                              100)},
        };
    }

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(0);

    private final EngineFactory engineFactory;
    private final String cipher;
    private final boolean useAlpn;
    private final String[] protocols;
    private final int rttMillis;

    private final ByteBuffer clientApplicationBuffer;
    private final ByteBuffer clientPacketBuffer;
    private final ByteBuffer serverApplicationBuffer;
    private final ByteBuffer serverPacketBuffer;

    private void setup(Config config) throws Exception {
        engineFactory = config.engineFactory();
        cipher = config.cipher();
        useAlpn = config.useAlpn();
        protocols = config.protocol().getProtocols();
        rttMillis = config.rttMillis();
        BufferType bufferType = config.bufferType();

        SSLEngine clientEngine = engineFactory.newClientEngine(cipher, useAlpn);
        SSLEngine serverEngine = engineFactory.newServerEngine(cipher, useAlpn);

        // Create the application and packet buffers for both endpoints.
        clientApplicationBuffer = bufferType.newApplicationBuffer(clientEngine);
        serverApplicationBuffer = bufferType.newApplicationBuffer(serverEngine);
        clientPacketBuffer = bufferType.newPacketBuffer(clientEngine);
        serverPacketBuffer = bufferType.newPacketBuffer(serverEngine);

        engineFactory.dispose(clientEngine);
        engineFactory.dispose(serverEngine);
    }

    @Test
    @Parameters(method = "getParams")
    public void handshake(Config config) throws SSLException {
        setup(config);
        SSLEngine client = engineFactory.newClientEngine(cipher, useAlpn);
        SSLEngine server = engineFactory.newServerEngine(cipher, useAlpn);
        clientApplicationBuffer.clear();
        clientPacketBuffer.clear();
        serverApplicationBuffer.clear();
        serverPacketBuffer.clear();

        client.setEnabledProtocols(protocols);
        server.setEnabledProtocols(protocols);

        client.beginHandshake();
        server.beginHandshake();

        doHandshake(client, server);

        engineFactory.dispose(client);
        engineFactory.dispose(server);
    }

    private void doHandshake(SSLEngine client, SSLEngine server) throws SSLException {

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            // Send as many client-to-server messages as possible
            doHalfHandshake(client, server, clientPacketBuffer, serverApplicationBuffer);

            if (client.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
                    && server.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
                return;
            }

            // Do the same with server-to-client messages
            doHalfHandshake(server, client, serverPacketBuffer, clientApplicationBuffer);

            if (client.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
                    && server.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
                return;
            }
        }
    }

    private void doHalfHandshake(SSLEngine sender, SSLEngine receiver,
            ByteBuffer senderPacketBuffer, ByteBuffer receiverApplicationBuffer)
            throws SSLException {
        SSLEngineResult senderResult;
        SSLEngineResult receiverResult;

        do {
            senderResult = sender.wrap(EMPTY_BUFFER, senderPacketBuffer);
            runDelegatedTasks(senderResult, sender);
            senderPacketBuffer.flip();
            receiverResult = receiver.unwrap(senderPacketBuffer, receiverApplicationBuffer);
            runDelegatedTasks(receiverResult, receiver);
            senderPacketBuffer.compact();
        } while (senderResult.getHandshakeStatus() == HandshakeStatus.NEED_WRAP);

        if (rttMillis > 0) {
            try {
                Thread.sleep(rttMillis / 2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void runDelegatedTasks(SSLEngineResult result, SSLEngine engine) {
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            for (;;) {
                Runnable task = engine.getDelegatedTask();
                if (task == null) {
                    break;
                }
                task.run();
            }
        }
    }
}