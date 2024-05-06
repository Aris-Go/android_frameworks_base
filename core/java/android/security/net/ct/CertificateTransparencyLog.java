/*
 * Copyright 2024 The Android Open Source Project
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
package android.security.net.ct;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.Flags;

import java.time.Duration;
import java.time.Instant;

/** A Certificate Transparency log. */
@FlaggedApi(Flags.FLAG_CERTIFICATE_TRANSPARENCY_LOG_LIST_SERVICE)
public final class CertificateTransparencyLog implements Parcelable {

    @NonNull
    public static final Parcelable.Creator<CertificateTransparencyLog> CREATOR =
            new Parcelable.Creator<CertificateTransparencyLog>() {

                @Override
                public CertificateTransparencyLog createFromParcel(Parcel source) {
                    return new CertificateTransparencyLog();
                }

                @Override
                public CertificateTransparencyLog[] newArray(int size) {
                    return new CertificateTransparencyLog[size];
                }
            };

    /**
     * The log's public key as a DER-encoded ASN.1 SubjectPublicKeyInfo structure, encoded as base64
     * (https://tools.ietf.org/html/rfc5280#section-4.1.2.7).
     *
     * @return the public key of the CT log.
     */
    @NonNull
    public byte[] getKey() {
        return new byte[0];
    }

    /**
     * This is the LogID found in Signed Certificate Timestamps issued by this log
     * (https://tools.ietf.org/html/rfc6962#section-3.2).
     *
     * @return the SHA-256 hash of the CT log's public key, base64-encoded.
     */
    @NonNull
    public byte[] getId() {
        return new byte[0];
    }

    /**
     * @return the base URL of the CT log's HTTP API.
     */
    @NonNull
    public String getUrl() {
        return "";
    }

    /**
     * The CT log should not take longer than this to incorporate a certificate
     * (https://tools.ietf.org/html/rfc6962#section-3).
     *
     * @return the Maximum Merge Delay.
     */
    @NonNull
    public Duration getMaximumMergeDelay() {
        return Duration.ZERO;
    }

    /**
     * @return a human-readable description that can identify this log.
     */
    @Nullable
    public String getDescription() {
        return null;
    }

    /** The validity interval of this log. */
    public class ValidityInterval {
        // All certificates must expire on this date or later.
        @Nullable public final Instant start;
        // All certificates must expire before this date.
        @Nullable public final Instant end;

        ValidityInterval(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * @return the {@link ValidityInterval} of this log. The log will only accept certificates that
     *     expire within the validity interval.
     */
    @Nullable
    public ValidityInterval getValidityInterval() {
        return null;
    }

    /**
     * @return the {@link LogState} of this log.
     */
    @CertificateTransparencyConstants.LogState
    public int getState() {
        return CertificateTransparencyConstants.CT_LOG_STATE_PENDING;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}
}
