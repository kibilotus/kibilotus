package com.kibilotus.supplymanager.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kibilotus.supplymanager.AppContainer
import com.kibilotus.supplymanager.SupplyApplication

@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        (context.applicationContext as SupplyApplication).container
    }
}

/** 생성자 인자가 있는 ViewModel 을 수동 조립하기 위한 최소 팩토리. */
class SimpleViewModelFactory<VM : ViewModel>(
    private val producer: () -> VM,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = producer() as T
}
