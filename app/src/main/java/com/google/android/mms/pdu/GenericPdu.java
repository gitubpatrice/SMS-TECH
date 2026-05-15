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
 * Base class shared by every MMS PDU we encode or decode. Holds a [PduHeaders] map and exposes
 * the four headers all PDUs carry: message-type, MMS-version, transaction-id, from. Mirrors AOSP.
 */
public class GenericPdu {

    /** Header bag. Subclasses delegate get/set to it. */
    PduHeaders mPduHeaders;

    public GenericPdu() {
        mPduHeaders = new PduHeaders();
    }

    GenericPdu(PduHeaders headers) {
        mPduHeaders = headers;
    }

    public PduHeaders getPduHeaders() { return mPduHeaders; }

    public int getMessageType() { return mPduHeaders.getOctet(PduHeaders.MESSAGE_TYPE); }

    public void setMessageType(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.MESSAGE_TYPE);
    }

    public int getMmsVersion() { return mPduHeaders.getOctet(PduHeaders.MMS_VERSION); }
    public void setMmsVersion(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.MMS_VERSION);
    }

    public EncodedStringValue getFrom() { return mPduHeaders.getEncodedStringValue(PduHeaders.FROM); }
    public void setFrom(EncodedStringValue value) { mPduHeaders.setEncodedStringValue(value, PduHeaders.FROM); }

    public byte[] getTransactionId() { return mPduHeaders.getTextString(PduHeaders.TRANSACTION_ID); }
    public void setTransactionId(byte[] value) { mPduHeaders.setTextString(value, PduHeaders.TRANSACTION_ID); }
}
