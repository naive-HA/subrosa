/*
 * Copyright (C) 2022 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package acab.naiveha.subrosa.ui

import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import acab.naiveha.subrosa.R
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UiThread
suspend fun getSecret(
    context: Context,
    title: Any,
    @StringRes hint: Int = R.string.pin,
    showPaste: Boolean = false,
    defaultValue: CharArray? = null,
    inputType: Int? = null,
    clearTextByDefault: Boolean = false
): CharArray? = suspendCoroutine { cont ->
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin, null).apply {
        val textInputLayout = findViewById<TextInputLayout>(R.id.dialog_pin_textinputlayout)
        val editText = findViewById<EditText>(R.id.dialog_pin_edittext)
        textInputLayout.hint = context.getString(hint)

        inputType?.let { editText.inputType = it }
        if (clearTextByDefault) {
            editText.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()
        }

        defaultValue?.let {
            editText.setText(it, 0, it.size)
            editText.selectAll()
        }
        editText.requestFocus()

        if (showPaste) {
            textInputLayout.setStartIconOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.let {
                    editText.setText(it)
                }
            }
        } else {
            textInputLayout.startIconDrawable = null
        }
    }
    val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(when (title) {
                is Int -> context.getString(title)
                is String -> title
                else -> title.toString()
            })
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val editable = view.findViewById<EditText>(R.id.dialog_pin_edittext).text
                val chars = CharArray(editable.length)
                TextUtils.getChars(editable, 0, editable.length, chars, 0)
                cont.resume(chars)
            }
            .setNeutralButton(android.R.string.cancel) { d, _ ->
                d.dismiss()
                cont.resume(null)
            }
            .setOnCancelListener {
                cont.resume(null)
            }
            .create()

    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()

    view.findViewById<EditText>(R.id.dialog_pin_edittext).run {
        requestFocus()
        postDelayed({
            dialog.window?.let { window ->
                WindowCompat.getInsetsController(window, this).show(WindowInsetsCompat.Type.ime())
            }
        }, 100)
    }
}

@UiThread
suspend fun collectPin(
    context: Context,
    title: Any,
    minLength: Int,
    @StringRes tooShortRes: Int,
    inputType: Int,
    tag: String,
    logLabel: String,
    defaultValue: CharArray? = null,
    clearTextByDefault: Boolean = false,
): CharArray? {
    while (true) {
        val entered = getSecret(
            context,
            title,
            inputType = inputType,
            defaultValue = defaultValue,
            clearTextByDefault = clearTextByDefault,
        ) ?: run { Log.d(tag, "$logLabel cancelled"); return null }

        if (entered.size < minLength) {
            Log.w(tag, "$logLabel too short (${entered.size} < $minLength) — re-showing dialog")
            Toast.makeText(context, tooShortRes, Toast.LENGTH_SHORT).show()
            entered.fill('\u0000')
            continue
        }

        Log.d(tag, "$logLabel collected (length=${entered.size})")
        return entered
    }
}

fun Fragment.bindAutoClearStatus(
    status: LiveData<String>,
    target: TextView,
    vararg completeStatuses: String,
    hideWhenBlank: Boolean = false,
    shouldHide: (String) -> Boolean = { false },
    post: (String) -> Unit,
) {
    var clearJob: Job? = null
    status.observe(viewLifecycleOwner) { message ->
        clearJob?.cancel()

        if (shouldHide(message)) {
            target.visibility = View.GONE
            return@observe
        }

        target.text = message
        target.visibility = if (hideWhenBlank && message.isBlank()) View.GONE else View.VISIBLE

        if (message in completeStatuses) {
            clearJob = lifecycleScope.launch {
                delay(2000.milliseconds)
                post("")
            }
        }
    }
}

/**
 * A destructive-action confirm/cancel dialog, with the confirm/cancel/dismiss outcome logged
 * under [logLabel]. Shared by every "are you sure" dialog in the app so wording, button
 * placement, and logging stay consistent — see [showOpenPgpAppletResetDialog] for a themed
 * wrapper example.
 */
fun Fragment.showConfirmationDialog(
    tag: String,
    logLabel: String,
    @StringRes title: Int,
    message: String,
    @StringRes confirmText: Int,
    onConfirmed: () -> Unit,
    onCancelled: () -> Unit = {},
) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(confirmText) { _, _ ->
            Log.d(tag, "$logLabel confirmed")
            onConfirmed()
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            Log.d(tag, "$logLabel cancelled")
            onCancelled()
        }
        .setOnCancelListener {
            Log.d(tag, "$logLabel dialog dismissed")
            onCancelled()
        }
        .show()
}

fun Fragment.showOpenPgpAppletResetDialog(
    tag: String,
    onConfirmed: () -> Unit,
    onCancelled: () -> Unit = {},
) = showConfirmationDialog(
    tag         = tag,
    logLabel    = "OpenPGP applet reset",
    title       = R.string.openpgp_reset_title,
    message     = getString(R.string.openpgp_reset_message),
    confirmText = R.string.openpgp_reset_confirm,
    onConfirmed = onConfirmed,
    onCancelled = onCancelled,
)

private const val ADMIN_PIN_MIN_LENGTH = 8
private const val USER_PIN_MIN_LENGTH = 6
private val PIN_NUMERIC_INPUT_TYPE = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

@UiThread
suspend fun Fragment.collectAdminPin(
    title: String,
    tag: String,
    logLabel: String = "Admin PIN",
    defaultValue: CharArray? = null,
    clearTextByDefault: Boolean = false,
): CharArray? = collectPin(
    requireContext(),
    title,
    minLength = ADMIN_PIN_MIN_LENGTH,
    tooShortRes = R.string.openpgp_admin_pin_too_short,
    inputType = PIN_NUMERIC_INPUT_TYPE,
    tag = tag,
    logLabel = logLabel,
    defaultValue = defaultValue,
    clearTextByDefault = clearTextByDefault,
)

@UiThread
suspend fun Fragment.collectUserPin(
    title: String,
    tag: String,
    logLabel: String = "User PIN",
    defaultValue: CharArray? = null,
    clearTextByDefault: Boolean = false,
): CharArray? = collectPin(
    requireContext(),
    title,
    minLength = USER_PIN_MIN_LENGTH,
    tooShortRes = R.string.openpgp_user_pin_too_short,
    inputType = PIN_NUMERIC_INPUT_TYPE,
    tag = tag,
    logLabel = logLabel,
    defaultValue = defaultValue,
    clearTextByDefault = clearTextByDefault,
)

@UiThread
private suspend fun Fragment.collectNewPin(
    label: String,
    minLength: Int,
    @StringRes tooShortRes: Int,
    tag: String,
): CharArray? {
    while (true) {
        val entered = collectPin(
            requireContext(),
            "Enter new Device $label PIN",
            minLength = minLength,
            tooShortRes = tooShortRes,
            inputType = PIN_NUMERIC_INPUT_TYPE,
            tag = tag,
            logLabel = "New $label PIN",
        ) ?: return null

        val confirm = getSecret(
            requireContext(),
            "Confirm new Device $label PIN",
            inputType = PIN_NUMERIC_INPUT_TYPE,
        ) ?: run {
            entered.fill('\u0000')
            Log.d(tag, "New $label PIN confirmation cancelled")
            return null
        }

        if (!confirm.contentEquals(entered)) {
            entered.fill('\u0000')
            confirm.fill('\u0000')
            Log.w(tag, "New $label PIN confirmation did not match — re-showing dialog")
            Toast.makeText(requireContext(), R.string.openpgp_new_pin_mismatch, Toast.LENGTH_SHORT).show()
            continue
        }

        confirm.fill('\u0000')
        Log.d(tag, "New $label PIN collected and confirmed (length=${entered.size})")
        return entered
    }
}

@UiThread
suspend fun Fragment.collectNewAdminPin(tag: String): CharArray? =
    collectNewPin("Admin", ADMIN_PIN_MIN_LENGTH, R.string.openpgp_admin_pin_too_short, tag)

@UiThread
suspend fun Fragment.collectNewUserPin(tag: String): CharArray? =
    collectNewPin("User", USER_PIN_MIN_LENGTH, R.string.openpgp_user_pin_too_short, tag)
