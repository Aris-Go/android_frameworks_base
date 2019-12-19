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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Class that includes information for a suspected data stall on a specific Network */
public final class DataStallReport implements Parcelable {
    public static final int DETECTION_METHOD_DNS_EVENTS = 1;
    public static final int DETECTION_METHOD_TCP_METRICS = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"DETECTION_METHOD_"},
            value = {DETECTION_METHOD_DNS_EVENTS, DETECTION_METHOD_TCP_METRICS})
    public @interface DetectionMethod {}

    /** The Network for which this DataStallReport applied */
    @NonNull private final Network mNetwork;

    /**
     * The timestamp for the report. The timestamp is taken from {@link System#currentTimeMillis}.
     */
    private long mReportTimestamp;

    /** The detection method used to identify the suspected data stall */
    @DetectionMethod private final int mDetectionMethod;

    /** PersistableBundle that may contain additional information on the suspected data stall */
    @NonNull private final PersistableBundle mStallDetails;

    /**
     * Constructor for DataStallReport.
     *
     * <p>Apps should obtain instances through {@link
     * ConnectivityDiagnosticsCallback#onDataStallSuspected} instead of instantiating their own
     * instances (unless for testing purposes).
     *
     * @param network The Network for which this DataStallReport applies
     * @param reportTimestamp The timestamp for the report
     * @param detectionMethod The detection method used to identify this data stall
     * @param stallDetails A PersistableBundle that may contain additional info about the report
     */
    public DataStallReport(
            @NonNull Network network,
            long reportTimestamp,
            @DetectionMethod int detectionMethod,
            @NonNull PersistableBundle stallDetails) {
        mNetwork = network;
        mReportTimestamp = reportTimestamp;
        mDetectionMethod = detectionMethod;
        mStallDetails = stallDetails;
    }

    /**
     * Returns the Network for this DataStallReport.
     *
     * @return The Network for which this DataStallReport applied
     */
    @NonNull
    public Network getNetwork() {
        return mNetwork;
    }

    /**
     * Returns the epoch timestamp (milliseconds) for when this report was taken.
     *
     * @return The timestamp for the report. Taken from {@link System#currentTimeMillis}.
     */
    public long getReportTimestamp() {
        return mReportTimestamp;
    }

    /**
     * Returns the detection method used to identify this suspected data stall.
     *
     * @return The detection method used to identify the suspected data stall
     */
    public int getDetectionMethod() {
        return mDetectionMethod;
    }

    /**
     * Returns a PersistableBundle with additional info for this report.
     *
     * @return PersistableBundle that may contain additional information on the suspected data stall
     */
    @NonNull
    public PersistableBundle getStallDetails() {
        return mStallDetails;
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mNetwork.writeToParcel(dest, flags);
        dest.writeLong(mReportTimestamp);
        dest.writeInt(mDetectionMethod);
        mStallDetails.writeToParcel(dest, flags);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<DataStallReport> CREATOR =
            new Creator<DataStallReport>() {
                public DataStallReport createFromParcel(Parcel in) {
                    return new DataStallReport(
                            Network.CREATOR.createFromParcel(in),
                            in.readLong(),
                            in.readInt(),
                            PersistableBundle.CREATOR.createFromParcel(in));
                }

                public DataStallReport[] newArray(int size) {
                    return new DataStallReport[size];
                }
            };
}
