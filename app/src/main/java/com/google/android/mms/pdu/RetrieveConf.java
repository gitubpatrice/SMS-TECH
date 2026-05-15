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
 * MMS m-retrieve.conf PDU (OMA-MMS-ENC §6.3). This is the actual message body the MMSC returns
 * over HTTP after we trigger SmsManager.downloadMultimediaMessage. We parse it to extract the
 * sender and the [PduBody] parts (text, images, audio).
 */
public class RetrieveConf extends MultimediaMessagePdu {

    public RetrieveConf() {
        super();
        try {
            setMessageType(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF);
            setMmsVersion(PduHeaders.CURRENT_MMS_VERSION);
        } catch (InvalidHeaderValueException e) {
            throw new IllegalStateException(e);
        }
    }

    public RetrieveConf(PduHeaders headers, PduBody body) { super(headers, body); }

    public byte[] getContentType() { return mPduHeaders.getTextString(PduHeaders.CONTENT_TYPE); }
    public void setContentType(byte[] value) { mPduHeaders.setTextString(value, PduHeaders.CONTENT_TYPE); }

    public EncodedStringValue[] getCc() { return mPduHeaders.getEncodedStringValues(PduHeaders.CC); }
    public void addCc(EncodedStringValue value) { mPduHeaders.appendEncodedStringValue(value, PduHeaders.CC); }

    public byte[] getMessageId() { return mPduHeaders.getTextString(PduHeaders.MESSAGE_ID); }
    public void setMessageId(byte[] value) { mPduHeaders.setTextString(value, PduHeaders.MESSAGE_ID); }

    public int getRetrieveStatus() { return mPduHeaders.getOctet(PduHeaders.RETRIEVE_STATUS); }
    public void setRetrieveStatus(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.RETRIEVE_STATUS);
    }

    public EncodedStringValue getRetrieveText() { return mPduHeaders.getEncodedStringValue(PduHeaders.RETRIEVE_TEXT); }
    public void setRetrieveText(EncodedStringValue value) { mPduHeaders.setEncodedStringValue(value, PduHeaders.RETRIEVE_TEXT); }
}
