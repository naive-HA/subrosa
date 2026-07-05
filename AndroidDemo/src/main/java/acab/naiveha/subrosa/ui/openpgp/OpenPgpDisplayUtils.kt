package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.openpgp.AlgorithmAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun AlgorithmAttributes.toDisplayString(): String = try {
    when (javaClass.simpleName) {
        "Rsa" -> "RSA-${
            javaClass.getDeclaredField("nLen").also { it.isAccessible = true }.getInt(this)
        }"
        "Ec"  -> javaClass.getDeclaredField("curve")
            .also { it.isAccessible = true }
            .get(this)?.toString() ?: "EC"
        else  -> javaClass.simpleName ?: "Unknown"
    }
} catch (_: Exception) { javaClass.simpleName ?: "Unknown" }

internal fun ByteArray.toFingerprintDisplay(): String =
    joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

internal fun Int.toDateDisplay(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(toLong() * 1000L))

internal fun String.fromNameDo(): String {
    if (isBlank()) return "—"
    val parts = trim().split("<<", limit = 2)
    return if (parts.size == 2) {
        val given   = parts[1].replace('<', ' ').trim()
            .split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar(Char::uppercase) }
        val surname = parts[0].trim().lowercase().replaceFirstChar(Char::uppercase)
        "$given $surname".trim().ifBlank { "—" }
    } else {
        trim().replace('<', ' ').ifBlank { "—" }
    }
}

internal fun OpenPgpCardInfo.toDisplayText(): String = buildString {
    if (cardholderName != "—") appendLine("Name        $cardholderName")
    if (login          != "—") appendLine("Login       $login")
    appendLine()

    for (slot in slots) {
        val mark = if (slot.hasKey) "✓" else "·"
        appendLine("── ${slot.ref.name}  $mark  ${slot.algorithm}")
        if (slot.hasKey) {
            appendLine("   ${slot.created}")
            appendLine("   ${slot.fingerprint}")
        } else {
            appendLine("   (empty slot)")
        }
        appendLine()
    }

    appendLine("── PIN retries")
    appendLine("   User         $userPinRetries")
    appendLine("   Reset code   $resetRetries")
    append(  "   Admin        $adminPinRetries")
}
