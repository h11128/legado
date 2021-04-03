package io.legado.app.ui.book.source.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel

class CheckSourceViewModel(application: Application) : BaseViewModel(application) {
    val statusLiveData: MutableLiveData<MutableList<String>> = MutableLiveData()
    val checkTextVisibility: MutableLiveData<MutableList<Boolean>> = MutableLiveData()
    val loadingPanelVisibility: MutableLiveData<MutableList<Boolean>> = MutableLiveData()

    fun updateStatus(position: Int, checkResult: String) {
        statusLiveData.value?.set(position, checkResult)
        statusLiveData.notifyObserver()
        if (checkResult.contains("Fail") || checkResult.contains("Success") || checkResult.isEmpty()) {
            loadingPanelVisibility.value?.set(position, false)
            loadingPanelVisibility.notifyObserver()
        } else {
            loadingPanelVisibility.value?.set(position, true)
            loadingPanelVisibility.notifyObserver()
        }
        checkTextVisibility.value?.set(position, true)
        checkTextVisibility.notifyObserver()
    }

    private fun <T> MutableLiveData<T>.notifyObserver() {
        this.value = this.value
    }

}

enum class CheckSourceState {
    CheckSearch, CheckExplore, CheckInfo, CheckChapterList, CheckContent, CheckSearchFail, CheckExploreFail, CheckInfoFail, CheckChapterListFail, CheckContentFail, CheckSearchSuccess, CheckExploreSuccess, CheckInfoSuccess, CheckChapterListSuccess, CheckContentSuccess
}