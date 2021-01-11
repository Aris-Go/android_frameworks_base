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

package android.telephony;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Request used to register {@link SignalThresholdInfo} to be notified when the signal strength
 * breach the specified thresholds.
 */
public final class SignalStrengthUpdateRequest implements Parcelable {

    /**
     * The minimum number of thresholds allowed in the request for each SignalThresholdInfo.
     *
     * @see SignalThresholdInfo#getThresholds()
     */
    public static final int MINIMUM_NUMBER_OF_THRESHOLDS = 1;

    /**
     * The maximum number of thresholds allowed in the request for each SignalThresholdInfo.
     *
     * @see SignalThresholdInfo#getThresholds()
     */
    public static final int MAXIMUM_NUMBER_OF_THRESHOLDS = 4;

    /**
     * List of SignalThresholdInfo for the request.
     */
    private final ArrayList<SignalThresholdInfo> mSignalThresholdInfos;

    /**
     * Whether the system thresholds should be honored when screen is off.
     *
     * System signal thresholds are loaded from carrier config items and mainly used for UI
     * displaying. By default, they are ignored when screen is off. When setting the value to true,
     * modem will continue reporting signal strength changes over the system signal thresholds even
     * screen is off.
     *
     * This should only set to true by the system caller.
     */
    private final boolean mHonorSystemThresholdsWhenScreenOff;

    /**
     * A IBinder object as a token for server side to check if the request client is still living.
     * Note that this token should NOT work as uniqueness of the request. In other word, this token
     * does not count in object equals check.
     */
    private IBinder mLiveToken;

    /** @hide */
    public SignalStrengthUpdateRequest(
            @NonNull Collection<SignalThresholdInfo> signalThresholdInfos,
            boolean honorSystemThresholdsWhenScreenOff) {
        if (!isValid(signalThresholdInfos)) {
            throw new IllegalArgumentException("Invalid collection of SignalThresholdInfo");
        }

        mSignalThresholdInfos = new ArrayList<>(signalThresholdInfos);
        // Sort the collection with RAN ascending order, make the ordering not matter for equals
        mSignalThresholdInfos.sort(
                Comparator.comparingInt(SignalThresholdInfo::getRadioAccessNetworkType));
        mHonorSystemThresholdsWhenScreenOff = honorSystemThresholdsWhenScreenOff;
        mLiveToken = new Binder();
    }

    /**
     * Construct request from a collection of {@link SignalThresholdInfo}.
     *
     * Note that the collection should not be empty size. Each radio access network type in the
     * collection should be unique. In each SignalThresholdInfo, the length of thresholds should
     * between {@link #MINIMUM_NUMBER_OF_THRESHOLDS} and {@link #MAXIMUM_NUMBER_OF_THRESHOLDS}. An
     * IllegalArgumentException will throw otherwise.
     *
     * @param signalThresholdInfos a collection of SignalThresholdInfo which specifies the
     *                             radio access network type, SignalMeasurementType and
     *                             corresponding thresholds.
     */
    public SignalStrengthUpdateRequest(
            @NonNull Collection<SignalThresholdInfo> signalThresholdInfos) {
        this(signalThresholdInfos, false /* honorSystemThresholdsWhenScreenOff */);
    }

    private SignalStrengthUpdateRequest(Parcel in) {
        mSignalThresholdInfos = in.createTypedArrayList(SignalThresholdInfo.CREATOR);
        // Sort the collection with RAN ascending order
        mSignalThresholdInfos.sort(
                Comparator.comparingInt(SignalThresholdInfo::getRadioAccessNetworkType));
        mHonorSystemThresholdsWhenScreenOff = in.readBoolean();
    }

    /**
     * Get the collection of SignalThresholdInfo in the request.
     *
     * @return the collection of SignalThresholdInfo.
     */
    @NonNull
    public Collection<SignalThresholdInfo> getSignalThresholdInfos() {
        return mSignalThresholdInfos;
    }

    /**
     * @return if system thresholds is honored when screen is off.
     *
     * @hide
     */
    public boolean getHonorSystemThresholdWhenScreenOff() {
        return mHonorSystemThresholdsWhenScreenOff;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mSignalThresholdInfos);
        dest.writeBoolean(mHonorSystemThresholdsWhenScreenOff);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;

        if (!(other instanceof SignalStrengthUpdateRequest)) {
            return false;
        }

        SignalStrengthUpdateRequest request = (SignalStrengthUpdateRequest) other;
        return request.mSignalThresholdInfos.equals(mSignalThresholdInfos)
                && request.mHonorSystemThresholdsWhenScreenOff
                == mHonorSystemThresholdsWhenScreenOff;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSignalThresholdInfos, mHonorSystemThresholdsWhenScreenOff);
    }

    public static final @NonNull Parcelable.Creator<SignalStrengthUpdateRequest> CREATOR =
            new Parcelable.Creator<SignalStrengthUpdateRequest>() {
                @Override
                public SignalStrengthUpdateRequest createFromParcel(Parcel source) {
                    return new SignalStrengthUpdateRequest(source);
                }

                @Override
                public SignalStrengthUpdateRequest[] newArray(int size) {
                    return new SignalStrengthUpdateRequest[size];
                }
            };

    @Override
    public String toString() {
        return new StringBuilder("SignalStrengthUpdateRequest{")
                .append("mSignalThresholdInfos=")
                .append(mSignalThresholdInfos)
                .append("mHonorSystemThresholdsWhenScreenOff=")
                .append(mHonorSystemThresholdsWhenScreenOff)
                .append("}").toString();
    }

    /**
     * @return the live token of the request.
     *
     * @hide
     */
    public IBinder getLiveToken() {
        return mLiveToken;
    }


    /**
     * Return false when one of the conditions met:
     * 1. The collection is empty
     * 2. The RAN in the collection is not unique
     * 3. The thresholds array includes too little or too many values
     */
    private static boolean isValid(Collection<SignalThresholdInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            return false;
        }

        Set<Integer> uniqueRan = new HashSet<>(infos.size());
        for (SignalThresholdInfo info : infos) {
            final int ran = info.getRadioAccessNetworkType();
            if (!uniqueRan.add(ran)) {
                return false;
            }

            int[] thresholds = info.getThresholds();
            if (ArrayUtils.isEmpty(thresholds)
                    || thresholds.length > MAXIMUM_NUMBER_OF_THRESHOLDS) {
                return false;
            }
        }

        return true;
    }
}
