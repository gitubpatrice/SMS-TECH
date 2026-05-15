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

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;

/**
 * Serialises a [GenericPdu] (typically a [SendReq] or [NotifyRespInd]) into the binary OMA-MMS
 * encoding suitable for SmsManager.sendMultimediaMessage.
 *
 * The encoding follows WAP-230-WSP + OMA-TS-MMS-ENC-V1_3. The class mirrors AOSP PduComposer
 * with the rarely-used SMIL / DRM paths stripped.
 */
public class PduComposer {
    private static final String TAG = "PduComposer";

    /* ─── Encoding constants ───────────────────────────────────────────────── */
    static final int START_VALUE_LENGTH      = 0x1F;
    static final int LENGTH_QUOTE            = 0x1F;
    static final int TEXT_MAX                = 0x7F;
    static final int SHORT_INTEGER_MAX       = 0x7F;
    static final int LONG_INTEGER_LENGTH_MAX = 8;
    static final int QUOTED_STRING_FLAG      = 0x22;
    static final int END_STRING_FLAG         = 0x00;

    private static final int PDU_COMPOSER_BLOCK_SIZE = 1024;

    private static final int PDU_COMPOSE_SUCCESS              = 0;
    private static final int PDU_COMPOSE_CONTENT_ERROR        = 1;
    private static final int PDU_COMPOSE_FIELD_NOT_SET        = 2;
    private static final int PDU_COMPOSE_FIELD_NOT_SUPPORTED  = 3;

    /** Subset of PduPart content types we encode inline as a single byte (WSP well-known). */
    private static final HashMap<String, Integer> mContentTypeMap = new HashMap<>();
    static {
        String[] table = PduContentTypes.contentTypes;
        for (int i = 0; i < table.length; i++) mContentTypeMap.put(table[i], i);
    }

    private ByteArrayOutputStream mMessage;
    /** LIFO of stream positions used by the multipart length writer. */
    private final PositionMarker mStack = new PositionMarker();
    private final GenericPdu mPdu;
    private final PduHeaders mPduHeader;
    private final Context mContext;

    public PduComposer(Context context, GenericPdu pdu) {
        mContext = context;
        mPdu = pdu;
        mPduHeader = pdu.getPduHeaders();
        mMessage = new ByteArrayOutputStream();
    }

    /** Builds the encoded PDU and returns the bytes (null if mandatory headers are missing). */
    public byte[] make() {
        mMessage = new ByteArrayOutputStream(PDU_COMPOSER_BLOCK_SIZE);
        int messageType = mPdu.getMessageType();
        int status;
        switch (messageType) {
            case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                status = makeSendReqPdu();
                break;
            case PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND:
                status = makeNotifyResp();
                break;
            case PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND:
                status = makeAckInd();
                break;
            case PduHeaders.MESSAGE_TYPE_READ_REC_IND:
                status = makeReadRecInd();
                break;
            default:
                return null;
        }
        if (status != PDU_COMPOSE_SUCCESS) return null;
        return mMessage.toByteArray();
    }

    /* ─── Primitive WSP encoders ───────────────────────────────────────────── */

    /** Writes one byte. */
    void append(int data) { mMessage.write(data); }

    /** Writes a raw byte buffer. */
    void arraycopy(byte[] buf, int pos, int length) {
        mMessage.write(buf, pos, length);
    }

    /** Short-integer = a single byte with MSB set (0x80 | v) where v in 0..127. */
    private void appendShortInteger(int value) {
        mMessage.write((value | 0x80) & 0xFF);
    }

    /** Octet = a single raw byte (0..255). */
    private void appendOctet(int value) {
        mMessage.write(value & 0xFF);
    }

    /** Long-integer: length octet (1..8) followed by big-endian bytes (no leading zeros). */
    private void appendLongInteger(long value) {
        long temp = value;
        int size = 0;
        do { temp >>>= 8; size++; } while (temp > 0);
        mMessage.write(size & 0xFF);
        for (int i = size - 1; i >= 0; i--) {
            mMessage.write((int) ((value >>> (i * 8)) & 0xFF));
        }
    }

    /**
     * Uintvar (multi-byte 7-bit chain). Used for value-lengths > 30. The last byte has its MSB
     * clear; preceding bytes have MSB set.
     */
    private void appendUintvarInteger(long value) {
        long max = 0x7FL;
        for (int i = 0; i < 5; i++) {
            if (value < (max << (i * 7))) {
                long v = value;
                int written = 0;
                int[] tmp = new int[5];
                int n = 0;
                do {
                    tmp[n++] = (int) (v & 0x7F);
                    v >>>= 7;
                } while (v > 0);
                for (int j = n - 1; j >= 0; j--) {
                    int b = tmp[j];
                    if (j != 0) b |= 0x80;
                    mMessage.write(b);
                }
                return;
            }
        }
        // value too large
        mMessage.write(0);
    }

    /** Text-string: null-terminated. If the first byte is >= 0x80 we quote with 0x7F. */
    private void appendTextString(byte[] value) {
        if (value == null || value.length == 0) {
            mMessage.write(0);
            return;
        }
        if ((value[0] & 0xFF) > TEXT_MAX) {
            mMessage.write(0x7F); // quote
        }
        mMessage.write(value, 0, value.length);
        mMessage.write(0);
    }

    private void appendTextString(String value) {
        appendTextString(value.getBytes());
    }

    /**
     * Encoded-string-value: either a plain Text-string (charset omitted) or a Value-length
     * + char-set + Text-string sequence when [value]'s charset isn't ANY_CHARSET.
     */
    private void appendEncodedString(EncodedStringValue value) {
        int cs = value.getCharacterSet();
        byte[] data = value.getTextString();
        if (CharacterSets.ANY_CHARSET == cs) {
            appendTextString(data);
            return;
        }
        // Value-length [charset] text-string
        ByteArrayOutputStream sub = new ByteArrayOutputStream();
        // charset as Short-integer (allowed up to 127 → MIB-enum)
        if (cs > 0 && cs <= SHORT_INTEGER_MAX) {
            sub.write((cs | 0x80) & 0xFF);
        } else {
            // Integer-value: length + bytes
            int n = 0; long t = cs; do { t >>>= 8; n++; } while (t > 0);
            sub.write(n);
            for (int i = n - 1; i >= 0; i--) sub.write((int) ((cs >>> (i * 8)) & 0xFF));
        }
        if ((data.length > 0) && ((data[0] & 0xFF) > TEXT_MAX)) {
            sub.write(0x7F);
        }
        sub.write(data, 0, data.length);
        sub.write(0);
        byte[] subBytes = sub.toByteArray();
        appendValueLength(subBytes.length);
        mMessage.write(subBytes, 0, subBytes.length);
    }

    private void appendQuotedString(byte[] value) {
        // 0x22 + text-string
        mMessage.write(QUOTED_STRING_FLAG);
        mMessage.write(value, 0, value.length);
        mMessage.write(0);
    }

    /** Value-length: short (0..30) or token 0x1F + uintvar. */
    private void appendValueLength(long length) {
        if (length < 31) mMessage.write((int) length);
        else { mMessage.write(LENGTH_QUOTE); appendUintvarInteger(length); }
    }

    /** Header: short-integer field + encoded-string-value. */
    private void appendHeader(int field) {
        appendShortInteger(field);
    }

    /* ─── SendReq composer ─────────────────────────────────────────────────── */

    private int makeSendReqPdu() {
        // Mandatory: Bcc/Cc/To at least one; Content-Type; Date; From; Message-Type;
        // MMS-Version; Transaction-Id.
        try {
            // X-Mms-Message-Type
            appendHeader(PduHeaders.MESSAGE_TYPE);
            appendOctet(PduHeaders.MESSAGE_TYPE_SEND_REQ);

            // X-Mms-Transaction-Id
            byte[] tid = mPduHeader.getTextString(PduHeaders.TRANSACTION_ID);
            if (tid == null) return PDU_COMPOSE_FIELD_NOT_SET;
            appendHeader(PduHeaders.TRANSACTION_ID);
            appendTextString(tid);

            // X-Mms-MMS-Version
            appendHeader(PduHeaders.MMS_VERSION);
            int ver = mPduHeader.getOctet(PduHeaders.MMS_VERSION);
            if (ver == 0) ver = PduHeaders.CURRENT_MMS_VERSION;
            appendShortInteger(ver);

            // Date — composer-side optional, MMSC stamps it anyway.
            long date = mPduHeader.getLongInteger(PduHeaders.DATE);
            if (date < 0) date = System.currentTimeMillis() / 1000L;
            appendHeader(PduHeaders.DATE);
            appendLongInteger(date);

            // From: insert-address-token (let the MMSC fill in)
            appendHeader(PduHeaders.FROM);
            EncodedStringValue from = mPduHeader.getEncodedStringValue(PduHeaders.FROM);
            ByteArrayOutputStream fromValue = new ByteArrayOutputStream();
            if (from == null || PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.equals(from.getString())) {
                fromValue.write(PduHeaders.FROM_INSERT_ADDRESS_TOKEN);
            } else {
                fromValue.write(PduHeaders.FROM_ADDRESS_PRESENT_TOKEN);
                // encoded-string-value inside the value-length wrapper
                appendValueLength(fromValue.size() + encodedStringValueSize(from));
                appendOctet(PduHeaders.FROM_ADDRESS_PRESENT_TOKEN);
                appendEncodedString(from);
                // continue with the rest of the headers (skip the fromValue branch below)
                // ─ Recipients
                appendRecipientsAndRest(date);
                return PDU_COMPOSE_SUCCESS;
            }
            byte[] fb = fromValue.toByteArray();
            appendValueLength(fb.length);
            mMessage.write(fb, 0, fb.length);

            appendRecipientsAndRest(date);
            return PDU_COMPOSE_SUCCESS;
        } catch (Exception e) {
            Log.w(TAG, "makeSendReqPdu failed", e);
            return PDU_COMPOSE_CONTENT_ERROR;
        }
    }

    private void appendRecipientsAndRest(long date) {
        // To / Cc / Bcc
        EncodedStringValue[] to = mPduHeader.getEncodedStringValues(PduHeaders.TO);
        if (to != null) for (EncodedStringValue v : to) {
            appendHeader(PduHeaders.TO);
            appendEncodedString(v);
        }
        EncodedStringValue[] cc = mPduHeader.getEncodedStringValues(PduHeaders.CC);
        if (cc != null) for (EncodedStringValue v : cc) {
            appendHeader(PduHeaders.CC);
            appendEncodedString(v);
        }
        EncodedStringValue[] bcc = mPduHeader.getEncodedStringValues(PduHeaders.BCC);
        if (bcc != null) for (EncodedStringValue v : bcc) {
            appendHeader(PduHeaders.BCC);
            appendEncodedString(v);
        }

        // X-Mms-Read-Report (optional)
        int rr = mPduHeader.getOctet(PduHeaders.READ_REPORT);
        if (rr != 0) { appendHeader(PduHeaders.READ_REPORT); appendOctet(rr); }
        // X-Mms-Delivery-Report (optional)
        int dr = mPduHeader.getOctet(PduHeaders.DELIVERY_REPORT);
        if (dr != 0) { appendHeader(PduHeaders.DELIVERY_REPORT); appendOctet(dr); }

        // Subject (optional)
        EncodedStringValue subject = mPduHeader.getEncodedStringValue(PduHeaders.SUBJECT);
        if (subject != null) { appendHeader(PduHeaders.SUBJECT); appendEncodedString(subject); }

        // Content-Type — last header, then the body bytes follow.
        appendHeader(PduHeaders.CONTENT_TYPE);
        byte[] contentType = mPduHeader.getTextString(PduHeaders.CONTENT_TYPE);
        if (contentType == null) contentType = "application/vnd.wap.multipart.related".getBytes();
        Integer ctIdx = mContentTypeMap.get(new String(contentType));
        if (ctIdx != null) {
            appendShortInteger(ctIdx);
        } else {
            appendTextString(contentType);
        }

        // Multipart body
        PduBody body = ((MultimediaMessagePdu) mPdu).getBody();
        if (body != null && body.getPartsNum() > 0) {
            appendMultipart(body);
        }
    }

    /** Approximates the encoded size of an EncodedStringValue (charset short-int + bytes + nul). */
    private static int encodedStringValueSize(EncodedStringValue v) {
        return 2 + v.getTextString().length + 1;
    }

    /** Encodes the [body] as application/vnd.wap.multipart.related (RFC 2387 binary form). */
    private void appendMultipart(PduBody body) {
        int parts = body.getPartsNum();
        appendUintvarInteger(parts);
        for (int i = 0; i < parts; i++) {
            PduPart part = body.getPart(i);
            encodePart(part);
        }
    }

    private void encodePart(PduPart part) {
        // headersLen | dataLen | headers | data
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        ByteArrayOutputStream dataOut = new ByteArrayOutputStream();

        // ── Content-Type (well-known short-int or text) + parameters
        byte[] ct = part.getContentType();
        if (ct == null) ct = "application/octet-stream".getBytes();
        Integer ctIdx = mContentTypeMap.get(new String(ct));

        ByteArrayOutputStream ctBuf = new ByteArrayOutputStream();
        if (ctIdx != null) {
            ctBuf.write((ctIdx | 0x80) & 0xFF);
        } else {
            for (byte b : ct) ctBuf.write(b);
            ctBuf.write(0);
        }
        if (part.getName() != null) {
            ctBuf.write(PduPart.P_NAME | 0x80);
            for (byte b : part.getName()) ctBuf.write(b);
            ctBuf.write(0);
        }

        byte[] ctBytes = ctBuf.toByteArray();
        // value-length followed by content-type-value (always content-general-form for safety)
        writeValueLengthTo(headers, ctBytes.length);
        try { headers.write(ctBytes); } catch (Exception e) { /* unreachable */ }

        // ── Content-Location
        if (part.getContentLocation() != null) {
            headers.write(PduPart.P_CONTENT_LOCATION & 0xFF);
            for (byte b : part.getContentLocation()) headers.write(b);
            headers.write(0);
        }
        // ── Content-Id  (encoded as quoted-string)
        if (part.getContentId() != null) {
            headers.write(PduPart.P_CONTENT_ID & 0xFF);
            headers.write(QUOTED_STRING_FLAG);
            headers.write('<');
            for (byte b : part.getContentId()) headers.write(b);
            headers.write('>');
            headers.write(0);
        }
        // ── Content-Disposition (optional)
        if (part.getContentDisposition() != null) {
            headers.write(PduPart.P_CONTENT_DISPOSITION & 0xFF);
            ByteArrayOutputStream dispVal = new ByteArrayOutputStream();
            byte[] disp = part.getContentDisposition();
            if (java.util.Arrays.equals(PduPart.DISPOSITION_INLINE, disp)) {
                dispVal.write(0xC1); // inline well-known
            } else if (java.util.Arrays.equals(PduPart.DISPOSITION_ATTACHMENT, disp)) {
                dispVal.write(0xC0); // attachment well-known
            } else {
                for (byte b : disp) dispVal.write(b);
                dispVal.write(0);
            }
            byte[] dispBytes = dispVal.toByteArray();
            writeValueLengthTo(headers, dispBytes.length);
            try { headers.write(dispBytes); } catch (Exception e) { /* unreachable */ }
        }

        // ── Data: from byte[] or from Uri (streamed)
        byte[] data = part.getData();
        if (data == null) {
            Uri uri = part.getDataUri();
            if (uri != null) {
                data = readBytes(uri);
            }
        }
        if (data == null) data = new byte[0];
        try { dataOut.write(data); } catch (Exception e) { /* unreachable */ }

        byte[] headerBytes = headers.toByteArray();
        byte[] dataBytes = dataOut.toByteArray();

        appendUintvarInteger(headerBytes.length);
        appendUintvarInteger(dataBytes.length);
        mMessage.write(headerBytes, 0, headerBytes.length);
        mMessage.write(dataBytes, 0, dataBytes.length);
    }

    private byte[] readBytes(Uri uri) {
        try {
            ContentResolver cr = mContext.getContentResolver();
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            Log.w(TAG, "readBytes failed for " + uri, e);
            return null;
        }
    }

    private void writeValueLengthTo(ByteArrayOutputStream target, int length) {
        if (length < 31) target.write(length);
        else {
            target.write(LENGTH_QUOTE);
            // uintvar
            int max = 0x7F;
            int[] tmp = new int[5];
            int n = 0;
            int v = length;
            do { tmp[n++] = v & 0x7F; v >>>= 7; } while (v > 0);
            for (int j = n - 1; j >= 0; j--) {
                int b = tmp[j];
                if (j != 0) b |= 0x80;
                target.write(b);
            }
        }
    }

    /* ─── NotifyResp / Ack / ReadRec composers (short responses) ───────────── */

    private int makeNotifyResp() {
        byte[] tid = mPduHeader.getTextString(PduHeaders.TRANSACTION_ID);
        if (tid == null) return PDU_COMPOSE_FIELD_NOT_SET;
        appendHeader(PduHeaders.MESSAGE_TYPE);
        appendOctet(PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND);
        appendHeader(PduHeaders.TRANSACTION_ID);
        appendTextString(tid);
        appendHeader(PduHeaders.MMS_VERSION);
        appendShortInteger(PduHeaders.CURRENT_MMS_VERSION);
        appendHeader(PduHeaders.STATUS);
        appendOctet(mPduHeader.getOctet(PduHeaders.STATUS));
        return PDU_COMPOSE_SUCCESS;
    }

    private int makeAckInd() {
        byte[] tid = mPduHeader.getTextString(PduHeaders.TRANSACTION_ID);
        if (tid == null) return PDU_COMPOSE_FIELD_NOT_SET;
        appendHeader(PduHeaders.MESSAGE_TYPE);
        appendOctet(PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND);
        appendHeader(PduHeaders.TRANSACTION_ID);
        appendTextString(tid);
        appendHeader(PduHeaders.MMS_VERSION);
        appendShortInteger(PduHeaders.CURRENT_MMS_VERSION);
        return PDU_COMPOSE_SUCCESS;
    }

    private int makeReadRecInd() {
        // Out of scope for SMS Tech v1 — not implemented.
        return PDU_COMPOSE_FIELD_NOT_SUPPORTED;
    }

    /** No-op position marker, retained for forward compatibility with AOSP code style. */
    private static class PositionMarker {}
}
