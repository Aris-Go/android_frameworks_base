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
 * limitations under the License.
 */

package android.net;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.util.concurrent.Executor;

/**
 * Class that provides utilities for collecting network connectivity diagnostics information.
 * Connectivity information is made available through triggerable diagnostics tools and by listening
 * to System validations. Some diagnostics information may be permissions-restricted.
 *
 * <p>ConnectivityDiagnosticsManager is intended for use by applications offering network
 * connectivity on a user device. These tools will provide several mechanisms for these applications
 * to be alerted to network conditions as well as diagnose potential network issues themselves.
 *
 * <p>The primary responsibilities of this class are to:
 *
 * <ul>
 *   <li>Allow permissioned applications to register and unregister callbacks for network event
 *       notifications
 *   <li>Invoke callbacks for network event notifications, including:
 *       <ul>
 *         <li>Network validations
 *         <li>Data stalls
 *         <li>Connectivity reports from applications
 *       </ul>
 * </ul>
 */
public class ConnectivityDiagnosticsManager {
    private final Context mContext;
    private final IConnectivityManager mService;

    /** @hide */
    public ConnectivityDiagnosticsManager(Context context, IConnectivityManager service) {
        mContext = Preconditions.checkNotNull(context, "missing context");
        mService = Preconditions.checkNotNull(service, "missing IConnectivityManager");
    }

    /**
     * Abstract base class for Connectivity Diagnostics callbacks. Used for notifications about
     * network connectivity events. Must be extended by applications wanting notifications.
     */
    public abstract static class ConnectivityDiagnosticsCallback {
        private static class ConnectivityDiagnosticsBinder
                extends IConnectivityDiagnosticsCallback.Stub {
            private final ConnectivityDiagnosticsCallback mLocalCallback;
            private Executor mExecutor;

            private ConnectivityDiagnosticsBinder(ConnectivityDiagnosticsCallback localCallback) {
                mLocalCallback = localCallback;
            }

            private void setExecutor(Executor e) {
                mExecutor = e;
            }

            public void onConnectivityReport(@NonNull ConnectivityReport report) {
                if (mExecutor == null) return;
                Binder.withCleanCallingIdentity(
                        () -> mExecutor.execute(() -> mLocalCallback.onConnectivityReport(report)));
            }

            public void onDataStallSuspected(@NonNull DataStallReport report) {
                if (mExecutor == null) return;
                Binder.withCleanCallingIdentity(
                        () -> mExecutor.execute(() -> mLocalCallback.onDataStallSuspected(report)));
            }

            public void onNetworkConnectivityReported(
                    @NonNull Network network, boolean hasConnectivity) {
                if (mExecutor == null) return;
                Binder.withCleanCallingIdentity(
                        () ->
                                mExecutor.execute(
                                        () ->
                                                mLocalCallback.onNetworkConnectivityReported(
                                                        network, hasConnectivity)));
            }
        }

        private final ConnectivityDiagnosticsBinder mBinder =
                new ConnectivityDiagnosticsBinder(this);

        /**
         * Called when the platform completes a data connectivity check. This will also be invoked
         * upon registration with the latest report.
         *
         * <p>The Network specified in the ConnectivityReport may not be active any more when this
         * method is invoked.
         *
         * @param report The ConnectivityReport containing information about a connectivity check
         */
        public void onConnectivityReport(@NonNull ConnectivityReport report) {}

        /**
         * Called when the platform suspects a data stall on some Network.
         *
         * <p>The Network specified in the DataStallReport may not be active any more when this
         * method is invoked.
         *
         * @param report The DataStallReport containing information about the suspected data stall
         */
        public void onDataStallSuspected(@NonNull DataStallReport report) {}

        /**
         * Called when any app reports connectivity to the System.
         *
         * @param network The Network for which connectivity has been reported
         * @param hasConnectivity The connectivity reported to the System
         */
        public void onNetworkConnectivityReported(
                @NonNull Network network, boolean hasConnectivity) {}
    }

    /**
     * Registers a ConnectivityDiagnosticsCallback with the System.
     *
     * <p>Only apps that offer network connectivity to the user are allowed to register callbacks.
     * This includes:
     *
     * <ul>
     *   <li>Carrier apps with active subscriptions
     *   <li>Active VPNs
     *   <li>WiFi Suggesters
     * </ul>
     *
     * <p>Callbacks will be limited to receiving notifications for networks over which apps provide
     * connectivity.
     *
     * <p>If a registering app loses its relevant permissions, any callbacks it registered will
     * silently stop receiving callbacks.
     *
     * <p>Each register() call <b>MUST</b> use a unique ConnectivityDiagnosticsCallback instance. If
     * a single instance is registered with multiple NetworkRequests, an IllegalArgumentException
     * will be thrown.
     *
     * @param request The NetworkRequest that will be used to match with Networks for which
     *     callbacks will be fired
     * @param e The Executor to be used for running the callback method invocations
     * @param callback The ConnectivityDiagnosticsCallback that the caller wants registered with the
     *     System
     * @throws IllegalArgumentException if the same callback instance is registered with multiple
     *     NetworkRequests
     * @throws SecurityException if the caller does not have appropriate permissions to register a
     *     callback
     */
    public void registerConnectivityDiagnosticsCallback(
            @NonNull NetworkRequest request,
            @NonNull Executor e,
            @NonNull ConnectivityDiagnosticsCallback callback) {
        synchronized (callback.mBinder) {
            if (callback.mBinder.mExecutor != null) {
                throw new IllegalArgumentException("Callbacks must be unique for each register()");
            }

            callback.mBinder.setExecutor(e);
        }

        try {
            mService.registerConnectivityDiagnosticsCallback(callback.mBinder, request);
        } catch (RemoteException exception) {
            exception.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a ConnectivityDiagnosticsCallback with the System.
     *
     * <p>If the given callback is not currently registered with the System, this operation will be
     * a no-op.
     *
     * @param callback The ConnectivityDiagnosticsCallback to be unregistered from the System.
     */
    public void unregisterConnectivityDiagnosticsCallback(
            @NonNull ConnectivityDiagnosticsCallback callback) {
        synchronized (callback.mBinder) {
            // If this is true, the callback has never been registered in the first place
            if (callback.mBinder.mExecutor == null) return;
        }

        try {
            mService.unregisterConnectivityDiagnosticsCallback(callback.mBinder);
        } catch (RemoteException exception) {
            exception.rethrowFromSystemServer();
        }
    }
}
