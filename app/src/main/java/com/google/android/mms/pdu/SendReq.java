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

/**
 * MMS m-send.req PDU (OMA-MMS-ENC §6.1.1). Built by the sending side and serialised via
 * [PduComposer]; the resulting bytes are written to a content URI consumed by
 * SmsManager.sendMultimediaMessage on Android 5+.
 */
public class SendReq extends MultimediaMessagePdu {

    public SendReq() {
        super();
        try {
            setMessageType(PduHeaders.MESSAGE_TYPE_SEND_REQ);
            setMmsVersion(PduHeaders.CURRENT_MMS_VERSION);
            setContentType("application/vnd.wap.multipart.related".getBytes());
            setFrom(new EncodedStringValue(PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR));
            setTransactionId(generateTransactionId());
        } catch (InvalidHeaderValueException e) {
            // Constants are valid by construction — only catchable to satisfy the API contract.
            throw new IllegalStateException(e);
        }
    }

    public SendReq(PduHeaders headers, PduBody body) {
        super(headers, body);
    }

    private static byte[] generateTransactionId() {
        String prefix = "T" + Long.toHexString(System.currentTimeMillis());
        return prefix.getBytes();
    }

    public EncodedStringValue[] getBcc() { return mPduHeaders.getEncodedStringValues(PduHeaders.BCC); }
    public void addBcc(EncodedStringValue value) { mPduHeaders.appendEncodedStringValue(value, PduHeaders.BCC); }

    public EncodedStringValue[] getCc() { return mPduHeaders.getEncodedStringValues(PduHeaders.CC); }
    public void addCc(EncodedStringValue value) { mPduHeaders.appendEncodedStringValue(value, PduHeaders.CC); }

    public byte[] getContentType() { return mPduHeaders.getTextString(PduHeaders.CONTENT_TYPE); }
    public void setContentType(byte[] value) { mPduHeaders.setTextString(value, PduHeaders.CONTENT_TYPE); }

    public int getDeliveryReport() { return mPduHeaders.getOctet(PduHeaders.DELIVERY_REPORT); }
    public void setDeliveryReport(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.DELIVERY_REPORT);
    }

    public long getExpiry() { return mPduHeaders.getLongInteger(PduHeaders.EXPIRY); }
    public void setExpiry(long value) { mPduHeaders.setLongInteger(value, PduHeaders.EXPIRY); }

    public byte[] getMessageClass() { return mPduHeaders.getTextString(PduHeaders.MESSAGE_CLASS); }
    public void setMessageClass(byte[] value) { mPduHeaders.setTextString(value, PduHeaders.MESSAGE_CLASS); }

    public long getMessageSize() { return mPduHeaders.getLongInteger(PduHeaders.MESSAGE_SIZE); }
    public void setMessageSize(long value) { mPduHeaders.setLongInteger(value, PduHeaders.MESSAGE_SIZE); }

    public int getReadReport() { return mPduHeaders.getOctet(PduHeaders.READ_REPORT); }
    public void setReadReport(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.READ_REPORT);
    }
}
