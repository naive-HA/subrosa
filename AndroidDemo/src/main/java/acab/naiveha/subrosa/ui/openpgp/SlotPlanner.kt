package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.openpgp.KeyRef
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import java.util.Date

enum class KeyValidity { VALID, EXPIRED, REVOKED }

internal data class KeyTraits(
    val isPrimary: Boolean,
    val keyFlags: Int,
    val canEncrypt: Boolean,
    val validity: KeyValidity = KeyValidity.VALID,
)

internal fun PGPPublicKey.validity(asOf: Date): KeyValidity = when {
    hasRevocation() -> KeyValidity.REVOKED
    expirationDate()?.before(asOf) == true -> KeyValidity.EXPIRED
    else -> KeyValidity.VALID
}

internal fun PGPPublicKey.toKeyTraits(asOf: Date) = KeyTraits(
    isPrimary = isMasterKey,
    keyFlags = keyFlags(),
    canEncrypt = isEncryptionKey,
    validity = validity(asOf),
)

enum class SkipReason { SLOT_FILLED, CERTIFY_ONLY, NO_CARD_ROLE, NO_PRIVATE_KEY }

data class KeyPlan(
    val slots: List<KeyRef> = emptyList(),
    val skipReason: SkipReason? = null,
    val blockedSlots: List<KeyRef> = emptyList(),
)

internal object SlotPlanner {

    private val ENCRYPT_ANY = KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE

    fun plan(keys: List<KeyTraits>): List<List<KeyRef>> {
        val subkeys = keys.indices.filter { !keys[it].isPrimary }
        val positional = assign(keys, subkeys)
        if (keys.all { it.validity == KeyValidity.VALID }) return positional

        val validFirst = assign(keys, subkeys.sortedBy { keys[it].validity != KeyValidity.VALID })
        return if (score(keys, positional) > score(keys, validFirst)) positional else validFirst
    }

    private fun score(keys: List<KeyTraits>, slots: List<List<KeyRef>>): Int {
        val byValidKeys = slots.withIndex().sumOf { (i, s) -> if (keys[i].validity == KeyValidity.VALID) s.size else 0 }
        return byValidKeys * 4 + slots.sumOf { it.size }
    }

    private fun assign(keys: List<KeyTraits>, subkeyOrder: List<Int>): List<List<KeyRef>> {
        val slots = List(keys.size) { mutableListOf<KeyRef>() }
        val primaryIndex = keys.indexOfFirst { it.isPrimary }
        val primary = keys.getOrNull(primaryIndex)

        val primaryCanSign = primary != null &&
            (primary.keyFlags == 0 || (primary.keyFlags and KeyFlags.SIGN_DATA) != 0)

        var sigTaken = false
        var decTaken = false
        var autTaken = false

        for (index in subkeyOrder) {
            val key = keys[index]
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

    fun explain(keys: List<KeyTraits>): List<KeyPlan> {
        val slots = plan(keys)
        return keys.mapIndexed { index, key ->
            if (slots[index].isNotEmpty()) {
                KeyPlan(slots = slots[index])
            } else {
                val wanted = wantedSlots(key)
                val reason = when {
                    wanted.isNotEmpty() -> SkipReason.SLOT_FILLED
                    key.keyFlags == KeyFlags.CERTIFY_OTHER -> SkipReason.CERTIFY_ONLY
                    else -> SkipReason.NO_CARD_ROLE
                }
                KeyPlan(skipReason = reason, blockedSlots = wanted)
            }
        }
    }

    private fun wantedSlots(key: KeyTraits): List<KeyRef> = buildList {
        val flags = key.keyFlags
        if ((flags and KeyFlags.SIGN_DATA) != 0) add(KeyRef.SIG)
        if ((flags and ENCRYPT_ANY) != 0 && (!key.isPrimary || key.canEncrypt)) add(KeyRef.DEC)
        if ((flags and KeyFlags.AUTHENTICATION) != 0) add(KeyRef.AUT)
        if (flags == 0 && !key.isPrimary) add(if (key.canEncrypt) KeyRef.DEC else KeyRef.AUT)
    }
}
