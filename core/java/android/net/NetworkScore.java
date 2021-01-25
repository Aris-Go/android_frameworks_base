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

package android.net;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Object representing the quality of a network as perceived by the user.
 *
 * A NetworkScore object represents the characteristics of a network that affects how good the
 * network is considered for a particular use.
 * @hide
 */
@SystemApi
public final class NetworkScore implements Parcelable {
    // This will be removed soon. Do *NOT* depend on it for any new code that is not part of
    // a migration.
    private final int mLegacyInt;

    // Agent-managed policies
    // TODO : add them here, starting from 1
    // CS-managed policies
    // This network is validated. CS-managed because the source of truth is in NetworkCapabilities.
    /** @hide */
    public static final int POLICY_IS_VALIDATED = 63;

    // This is a VPN and behaves as one for scoring purposes.
    /** @hide */
    public static final int POLICY_IS_VPN = 62;

    // This network has been selected by the user manually from settings or a 3rd party app
    // at least once. {@see NetworkAgentConfig#explicitlySelected}.
    /** @hide */
    public static final int POLICY_ONCE_CHOSEN_BY_USER = 61;

    // The user has indicated in UI that this network should be used even if it doesn't
    // validate. {@see NetworkAgentConfig#acceptUnvalidated}.
    /** @hide */
    public static final int POLICY_ACCEPT_UNVALIDATED = 60;

    private final long mPolicy;

    /** @hide */
    NetworkScore(final int legacyInt, final long policy) {
        mLegacyInt = legacyInt;
        mPolicy = policy;
    }

    private NetworkScore(@NonNull final Parcel in) {
        mLegacyInt = in.readInt();
        mPolicy = in.readLong();
    }

    // Helper methods for connectivity service to mix in bits
    /** @hide */
    public NetworkScore withPolicyBits(
            final boolean isValidated,
            final boolean isVpn,
            final boolean onceChosenByUser,
            final boolean acceptUnvalidated) {
        return new NetworkScore(mLegacyInt,
                (isValidated ? 1L << POLICY_IS_VALIDATED : 0)
                | (isVpn ? 1L << POLICY_IS_VPN : 0)
                | (onceChosenByUser ? 1L << POLICY_ONCE_CHOSEN_BY_USER : 0)
                | (acceptUnvalidated ? 1L << POLICY_ACCEPT_UNVALIDATED : 0));
    }

    public int getLegacyInt() {
        return getLegacyInt(false);
    }

    /** @hide */
    public int getLegacyIntAsValidated() {
        return getLegacyInt(true);
    }

    // TODO : remove these two constants
    // Penalty applied to scores of Networks that have not been validated.
    private static final int UNVALIDATED_SCORE_PENALTY = 40;

    // Score for a network that can be used unvalidated
    private static final int ACCEPT_UNVALIDATED_NETWORK_SCORE = 100;

    private int getLegacyInt(boolean pretendValidated) {
        // If the user has chosen this network at least once, give it the maximum score when
        // checking to pretend it's validated, or if it doesn't need to validate because the
        // user said to use it even if it doesn't validate.
        // This ensures that networks that have been selected in UI are not torn down before the
        // user gets a chance to prefer it when a higher-scoring network (e.g., Ethernet) is
        // available.
        if (hasPolicy(POLICY_ONCE_CHOSEN_BY_USER)
                && (hasPolicy(POLICY_ACCEPT_UNVALIDATED) || pretendValidated)) {
            return ACCEPT_UNVALIDATED_NETWORK_SCORE;
        }

        int score = mLegacyInt;
        if (!hasPolicy(POLICY_IS_VALIDATED) && !pretendValidated && !hasPolicy(POLICY_IS_VPN)) {
            score -= UNVALIDATED_SCORE_PENALTY;
        }
        if (score < 0) score = 0;
        return score;
    }

    private boolean hasPolicy(final int policy) {
        return 0 != (mPolicy & (1L << policy));
    }

    @Override
    public String toString() {
        return "Score(" + mLegacyInt + " ; " + mPolicy + ")";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final NetworkScore that = (NetworkScore) o;

        if (mLegacyInt != that.mLegacyInt) return false;
        return mPolicy == that.mPolicy;
    }

    @Override
    public int hashCode() {
        // Policy bits are at the top and the bottom, so by multiplying the legacy int by
        // 256 this is a perfect hash until policy bits get up to bit 8 or down to
        // bit 32 - 6 = 24 (6 because legacyInt max value is 101 which fits in 6 bits).
        // By the time there are this many policy bits, the legacy int will be gone.
        return 256 * mLegacyInt + (int) (mPolicy ^ (mPolicy >>> 32));
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeInt(mLegacyInt);
        dest.writeLong(mPolicy);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull public static final Creator<NetworkScore> CREATOR = new Creator<>() {
        @Override
        @NonNull
        public NetworkScore createFromParcel(@NonNull final Parcel in) {
            return new NetworkScore(in);
        }

        @Override
        @NonNull
        public NetworkScore[] newArray(int size) {
            return new NetworkScore[size];
        }
    };

    /**
     * A builder for NetworkScore.
     */
    public static final class Builder {
        private static final int INVALID_LEGACY_INT = Integer.MIN_VALUE;
        private int mLegacyInt = INVALID_LEGACY_INT;

        /**
         * Sets the legacy int for this score.
         *
         * Do not rely on this. It will be gone by the time S is released.
         *
         * @param score the legacy int
         * @return this
         */
        @NonNull
        public Builder setLegacyInt(final int score) {
            mLegacyInt = score;
            return this;
        }

        /**
         * Builds this NetworkScore.
         * @return The built NetworkScore object.
         */
        @NonNull
        public NetworkScore build() {
            return new NetworkScore(mLegacyInt, 0L /* policy */);
        }
    }
}
