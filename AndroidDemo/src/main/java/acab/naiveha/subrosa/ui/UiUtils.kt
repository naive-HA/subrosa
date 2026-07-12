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
    defaultValue: String? = null,
    inputType: Int? = null,
    clearTextByDefault: Boolean = false
) = suspendCoroutine { cont ->
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin, null).apply {
        val textInputLayout = findViewById<TextInputLayout>(R.id.dialog_pin_textinputlayout)
        val editText = findViewById<EditText>(R.id.dialog_pin_edittext)
        textInputLayout.hint = context.getString(hint)

        inputType?.let { editText.inputType = it }
        if (clearTextByDefault) {
            editText.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()
        }

        defaultValue?.let {
            editText.setText(it)
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
                cont.resume(view.findViewById<EditText>(R.id.dialog_pin_edittext).text.toString())
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
    defaultValue: String? = null,
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

        if (entered.length < minLength) {
            Log.w(tag, "$logLabel too short (${entered.length} < $minLength) — re-showing dialog")
            Toast.makeText(context, tooShortRes, Toast.LENGTH_SHORT).show()
            continue
        }

        Log.d(tag, "$logLabel collected (length=${entered.length})")
        return entered.toCharArray()
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

fun Fragment.showOpenPgpAppletResetDialog(
    tag: String,
    onConfirmed: () -> Unit,
    onCancelled: () -> Unit = {},
) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.openpgp_reset_title)
        .setMessage(R.string.openpgp_reset_message)
        .setPositiveButton(R.string.openpgp_reset_confirm) { _, _ ->
            Log.d(tag, "OpenPGP applet reset confirmed")
            onConfirmed()
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            Log.d(tag, "OpenPGP applet reset cancelled")
            onCancelled()
        }
        .setOnCancelListener {
            Log.d(tag, "OpenPGP applet reset dialog dismissed")
            onCancelled()
        }
        .show()
}
