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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Ordered list of [PduPart] (the MMS multipart body). Mirrors AOSP PduBody.
 *
 * Indexes by Content-ID and Content-Location are kept up to date so SMIL references resolve
 * efficiently.
 */
public class PduBody {

    private final List<PduPart> mParts = new ArrayList<>();
    private final HashMap<String, PduPart> mPartMapByContentId = new HashMap<>();
    private final HashMap<String, PduPart> mPartMapByContentLocation = new HashMap<>();
    private final HashMap<String, PduPart> mPartMapByName = new HashMap<>();
    private final HashMap<String, PduPart> mPartMapByFilename = new HashMap<>();

    public PduBody() {}

    private void putPartToMaps(PduPart part) {
        byte[] cid = part.getContentId();
        if (cid != null) mPartMapByContentId.put(new String(cid), part);
        byte[] cl = part.getContentLocation();
        if (cl != null) mPartMapByContentLocation.put(new String(cl), part);
        byte[] name = part.getName();
        if (name != null) mPartMapByName.put(new String(name), part);
        byte[] fn = part.getFilename();
        if (fn != null) mPartMapByFilename.put(new String(fn), part);
    }

    public void addPart(PduPart part) {
        if (part == null) throw new NullPointerException("part");
        mParts.add(part);
        putPartToMaps(part);
    }

    public void addPart(int index, PduPart part) {
        if (part == null) throw new NullPointerException("part");
        mParts.add(index, part);
        putPartToMaps(part);
    }

    public PduPart getPart(int index) { return mParts.get(index); }
    public int getPartIndex(PduPart part) { return mParts.indexOf(part); }
    public int getPartsNum() { return mParts.size(); }

    public PduPart getPartByContentId(String contentId) {
        if (contentId == null) return null;
        // Try both "<cid>" and "cid" forms.
        PduPart p = mPartMapByContentId.get(contentId);
        if (p != null) return p;
        if (contentId.startsWith("<") && contentId.endsWith(">")) {
            return mPartMapByContentId.get(contentId.substring(1, contentId.length() - 1));
        }
        return mPartMapByContentId.get('<' + contentId + '>');
    }

    public PduPart getPartByContentLocation(String contentLocation) {
        return mPartMapByContentLocation.get(contentLocation);
    }

    public PduPart getPartByName(String name) { return mPartMapByName.get(name); }
    public PduPart getPartByFileName(String filename) { return mPartMapByFilename.get(filename); }

    public boolean removePart(PduPart part) {
        boolean removed = mParts.remove(part);
        if (removed) {
            byte[] cid = part.getContentId();
            if (cid != null) mPartMapByContentId.remove(new String(cid));
            byte[] cl = part.getContentLocation();
            if (cl != null) mPartMapByContentLocation.remove(new String(cl));
        }
        return removed;
    }
}
