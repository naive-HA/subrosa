package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.openpgp.KeyRef
import org.bouncycastle.bcpg.sig.KeyFlags

internal data class KeyTraits(
    val isPrimary: Boolean,
    val keyFlags: Int,
    val canEncrypt: Boolean,
)

internal object SlotPlanner {

    private val ENCRYPT_ANY = KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE

    fun plan(keys: List<KeyTraits>): List<List<KeyRef>> {
        val slots = List(keys.size) { mutableListOf<KeyRef>() }
        val primaryIndex = keys.indexOfFirst { it.isPrimary }
        val primary = keys.getOrNull(primaryIndex)

        val primaryCanSign = primary != null &&
            (primary.keyFlags == 0 || (primary.keyFlags and KeyFlags.SIGN_DATA) != 0)

        var sigTaken = false
        var decTaken = false
        var autTaken = false

        for ((index, key) in keys.withIndex()) {
            if (key.isPrimary) continue
            val flags = key.keyFlags
            when {
                (flags and KeyFlags.AUTHENTICATION) != 0 && !autTaken -> {
                    slots[index].add(KeyRef.AUT)
                    autTaken = true
                }
                (flags and ENCRYPT_ANY) != 0 && !decTaken -> {
                    slots[index].add(KeyRef.DEC)
                    decTaken = true
                }
                flags == 0 && key.canEncrypt && !decTaken -> {
                    slots[index].add(KeyRef.DEC)
                    decTaken = true
                }
                flags == 0 && !key.canEncrypt && !autTaken -> {
                    slots[index].add(KeyRef.AUT)
                    autTaken = true
                }
            }
            if (!primaryCanSign && (flags and KeyFlags.SIGN_DATA) != 0 && !sigTaken) {
                slots[index].add(KeyRef.SIG)
                sigTaken = true
            }
        }

        if (primary != null) {
            val own = slots[primaryIndex]
            if (!sigTaken) own.add(KeyRef.SIG)
            if (!decTaken && (primary.keyFlags and ENCRYPT_ANY) != 0 && primary.canEncrypt) {
                own.add(KeyRef.DEC)
            }
            if (!autTaken && (primary.keyFlags and KeyFlags.AUTHENTICATION) != 0) {
                own.add(KeyRef.AUT)
            }
        }
        return slots
    }
}
