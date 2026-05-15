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
 * MMS m-notification.ind PDU (OMA-MMS-ENC §6.2). This is what the network pushes via WAP_PUSH
 * to tell us a new message is sitting on the MMSC. We parse it to extract the [contentLocation]
 * URL, then call SmsManager.downloadMultimediaMessage to fetch the real RetrieveConf.
 */
public class NotificationInd extends GenericPdu {

    public NotificationInd() {
        super();
        try {
            setMessageType(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND);
        } catch (InvalidHeaderValueException e) {
            throw new IllegalStateException(e);
        }
    }

    public NotificationInd(PduHeaders headers) { super(headers); }

    public byte[] getContentClass() { return mPduHeaders.getTextString(PduHeaders.CONTENT_CLASS); }
    public byte[] getContentLocation() { return mPduHeaders.getTextString(PduHeaders.CONTENT_LOCATION); }
    public void setContentLocation(byte[] value) { mPduHeaders.setTextString(value, PduHeaders.CONTENT_LOCATION); }

    public long getExpiry() { return mPduHeaders.getLongInteger(PduHeaders.EXPIRY); }
    public void setExpiry(long value) { mPduHeaders.setLongInteger(value, PduHeaders.EXPIRY); }

    public byte[] getMessageClass() { return mPduHeaders.getTextString(PduHeaders.MESSAGE_CLASS); }
    public void setMessageClass(byte[] value) { mPduHeaders.setTextString(value, PduHeaders.MESSAGE_CLASS); }

    public long getMessageSize() { return mPduHeaders.getLongInteger(PduHeaders.MESSAGE_SIZE); }
    public void setMessageSize(long value) { mPduHeaders.setLongInteger(value, PduHeaders.MESSAGE_SIZE); }

    public EncodedStringValue getSubject() { return mPduHeaders.getEncodedStringValue(PduHeaders.SUBJECT); }
    public void setSubject(EncodedStringValue value) { mPduHeaders.setEncodedStringValue(value, PduHeaders.SUBJECT); }

    public int getDeliveryReport() { return mPduHeaders.getOctet(PduHeaders.DELIVERY_REPORT); }
}
