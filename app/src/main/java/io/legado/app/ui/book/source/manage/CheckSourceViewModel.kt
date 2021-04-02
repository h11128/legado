package io.legado.app.ui.book.source.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel

class CheckSourceViewModel(application: Application) : BaseViewModel(application) {
    val statusLiveData: MutableLiveData<List<String>> = MutableLiveData()
    val checkTextVisibility: MutableLiveData<List<Boolean>> = MutableLiveData()
    val loadingPanelVisibility: MutableLiveData<List<Boolean>> = MutableLiveData()


}

enum class CheckSourceState {
    CheckSearch, CheckExplore, CheckInfo, CheckChapterList, CheckContent, CheckSearchFail, CheckExploreFail, CheckInfoFail, CheckChapterListFail, CheckContentFail, CheckSearchSuccess, CheckExploreSuccess, CheckInfoSuccess, CheckChapterListSuccess, CheckContentSuccess
}