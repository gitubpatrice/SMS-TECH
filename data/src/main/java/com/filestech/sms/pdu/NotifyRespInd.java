/*
 * Copyright (C) 2007 Esmertec AG.
 * Copyright (C) 2007 The Android Open Source Project
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

/**
 * MMS m-notifyresp.ind PDU (OMA-MMS-ENC §6.2): the answer the client owes the MMSC for an
 * m-notification.ind — the notification's transaction id, the MMS version and an X-Mms-Status
 * ({@link PduHeaders#STATUS_RETRIEVED}, {@link PduHeaders#STATUS_REJECTED}).
 *
 * Android's MMS service sends nothing back on its own: its DownloadRequest only fetches the
 * message, and AOSP Messaging answers from the app. {@link PduComposer} writes this PDU;
 * NotifyRespFormatTest keeps its bytes identical to what AOSP's composer writes.
 */
public class NotifyRespInd extends GenericPdu {

    public NotifyRespInd(int mmsVersion, byte[] transactionId, int status)
            throws InvalidHeaderValueException {
        super();
        setMessageType(PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND);
        setMmsVersion(mmsVersion);
        setTransactionId(transactionId);
        setStatus(status);
    }

    public int getStatus() { return mPduHeaders.getOctet(PduHeaders.STATUS); }

    public void setStatus(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.STATUS);
    }
}
