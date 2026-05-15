package com.filestech.sms.ui

import androidx.lifecycle.ViewModel
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppRootViewModel @Inject constructor(
    val appLock: AppLockManager,
) : ViewModel()
