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

import com.android.internal.util.BitUtils;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Defines basic data for DNS protocol based on RFC 1035.
 * Subclasses create the specific format used in DNS packet.
 *
 * @hide
 */
public abstract class DnsPacket {
    public class DnsHeader {
        private static final String TAG = "DnsHeader";
        public final int id;
        public final int flags;
        public final int rcode;
        private final int[] mSectionCount;

        /**
         * Create a new DnsHeader from a positioned ByteBuffer.
         *
         * The ByteBuffer must be in network byte order (which is the default).
         * Reads the passed ByteBuffer from its current position and decodes a DNS header.
         * When this constructor returns, the reading position of the ByteBuffer has been
         * advanced to the end of the DNS header record.
         * This is meant to chain with other methods reading a DNS response in sequence.
         *
         */
        DnsHeader(@NonNull ByteBuffer buf) throws BufferUnderflowException {
            id = BitUtils.uint16(buf.getShort());
            flags = BitUtils.uint16(buf.getShort());
            rcode = flags & 0xF;
            mSectionCount = new int[NUM_SECTIONS];
            for (int i = 0; i < NUM_SECTIONS; ++i) {
                mSectionCount[i] = BitUtils.uint16(buf.getShort());
            }
        }

        /**
         * Get section count by section type.
         */
        public int getSectionCount(int sectionType) {
            return mSectionCount[sectionType];
        }
    }

    public class DnsSection {
        private static final int MAXNAMESIZE = 255;
        private static final int MAXLABELSIZE = 63;
        private static final int MAXLABELCOUNT = 128;
        private static final int NAME_NORMAL = 0;
        private static final int NAME_COMPRESSION = 0xC0;
        private final DecimalFormat byteFormat = new DecimalFormat();
        private final FieldPosition pos = new FieldPosition(0);

        private static final String TAG = "DnsSection";

        public final String dName;
        public final int nsType;
        public final int nsClass;
        public final long ttl;
        private final byte[] mRR;

        private int mNormalLabelCount;
        private int mCompressedPos;

        /**
         * Create a new DnsSection from a positioned ByteBuffer.
         *
         * The ByteBuffer must be in network byte order (which is the default).
         * Reads the passed ByteBuffer from its current position and decodes a DNS section.
         * When this constructor returns, the reading position of the ByteBuffer has been
         * advanced to the end of the DNS header record.
         * This is meant to chain with other methods reading a DNS response in sequence.
         *
         */
        DnsSection(int sectionType, @NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            mCompressedPos = 0;
            mNormalLabelCount = 0;
            dName = parseName(buf);
            nsType = BitUtils.uint16(buf.getShort());
            nsClass = BitUtils.uint16(buf.getShort());

            if (sectionType != QDSECTION) {
                ttl = BitUtils.uint32(buf.getInt());
                final int length = BitUtils.uint16(buf.getShort());
                mRR = new byte[length];
                buf.get(mRR);
            } else {
                ttl = 0;
                mRR = null;
            }
        }

        /**
         * Get a copy of rr.
         */
        @Nullable public byte[] getRR() {
            return (mRR == null) ? null : mRR.clone();
        }

        /**
         * Convert label from {@code byte[]} to {@code String}
         *
         * It follows the same converting rule as native layer.
         * (See ns_name.c in libc)
         *
         */
        private String labelToString(@NonNull byte[] label) {
            final StringBuffer sb = new StringBuffer();
            for (int i = 0; i < label.length; ++i) {
                int b = BitUtils.uint8(label[i]);
                // Control characters and non-ASCII characters.
                if (b <= 0x20 || b >= 0x7f) {
                    sb.append('\\');
                    byteFormat.format(b, sb, pos);
                } else if (b == '"' || b == '.' || b == ';' || b == '\\'
                        || b == '(' || b == ')' || b == '@' || b == '$') {
                    sb.append('\\');
                    sb.append((char) b);
                } else {
                    sb.append((char) b);
                }
            }
            return sb.toString();
        }

        private String parseName(@NonNull ByteBuffer buf) throws
                BufferUnderflowException, ParseException {
            final StringJoiner sj = new StringJoiner(".");
            String nameSection = readNameSection(buf);
            int labelCount = 0;
            while (null != nameSection) {
                if (++labelCount > MAXLABELCOUNT) {
                    throw new ParseException("Parse name fail, too many labels");
                }
                sj.add(nameSection);
                nameSection = readNameSection(buf);
            }
            if (mCompressedPos != 0) {
                buf.position(mCompressedPos);
            }
            return sj.toString();
        }

        private String readNameSection(@NonNull ByteBuffer buf) throws
                BufferUnderflowException, ParseException {
            final int len = BitUtils.uint8(buf.get());
            if (len == 0) return null;
            final int mask = len & NAME_COMPRESSION;
            if (mask != NAME_NORMAL && mask != NAME_COMPRESSION) {
                throw new ParseException("Parse name fail, bad label type");
            }
            if (mask == NAME_NORMAL) {
                return readString(buf, len);
            } else {
                // Name compression based on RFC 1035 - 4.1.4 Message compression
                final int offset = ((len & ~NAME_COMPRESSION) << 8) + BitUtils.uint8(buf.get());
                return readCompressedName(buf, offset);
            }
        }

        private String readString(@NonNull ByteBuffer buf, int len) throws
                BufferUnderflowException, ParseException {
            if (len > MAXLABELSIZE) {
                throw new ParseException("Parse normal name fail, invalid label length");
            }
            final byte[] label = new byte[len];
            buf.get(label);
            if (mCompressedPos == 0) {
                ++mNormalLabelCount;
            }
            return labelToString(label);
        }

        private String readCompressedName(@NonNull ByteBuffer buf, int offset) throws
                BufferUnderflowException, ParseException {
            if (mCompressedPos != 0) {
                throw new ParseException("Parse compression name fail, too many pointers");
            }
            mCompressedPos = buf.position();
            if (offset >= (mCompressedPos - 2)) {
                throw new ParseException("Parse compression name fail, invalid compression");
            }

            buf.position(offset);
            final int len = BitUtils.uint8(buf.get());
            if (NAME_NORMAL != (len & NAME_COMPRESSION)) {
                throw new ParseException("Parse compression name fail, invalid offset");
            }
            final String name = readString(buf, len);

            return name;
        }
    }

    public static final int QDSECTION = 0;
    public static final int ANSECTION = 1;
    public static final int NSSECTION = 2;
    public static final int ARSECTION = 3;
    private static final int NUM_SECTIONS = ARSECTION + 1;

    private static final String TAG = DnsPacket.class.getSimpleName();

    protected final DnsHeader mHeader;
    protected final List<DnsSection>[] mSections;

    public static class ParseException extends Exception {
        public ParseException(String msg) {
            super(msg);
        }

        public ParseException(String msg, Throwable cause) {
            super(msg, cause);
        }
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

        mSections = new ArrayList[NUM_SECTIONS];

        for (int i = 0; i < NUM_SECTIONS; ++i) {
            final int count = mHeader.getSectionCount(i);
            if (count > 0) {
                mSections[i] = new ArrayList(count);
            }
            for (int j = 0; j < count; ++j) {
                try {
                    mSections[i].add(new DnsSection(i, buffer));
                } catch (BufferUnderflowException e) {
                    throw new ParseException("Parse section fail", e);
                }
            }
        }
    }
}
