package com.filestech.sms.domain.repository

import com.filestech.sms.domain.model.Contact

interface ContactRepository {
    suspend fun lookupByPhone(rawPhone: String): Contact?
    suspend fun listAll(): List<Contact>
}
