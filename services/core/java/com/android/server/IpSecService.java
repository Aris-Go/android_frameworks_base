/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.DUMP;
import static android.net.IpSecManager.INVALID_RESOURCE_ID;
import static android.net.IpSecManager.KEY_PORT;
import static android.net.IpSecManager.KEY_RESOURCE_ID;
import static android.net.IpSecManager.KEY_SOCKET;
import static android.net.IpSecManager.KEY_SPI;
import static android.net.IpSecManager.KEY_STATUS;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;

import android.content.Context;
import android.net.IIpSecService;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

/** @hide */
public class IpSecService extends IIpSecService.Stub {
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String NETD_SERVICE_NAME = "netd";
    private static final int[] DIRECTIONS =
            new int[] {IpSecTransform.DIRECTION_OUT, IpSecTransform.DIRECTION_IN};

    private static final int NETD_FETCH_TIMEOUT = 5000; //ms
    private static final int MAX_PORT_BIND_ATTEMPTS = 1000;
    private static final InetAddress INADDR_ANY;

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException("Unable to create INADDR_ANY! " + e);
        }
    }

    /* Binder context for this service */
    private final Context mContext;

    /* global lock for the IpSecService */
    private final Object mLock = new Object();

    /** Should be a never-repeating global ID for resources */
    private static AtomicInteger mNextResourceId = new AtomicInteger(0x00FADED0);

    @GuardedBy("mLock")
    private final SparseArray<SpiRecord> mSpiRecords = new SparseArray<>();

    @GuardedBy("mLock")
    private final SparseArray<TransformRecord> mTransformRecords = new SparseArray<>();

    @GuardedBy("mLock")
    private final SparseArray<UdpSocketRecord> mUdpSocketRecords = new SparseArray<>();

    /**
     * The ManagedResource class provides a facility to cleanly and reliably release system
     * resources. It relies on two things: an IBinder that allows ManagedResource to automatically
     * clean up in the event that the Binder dies. To use this class, the user should implement the
     * releaseResources() method that is responsible for releasing system resources when invoked.
     */
    private abstract class ManagedResource implements IBinder.DeathRecipient {
        final int pid;
        final int uid;
        private IBinder mBinder;
        protected int mResourceId;

        ManagedResource(int resourceId, IBinder binder) {
            super();
            mBinder = binder;
            mResourceId = resourceId;
            pid = Binder.getCallingPid();
            uid = Binder.getCallingUid();

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        protected void checkOwnerOrSystemAndThrow() {
            if (uid != Binder.getCallingUid()
                    && android.os.Process.SYSTEM_UID != Binder.getCallingUid()) {
                throw new SecurityException("Only the owner may access managed resources!");
            }
        }

        /**
         * When this record is no longer needed for managing system resources this function should
         * clean up all system resources and nullify the record. This function shall perform all
         * necessary cleanup of the resources managed by this record.
         */
        public final void release() {
            checkOwnerOrSystemAndThrow();
            synchronized (mLock) {
                if (mResourceId == INVALID_RESOURCE_ID) {
                    return;
                }
                releaseResources();
                if (mBinder != null) {
                    mBinder.unlinkToDeath(this, 0);
                }

                mBinder = null;
                mResourceId = INVALID_RESOURCE_ID;
            }
        }

        /**
         * If the Binder object dies, this function is called to free the system resources that are
         * being managed by this record and to subsequently release this record for garbage
         * collection
         */
        public final void binderDied() {
            release();
        }

        /**
         * Implement this method to release all system resources that are being protected by this
         * record. Once the resources are released, the record should be invalidated and no longer
         * used by calling release(). This should NEVER be called directly.
         *
         * <p>Calls to this are always guarded by mLock
         */
        protected abstract void releaseResources();
    };

    private final class TransformRecord extends ManagedResource {
        private IpSecConfig mConfig;
        SpiRecord[] mSpis;

        TransformRecord(int resourceId, IBinder binder, IpSecConfig config, SpiRecord[] spis) {
            super(resourceId, binder);
            mConfig = config;
            mSpis = spis;

            for (int direction : DIRECTIONS) {
                mSpis[direction].setLockedByTransform();
            }
        }

        public IpSecConfig getConfig() {
            return mConfig;
        }

        public SpiRecord getSpiRecord(int direction) {
            return mSpis[direction];
        }

        /** always guarded by mLock */
        @Override
        protected void releaseResources() {
            int spi;
            for (int direction : DIRECTIONS) {
                spi = mSpis[direction].getSpi();
                try {
                    getNetdInstance()
                            .ipSecDeleteSecurityAssociation(
                                    mResourceId,
                                    direction,
                                    (mConfig.getLocalAddress() != null)
                                            ? mConfig.getLocalAddress().getHostAddress()
                                            : "",
                                    (mConfig.getRemoteAddress() != null)
                                            ? mConfig.getRemoteAddress().getHostAddress()
                                            : "",
                                    spi);
                } catch (ServiceSpecificException e) {
                    // FIXME: get the error code and throw is at an IOException from Errno Exception
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to delete SA with ID: " + mResourceId);
                }
            }

            mConfig = null;
            mSpis = null;
        }
    }

    private final class SpiRecord extends ManagedResource {
        private final int mDirection;
        private final String mLocalAddress;
        private final String mRemoteAddress;
        private int mSpi;

        private boolean mLockedByTransform = false;

        SpiRecord(
                int resourceId,
                IBinder binder,
                int direction,
                String localAddress,
                String remoteAddress,
                int spi) {
            super(resourceId, binder);
            mDirection = direction;
            mLocalAddress = localAddress;
            mRemoteAddress = remoteAddress;
            mSpi = spi;
        }

        /** always guarded by mLock */
        @Override
        protected void releaseResources() {
            if (mLockedByTransform) {
                Log.d(TAG, "Cannot release Spi " + mSpi + "Currently locked by a Transform");
                return;
            }
            try {
                getNetdInstance()
                        .ipSecDeleteSecurityAssociation(
                                mResourceId, mDirection, mLocalAddress, mRemoteAddress, mSpi);
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to delete SPI reservation with ID: " + mResourceId);
            }
            mSpi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        }

        public int getSpi() {
            checkOwnerOrSystemAndThrow();
            return mSpi;
        }

        public void setLockedByTransform() {
            if (mLockedByTransform) {
                // Programming error
                Log.wtf(TAG, "Cannot re-lock an SPI twice!");
            }

            mLockedByTransform = true;
        }
    }

    private final class UdpSocketRecord extends ManagedResource {
        private FileDescriptor mSocket;
        private int mPort;

        UdpSocketRecord(int resourceId, IBinder binder, FileDescriptor socket, int port) {
            super(resourceId, binder);
            mSocket = socket;
            mPort = port;
        }

        /** always guarded by mLock */
        @Override
        protected void releaseResources() {
            try {
                Log.d(TAG, "Closing port " + mPort);
                Os.close(mSocket);
            } catch (ErrnoException e) {
                Log.e(TAG, "Failed to close UDP Encapsulation Socket " + mPort);
            }
            mSocket = null;
        }

        public int getPort() {
            return mPort;
        }
    }

    /**
     * Constructs a new IpSecService instance
     *
     * @param context Binder context for this service
     */
    private IpSecService(Context context) {
        mContext = context;
    }

    static IpSecService create(Context context) throws InterruptedException {
        final IpSecService service = new IpSecService(context);
        service.connectNativeNetdService();
        return service;
    }

    public void systemReady() {
        if (isNetdAlive()) {
            Slog.d(TAG, "IpSecService is ready");
        } else {
            Slog.wtf(TAG, "IpSecService not ready: failed to connect to NetD Native Service!");
        }
    }

    private void connectNativeNetdService() {
        // Avoid blocking the system server to do this
        Thread t =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                synchronized (mLock) {
                                    NetdService.get(NETD_FETCH_TIMEOUT);
                                }
                            }
                        });
        t.run();
    }

    INetd getNetdInstance() throws RemoteException {
        final INetd netd = NetdService.getInstance();
        if (netd == null) {
            throw new RemoteException("Failed to Get Netd Instance");
        }
        return netd;
    }

    boolean isNetdAlive() {
        synchronized (mLock) {
            try {
                final INetd netd = getNetdInstance();
                if (netd == null) {
                    return false;
                }
                return netd.isAlive();
            } catch (RemoteException re) {
                return false;
            }
        }
    }

    @Override
    /** Get a new SPI and maintain the reservation in the system server */
    public Bundle reserveSecurityParameterIndex(
            int direction, String remoteAddress, int requestedSpi, IBinder binder)
            throws RemoteException {
        int resourceId = mNextResourceId.getAndIncrement();

        int spi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        String localAddress = "";
        Bundle retBundle = new Bundle(3);
        try {
            synchronized (mLock) {
                spi =
                        getNetdInstance()
                                .ipSecAllocateSpi(
                                        resourceId,
                                        direction,
                                        localAddress,
                                        remoteAddress,
                                        requestedSpi);
                Log.d(TAG, "Allocated SPI " + spi);
                retBundle.putInt(KEY_STATUS, IpSecManager.Status.OK);
                retBundle.putInt(KEY_RESOURCE_ID, resourceId);
                retBundle.putInt(KEY_SPI, spi);
                mSpiRecords.put(
                        resourceId,
                        new SpiRecord(
                                resourceId, binder, direction, localAddress, remoteAddress, spi));
            }
        } catch (ServiceSpecificException e) {
            // TODO: Add appropriate checks when other ServiceSpecificException types are supported
            retBundle.putInt(KEY_STATUS, IpSecManager.Status.SPI_UNAVAILABLE);
            retBundle.putInt(KEY_RESOURCE_ID, IpSecManager.INVALID_RESOURCE_ID);
            retBundle.putInt(KEY_SPI, spi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return retBundle;
    }

    /* This method should never be called from within IpSecService */
    private <T extends ManagedResource> void releaseManagedResource(
            SparseArray<T> resArray, int resourceId, String typeName) {
        synchronized (mLock) {
            T record;
            // We want to non-destructively get so that we can check credentials before removing
            // this from the records.
            record = resArray.get(resourceId);

            if (record == null) {
                throw new IllegalArgumentException(
                        typeName + " " + resourceId + " is not available to be deleted");
            }

            record.release();
            resArray.remove(resourceId);
        }
    }

    /** Release a previously allocated SPI that has been registered with the system server */
    @Override
    public void releaseSecurityParameterIndex(int resourceId) throws RemoteException {
        releaseManagedResource(mSpiRecords, resourceId, "SecurityParameterIndex");
    }

    /**
     * Open a socket via the system server and bind it to the specified port (random if port=0).
     * This will return a PFD to the user that represent a bound UDP socket. The system server will
     * cache the socket and a record of its owner so that it can and must be freed when no longer
     * needed.
     */
    @Override
    public Bundle openUdpEncapsulationSocket(int port, IBinder binder) throws RemoteException {
        Bundle retBundle = new Bundle(2);
        int resourceId = mNextResourceId.getAndIncrement();
        FileDescriptor sockFd = null;
        try {
            sockFd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

            // TODO: Port number validation, either 0 or non-reserved
            if (port != 0) {
                Log.d(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
            } else {
                for (int i = MAX_PORT_BIND_ATTEMPTS; i > 0; i--) {
                    try {
                        FileDescriptor probeSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                        Os.bind(probeSocket, INADDR_ANY, 0);
                        port = ((InetSocketAddress) Os.getsockname(probeSocket)).getPort();
                        Os.close(probeSocket);
                        Log.d(TAG, "Binding to port " + port);
                        Os.bind(sockFd, INADDR_ANY, port);
                        break;
                    } catch (ErrnoException e) {
                        // A case statement would be natural but OsConstants can't be used in
                        // cases because they aren't traditional constants
                        if (e.errno == OsConstants.EADDRINUSE) {
                            continue;
                        }
                        throw e;
                    }
                }
            }
            Os.setsockoptInt(
                    sockFd,
                    OsConstants.IPPROTO_UDP,
                    OsConstants.UDP_ENCAP,
                    OsConstants.UDP_ENCAP_ESPINUDP);
            retBundle.putInt(KEY_STATUS, IpSecManager.Status.OK);
            retBundle.putInt(KEY_RESOURCE_ID, resourceId);
            retBundle.putParcelable(KEY_SOCKET, ParcelFileDescriptor.dup(sockFd));
            retBundle.putInt(KEY_PORT, port);
            synchronized (mLock) {
                mUdpSocketRecords.put(
                        resourceId, new UdpSocketRecord(resourceId, binder, sockFd, port));
            }
            return retBundle;

        } catch (IOException | ErrnoException e) {
            try {
                if (sockFd != null) Os.close(sockFd);
            } catch (Exception unused) {
            }
            Log.e(TAG, "Failed to do something native " + e);
            retBundle.putInt(KEY_STATUS, IpSecManager.Status.RESOURCE_UNAVAILABLE);
            retBundle.putInt(KEY_RESOURCE_ID, IpSecManager.INVALID_RESOURCE_ID);
            retBundle.putParcelable(KEY_SOCKET, null);
            retBundle.putInt(KEY_PORT, 0);
        }
        return retBundle;
    }

    /** close a socket that has been been allocated by and registered with the system server */
    @Override
    public void closeUdpEncapsulationSocket(int resourceId) {
        releaseManagedResource(mUdpSocketRecords, resourceId, "UdpEncapsulationSocket");
    }

    /**
     * Create a transport mode transform, which represent two security associations (one in each
     * direction) in the kernel. The transform will be cached by the system server and must be freed
     * when no longer needed. It is possible to free one, deleting the SA from underneath sockets
     * that are using it, which will result in all of those sockets becoming unable to send or
     * receive data.
     */
    @Override
    public Bundle createTransportModeTransform(IpSecConfig c, IBinder binder)
            throws RemoteException {
        int resourceId = mNextResourceId.getAndIncrement();
        // Synchronize the whole block here because we are using other ManagedResources
        synchronized (mLock) {
            SpiRecord[] spis = new SpiRecord[DIRECTIONS.length];
            // TODO: Basic input validation here since it's coming over the Binder
            for (int direction : DIRECTIONS) {
                IpSecAlgorithm auth = c.getAuthentication(direction);
                IpSecAlgorithm crypt = c.getEncryption(direction);

                int encapType, encapLocalPort = 0, encapRemotePort = 0;
                encapType = c.getEncapType();
                if (encapType != IpSecTransform.ENCAP_NONE) {
                    encapLocalPort = mUdpSocketRecords.get(c.getEncapLocalResourceId()).getPort();
                    encapRemotePort = c.getEncapRemotePort();
                }

                spis[direction] = mSpiRecords.get(c.getSpiResourceId(direction));
                try {
                    int result =
                            getNetdInstance()
                                    .ipSecAddSecurityAssociation(
                                            resourceId,
                                            c.getMode(),
                                            direction,
                                            (c.getLocalAddress() != null)
                                                    ? c.getLocalAddress().getHostAddress()
                                                    : "",
                                            (c.getRemoteAddress() != null)
                                                    ? c.getRemoteAddress().getHostAddress()
                                                    : "",
                                            (c.getNetwork() != null)
                                                    ? c.getNetwork().getNetworkHandle()
                                                    : 0,
                                            spis[direction].getSpi(),
                                            (auth != null) ? auth.getName() : "",
                                            (auth != null) ? auth.getKey() : null,
                                            (auth != null) ? auth.getTruncationLengthBits() : 0,
                                            (crypt != null) ? crypt.getName() : "",
                                            (crypt != null) ? crypt.getKey() : null,
                                            (crypt != null) ? crypt.getTruncationLengthBits() : 0,
                                            encapType,
                                            encapLocalPort,
                                            encapRemotePort);
                    if (result != spis[direction].getSpi()) {
                        // TODO: cleanup the first SA if creation of second SA fails
                        Bundle retBundle = new Bundle(2);
                        retBundle.putInt(KEY_STATUS, IpSecManager.Status.SPI_UNAVAILABLE);
                        retBundle.putInt(KEY_RESOURCE_ID, INVALID_RESOURCE_ID);
                        return retBundle;
                    }
                } catch (ServiceSpecificException e) {
                    // FIXME: get the error code and throw is at an IOException from Errno Exception
                }
            }
            // Both SAs were created successfully, time to construct a record and lock it away
            mTransformRecords.put(resourceId, new TransformRecord(resourceId, binder, c, spis));
        }
        Bundle retBundle = new Bundle(2);
        retBundle.putInt(KEY_STATUS, IpSecManager.Status.OK);
        retBundle.putInt(KEY_RESOURCE_ID, resourceId);
        return retBundle;
    }

    /**
     * Delete a transport mode transform that was previously allocated by + registered with the
     * system server. If this is called on an inactive (or non-existent) transform, it will not
     * return an error. It's safe to de-allocate transforms that may have already been deleted for
     * other reasons.
     */
    @Override
    public void deleteTransportModeTransform(int resourceId) throws RemoteException {
        releaseManagedResource(mTransformRecords, resourceId, "IpSecTransform");
    }

    /**
     * Apply an active transport mode transform to a socket, which will apply the IPsec security
     * association as a correspondent policy to the provided socket
     */
    @Override
    public void applyTransportModeTransform(ParcelFileDescriptor socket, int resourceId)
            throws RemoteException {
        // Synchronize liberally here because we are using ManagedResources in this block
        synchronized (mLock) {
            TransformRecord info;
            // FIXME: this code should be factored out into a security check + getter
            info = mTransformRecords.get(resourceId);

            if (info == null) {
                throw new IllegalArgumentException("Transform " + resourceId + " is not active");
            }

            // TODO: make this a function.
            if (info.pid != getCallingPid() || info.uid != getCallingUid()) {
                throw new SecurityException("Only the owner of an IpSec Transform may apply it!");
            }

            IpSecConfig c = info.getConfig();
            try {
                for (int direction : DIRECTIONS) {
                    getNetdInstance()
                            .ipSecApplyTransportModeTransform(
                                    socket.getFileDescriptor(),
                                    resourceId,
                                    direction,
                                    (c.getLocalAddress() != null)
                                            ? c.getLocalAddress().getHostAddress()
                                            : "",
                                    (c.getRemoteAddress() != null)
                                            ? c.getRemoteAddress().getHostAddress()
                                            : "",
                                    info.getSpiRecord(direction).getSpi());
                }
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            }
        }
    }

    /**
     * Remove a transport mode transform from a socket, applying the default (empty) policy. This
     * will ensure that NO IPsec policy is applied to the socket (would be the equivalent of
     * applying a policy that performs no IPsec). Today the resourceId parameter is passed but not
     * used: reserved for future improved input validation.
     */
    @Override
    public void removeTransportModeTransform(ParcelFileDescriptor socket, int resourceId)
            throws RemoteException {
        try {
            getNetdInstance().ipSecRemoveTransportModeTransform(socket.getFileDescriptor());
        } catch (ServiceSpecificException e) {
            // FIXME: get the error code and throw is at an IOException from Errno Exception
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);
        // TODO: Add dump code to print out a log of all the resources being tracked
        pw.println("IpSecService Log:");
        pw.println("NetdNativeService Connection: " + (isNetdAlive() ? "alive" : "dead"));
        pw.println();
    }
}
