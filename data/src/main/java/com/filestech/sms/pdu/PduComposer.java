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

package com.filestech.sms.pdu;

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
 * The encoding follows WAP-230-WSP + OMA-TS-MMS-ENC-V1_3. This is a shorter rewrite of AOSP's
 * PduComposer, not a copy: an m-send.req must come out byte for byte as AOSP's would, which
 * MmsBuilderFormatTest checks against bytes produced by AOSP's own composer (issue #53).
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
        int size = longIntegerSize(value);
        mMessage.write(size & 0xFF);
        for (int i = size - 1; i >= 0; i--) {
            mMessage.write((int) ((value >>> (i * 8)) & 0xFF));
        }
    }

    /** Number of octets of a Long-integer's value (at least one, no leading zeros). */
    private static int longIntegerSize(long value) {
        long temp = value;
        int size = 0;
        do { temp >>>= 8; size++; } while (temp > 0);
        return size;
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
        writeCharset(sub, cs);
        if ((data.length > 0) && ((data[0] & 0xFF) > TEXT_MAX)) {
            sub.write(0x7F);
        }
        sub.write(data, 0, data.length);
        sub.write(0);
        byte[] subBytes = sub.toByteArray();
        appendValueLength(subBytes.length);
        mMessage.write(subBytes, 0, subBytes.length);
    }

    /** Charset as an Integer-value: Short-integer (MIB-enum up to 127), else length + bytes. */
    private static void writeCharset(ByteArrayOutputStream target, int charset) {
        if (charset > 0 && charset <= SHORT_INTEGER_MAX) {
            target.write((charset | 0x80) & 0xFF);
        } else {
            int n = longIntegerSize(charset);
            target.write(n);
            for (int i = n - 1; i >= 0; i--) target.write((charset >>> (i * 8)) & 0xFF);
        }
    }

    /** Text-string written to [target]: null-terminated, quoted with 0x7F if the first byte is >= 0x80. */
    private static void writeTextString(ByteArrayOutputStream target, byte[] value) {
        if (value.length > 0 && (value[0] & 0xFF) > TEXT_MAX) target.write(0x7F);
        target.write(value, 0, value.length);
        target.write(0);
    }

    /** A content type as a well-known Short-integer when the WSP table has it, else as a Text-string. */
    private static void writeContentType(ByteArrayOutputStream target, byte[] contentType) {
        Integer known = mContentTypeMap.get(new String(contentType));
        if (known != null) {
            target.write((known | 0x80) & 0xFF);
        } else {
            writeTextString(target, contentType);
        }
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

            // From: insert-address-token (let the MMSC fill in), or the address itself
            appendHeader(PduHeaders.FROM);
            EncodedStringValue from = mPduHeader.getEncodedStringValue(PduHeaders.FROM);
            if (from == null || PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.equals(from.getString())) {
                appendValueLength(1);
                appendOctet(PduHeaders.FROM_INSERT_ADDRESS_TOKEN);
            } else {
                // Written aside first: the value-length needs the exact encoded size.
                ByteArrayOutputStream message = mMessage;
                mMessage = new ByteArrayOutputStream();
                appendOctet(PduHeaders.FROM_ADDRESS_PRESENT_TOKEN);
                appendEncodedString(from);
                byte[] value = mMessage.toByteArray();
                mMessage = message;
                appendValueLength(value.length);
                mMessage.write(value, 0, value.length);
            }

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

        // Subject (optional)
        EncodedStringValue subject = mPduHeader.getEncodedStringValue(PduHeaders.SUBJECT);
        if (subject != null) { appendHeader(PduHeaders.SUBJECT); appendEncodedString(subject); }

        // X-Mms-Message-Class (optional)
        byte[] messageClass = mPduHeader.getTextString(PduHeaders.MESSAGE_CLASS);
        if (messageClass != null) {
            appendHeader(PduHeaders.MESSAGE_CLASS);
            if (java.util.Arrays.equals(messageClass, PduHeaders.MESSAGE_CLASS_PERSONAL_STR.getBytes())) {
                appendOctet(PduHeaders.MESSAGE_CLASS_PERSONAL);
            } else {
                appendTextString(messageClass);
            }
        }
        // X-Mms-Expiry (optional): Value-length, relative token, Long-integer
        long expiry = mPduHeader.getLongInteger(PduHeaders.EXPIRY);
        if (expiry != -1L) {
            appendHeader(PduHeaders.EXPIRY);
            appendValueLength(2 + longIntegerSize(expiry));
            appendOctet(PduHeaders.VALUE_RELATIVE_TOKEN);
            appendLongInteger(expiry);
        }
        // X-Mms-Priority (optional)
        int priority = mPduHeader.getOctet(PduHeaders.PRIORITY);
        if (priority != 0) { appendHeader(PduHeaders.PRIORITY); appendOctet(priority); }
        // X-Mms-Delivery-Report (optional)
        int dr = mPduHeader.getOctet(PduHeaders.DELIVERY_REPORT);
        if (dr != 0) { appendHeader(PduHeaders.DELIVERY_REPORT); appendOctet(dr); }
        // X-Mms-Read-Report (optional)
        int rr = mPduHeader.getOctet(PduHeaders.READ_REPORT);
        if (rr != 0) { appendHeader(PduHeaders.READ_REPORT); appendOctet(rr); }

        // Content-Type — last header, then the body bytes follow.
        PduBody body = ((MultimediaMessagePdu) mPdu).getBody();
        appendHeader(PduHeaders.CONTENT_TYPE);
        byte[] contentType = mPduHeader.getTextString(PduHeaders.CONTENT_TYPE);
        if (contentType == null) contentType = "application/vnd.wap.multipart.related".getBytes();
        if (body == null || body.getPartsNum() == 0) {
            // No root part to name: the bare type, then a part count of zero, as AOSP writes it.
            writeContentType(mMessage, contentType);
            appendUintvarInteger(0);
            return;
        }
        appendMessageContentType(contentType, body.getPart(0));

        // Multipart body
        appendMultipart(body);
    }

    /**
     * The message's Content-type-value, naming its root part. RFC 2387 §3.1 requires `type` on
     * multipart/related, and OMA-TS-MMS-ENC §7.3.11 asks for `start`: without them a carrier's server
     * may answer Error-permanent-message-format-corrupt, 0xE2 (issue #53, a refusal that other
     * carriers' servers do not raise).
     */
    private void appendMessageContentType(byte[] contentType, PduPart root) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        writeContentType(value, contentType);
        byte[] start = root.getContentId();
        if (start != null) {
            value.write(PduPart.P_DEP_START | 0x80);
            writeTextString(value, ("<" + new String(start) + ">").getBytes());
        }
        byte[] rootType = root.getContentType();
        if (rootType != null) {
            value.write(PduPart.P_CT_MR_TYPE | 0x80);
            writeTextString(value, rootType);
        }
        appendValueLength(value.size());
        mMessage.write(value.toByteArray(), 0, value.size());
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

        ByteArrayOutputStream ctBuf = new ByteArrayOutputStream();
        writeContentType(ctBuf, ct);
        // Name: the same value as the file name or Content-Location, written with the WSP 1.1 code
        // (0x85), the one every gateway knows, as AOSP does (the WSP 1.4 code is 0x97).
        byte[] name = part.getName();
        if (name == null) name = part.getFilename();
        if (name == null) name = part.getContentLocation();
        if (name != null) {
            ctBuf.write(PduPart.P_DEP_NAME | 0x80);
            writeTextString(ctBuf, name);
        }
        // Charset: without it a caption's UTF-8 bytes reach the recipient undeclared.
        int charset = part.getCharset();
        if (charset != 0) {
            ctBuf.write(PduPart.P_CHARSET | 0x80);
            writeCharset(ctBuf, charset);
        }

        byte[] ctBytes = ctBuf.toByteArray();
        // value-length followed by content-type-value (always content-general-form for safety)
        writeValueLengthTo(headers, ctBytes.length);
        try { headers.write(ctBytes); } catch (Exception e) { /* unreachable */ }

        // ── Content-Id  (encoded as quoted-string)
        if (part.getContentId() != null) {
            headers.write(PduPart.P_CONTENT_ID & 0xFF);
            headers.write(QUOTED_STRING_FLAG);
            headers.write('<');
            for (byte b : part.getContentId()) headers.write(b);
            headers.write('>');
            headers.write(0);
        }
        // ── Content-Location
        if (part.getContentLocation() != null) {
            headers.write(PduPart.P_CONTENT_LOCATION & 0xFF);
            for (byte b : part.getContentLocation()) headers.write(b);
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
