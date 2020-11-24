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

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This is the exception type passed back through the onError method on {@link QosCallback}.
 * {@link QosCallbackException#getCause()} contains the actual error that caused this exception.
 *
 * The possible exception types as causes are:
 * 1. {@link android.net.QosCallbackException.NetworkReleasedException}
 * 2. {@link java.lang.SecurityException}
 * 3. {@link android.net.QosSocketInfo.SocketNotBoundException}
 * 4. {@link UnsupportedOperationException}
 * 5. {@link android.net.ConnectivityManager.TooManyRequestsException}
 *
 * @hide
 */
@SystemApi
public final class QosCallbackException extends Exception {

    /** @hide */
    @IntDef(prefix = {"EX_TYPE_"}, value = {
            EX_TYPE_FILTER_NETWORK_RELEASED,
            EX_TYPE_FILTER_NETWORK_SECURITY,
            EX_TYPE_FILTER_SOCKET_NOT_BOUND,
            EX_TYPE_FILTER_NOT_SUPPORTED,
            EX_TYPE_TOO_MANY_REQUESTS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ExceptionType {}

    private static final String TAG = "QosCallbackException";

    // Types of exceptions supported //
    /** {@hide} */
    public static final int EX_TYPE_FILTER_NETWORK_RELEASED = 1;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_NETWORK_SECURITY = 2;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_SOCKET_NOT_BOUND = 3;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_NOT_SUPPORTED = 4;

    /** {@hide} */
    public static final int EX_TYPE_TOO_MANY_REQUESTS = 5;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED = 6;

    /**
     * Creates exception based off of a type and message.  Not all types of exceptions accept a
     * custom message.
     *
     * {@hide}
     */
    @NonNull
    static QosCallbackException createException(@ExceptionType int type,
            String message) {
        if (type == EX_TYPE_FILTER_NETWORK_RELEASED) {
            return new QosCallbackException(new NetworkReleasedException());
        } else if (type == EX_TYPE_FILTER_NETWORK_SECURITY) {
            return new QosCallbackException(new SecurityException(message));
        } else if (type == EX_TYPE_FILTER_SOCKET_NOT_BOUND) {
            return new QosCallbackException(new QosSocketInfo.SocketNotBoundException());
        } else if (type == EX_TYPE_FILTER_NOT_SUPPORTED) {
            return new QosCallbackException(
                    new UnsupportedOperationException(
                            "This device does not support the specified filter"));
        } else if (type == EX_TYPE_TOO_MANY_REQUESTS) {
            return new QosCallbackException(new ConnectivityManager.TooManyRequestsException());
        } else if (type == EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED) {
            return new QosCallbackException(
                    new QosSocketInfo.SocketLocalAddressChangedException());
        } else {
            Log.wtf(TAG, "create: No case setup for the type: '" + type + "'");
            return new QosCallbackException(
                    new RuntimeException("Unknown exception code: " + type));
        }
    }

    public QosCallbackException(@NonNull final String message) {
        super(message);
    }

    public QosCallbackException(@NonNull final Throwable cause) {
        super(cause);
    }

    /**
     * Indicates that the Network used within the QosFilter is now invalid.
     * This is passed through QosCallbackException.getCause().
     */
    public static class NetworkReleasedException extends Exception {

        public NetworkReleasedException() {
            super();
        }

        @NonNull
        @Override
        public String getMessage() {
            return "The Network was released and is no longer available";
        }
    }
}
