package acab.naiveha.subrosa.ui.nitrokey3updater

internal data class DevicePreflight(
    val version: FirmwareVersion?,
    val internalFreeBlocks: Int?,
    val externalFreeBlocks: Int? = null,
    val initStatus: Int? = null,
) {
    fun summary(): String =
        "version=${version ?: "unknown"}, internalFreeBlocks=${internalFreeBlocks ?: "unknown"}, " +
            "externalFreeBlocks=${externalFreeBlocks ?: "unknown"}, " +
            "initStatus=${initStatus?.let { "0x%02X".format(it) } ?: "unknown"}"

    companion object {
        fun from(versionText: String?, status: StatusRead): DevicePreflight {
            val read = (status as? StatusRead.Value)?.status
            return DevicePreflight(
                version = FirmwareVersion.parse(versionText),
                internalFreeBlocks = read?.internalFreeBlocks,
                externalFreeBlocks = read?.externalFreeBlocks,
                initStatus = read?.initStatus,
            )
        }
    }
}

internal sealed interface FlashBlock {
    class ImageUnverified(val fileName: String) : FlashBlock

    class NotEnoughSpace(
        val freeBlocks: Int,
        val neededBlocks: Int,
        val installed: FirmwareVersion,
        val target: FirmwareVersion,
    ) : FlashBlock
}

internal object FirmwarePolicy {

    val IFS_MIGRATION_V2 = FirmwareVersion(1, 8, 2)

    const val MIN_FREE_BLOCKS_FOR_IFS_MIGRATION = 5

    fun needsIfsMigration(installed: FirmwareVersion?, target: FirmwareVersion?): Boolean =
        installed != null && target != null && installed < IFS_MIGRATION_V2 && target >= IFS_MIGRATION_V2

    fun flashBlock(image: FirmwareImage?, preflight: DevicePreflight?): FlashBlock? {
        if (image == null) return null
        if (!image.checksumVerified) return FlashBlock.ImageUnverified(image.fileName)
        return notEnoughSpace(image.version, preflight)
    }

    fun notEnoughSpace(target: FirmwareVersion?, preflight: DevicePreflight?): FlashBlock.NotEnoughSpace? {
        val installed = preflight?.version ?: return null
        val free = preflight.internalFreeBlocks ?: return null
        if (target == null || !needsIfsMigration(installed, target)) return null
        if (free >= MIN_FREE_BLOCKS_FOR_IFS_MIGRATION) return null
        return FlashBlock.NotEnoughSpace(free, MIN_FREE_BLOCKS_FOR_IFS_MIGRATION, installed, target)
    }
}
