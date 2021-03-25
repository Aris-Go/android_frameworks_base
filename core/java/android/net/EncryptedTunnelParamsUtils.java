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
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTunnelParams;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.net.vcn.persistablebundleutils.IkeSessionParamsUtils;
import android.net.vcn.persistablebundleutils.TunnelModeChildSessionParamsUtils;
import android.os.PersistableBundle;

import java.util.Objects;

/** Utility class to convert EncryptedTunnelParams to/from PersistableBundle */
public final class EncryptedTunnelParamsUtils {
    /** @hide */
    private static final String PARAMS_TYPE_KEY = "PARAMS_TYPE_KEY";
    /** @hide */
    private static final String PARAMS_KEY = "PARAMS_KEY";

    /** @hide */
    private static final int PARAMS_TYPE_IKE = 1;

    /** @hide */
    public static PersistableBundle toPersistableBundle(@NonNull EncryptedTunnelParams params) {
        final PersistableBundle result = new PersistableBundle();

        if (params instanceof IkeTunnelParams) {
            result.putInt(PARAMS_TYPE_KEY, PARAMS_TYPE_IKE);
            result.putPersistableBundle(
                    PARAMS_KEY, IkeTunnelParamsUtils.serializeIkeParams((IkeTunnelParams) params));
            return result;
        } else {
            throw new UnsupportedOperationException("Invalid EncryptedTunnelParams type");
        }
    }

    /** @hide */
    public static EncryptedTunnelParams fromPersistableBundle(@NonNull PersistableBundle in) {
        int configType = in.getInt(PARAMS_TYPE_KEY);
        switch (configType) {
            case PARAMS_TYPE_IKE:
                return IkeTunnelParamsUtils.deserializeIkeParams(
                        in.getPersistableBundle(PARAMS_KEY));
            default:
                throw new UnsupportedOperationException("Invalid EncryptedTunnelParams type");
        }
    }

    /** @hide */
    private static final class IkeTunnelParamsUtils {
        private static final String IKE_PARAMS_KEY = "IKE_PARAMS_KEY";
        private static final String CHILD_PARAMS_KEY = "CHILD_PARAMS_KEY";

        public static PersistableBundle serializeIkeParams(IkeTunnelParams ikeParams) {
            final PersistableBundle result = new PersistableBundle();

            result.putPersistableBundle(
                    IKE_PARAMS_KEY,
                    IkeSessionParamsUtils.toPersistableBundle(ikeParams.getIkeSessionParams()));
            result.putPersistableBundle(
                    CHILD_PARAMS_KEY,
                    TunnelModeChildSessionParamsUtils.toPersistableBundle(
                            ikeParams.getChildSessionParams()));
            return result;
        }

        public static IkeTunnelParams deserializeIkeParams(@NonNull PersistableBundle in) {
            final PersistableBundle ikeBundle = in.getPersistableBundle(IKE_PARAMS_KEY);
            final PersistableBundle childBundle = in.getPersistableBundle(CHILD_PARAMS_KEY);
            Objects.requireNonNull(ikeBundle, "IkeSessionParams was null");
            Objects.requireNonNull(ikeBundle, "TunnelModeChildSessionParams was null");

            final IkeSessionParams ikeParams =
                    IkeSessionParamsUtils.fromPersistableBundle(ikeBundle);
            final TunnelModeChildSessionParams childParams =
                    TunnelModeChildSessionParamsUtils.fromPersistableBundle(childBundle);
            return new IkeTunnelParams(ikeParams, childParams);
        }
    }
}
