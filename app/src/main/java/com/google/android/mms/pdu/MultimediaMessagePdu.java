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
 * Multi-part MMS PDU (carries an optional [PduBody]). Subclassed by [SendReq] and
 * [RetrieveConf]. Mirrors AOSP MultimediaMessagePdu.
 */
public class MultimediaMessagePdu extends GenericPdu {

    private PduBody mMessageBody;

    public MultimediaMessagePdu() { super(); }

    public MultimediaMessagePdu(PduHeaders headers) { super(headers); }

    public MultimediaMessagePdu(PduHeaders headers, PduBody body) {
        super(headers);
        mMessageBody = body;
    }

    public PduBody getBody() { return mMessageBody; }
    public void setBody(PduBody body) { mMessageBody = body; }

    /* ─── Common typed headers ─────────────────────────────────────────────── */

    public EncodedStringValue getSubject() {
        return mPduHeaders.getEncodedStringValue(PduHeaders.SUBJECT);
    }

    public void setSubject(EncodedStringValue value) {
        mPduHeaders.setEncodedStringValue(value, PduHeaders.SUBJECT);
    }

    public EncodedStringValue[] getTo() {
        return mPduHeaders.getEncodedStringValues(PduHeaders.TO);
    }

    public void addTo(EncodedStringValue value) {
        mPduHeaders.appendEncodedStringValue(value, PduHeaders.TO);
    }

    public int getPriority() { return mPduHeaders.getOctet(PduHeaders.PRIORITY); }
    public void setPriority(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.PRIORITY);
    }

    public long getDate() { return mPduHeaders.getLongInteger(PduHeaders.DATE); }
    public void setDate(long value) { mPduHeaders.setLongInteger(value, PduHeaders.DATE); }
}
