package com.filestech.sms.data.repository

import com.filestech.sms.data.contacts.ContactsReader
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.repository.ContactRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val reader: ContactsReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ContactRepository {

    override suspend fun lookupByPhone(rawPhone: String): Contact? = withContext(io) {
        reader.lookupByPhone(rawPhone)
    }

    override suspend fun listAll(): List<Contact> = withContext(io) { reader.listAll() }
}
