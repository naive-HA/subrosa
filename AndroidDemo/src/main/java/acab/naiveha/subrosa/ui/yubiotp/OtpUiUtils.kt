package acab.naiveha.subrosa.ui.yubiotp

import android.widget.EditText
import androidx.fragment.app.Fragment
import acab.naiveha.subrosa.R
import com.google.android.material.textfield.TextInputLayout
import com.yubico.yubikit.yubiotp.Slot


fun Fragment.resolveSlot(checkedId: Int, oneId: Int, twoId: Int): Slot =
    when (checkedId) {
        oneId -> Slot.ONE
        twoId -> Slot.TWO
        else -> throw IllegalStateException(getString(R.string.otp_no_slot_selected))
    }

fun TextInputLayout.bindRandomGenerator(editText: EditText, generator: () -> String) {
    fun regenerate() {
        editText.setText(generator())
    }
    setEndIconOnClickListener { regenerate() }
    regenerate()
}

fun Fragment.describeError(e: Exception): String = when (e) {
    is Keyboard.UnknownKeyboardException -> getString(R.string.otp_unknown_keyboard_desc, e.keyboard)
    is Keyboard.IllegalCharacterException -> getString(R.string.otp_illegal_char_desc, e.char.toString())
    is Keyboard.UnknownScanCodeException -> getString(R.string.otp_unknown_scan_code_desc)
    else -> e.message ?: e.toString()
}

inline fun Fragment.runValidated(viewModel: OtpViewModel, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        viewModel.postResult(Result.failure(Exception(describeError(e), e)))
    }
}
