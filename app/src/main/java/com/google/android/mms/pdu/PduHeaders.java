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

import com.google.android.mms.InvalidHeaderValueException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds the typed headers of a single MMS PDU. The integer field constants come from
 * OMA-TS-MMS-ENC-V1_3 §7 — they identify each header in the binary encoding.
 *
 * Mirrors the AOSP implementation of frameworks/opt/mms PduHeaders. Only the values actually
 * used by SMS Tech's pipeline are listed; other constants are kept for reference / forward
 * compatibility.
 */
public class PduHeaders {

    /** Default value reserved by the encoder to mean "header absent". */
    public static final int VALUE_UNKNOWN = 0;
    public static final byte VALUE_UNKNOWN_BYTE = 0;

    /* ─── Field identifiers (OMA-MMS-ENC §7.1) ─────────────────────────────── */
    public static final int BCC                                   = 0x81;
    public static final int CC                                    = 0x82;
    public static final int CONTENT_LOCATION                      = 0x83;
    public static final int CONTENT_TYPE                          = 0x84;
    public static final int DATE                                  = 0x85;
    public static final int DELIVERY_REPORT                       = 0x86;
    public static final int DELIVERY_TIME                         = 0x87;
    public static final int EXPIRY                                = 0x88;
    public static final int FROM                                  = 0x89;
    public static final int MESSAGE_CLASS                         = 0x8A;
    public static final int MESSAGE_ID                            = 0x8B;
    public static final int MESSAGE_TYPE                          = 0x8C;
    public static final int MMS_VERSION                           = 0x8D;
    public static final int MESSAGE_SIZE                          = 0x8E;
    public static final int PRIORITY                              = 0x8F;
    public static final int READ_REPLY                            = 0x90;
    public static final int READ_REPORT                           = 0x90;
    public static final int REPORT_ALLOWED                        = 0x91;
    public static final int RESPONSE_STATUS                       = 0x92;
    public static final int RESPONSE_TEXT                         = 0x93;
    public static final int SENDER_VISIBILITY                     = 0x94;
    public static final int STATUS                                = 0x95;
    public static final int SUBJECT                               = 0x96;
    public static final int TO                                    = 0x97;
    public static final int TRANSACTION_ID                        = 0x98;
    public static final int RETRIEVE_STATUS                       = 0x99;
    public static final int RETRIEVE_TEXT                         = 0x9A;
    public static final int READ_STATUS                           = 0x9B;
    public static final int REPLY_CHARGING                        = 0x9C;
    public static final int REPLY_CHARGING_DEADLINE               = 0x9D;
    public static final int REPLY_CHARGING_ID                     = 0x9E;
    public static final int REPLY_CHARGING_SIZE                   = 0x9F;
    public static final int PREVIOUSLY_SENT_BY                    = 0xA0;
    public static final int PREVIOUSLY_SENT_DATE                  = 0xA1;
    public static final int STORE                                 = 0xA2;
    public static final int MM_STATE                              = 0xA3;
    public static final int MM_FLAGS                              = 0xA4;
    public static final int STORE_STATUS                          = 0xA5;
    public static final int STORE_STATUS_TEXT                     = 0xA6;
    public static final int STORED                                = 0xA7;
    public static final int ATTRIBUTES                            = 0xA8;
    public static final int TOTALS                                = 0xA9;
    public static final int MBOX_TOTALS                           = 0xAA;
    public static final int QUOTAS                                = 0xAB;
    public static final int MBOX_QUOTAS                           = 0xAC;
    public static final int MESSAGE_COUNT                         = 0xAD;
    public static final int CONTENT                               = 0xAE;
    public static final int START                                 = 0xAF;
    public static final int ADDITIONAL_HEADERS                    = 0xB0;
    public static final int DISTRIBUTION_INDICATOR                = 0xB1;
    public static final int ELEMENT_DESCRIPTOR                    = 0xB2;
    public static final int LIMIT                                 = 0xB3;
    public static final int RECOMMENDED_RETRIEVAL_MODE            = 0xB4;
    public static final int RECOMMENDED_RETRIEVAL_MODE_TEXT       = 0xB5;
    public static final int STATUS_TEXT                           = 0xB6;
    public static final int APPLIC_ID                             = 0xB7;
    public static final int REPLY_APPLIC_ID                       = 0xB8;
    public static final int AUX_APPLIC_ID                         = 0xB9;
    public static final int CONTENT_CLASS                         = 0xBA;
    public static final int DRM_CONTENT                            = 0xBB;
    public static final int ADAPTATION_ALLOWED                    = 0xBC;
    public static final int REPLACE_ID                            = 0xBD;
    public static final int CANCEL_ID                             = 0xBE;
    public static final int CANCEL_STATUS                         = 0xBF;

    /* ─── Field values ─────────────────────────────────────────────────────── */
    public static final int MESSAGE_TYPE_SEND_REQ                 = 0x80;
    public static final int MESSAGE_TYPE_SEND_CONF                = 0x81;
    public static final int MESSAGE_TYPE_NOTIFICATION_IND         = 0x82;
    public static final int MESSAGE_TYPE_NOTIFYRESP_IND           = 0x83;
    public static final int MESSAGE_TYPE_RETRIEVE_CONF            = 0x84;
    public static final int MESSAGE_TYPE_ACKNOWLEDGE_IND          = 0x85;
    public static final int MESSAGE_TYPE_DELIVERY_IND             = 0x86;
    public static final int MESSAGE_TYPE_READ_REC_IND             = 0x87;
    public static final int MESSAGE_TYPE_READ_ORIG_IND            = 0x88;
    public static final int MESSAGE_TYPE_FORWARD_REQ              = 0x89;
    public static final int MESSAGE_TYPE_FORWARD_CONF             = 0x8A;
    public static final int MESSAGE_TYPE_MBOX_STORE_REQ           = 0x8B;
    public static final int MESSAGE_TYPE_MBOX_STORE_CONF          = 0x8C;
    public static final int MESSAGE_TYPE_MBOX_VIEW_REQ            = 0x8D;
    public static final int MESSAGE_TYPE_MBOX_VIEW_CONF           = 0x8E;
    public static final int MESSAGE_TYPE_MBOX_UPLOAD_REQ          = 0x8F;
    public static final int MESSAGE_TYPE_MBOX_UPLOAD_CONF         = 0x90;
    public static final int MESSAGE_TYPE_MBOX_DELETE_REQ          = 0x91;
    public static final int MESSAGE_TYPE_MBOX_DELETE_CONF         = 0x92;
    public static final int MESSAGE_TYPE_MBOX_DESCR               = 0x93;
    public static final int MESSAGE_TYPE_DELETE_REQ               = 0x94;
    public static final int MESSAGE_TYPE_DELETE_CONF              = 0x95;
    public static final int MESSAGE_TYPE_CANCEL_REQ               = 0x96;
    public static final int MESSAGE_TYPE_CANCEL_CONF              = 0x97;

    public static final int VALUE_YES = 0x80;
    public static final int VALUE_NO  = 0x81;

    public static final int CURRENT_MMS_VERSION = 0x12; // 1.2

    public static final int FROM_ADDRESS_PRESENT_TOKEN            = 0x80;
    public static final int FROM_INSERT_ADDRESS_TOKEN             = 0x81;
    public static final String FROM_ADDRESS_PRESENT_TOKEN_STR     = "address-present-token";
    public static final String FROM_INSERT_ADDRESS_TOKEN_STR      = "insert-address-token";

    public static final int RESPONSE_STATUS_OK                          = 0x80;
    public static final int RESPONSE_STATUS_ERROR_UNSPECIFIED           = 0x81;
    public static final int RESPONSE_STATUS_ERROR_SERVICE_DENIED        = 0x82;
    public static final int RESPONSE_STATUS_ERROR_MESSAGE_FORMAT_CORRUPT = 0x83;
    public static final int RESPONSE_STATUS_ERROR_SENDING_ADDRESS_UNRESOLVED = 0x84;
    public static final int RESPONSE_STATUS_ERROR_MESSAGE_NOT_FOUND     = 0x85;
    public static final int RESPONSE_STATUS_ERROR_NETWORK_PROBLEM       = 0x86;
    public static final int RESPONSE_STATUS_ERROR_CONTENT_NOT_ACCEPTED  = 0x87;
    public static final int RESPONSE_STATUS_ERROR_UNSUPPORTED_MESSAGE   = 0x88;

    public static final int RETRIEVE_STATUS_OK                          = 0x80;
    public static final int RETRIEVE_STATUS_ERROR_TRANSIENT_FAILURE     = 0xC0;
    public static final int RETRIEVE_STATUS_ERROR_PERMANENT_FAILURE     = 0xE0;

    /* ─── Internal storage ─────────────────────────────────────────────────── */
    private final Map<Integer, Object> mHeaderMap;

    public PduHeaders() {
        mHeaderMap = new HashMap<>();
    }

    /* ─── Octet (byte) value ──────────────────────────────────────────────── */
    public int getOctet(int field) {
        Integer value = (Integer) mHeaderMap.get(field);
        return value == null ? 0 : value;
    }

    public void setOctet(int value, int field) throws InvalidHeaderValueException {
        if (!isValidOctetValue(field, value)) {
            throw new InvalidHeaderValueException("invalid octet value for field " + field);
        }
        mHeaderMap.put(field, value);
    }

    private static boolean isValidOctetValue(int field, int value) {
        switch (field) {
            case MESSAGE_TYPE:
                return (value >= MESSAGE_TYPE_SEND_REQ && value <= MESSAGE_TYPE_CANCEL_CONF);
            case DELIVERY_REPORT:
            case READ_REPLY:
            case REPORT_ALLOWED:
            case SENDER_VISIBILITY:
            case STORE:
            case STORED:
            case ADAPTATION_ALLOWED:
            case DRM_CONTENT:
            case DISTRIBUTION_INDICATOR:
                return value == VALUE_YES || value == VALUE_NO;
            case MMS_VERSION:
                return true; // any byte
            case STATUS:
            case PRIORITY:
            case READ_STATUS:
            case CANCEL_STATUS:
            case CONTENT_CLASS:
            case RETRIEVE_STATUS:
            case RECOMMENDED_RETRIEVAL_MODE:
            case STORE_STATUS:
            case REPLY_CHARGING:
            case MM_STATE:
            case MM_FLAGS:
            case RESPONSE_STATUS:
                return true;
            default:
                return true; // permissive — unknown fields accepted
        }
    }

    /* ─── EncodedStringValue (single) ─────────────────────────────────────── */
    public EncodedStringValue getEncodedStringValue(int field) {
        return (EncodedStringValue) mHeaderMap.get(field);
    }

    public void setEncodedStringValue(EncodedStringValue value, int field) {
        if (value == null) throw new NullPointerException("value");
        mHeaderMap.put(field, value);
    }

    /* ─── EncodedStringValue (multiple, e.g. To/Cc/Bcc) ───────────────────── */
    public EncodedStringValue[] getEncodedStringValues(int field) {
        ArrayList<EncodedStringValue> list = castList(mHeaderMap.get(field));
        if (list == null) return null;
        return list.toArray(new EncodedStringValue[0]);
    }

    public void appendEncodedStringValue(EncodedStringValue value, int field) {
        if (value == null) throw new NullPointerException("value");
        ArrayList<EncodedStringValue> list = castList(mHeaderMap.get(field));
        if (list == null) {
            list = new ArrayList<>();
            mHeaderMap.put(field, list);
        }
        list.add(value);
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<EncodedStringValue> castList(Object o) {
        if (o instanceof ArrayList<?>) return (ArrayList<EncodedStringValue>) o;
        return null;
    }

    /* ─── byte[] (raw) ─────────────────────────────────────────────────────── */
    public byte[] getTextString(int field) {
        return (byte[]) mHeaderMap.get(field);
    }

    public void setTextString(byte[] value, int field) {
        if (value == null) throw new NullPointerException("value");
        mHeaderMap.put(field, value);
    }

    /* ─── long ────────────────────────────────────────────────────────────── */
    public long getLongInteger(int field) {
        Long value = (Long) mHeaderMap.get(field);
        return value == null ? -1L : value;
    }

    public void setLongInteger(long value, int field) {
        mHeaderMap.put(field, value);
    }

    /* ─── Convenience: report any header set ──────────────────────────────── */
    public boolean hasHeader(int field) { return mHeaderMap.containsKey(field); }
    public void remove(int field) { mHeaderMap.remove(field); }
}
