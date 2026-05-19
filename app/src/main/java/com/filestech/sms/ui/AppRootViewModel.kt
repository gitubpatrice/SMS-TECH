package com.filestech.sms.ui

import androidx.lifecycle.ViewModel
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.system.share.IncomingShareHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppRootViewModel @Inject constructor(
    val appLock: AppLockManager,
    /**
     * v1.4.1 — exposed so [AppRoot] can observe `pending` and route the user to the
     * [com.filestech.sms.ui.screens.compose.ComposeScreen] picker whenever a share-target
     * payload arrives (`ACTION_SEND` from the Gallery, the browser, etc.). Previously the
     * payload silently sat in the holder until the user happened to open a thread, which
     * felt like the share had been dropped on the floor.
     */
    val incomingShare: IncomingShareHolder,
) : ViewModel()
