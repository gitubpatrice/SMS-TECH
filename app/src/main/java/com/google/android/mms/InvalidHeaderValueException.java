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

package com.google.android.mms;

/**
 * Thrown when an invalid header value is read or written. Mirrors AOSP
 * frameworks/opt/mms/src/java/com/google/android/mms/InvalidHeaderValueException.java.
 */
public class InvalidHeaderValueException extends MmsException {
    private static final long serialVersionUID = -1474262515174718841L;

    public InvalidHeaderValueException() { super(); }
    public InvalidHeaderValueException(String message) { super(message); }
}
