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

import java.io.UnsupportedEncodingException;
import java.util.HashMap;

/**
 * IANA MIB-enum mapping for the character sets used in MMS PDU headers. Numbers come from the
 * IANA registry (https://www.iana.org/assignments/character-sets/) and are written into the
 * binary PDU encoding as a wireless short-integer.
 */
public class CharacterSets {
    /** IANA "Any-Charset" — used by the encoder when no specific charset is meaningful. */
    public static final int ANY_CHARSET   = 0x00;
    public static final int US_ASCII      = 0x03;
    public static final int ISO_8859_1    = 0x04;
    public static final int ISO_8859_2    = 0x05;
    public static final int ISO_8859_3    = 0x06;
    public static final int ISO_8859_4    = 0x07;
    public static final int ISO_8859_5    = 0x08;
    public static final int ISO_8859_6    = 0x09;
    public static final int ISO_8859_7    = 0x0A;
    public static final int ISO_8859_8    = 0x0B;
    public static final int ISO_8859_9    = 0x0C;
    public static final int SHIFT_JIS     = 0x11;
    public static final int UTF_8         = 0x6A;
    public static final int BIG5          = 0x07EA;
    public static final int UCS2          = 0x03E8;
    public static final int UTF_16        = 0x03F7;

    /** Java charset name used to decode UTF-8 strings — kept short for callers. */
    public static final String MIMENAME_ANY_CHARSET = "*";
    public static final String MIMENAME_US_ASCII    = "us-ascii";
    public static final String MIMENAME_ISO_8859_1  = "iso-8859-1";
    public static final String MIMENAME_ISO_8859_2  = "iso-8859-2";
    public static final String MIMENAME_ISO_8859_3  = "iso-8859-3";
    public static final String MIMENAME_ISO_8859_4  = "iso-8859-4";
    public static final String MIMENAME_ISO_8859_5  = "iso-8859-5";
    public static final String MIMENAME_ISO_8859_6  = "iso-8859-6";
    public static final String MIMENAME_ISO_8859_7  = "iso-8859-7";
    public static final String MIMENAME_ISO_8859_8  = "iso-8859-8";
    public static final String MIMENAME_ISO_8859_9  = "iso-8859-9";
    public static final String MIMENAME_SHIFT_JIS   = "shift_JIS";
    public static final String MIMENAME_UTF_8       = "utf-8";
    public static final String MIMENAME_BIG5        = "big5";
    public static final String MIMENAME_UCS2        = "iso-10646-ucs-2";
    public static final String MIMENAME_UTF_16      = "utf-16";

    public static final String DEFAULT_CHARSET = MIMENAME_ISO_8859_1;
    public static final int DEFAULT_CHARSET_MIB_ENUM = ISO_8859_1;

    public static final int[] MIBENUM_NUMBERS = {
            ANY_CHARSET, US_ASCII, ISO_8859_1, ISO_8859_2, ISO_8859_3, ISO_8859_4,
            ISO_8859_5, ISO_8859_6, ISO_8859_7, ISO_8859_8, ISO_8859_9, SHIFT_JIS,
            UTF_8, BIG5, UCS2, UTF_16,
    };

    public static final String[] MIME_NAMES = {
            MIMENAME_ANY_CHARSET, MIMENAME_US_ASCII, MIMENAME_ISO_8859_1, MIMENAME_ISO_8859_2,
            MIMENAME_ISO_8859_3, MIMENAME_ISO_8859_4, MIMENAME_ISO_8859_5, MIMENAME_ISO_8859_6,
            MIMENAME_ISO_8859_7, MIMENAME_ISO_8859_8, MIMENAME_ISO_8859_9, MIMENAME_SHIFT_JIS,
            MIMENAME_UTF_8, MIMENAME_BIG5, MIMENAME_UCS2, MIMENAME_UTF_16,
    };

    private static final HashMap<Integer, String> MIBENUM_TO_NAME_MAP = new HashMap<>();
    private static final HashMap<String, Integer> NAME_TO_MIBENUM_MAP = new HashMap<>();

    static {
        for (int i = 0; i < MIBENUM_NUMBERS.length; i++) {
            MIBENUM_TO_NAME_MAP.put(MIBENUM_NUMBERS[i], MIME_NAMES[i]);
            NAME_TO_MIBENUM_MAP.put(MIME_NAMES[i], MIBENUM_NUMBERS[i]);
        }
    }

    private CharacterSets() {}

    /** Returns the Java MIME name (e.g. "utf-8") for an IANA MIB-enum, or throws on unknown. */
    public static String getMimeName(int mibEnum) throws UnsupportedEncodingException {
        String name = MIBENUM_TO_NAME_MAP.get(mibEnum);
        if (name == null) throw new UnsupportedEncodingException();
        return name;
    }

    /** Inverse of [getMimeName]. */
    public static int getMibEnumValue(String mimeName) throws UnsupportedEncodingException {
        if (mimeName == null) throw new UnsupportedEncodingException();
        Integer mibEnum = NAME_TO_MIBENUM_MAP.get(mimeName.toLowerCase());
        if (mibEnum == null) throw new UnsupportedEncodingException();
        return mibEnum;
    }
}
