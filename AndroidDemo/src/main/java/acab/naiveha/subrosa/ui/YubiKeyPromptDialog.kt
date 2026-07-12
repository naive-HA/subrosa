package acab.naiveha.subrosa.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import acab.naiveha.subrosa.R
import com.google.android.material.button.MaterialButton

class YubiKeyPromptDialog(
    context: Context,
    onCancelled: () -> Unit,
) {
    private val helpText: TextView
    private val dialog: Dialog

    init {
        val themedContext = ContextThemeWrapper(context, com.yubico.yubikit.android.R.style.YubiKitPromptDialogTheme)
        val promptView = LayoutInflater.from(themedContext)
            .inflate(com.yubico.yubikit.android.R.layout.yubikit_yubikey_prompt_content, null)

        val primaryColor = ContextCompat.getColor(themedContext, com.yubico.yubikit.android.R.color.yubikit_text_color_primary)
        val secondaryColor = ContextCompat.getColor(themedContext, com.yubico.yubikit.android.R.color.yubikit_text_color_secondary)

        promptView.findViewById<TextView>(com.yubico.yubikit.android.R.id.yubikit_prompt_title).apply {
            text = context.getString(R.string.yubikit_prompt_activity_title)
            setTextColor(primaryColor)
        }
        helpText = promptView.findViewById<TextView>(com.yubico.yubikit.android.R.id.yubikit_prompt_help_text_view).apply {
            text = context.getString(R.string.yubikit_prompt_plug_in_or_tap)
            setTextColor(secondaryColor)
        }

        dialog = Dialog(context, com.yubico.yubikit.android.R.style.YubiKitPromptDialogTheme).apply {
            setContentView(promptView)
            setOnCancelListener { onCancelled() }
        }

        promptView.findViewById<Button>(com.yubico.yubikit.android.R.id.yubikit_prompt_cancel_btn).apply {
            background = Color.TRANSPARENT.toDrawable()
            stateListAnimator = null
            elevation = 0f
            if (this is MaterialButton) {
                strokeWidth = 0
            }
            setTextColor(primaryColor)
            setOnClickListener { dialog.cancel() }
        }
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun setHelpText(text: String) {
        helpText.text = text
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()
}
