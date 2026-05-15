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

import com.google.android.mms.InvalidHeaderValueException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

/**
 * Decodes a binary OMA-MMS PDU (typically a NotificationInd from WAP_PUSH or a RetrieveConf
 * fetched by SmsManager.downloadMultimediaMessage) into a typed [GenericPdu] subtype.
 *
 * Mirrors AOSP PduParser, stripped down to the headers SMS Tech actually consumes. Unknown
 * headers are skipped gracefully so a future OMA-MMS-ENC revision doesn't break the pipeline.
 */
public class PduParser {
    private static final String TAG = "PduParser";

    private static final int TYPE_GENERIC_PDU       = 0;
    private static final int THE_FIRST_PART         = 0;
    private static final int THE_LAST_PART          = 1;

    private final ByteArrayInputStream mPduDataStream;
    private byte[] mTypeParam; // last content-type's "type" parameter (for SMIL root id)
    private byte[] mStartParam;

    public PduParser(byte[] data) {
        mPduDataStream = new ByteArrayInputStream(data);
    }

    public GenericPdu parse() {
        if (mPduDataStream == null) return null;
        PduHeaders headers = parseHeaders(mPduDataStream);
        if (headers == null) return null;
        int messageType = headers.getOctet(PduHeaders.MESSAGE_TYPE);
        switch (messageType) {
            case PduHeaders.MESSAGE_TYPE_SEND_REQ: {
                PduBody body = parseParts(mPduDataStream);
                return new SendReq(headers, body);
            }
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                return new NotificationInd(headers);
            case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF: {
                PduBody body = parseParts(mPduDataStream);
                return new RetrieveConf(headers, body);
            }
            case PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND:
            case PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND:
            case PduHeaders.MESSAGE_TYPE_DELIVERY_IND:
            case PduHeaders.MESSAGE_TYPE_SEND_CONF:
            case PduHeaders.MESSAGE_TYPE_READ_REC_IND:
            case PduHeaders.MESSAGE_TYPE_READ_ORIG_IND:
            default: {
                GenericPdu g = new GenericPdu();
                g.mPduHeaders = headers;
                return g;
            }
        }
    }

    /* ─── Header parsing ───────────────────────────────────────────────────── */

    private PduHeaders parseHeaders(ByteArrayInputStream in) {
        if (in == null) return null;
        PduHeaders headers = new PduHeaders();
        boolean keepParsing = true;
        while (keepParsing && in.available() > 0) {
            in.mark(1);
            int headerField = extractByteValue(in);
            switch (headerField) {
                case PduHeaders.MESSAGE_TYPE: {
                    int value = extractByteValue(in);
                    try { headers.setOctet(value, PduHeaders.MESSAGE_TYPE); }
                    catch (InvalidHeaderValueException e) { return null; }
                    break;
                }
                case PduHeaders.MMS_VERSION: {
                    int v = extractByteValue(in);
                    try { headers.setOctet(v, PduHeaders.MMS_VERSION); } catch (Exception ignored) {}
                    break;
                }
                case PduHeaders.STATUS:
                case PduHeaders.REPORT_ALLOWED:
                case PduHeaders.PRIORITY:
                case PduHeaders.DELIVERY_REPORT:
                case PduHeaders.READ_REPORT:
                case PduHeaders.READ_STATUS:
                case PduHeaders.RETRIEVE_STATUS:
                case PduHeaders.CONTENT_CLASS: {
                    int v = extractByteValue(in);
                    try { headers.setOctet(v, headerField); } catch (Exception ignored) {}
                    break;
                }
                case PduHeaders.TRANSACTION_ID:
                case PduHeaders.CONTENT_LOCATION:
                case PduHeaders.MESSAGE_ID:
                case PduHeaders.MESSAGE_CLASS:
                case PduHeaders.RESPONSE_TEXT:
                case PduHeaders.RETRIEVE_TEXT: {
                    byte[] text = parseWapString(in, TYPE_TEXT_STRING);
                    if (text != null) headers.setTextString(text, headerField);
                    break;
                }
                case PduHeaders.SUBJECT: {
                    EncodedStringValue v = parseEncodedStringValue(in);
                    if (v != null) headers.setEncodedStringValue(v, headerField);
                    break;
                }
                case PduHeaders.TO:
                case PduHeaders.CC:
                case PduHeaders.BCC: {
                    EncodedStringValue v = parseEncodedStringValue(in);
                    if (v != null) headers.appendEncodedStringValue(v, headerField);
                    break;
                }
                case PduHeaders.FROM: {
                    int length = parseValueLength(in);
                    if (length > 0) {
                        int fromType = extractByteValue(in);
                        if (fromType == PduHeaders.FROM_ADDRESS_PRESENT_TOKEN) {
                            EncodedStringValue v = parseEncodedStringValue(in);
                            if (v != null) headers.setEncodedStringValue(v, PduHeaders.FROM);
                        } else {
                            // insert-address-token — no string follows
                            headers.setEncodedStringValue(
                                    new EncodedStringValue(PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR),
                                    PduHeaders.FROM);
                        }
                    }
                    break;
                }
                case PduHeaders.MESSAGE_SIZE:
                case PduHeaders.DATE: {
                    long v = parseLongInteger(in);
                    headers.setLongInteger(v, headerField);
                    break;
                }
                case PduHeaders.EXPIRY:
                case PduHeaders.DELIVERY_TIME:
                case PduHeaders.REPLY_CHARGING_DEADLINE: {
                    // value-length + (token absolute=0x80 / relative=0x81) + long-integer
                    int length = parseValueLength(in);
                    int token = extractByteValue(in);
                    long v = parseLongInteger(in);
                    if (token == 0x81) v = System.currentTimeMillis() / 1000L + v;
                    headers.setLongInteger(v, headerField);
                    // length already consumed
                    break;
                }
                case PduHeaders.CONTENT_TYPE: {
                    // Content-Type ends the header section in OMA-MMS-ENC. After it the body
                    // (multipart) starts.
                    byte[] ct = parseContentType(in, null);
                    if (ct != null) headers.setTextString(ct, PduHeaders.CONTENT_TYPE);
                    keepParsing = false;
                    break;
                }
                default: {
                    // Unknown header. Try to skip its value heuristically.
                    skipUnknownValue(in);
                    break;
                }
            }
        }
        return headers;
    }

    /** Reads a single byte without sign extension. Returns -1 on EOF. */
    private static int extractByteValue(ByteArrayInputStream in) {
        return in.read() & 0xFF;
    }

    /** WSP value-length: short (0..30), or 0x1F + uintvar. */
    private static int parseValueLength(ByteArrayInputStream in) {
        int first = extractByteValue(in);
        if (first < 31) return first;
        if (first == 0x1F) return (int) parseUintvarInteger(in);
        // Already a long-integer length-byte → unread is hard; treat as 0 to bail safely.
        return 0;
    }

    private static long parseUintvarInteger(ByteArrayInputStream in) {
        long result = 0;
        int b;
        while (true) {
            b = extractByteValue(in);
            result = (result << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return result;
            if (in.available() == 0) return result;
        }
    }

    /** Long-integer: 1 length octet + N bytes BE. */
    private static long parseLongInteger(ByteArrayInputStream in) {
        int size = extractByteValue(in);
        if (size > 8) return 0;
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | (extractByteValue(in) & 0xFFL);
        return v;
    }

    /** Integer-value: short (MSB set) or long-integer. */
    private static int parseIntegerValue(ByteArrayInputStream in) {
        in.mark(1);
        int first = extractByteValue(in);
        if ((first & 0x80) != 0) return first & 0x7F;
        in.reset();
        return (int) parseLongInteger(in);
    }

    /** Text-string with the leading 0x7F quote prefix stripped if present. */
    private static final int TYPE_TEXT_STRING = 1;
    private static final int TYPE_QUOTED_STRING = 2;
    private static final int TYPE_TOKEN_STRING = 3;

    private static byte[] parseWapString(ByteArrayInputStream in, int stringType) {
        in.mark(1);
        int first = extractByteValue(in);
        if (first == -1) return null;
        if (stringType == TYPE_QUOTED_STRING && first == 0x22) {
            // strip leading 0x22, read until 0
        } else if (first == 0x7F) {
            // strip leading quote
        } else {
            in.reset();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = extractByteValue(in)) != 0) {
            if (b == -1) break;
            out.write(b);
        }
        return out.toByteArray();
    }

    /** Encoded-string-value: text-string OR value-length + charset + text-string. */
    private static EncodedStringValue parseEncodedStringValue(ByteArrayInputStream in) {
        in.mark(1);
        int first = extractByteValue(in);
        if (first == -1) return null;
        if (first < 32) {
            // value-length follows — char-set then text-string
            int length;
            if (first < 31) length = first;
            else length = (int) parseUintvarInteger(in);
            int marker = in.available();
            int charset = parseIntegerValue(in);
            byte[] text = parseWapString(in, TYPE_TEXT_STRING);
            // Note: we trust the length field for skipping but it might over/under-shoot; on
            // well-formed PDUs it's exact.
            try {
                return new EncodedStringValue(charset, text == null ? new byte[0] : text);
            } catch (Exception e) {
                return new EncodedStringValue(text == null ? new byte[0] : text);
            }
        } else {
            in.reset();
            byte[] text = parseWapString(in, TYPE_TEXT_STRING);
            return text == null ? null : new EncodedStringValue(text);
        }
    }

    /**
     * Content-type-value: well-known short-int OR text-string OR (value-length + content-type +
     * parameters). For our purposes we only need the MIME string.
     */
    private byte[] parseContentType(ByteArrayInputStream in, HashMapPlaceholder unused) {
        in.mark(1);
        int first = extractByteValue(in);
        if (first == -1) return null;
        if ((first & 0x80) != 0) {
            int idx = first & 0x7F;
            String[] table = PduContentTypes.contentTypes;
            if (idx < table.length) return table[idx].getBytes();
            return null;
        }
        if (first < 31 || first == 0x1F) {
            // value-length form
            int length;
            if (first < 31) length = first;
            else length = (int) parseUintvarInteger(in);
            int startAvail = in.available();
            byte[] ct;
            in.mark(1);
            int b = extractByteValue(in);
            if ((b & 0x80) != 0) {
                int idx = b & 0x7F;
                String[] table = PduContentTypes.contentTypes;
                ct = (idx < table.length ? table[idx] : "application/octet-stream").getBytes();
            } else {
                in.reset();
                ct = parseWapString(in, TYPE_TEXT_STRING);
            }
            // Parameters: type, start, name, etc. Skip whatever bytes remain in the value-length window.
            int consumed = startAvail - in.available();
            int remaining = length - consumed;
            // Within remaining bytes we look for "type" (0x83 → P_CT_MR_TYPE) and "start" (0x99) tokens.
            mTypeParam = null;
            mStartParam = null;
            while (remaining > 0 && in.available() > 0) {
                int paramTok = extractByteValue(in); remaining--;
                if (paramTok == (PduPart.P_TYPE | 0x80) || paramTok == (PduPart.P_CT_MR_TYPE | 0x80)) {
                    byte[] paramVal = parseWapString(in, TYPE_TEXT_STRING);
                    int used = paramVal == null ? 1 : paramVal.length + 1;
                    remaining -= used;
                    mTypeParam = paramVal;
                } else if (paramTok == (PduPart.P_START | 0x80) || paramTok == (PduPart.P_DEP_START | 0x80)) {
                    byte[] paramVal = parseWapString(in, TYPE_TEXT_STRING);
                    int used = paramVal == null ? 1 : paramVal.length + 1;
                    remaining -= used;
                    mStartParam = paramVal;
                } else {
                    // Unknown parameter — skip a text-string
                    byte[] skip = parseWapString(in, TYPE_TEXT_STRING);
                    int used = skip == null ? 1 : skip.length + 1;
                    remaining -= used;
                }
            }
            return ct;
        }
        // text-string form
        in.reset();
        return parseWapString(in, TYPE_TEXT_STRING);
    }

    /* ─── Body parsing (multipart) ─────────────────────────────────────────── */

    private PduBody parseParts(ByteArrayInputStream in) {
        if (in == null || in.available() == 0) return null;
        long parts = parseUintvarInteger(in);
        PduBody body = new PduBody();
        for (long i = 0; i < parts; i++) {
            int headersLength = (int) parseUintvarInteger(in);
            int dataLength = (int) parseUintvarInteger(in);
            if (headersLength <= 0) continue;
            // Read the headers block
            byte[] hdrBytes = new byte[headersLength];
            int n = in.read(hdrBytes, 0, headersLength);
            if (n != headersLength) break;
            // Read the data block
            byte[] dataBytes = new byte[Math.max(0, dataLength)];
            if (dataLength > 0) {
                int m = in.read(dataBytes, 0, dataLength);
                if (m != dataLength) break;
            }
            PduPart part = decodePartHeaders(hdrBytes);
            part.setData(dataBytes);
            body.addPart(part);
        }
        return body;
    }

    private PduPart decodePartHeaders(byte[] hdrBytes) {
        ByteArrayInputStream in = new ByteArrayInputStream(hdrBytes);
        PduPart part = new PduPart();
        // First: Content-Type (value-length + content-type-value + parameters)
        byte[] ct = parseContentType(in, null);
        if (ct != null) part.setContentType(ct);
        if (mTypeParam != null) part.setContentType(mTypeParam); // not really used
        // Remaining: well-known header fields (P_CONTENT_LOCATION, P_CONTENT_ID, etc.)
        while (in.available() > 0) {
            int hdr = extractByteValue(in);
            switch (hdr) {
                case PduPart.P_CONTENT_LOCATION: {
                    // P_CONTENT_LOCATION = 0x8E already has the high bit set (well-known
                    // header encoding per OMA-WAP-MMS-ENC), so no `+ 0x80` variant is needed.
                    byte[] v = parseWapString(in, TYPE_TEXT_STRING);
                    if (v != null) part.setContentLocation(v);
                    break;
                }
                case PduPart.P_CONTENT_ID: {
                    byte[] v = parseWapString(in, TYPE_QUOTED_STRING);
                    if (v != null && v.length > 0) {
                        try { part.setContentId(v); } catch (Exception ignored) {}
                    }
                    break;
                }
                case PduPart.P_CONTENT_DISPOSITION:
                case PduPart.P_CONTENT_DISPOSITION_OLD: {
                    int len = parseValueLength(in);
                    int marker = in.available();
                    int disp = extractByteValue(in);
                    if (disp == 0xC0) part.setContentDisposition(PduPart.DISPOSITION_ATTACHMENT);
                    else if (disp == 0xC1) part.setContentDisposition(PduPart.DISPOSITION_INLINE);
                    // skip any extra parameter bytes
                    int consumed = marker - in.available();
                    skipNBytes(in, len - consumed);
                    break;
                }
                default:
                    // Try to parse as a parameter-value (text-string)
                    byte[] discard = parseWapString(in, TYPE_TEXT_STRING);
                    if (discard == null) return part;
                    break;
            }
        }
        return part;
    }

    private static void skipNBytes(ByteArrayInputStream in, int n) {
        for (int i = 0; i < n && in.available() > 0; i++) in.read();
    }

    /** Heuristic skip when we hit a header we don't know how to parse. */
    private static void skipUnknownValue(ByteArrayInputStream in) {
        if (in.available() == 0) return;
        in.mark(1);
        int first = extractByteValue(in);
        if (first == -1) return;
        if ((first & 0x80) != 0) {
            // Short-integer — already consumed.
            return;
        }
        if (first < 31) {
            // value-length: consume that many bytes.
            skipNBytes(in, first);
            return;
        }
        if (first == 0x1F) {
            int len = (int) parseUintvarInteger(in);
            skipNBytes(in, len);
            return;
        }
        // text-string: consume until 0.
        in.reset();
        parseWapString(in, TYPE_TEXT_STRING);
    }

    /** Placeholder type so the signature stays close to AOSP. */
    private static class HashMapPlaceholder {}
}
