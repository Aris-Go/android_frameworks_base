/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.platform.aconfig;

import static com.android.configinfrastructure.flags.Flags.FLAG_NEW_STORAGE_PLATFORM_SYSTEM_API;

import android.aconfig.storage.AconfigPackageImpl;
import android.aconfig.storage.StorageFileProvider;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Represents an Aconfig package containing the enabled state of its flags.
 *
 * <p><b>Note: This class is intended for use by generated code in updatable partitions system
 * product and vendor. To determine if a flag is enabled in application code, use the generated
 * Android flags.</b>
 *
 * <p>Each instance of this class caches information related to a single Aconfig package. To read
 * flags from a different package, {@link #load load} a new instance.
 *
 * @hide
 */
@FlaggedApi(FLAG_NEW_STORAGE_PLATFORM_SYSTEM_API)
@SystemApi
public class AconfigPackageLocal {

    private AconfigPackageImpl mImpl;

    private AconfigPackageLocal() {}

    /**
     * Loads an Aconfig Package from Aconfig Storage.
     *
     * <p>This method loads the specified Aconfig Package from the given container and verifies its
     * fingerprint. The fingerprint, generated at build time, ensures that the loaded package
     * matches the expected version.
     *
     * <p>Providing a matching fingerprint optimizes flag retrieval. If the loaded package's
     * fingerprint matches the provided {@code packageFingerprint}, subsequent calls to {@link
     * #getBooleanFlagValue(int)} can use direct indexing for faster access.
     *
     * @param container The name of the container.
     * @param packageName The name of the Aconfig package.
     * @param packageFingerprint The expected fingerprint of the package.
     * @return An instance of {@link AconfigPackageLocal}.
     * @throws AconfigStorageReadException if there is an error reading from Aconfig Storage, such
     *     as if the storage system is not found, the container is not found, there is an error
     *     reading the storage file, or the fingerprint does not match. The specific error code can
     *     be obtained using {@link AconfigStorageReadException#getErrorCode()}.
     * @hide
     */
    @FlaggedApi(FLAG_NEW_STORAGE_PLATFORM_SYSTEM_API)
    @SystemApi
    public static @NonNull AconfigPackageLocal load(
            @NonNull String container, @NonNull String packageName, long packageFingerprint) {
        AconfigPackageLocal aPackage = new AconfigPackageLocal();
        try {
            aPackage.mImpl =
                    AconfigPackageImpl.load(packageName, StorageFileProvider.getDefaultProvider());
        } catch (AconfigStorageException e) {
            throw new AconfigStorageReadException(e.getErrorCode(), e);
        }

        if (!aPackage.mImpl.hasPackageFingerprint()
                || Long.compareUnsigned(packageFingerprint, aPackage.mImpl.getPackageFingerprint())
                        != 0) {
            throw new AconfigStorageReadException(
                    AconfigStorageReadException.ERROR_FILE_FINGERPRINT_MISMATCH,
                    "The fingerprint provided for the Aconfig package "
                            + packageName
                            + " in container "
                            + container
                            + " does not match the"
                            + " fingerprint of the package found on the device");
        }
        return aPackage;
    }

    /**
     * Retrieves the value of a boolean flag using its index.
     *
     * <p>This method retrieves the value of a flag within the Aconfig Package using its index. The
     * index is generated at build time and may vary between builds.
     *
     * <p>To ensure you are using the correct index for the current build, verify that the package's
     * fingerprint matches the expected fingerprint before calling this method. If the fingerprints
     * do not match, use {@link #getBooleanFlagValue(String, boolean)} instead.
     *
     * @param index The index of the flag within the package.
     * @return The boolean value of the flag.
     * @hide
     */
    @FlaggedApi(FLAG_NEW_STORAGE_PLATFORM_SYSTEM_API)
    @SystemApi
    public boolean getBooleanFlagValue(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index should not be negative");
        }
        return mImpl.getBooleanFlagValue(index);
    }
}
