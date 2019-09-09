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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.text.TextUtils;

import com.android.internal.util.BitUtils;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines basic data for DNS protocol based on RFC 1035.
 * Subclasses create the specific format used in DNS packet.
 *
 * @hide
 */
@SystemApi
@TestApi
public abstract class DnsPacket {
    /**
     * Class of DnsHeader for DNS protocol based on RFC 1035.
     */
    public static class DnsHeader {
        private static final String TAG = "DnsHeader";
        public final int id;
        public final int flags;
        public final int rcode;
        private final int[] mRecordCount;

        /**
         * Create a new DnsHeader from a positioned ByteBuffer.
         *
         * The ByteBuffer must be in network byte order (which is the default).
         * Reads the passed ByteBuffer from its current position and decodes a DNS header.
         * When this constructor returns, the reading position of the ByteBuffer has been
         * advanced to the end of the DNS header record.
         * This is meant to chain with other methods reading a DNS response in sequence.
         */
        DnsHeader(@NonNull ByteBuffer buf) throws BufferUnderflowException {
            id = BitUtils.uint16(buf.getShort());
            flags = BitUtils.uint16(buf.getShort());
            rcode = flags & 0xF;
            mRecordCount = new int[NUM_SECTIONS];
            for (int i = 0; i < NUM_SECTIONS; ++i) {
                mRecordCount[i] = BitUtils.uint16(buf.getShort());
            }
        }

        DnsHeader(int id, int flags, int rcode, @NonNull int[] recordCount) {
            this.id = id;
            this.flags = flags;
            this.rcode = rcode;
            mRecordCount = recordCount;
        }

        /**
         * Builder of DnsHeader.
         */
        public static final class Builder {
            private int mId;
            private int mFlags;
            private int mRcode;
            private int[] mRecordCount;

            /**
             * Set id of DnsHeader.
             */
            @NonNull
            public Builder setId(int id) {
                mId = id;
                return this;
            }

            /**
             * Set flags of DnsHeader.
             */
            @NonNull
            public Builder setFlags(int flags) {
                mFlags = flags;
                return this;
            }
            /**
             * Set setRcode of DnsHeader.
             */
            @NonNull
            public Builder setRcode(int rcode) {
                mRcode = rcode;
                return this;
            }

            /**
             * Set setRcode of DnsHeader.
             */
            @NonNull
            public Builder setRecordCount(@NonNull int[] recordCount) {
                mRecordCount = recordCount;
                return this;
            }
            /**
             * Create a new {@link DnsHeader}.
             */
            @NonNull
            public DnsHeader build() {
                return new DnsHeader(mId, mFlags, mRcode, mRecordCount);
            }
        }

        /**
         * Get record count by type.
         */
        public int getRecordCount(int type) {
            return mRecordCount[type];
        }
    }

    /**
     * Superclass for DNS questions and DNS resource records.
     *
     * DNS questions (No TTL/RDATA)
     * DNS resource records (With TTL/RDATA)
     */
    public static class DnsRecord {
        private static final int MAXNAMESIZE = 255;
        private static final int MAXLABELSIZE = 63;
        private static final int MAXLABELCOUNT = 128;
        private static final int NAME_NORMAL = 0;
        private static final int NAME_COMPRESSION = 0xC0;
        private final DecimalFormat byteFormat = new DecimalFormat();
        private final FieldPosition pos = new FieldPosition(0);

        private static final String TAG = "DnsRecord";

        public final String dName;
        public final int nsType;
        public final int nsClass;
        public final long ttl;
        private final byte[] mRdata;

        /**
         * Create a new DnsRecord from a positioned ByteBuffer.
         *
         * Reads the passed ByteBuffer from its current position and decodes a DNS record.
         * When this constructor returns, the reading position of the ByteBuffer has been
         * advanced to the end of the DNS header record.
         * This is meant to chain with other methods reading a DNS response in sequence.
         *
         * @param ByteBuffer input of record, must be in network byte order
         *         (which is the default).
         */
        DnsRecord(int recordType, @NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            dName = parseName(buf, 0 /* Parse depth */);
            if (dName.length() > MAXNAMESIZE) {
                throw new ParseException(
                        "Parse name fail, name size is too long: " + dName.length());
            }
            nsType = BitUtils.uint16(buf.getShort());
            nsClass = BitUtils.uint16(buf.getShort());

            if (recordType != QDSECTION) {
                ttl = BitUtils.uint32(buf.getInt());
                final int length = BitUtils.uint16(buf.getShort());
                mRdata = new byte[length];
                buf.get(mRdata);
            } else {
                ttl = 0;
                mRdata = null;
            }
        }

        DnsRecord(@NonNull String dName, int nsType, int nsClass,
                long ttl, @Nullable byte[] rdata) {
            this.dName = dName;
            this.nsType = nsType;
            this.nsClass = nsClass;
            this.ttl = ttl;
            mRdata = rdata;
        }

        /**
         * Builder of DnsRecord.
         */
        public static final class Builder {
            private String mDName;
            private int mNsType;
            private int mNsClass;
            private long mTtl;
            private byte[] mRdata;

            /**
             * Set domain name of DnsRecord.
             */
            @NonNull
            public Builder setDomainName(@NonNull String name) {
                mDName = name;
                return this;
            }

            /**
             * Set nsType of DnsRecord.
             */
            @NonNull
            public Builder setNsType(int nsType) {
                mNsType = nsType;
                return this;
            }
            /**
             * Set nsClass of DnsRecord.
             */
            @NonNull
            public Builder setNsClass(int nsClass) {
                mNsClass = nsClass;
                return this;
            }

            /**
             * Set ttl of DnsRecord.
             */
            @NonNull
            public Builder setTtl(long ttl) {
                mTtl = ttl;
                return this;
            }

            /**
             * Set resource data of DnsRecord.
             */
            @NonNull
            public Builder setResourceData(@Nullable byte[] rData) {
                mRdata = rData;
                return this;
            }

            /**
             * Create a new {@link DnsRecord}.
             */
            @NonNull
            public DnsRecord build() {
                return new DnsRecord(mDName, mNsType, mNsClass, mTtl, mRdata);
            }
        }

        /**
         * Get a copy of rdata.
         */
        @Nullable
        public byte[] getRR() {
            return (mRdata == null) ? null : mRdata.clone();
        }

        /**
         * Convert label from {@code byte[]} to {@code String}
         *
         * Follows the same conversion rules of the native code (ns_name.c in libc)
         */
        private String labelToString(@NonNull byte[] label) {
            final StringBuffer sb = new StringBuffer();
            for (int i = 0; i < label.length; ++i) {
                int b = BitUtils.uint8(label[i]);
                // Control characters and non-ASCII characters.
                if (b <= 0x20 || b >= 0x7f) {
                    // Append the byte as an escaped decimal number, e.g., "\19" for 0x13.
                    sb.append('\\');
                    byteFormat.format(b, sb, pos);
                } else if (b == '"' || b == '.' || b == ';' || b == '\\'
                        || b == '(' || b == ')' || b == '@' || b == '$') {
                    // Append the byte as an escaped character, e.g., "\:" for 0x3a.
                    sb.append('\\');
                    sb.append((char) b);
                } else {
                    // Append the byte as a character, e.g., "a" for 0x61.
                    sb.append((char) b);
                }
            }
            return sb.toString();
        }

        private String parseName(@NonNull ByteBuffer buf, int depth) throws
                BufferUnderflowException, ParseException {
            if (depth > MAXLABELCOUNT) {
                throw new ParseException("Failed to parse name, too many labels");
            }
            final int len = BitUtils.uint8(buf.get());
            final int mask = len & NAME_COMPRESSION;
            if (0 == len) {
                return "";
            } else if (mask != NAME_NORMAL && mask != NAME_COMPRESSION) {
                throw new ParseException("Parse name fail, bad label type");
            } else if (mask == NAME_COMPRESSION) {
                // Name compression based on RFC 1035 - 4.1.4 Message compression
                final int offset = ((len & ~NAME_COMPRESSION) << 8) + BitUtils.uint8(buf.get());
                final int oldPos = buf.position();
                if (offset >= oldPos - 2) {
                    throw new ParseException("Parse compression name fail, invalid compression");
                }
                buf.position(offset);
                final String pointed = parseName(buf, depth + 1);
                buf.position(oldPos);
                return pointed;
            } else {
                final byte[] label = new byte[len];
                buf.get(label);
                final String head = labelToString(label);
                if (head.length() > MAXLABELSIZE) {
                    throw new ParseException("Parse name fail, invalid label length");
                }
                final String tail = parseName(buf, depth + 1);
                return TextUtils.isEmpty(tail) ? head : head + "." + tail;
            }
        }
    }

    public static final int QDSECTION = 0;
    public static final int ANSECTION = 1;
    public static final int NSSECTION = 2;
    public static final int ARSECTION = 3;
    private static final int NUM_SECTIONS = ARSECTION + 1;

    private static final String TAG = DnsPacket.class.getSimpleName();

    private final DnsHeader mHeader;
    private final List<DnsRecord>[] mRecords;

    /**
     * Get DnsHeader
     */
    @NonNull
    public DnsHeader getHeader() {
        return mHeader;
    }

    /**
     * Get all DnsRecord
     */
    @NonNull
    public List<DnsRecord>[] getRecords() {
        return mRecords;
    }

    protected DnsPacket(@NonNull byte[] data) throws ParseException {
        if (null == data) throw new ParseException("Parse header failed, null input data");
        final ByteBuffer buffer;
        try {
            buffer = ByteBuffer.wrap(data);
            mHeader = new DnsHeader(buffer);
        } catch (BufferUnderflowException e) {
            throw new ParseException("Parse Header fail, bad input data", e);
        }

        mRecords = new ArrayList[NUM_SECTIONS];

        for (int i = 0; i < NUM_SECTIONS; ++i) {
            final int count = mHeader.getRecordCount(i);
            if (count > 0) {
                mRecords[i] = new ArrayList(count);
            }
            for (int j = 0; j < count; ++j) {
                try {
                    mRecords[i].add(new DnsRecord(i, buffer));
                } catch (BufferUnderflowException e) {
                    throw new ParseException("Parse record fail", e);
                }
            }
        }
    }
}
