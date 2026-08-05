package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.help.config.ChangeSourcePrefsApply
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 网络一键导入
 * 格式: legado://import/{path}?src={url}
 *
 * Agent prefs: legado://import/changeSourcePrefs?loadWordCount=true&earlyStop=true
 */
class OnLineImportActivity :
    VMBaseActivity<ActivityTranslucenceBinding, OnLineImportViewModel>() {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)
    override val viewModel by viewModels<OnLineImportViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(it.second, true)
                )
                "rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(it.second, true)
                )
                "replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(it.second, true)
                )
                "httpTts" -> showDialogFragment(
                    ImportHttpTtsDialog(it.second, true)
                )
                "theme" -> showDialogFragment(
                    ImportThemeDialog(it.second, true)
                )
                "txtRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(it.second, true)
                )
                "dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(it.second, true)
                )
                "autoTask" -> showDialogFragment(
                    ImportAutoTaskDialog(it.second, true)
                )
                "readConfig" -> finallyDialog(getString(R.string.success), it.second)
            }
        }
        viewModel.readConfigLive.observe(this) {
            if (it != null) {
                confirmReadConfigImport()
            }
        }
        viewModel.errorLive.observe(this) {
            finallyDialog(getString(R.string.error), it)
        }
        if (applyChangeSourcePrefsIfPresent(intent)) {
            finish()
            return
        }
        if (viewModel.intentHandled) return
        viewModel.intentHandled = true
        handleImportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyChangeSourcePrefsIfPresent(intent)) {
            finish()
        }
    }

    /** @return true if this was a changeSourcePrefs deep link (always re-handled). */
    private fun applyChangeSourcePrefsIfPresent(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.path != "/changeSourcePrefs") return false
        val msg = ChangeSourcePrefsApply.applyFromUri(data)
        toastOnUi(msg)
        return true
    }

    private fun handleImportIntent(intent: Intent) {
        intent.data?.let {
            val url = it.getQueryParameter("src")
            if (url.isNullOrEmpty()) {
                finish()
                return
            }
            when (it.path) {
                "/bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(url, true)
                )

                "/rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(url, true)
                )

                "/replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(url, true)
                )
                "/textTocRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(url, true)
                )
                "/httpTTS" -> showDialogFragment(
                    ImportHttpTtsDialog(url, true)
                )
                "/dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(url, true)
                )
                "/theme" -> showDialogFragment(
                    ImportThemeDialog(url, true)
                )
                "/autoTask" -> showDialogFragment(
                    ImportAutoTaskDialog(url, true)
                )
                "/auto" -> viewModel.determineType(url)
                "/readConfig" -> viewModel.getReadConfig(url)
                "/addToBookshelf" -> showDialogFragment(
                    AddToBookshelfDialog(url, true)
                )
                "/importonline" -> when (it.host) {
                    "booksource" -> showDialogFragment(
                        ImportBookSourceDialog(url, true)
                    )
                    "rsssource" -> showDialogFragment(
                        ImportRssSourceDialog(url, true)
                    )
                    "replace" -> showDialogFragment(
                        ImportReplaceRuleDialog(url, true)
                    )
                    else -> {
                        viewModel.determineType(url)
                    }
                }
                else -> viewModel.determineType(url)
            }
        }
    }

    private fun confirmReadConfigImport() {
        alert(
            titleResource = R.string.import_str,
            messageResource = R.string.confirm_read_config_import
        ) {
            yesButton {
                viewModel.importReadConfig()
            }
            noButton {
                viewModel.cancelReadConfigImport()
                finish()
            }
            onCancelled {
                viewModel.cancelReadConfigImport()
                finish()
            }
        }
    }

    private fun finallyDialog(title: String, msg: String) {
        alert(title, msg) {
            okButton()
            onDismiss {
                finish()
            }
        }
    }

}
