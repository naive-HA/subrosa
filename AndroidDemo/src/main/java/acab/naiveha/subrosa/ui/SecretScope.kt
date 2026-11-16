package acab.naiveha.subrosa.ui

class SecretScope {
    private val held = ArrayList<CharArray>()

    fun hold(secret: CharArray?): CharArray? {
        if (secret != null) held.add(secret)
        return secret
    }

    fun handOver(vararg secrets: CharArray) {
        held.removeAll { candidate -> secrets.any { it === candidate } }
    }

    @PublishedApi
    internal fun wipe() {
        held.forEach { it.fill('\u0000') }
        held.clear()
    }
}

inline fun <R> withSecrets(block: SecretScope.() -> R): R {
    val scope = SecretScope()
    try {
        return scope.block()
    } finally {
        scope.wipe()
    }
}
