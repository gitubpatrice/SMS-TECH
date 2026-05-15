/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.google.android.mms.pdu;

import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Wireless-encoded text (data + IANA MIB-enum charset). Used by MMS PDU headers that carry
 * non-ASCII text. Mirrors AOSP EncodedStringValue.
 */
public class EncodedStringValue implements Cloneable {
    private static final String TAG = "EncodedStringValue";

    /** IANA MIB-enum identifying the character set of [mData]. */
    private int mCharacterSet;

    /** Raw bytes (in the [mCharacterSet] encoding). Defensive copy on get/set. */
    private byte[] mData;

    public EncodedStringValue(int charset, byte[] data) {
        if (data == null) throw new NullPointerException("data");
        mCharacterSet = charset;
        mData = new byte[data.length];
        System.arraycopy(data, 0, mData, 0, data.length);
    }

    public EncodedStringValue(byte[] data) {
        this(CharacterSets.DEFAULT_CHARSET_MIB_ENUM, data);
    }

    public EncodedStringValue(String data) {
        try {
            mData = data.getBytes(CharacterSets.MIMENAME_ISO_8859_1);
            mCharacterSet = CharacterSets.DEFAULT_CHARSET_MIB_ENUM;
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "default charset missing", e);
        }
    }

    public int getCharacterSet() { return mCharacterSet; }
    public void setCharacterSet(int charset) { mCharacterSet = charset; }

    public byte[] getTextString() {
        byte[] copy = new byte[mData.length];
        System.arraycopy(mData, 0, copy, 0, mData.length);
        return copy;
    }

    public void setTextString(byte[] data) {
        if (data == null) throw new NullPointerException("data");
        mData = new byte[data.length];
        System.arraycopy(data, 0, mData, 0, data.length);
    }

    /** Decodes the wireless-encoded bytes into a Java string using the carried charset. */
    public String getString() {
        if (CharacterSets.ANY_CHARSET == mCharacterSet) {
            return new String(mData);
        }
        try {
            String name = CharacterSets.getMimeName(mCharacterSet);
            return new String(mData, name);
        } catch (UnsupportedEncodingException e) {
            try {
                return new String(mData, CharacterSets.MIMENAME_ISO_8859_1);
            } catch (UnsupportedEncodingException e2) {
                return new String(mData); // last resort
            }
        }
    }

    /** Appends [value]'s bytes to ours, separated by a space (RFC 2047 spec for header folding). */
    public void appendTextString(byte[] textString) {
        if (textString == null) throw new NullPointerException("textString");
        if (mData == null) {
            mData = new byte[textString.length];
            System.arraycopy(textString, 0, mData, 0, textString.length);
            return;
        }
        byte[] newData = new byte[mData.length + textString.length];
        System.arraycopy(mData, 0, newData, 0, mData.length);
        System.arraycopy(textString, 0, newData, mData.length, textString.length);
        mData = newData;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        super.clone();
        return new EncodedStringValue(mCharacterSet, mData);
    }

    /**
     * Splits a comma- or semicolon-separated multi-address string into individual values, each
     * carrying the same character set. Used by To/Cc/Bcc encoders.
     */
    public EncodedStringValue[] split(String pattern) {
        String[] tokens = getString().split(pattern);
        EncodedStringValue[] out = new EncodedStringValue[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                out[i] = new EncodedStringValue(mCharacterSet,
                        tokens[i].getBytes(CharacterSets.getMimeName(mCharacterSet)));
            } catch (UnsupportedEncodingException e) {
                out[i] = new EncodedStringValue(tokens[i]);
            }
        }
        return out;
    }

    /** Convenience: stuffs an array of strings into an array of EncodedStringValue objects. */
    public static EncodedStringValue[] encodeStrings(String[] array) {
        if (array == null) return null;
        ArrayList<EncodedStringValue> out = new ArrayList<>(array.length);
        for (String s : array) {
            if (s != null && !s.isEmpty()) out.add(new EncodedStringValue(s));
        }
        return out.toArray(new EncodedStringValue[0]);
    }

    /** Concatenates string forms of [values] with [separator]. Convenience for serialisation. */
    public static String concat(EncodedStringValue[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(values[i].getString());
        }
        return sb.toString();
    }

    /** Builds a single PDU "wireless-encoded text" from a plain Java string in UTF-8. */
    public static EncodedStringValue copy(EncodedStringValue source) {
        if (source == null) return null;
        return new EncodedStringValue(source.mCharacterSet, source.mData);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EncodedStringValue)) return false;
        EncodedStringValue other = (EncodedStringValue) o;
        return mCharacterSet == other.mCharacterSet && Arrays.equals(mData, other.mData);
    }

    @Override
    public int hashCode() {
        return 31 * mCharacterSet + Arrays.hashCode(mData);
    }
}
