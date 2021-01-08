/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

/**
 * Snapshot of network state.
 *
 * @hide
 */
@SystemApi
public final class NetworkStateSnapshot implements Parcelable {
    private static final boolean VALIDATE_ROAMING_STATE = false;

    /** @hide */
    public static final NetworkStateSnapshot EMPTY = new NetworkStateSnapshot(null, null, null,
            null);

    /** @hide */
    @Nullable
    public final NetworkInfo networkInfo;
    @Nullable
    public final LinkProperties linkProperties;
    @Nullable
    public final NetworkCapabilities networkCapabilities;
    @Nullable
    public final Network network;

    public NetworkStateSnapshot(@Nullable LinkProperties linkProperties,
            @Nullable NetworkCapabilities networkCapabilities, @Nullable Network network) {
        this(null, linkProperties, networkCapabilities, network);
    }

    /** @hide */
    public NetworkStateSnapshot(NetworkInfo networkInfo, LinkProperties linkProperties,
            NetworkCapabilities networkCapabilities, Network network) {
        this.networkInfo = networkInfo;
        this.linkProperties = linkProperties;
        this.networkCapabilities = networkCapabilities;
        this.network = network;

        // This object is an atomic view of a network, so the various components
        // should always agree on roaming state.
        if (VALIDATE_ROAMING_STATE && networkInfo != null && networkCapabilities != null) {
            if (networkInfo.isRoaming() == networkCapabilities
                    .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                Slog.wtf("NetworkState", "Roaming state disagreement between " + networkInfo
                        + " and " + networkCapabilities);
            }
        }
    }

    /** @hide */
    public NetworkStateSnapshot(Parcel in) {
        networkInfo = in.readParcelable(null);
        linkProperties = in.readParcelable(null);
        networkCapabilities = in.readParcelable(null);
        network = in.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(networkInfo, flags);
        out.writeParcelable(linkProperties, flags);
        out.writeParcelable(networkCapabilities, flags);
        out.writeParcelable(network, flags);
    }

    @NonNull
    public static final Creator<NetworkStateSnapshot> CREATOR =
            new Creator<NetworkStateSnapshot>() {
        @Override
        public NetworkStateSnapshot createFromParcel(Parcel in) {
            return new NetworkStateSnapshot(in);
        }

        @Override
        public NetworkStateSnapshot[] newArray(int size) {
            return new NetworkStateSnapshot[size];
        }
    };
}
