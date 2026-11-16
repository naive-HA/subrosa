package acab.naiveha.subrosa.ui.nitrokey3updater

internal data class FirmwareVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<FirmwareVersion> {
    override fun compareTo(other: FirmwareVersion): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        else -> patch.compareTo(other.patch)
    }
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        private val PATTERN = Regex("""[vV]?(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:[-+].*)?""")
        fun parse(text: String?): FirmwareVersion? {
            val match = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (major, minor, patch) = match.destructured
            return FirmwareVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
