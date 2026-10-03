package com.taolesi.nas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.junit.Test

class ViewModelScopeTest {
    @Test
    fun testDirectInstantiateViewModelScope() {
        val vm = object : ViewModel() {}
        try {
            val scope = vm.viewModelScope
            println("Scope obtained: " + scope)
        } catch (t: Throwable) {
            println("FAILED with: " + t)
            t.printStackTrace()
        }
    }
}
