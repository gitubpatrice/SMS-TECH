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

import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

/**
 * One part of an MMS multipart message: an opaque payload plus typed metadata (content-type,
 * filename, content-id, charset…). Mirrors AOSP PduPart.
 */
public class PduPart {

    /* ─── Constants for content-type parameters (OMA-WSP §8.4.2.24) ────────── */
    public static final int P_TYPE                  = 0x09;
    public static final int P_CHARSET               = 0x01;
    public static final int P_CT_MR_TYPE            = 0x03;
    public static final int P_DEP_NAME              = 0x05;
    public static final int P_DEP_FILENAME          = 0x06;
    public static final int P_DEP_START             = 0x0A;
    public static final int P_DEP_START_INFO        = 0x0B;
    public static final int P_DEP_DOMAIN            = 0x0C;
    public static final int P_DEP_PATH              = 0x0D;
    public static final int P_NAME                  = 0x17;
    public static final int P_FILENAME              = 0x18;
    public static final int P_START                 = 0x19;
    public static final int P_START_INFO            = 0x1A;
    public static final int P_DOMAIN                = 0x1B;
    public static final int P_PATH                  = 0x1C;

    /* ─── Generic part headers ─────────────────────────────────────────────── */
    public static final int P_CONTENT_ID            = 0xC0;
    public static final int P_CONTENT_LOCATION      = 0x8E;
    public static final int P_CONTENT_DISPOSITION   = 0xAE;
    public static final int P_CONTENT_DISPOSITION_OLD = 0xC5;

    /* ─── Disposition tokens (OMA-MMS-ENC §7) ──────────────────────────────── */
    public static final byte[] DISPOSITION_FROM_DATA      = "from-data".getBytes();
    public static final byte[] DISPOSITION_ATTACHMENT     = "attachment".getBytes();
    public static final byte[] DISPOSITION_INLINE         = "inline".getBytes();

    private final Map<Integer, Object> mPartHeader = new HashMap<>();
    private byte[] mPartData;
    private Uri mUri;
    private byte[] mContentType;

    public PduPart() {
        // Default content type kept null on purpose — callers must set it explicitly.
    }

    public byte[] getData() {
        if (mPartData == null) return null;
        byte[] copy = new byte[mPartData.length];
        System.arraycopy(mPartData, 0, copy, 0, mPartData.length);
        return copy;
    }

    public void setData(byte[] data) {
        if (data == null) { mPartData = null; return; }
        mPartData = new byte[data.length];
        System.arraycopy(data, 0, mPartData, 0, data.length);
    }

    public int getDataLength() { return mPartData == null ? 0 : mPartData.length; }

    /** When a Uri is set the encoder will stream the bytes from the ContentResolver instead. */
    public Uri getDataUri() { return mUri; }
    public void setDataUri(Uri uri) { mUri = uri; }

    public byte[] getContentType() { return mContentType; }
    public void setContentType(byte[] value) { mContentType = value; }

    public byte[] getName() { return (byte[]) mPartHeader.get(P_NAME); }
    public void setName(byte[] name) { mPartHeader.put(P_NAME, name); }

    public byte[] getFilename() { return (byte[]) mPartHeader.get(P_FILENAME); }
    public void setFilename(byte[] filename) { mPartHeader.put(P_FILENAME, filename); }

    public byte[] getContentLocation() { return (byte[]) mPartHeader.get(P_CONTENT_LOCATION); }
    public void setContentLocation(byte[] contentLocation) { mPartHeader.put(P_CONTENT_LOCATION, contentLocation); }

    public byte[] getContentId() { return (byte[]) mPartHeader.get(P_CONTENT_ID); }
    public void setContentId(byte[] contentId) {
        if (contentId == null || contentId.length == 0) {
            throw new IllegalArgumentException("contentId must not be empty");
        }
        // Strip surrounding < >, AOSP convention.
        if (contentId.length > 2 && contentId[0] == '<' && contentId[contentId.length - 1] == '>') {
            byte[] stripped = new byte[contentId.length - 2];
            System.arraycopy(contentId, 1, stripped, 0, stripped.length);
            mPartHeader.put(P_CONTENT_ID, stripped);
        } else {
            mPartHeader.put(P_CONTENT_ID, contentId);
        }
    }

    public byte[] getContentDisposition() { return (byte[]) mPartHeader.get(P_CONTENT_DISPOSITION); }
    public void setContentDisposition(byte[] value) { mPartHeader.put(P_CONTENT_DISPOSITION, value); }

    public int getCharset() {
        Integer v = (Integer) mPartHeader.get(P_CHARSET);
        return v == null ? 0 : v;
    }
    public void setCharset(int charset) { mPartHeader.put(P_CHARSET, charset); }
}
