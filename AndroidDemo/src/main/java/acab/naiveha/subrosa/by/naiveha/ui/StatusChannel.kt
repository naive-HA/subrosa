package acab.naiveha.subrosa.by.naiveha.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class StatusChannel {
    private val _value = MutableLiveData("")
    val value: LiveData<String> = _value
    fun post(message: String) = _value.postValue(message)
}
