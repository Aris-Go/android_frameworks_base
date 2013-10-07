/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.renderscript;

/**
 * Vector version of the basic long type.
 * Provides four long fields packed.
 */
public class Long4 {
    public long x;
    public long y;
    public long z;
    public long w;

    public Long4() {
    }

    public Long4(long i) {
        this.x = this.y = this.z = this.w = i;
    }

    public Long4(long x, long y, long z, long w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Long4(Long4 source) {
        this.x = source.x;
        this.y = source.y;
        this.z = source.z;
        this.w = source.w;
    }

    /**
     * Vector add
     *
     * @param a
     */
    public void add(Long4 a) {
        this.x += a.x;
        this.y += a.y;
        this.z += a.z;
        this.w += a.w;
    }

    /**
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 add(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x + b.x;
        result.y = a.y + b.y;
        result.z = a.z + b.z;
        result.w = a.w + b.w;

        return result;
    }

    /**
     * Vector add
     *
     * @param value
     */
    public void add(Long value) {
        x += value.x;
        y += value.x;
        z += value.x;
        w += value.x;
    }

    /**
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 add(Long4 a, Long b) {
        Long4 result = new Long4();
        result.x = a.x + b.x;
        result.y = a.y + b.x;
        result.z = a.z + b.x;
        result.w = a.w + b.x;

        return result;
    }

    /**
     * Vector subtraction
     *
     * @param a
     */
    public void sub(Long4 a) {
        this.x -= a.x;
        this.y -= a.y;
        this.z -= a.z;
        this.w -= a.w;
    }

    /**
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 sub(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x - b.x;
        result.y = a.y - b.y;
        result.z = a.z - b.z;
        result.w = a.w - b.w;

        return result;
    }

    /**
     * Vector subtraction
     *
     * @param value
     */
    public void sub(Long value) {
        x -= value.x;
        y -= value.x;
        z -= value.x;
        w -= value.x;
    }

    /**
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 sub(Long4 a, Long b) {
        Long4 result = new Long4();
        result.x = a.x - b.x;
        result.y = a.y - b.x;
        result.z = a.z - b.x;
        result.w = a.w - b.x;

        return result;
    }

    /**
     * Vector multiplication
     *
     * @param a
     */
    public void mul(Long4 a) {
        this.x *= a.x;
        this.y *= a.y;
        this.z *= a.z;
        this.w *= a.w;
    }

    /**
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mul(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x * b.x;
        result.y = a.y * b.y;
        result.z = a.z * b.z;
        result.w = a.w * b.w;

        return result;
    }

    /**
     * Vector multiplication
     *
     * @param value
     */
    public void mul(Long value) {
        x *= value.x;
        y *= value.x;
        z *= value.x;
        w *= value.x;
    }

    /**
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mul(Long4 a, Long b) {
        Long4 result = new Long4();
        result.x = a.x * b.x;
        result.y = a.y * b.x;
        result.z = a.z * b.x;
        result.w = a.w * b.x;

        return result;
    }

    /**
     * Vector division
     *
     * @param a
     */
    public void div(Long4 a) {
        this.x /= a.x;
        this.y /= a.y;
        this.z /= a.z;
        this.w /= a.w;
    }

    /**
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 div(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x / b.x;
        result.y = a.y / b.y;
        result.z = a.z / b.z;
        result.w = a.w / b.w;

        return result;
    }

    /**
     * Vector division
     *
     * @param value
     */
    public void div(Long value) {
        x /= value.x;
        y /= value.x;
        z /= value.x;
        w /= value.x;
    }

    /**
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 div(Long4 a, Long b) {
        Long4 result = new Long4();
        result.x = a.x / b.x;
        result.y = a.y / b.x;
        result.z = a.z / b.x;
        result.w = a.w / b.x;

        return result;
    }

    /**
     * Vector Modulo
     *
     * @param a
     */
    public void mod(Long4 a) {
        this.x %= a.x;
        this.y %= a.y;
        this.z %= a.z;
        this.w %= a.w;
    }

    /**
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mod(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x % b.x;
        result.y = a.y % b.y;
        result.z = a.z % b.z;
        result.w = a.w % b.w;

        return result;
    }

    /**
     * Vector Modulo
     *
     * @param value
     */
    public void mod(Long value) {
        x %= value.x;
        y %= value.x;
        z %= value.x;
        w %= value.x;
    }

    /**
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mod(Long4 a, Long b) {
        Long4 result = new Long4();
        result.x = a.x % b.x;
        result.y = a.y % b.x;
        result.z = a.z % b.x;
        result.w = a.w % b.x;

        return result;
    }

    /**
     * get vector length
     *
     * @return
     */
    public long length() {
        return 4;
    }

    /**
     * set vector negate
     */
    public void negate() {
        this.x = -x;
        this.y = -y;
        this.z = -z;
        this.w = -w;
    }

    /**
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public Long dotProduct(Long4 a) {
        return new Long((x * a.x) + (y * a.y) + (z * a.z) + (w * a.w));
    }

    /**
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static Long dotProduct(Long4 a, Long4 b) {
        return new Long((b.x * a.x) + (b.y * a.y) + (b.z * a.z) + (b.w * a.w));
    }

    /**
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Long4 a, Long factor) {
        x += a.x * factor.x;
        y += a.y * factor.x;
        z += a.z * factor.x;
        w += a.w * factor.x;
    }

    /**
     * set vector value by Long4
     *
     * @param a
     */
    public void set(Long4 a) {
        this.x = a.x;
        this.y = a.y;
        this.z = a.z;
        this.w = a.w;
    }

    /**
     * set the vector field value by Long
     *
     * @param a
     * @param b
     * @param c
     * @param d
     */
    public void setValues(Long a, Long b, Long c, Long d) {
        this.x = a.x;
        this.y = b.x;
        this.z = c.x;
        this.w = d.x;
    }

    /**
     * return the element sum of vector
     *
     * @return
     */
    public Long elementSum() {
        return new Long(x + y + z + w);
    }

    /**
     * get the vector field value by index
     *
     * @param i
     * @return
     */
    public Long get(int i) {
        switch (i) {
        case 0:
            return new Long(x);
        case 1:
            return new Long(y);
        case 2:
            return new Long(z);
        case 3:
            return new Long(w);
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /**
     * set the vector field value by index
     *
     * @param i
     * @param value
     */
    public void set(int i, Long value) {
        switch (i) {
        case 0:
            x = value.x;
            return;
        case 1:
            y = value.x;
            return;
        case 2:
            z = value.x;
            return;
        case 3:
            w = value.x;
            return;
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /**
     * add the vector field value by index
     *
     * @param i
     * @param value
     */
    public void addAt(int i, Long value) {
        switch (i) {
        case 0:
            x += value.x;
            return;
        case 1:
            y += value.x;
            return;
        case 2:
            z += value.x;
            return;
        case 3:
            w += value.x;
            return;
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /**
     * copy the vector to Long array
     *
     * @param data
     * @param offset
     */
    public void copyTo(Long[] data, int offset) {
        data[offset] = new Long(x);
        data[offset + 1] = new Long(y);
        data[offset + 2] = new Long(z);
        data[offset + 3] = new Long(w);
    }
}
