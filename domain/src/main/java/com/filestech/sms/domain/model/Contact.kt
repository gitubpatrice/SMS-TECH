package com.filestech.sms.domain.model

data class Contact(
    val id: Long?,
    val displayName: String?,
    val phones: List<PhoneAddress>,
    val photoUri: String? = null,
) {
    val firstPhone: PhoneAddress? get() = phones.firstOrNull()
}
