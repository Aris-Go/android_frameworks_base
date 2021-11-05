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

package com.android.server.health;

import android.annotation.Nullable;
import android.os.BatteryProperty;
import android.os.HandlerThread;
import android.os.IBatteryPropertiesRegistrar;
import android.os.RemoteException;

import com.android.internal.annotations.VisibleForTesting;

import java.util.NoSuchElementException;

/**
 * HealthServiceWrapper wraps the internal IHealth service and refreshes the service when necessary.
 * This is essentially a wrapper over IHealth that is useful for BatteryService.
 *
 * <p>The implementation may be backed by a HIDL or AIDL HAL.
 *
 * <p>On new registration of IHealth service, the internal service is refreshed. On death of an
 * existing IHealth service, the internal service is NOT cleared to avoid race condition between
 * death notification and new service notification. Hence, a caller must check for transaction
 * errors when calling into the service.
 *
 * @hide Should only be used internally.
 */
public interface HealthServiceWrapper {
    /**
     * Initialize the wrapper.
     * @param healthInfoCallback
     * @throws RemoteException
     * @throws NoSuchElementException
     */
    void init(@Nullable HealthInfoCallback healthInfoCallback)
            throws RemoteException, NoSuchElementException;

    /**
     * @return the handler thread. Exposed for testing.
     */
    @VisibleForTesting
    HandlerThread getHandlerThread();

    /**
     * Calls into get*() functions in the health HAL. This reads into the kernel interfaces
     * directly. See {@link IBatteryPropertiesRegistrar#getProperty}.
     */
    int getProperty(int id, BatteryProperty prop) throws RemoteException;

    /** Calls update() in the health HAL. See {@link IBatteryPropertiesRegistrar#scheduleUpdate}. */
    void scheduleUpdate() throws RemoteException;

    /**
     * Calls into getHealthInfo() in the health HAL. This returns a cached value in the health HAL
     * implementation.
     *
     * @return health info. {@code null} if no health HAL service. {@code null} if any
     *     service-specific error when calling {@code getHealthInfo}, e.g. it is unsupported.
     * @throws RemoteException for any transaction-level errors
     */
    // TODO(b/177269435): AIDL
    android.hardware.health.V1_0.HealthInfo getHealthInfo() throws RemoteException;

    /**
     * Create a new HealthServiceWrapper instance.
     *
     * @param healthInfoCallback the callback to call when health info changes
     * @return the new HealthServiceWrapper instance, which may be backed by HIDL or AIDL service,
     * @throws RemoteException transaction errors
     * @throws NoSuchElementException no HIDL or AIDL service is available
     */
    static HealthServiceWrapper create(@Nullable HealthInfoCallback healthInfoCallback)
            throws RemoteException, NoSuchElementException {
        HealthServiceWrapper service = new HealthServiceWrapperHidl();
        service.init(healthInfoCallback);
        return service;
    }
}
