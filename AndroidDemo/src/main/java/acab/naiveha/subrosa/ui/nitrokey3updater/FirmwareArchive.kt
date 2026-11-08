package acab.naiveha.subrosa.ui.nitrokey3updater

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import android.util.Log

internal enum class Nk3Variant(
    val displayName: String,
    val manifestKey: String,
    val imageExtension: String,
) {
    LPC55("LPC55 (NXP)", "lpc55", ".sb2"),
    NRF52("nRF52 (Nordic)", "nrf52", ".zip"),
}

internal class FirmwareException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class FirmwareImage(
    val variant: Nk3Variant,
    val fileName: String,
    val data: ByteArray,
    val sha256: String,
    val releaseVersion: String?,
    val checksumVerified: Boolean,
    val sb2Header: Sb2Header?,
) {
    val displayVersion: String?
        get() = releaseVersion ?: sb2Header?.productVersion?.let { "v$it" }
}

internal object FirmwareArchive {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MANIFEST_NAME = "manifest.json"
    private const val CHECKSUMS_NAME = "sha256sums"

    private val CHECKSUM_LINE = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+)$")

    fun validate(archiveBytes: ByteArray) {
        val entries = try {
            readEntries(archiveBytes)
        } catch (e: ZipException) {
            val msg = "The imported file is not a valid zip archive, or it is damaged (${e.message})."
            Log.e("FirmwareArchive", msg, e)
            throw FirmwareException(msg, e)
        } catch (e: IOException) {
            val msg = "The firmware archive could not be read: ${e.message}"
            Log.e("FirmwareArchive", msg, e)
            throw FirmwareException(msg, e)
        }

        if (entries.isEmpty()) {
            val msg = "The firmware archive contains no files."
            Log.e("FirmwareArchive", msg)
            throw FirmwareException(msg)
        }

        val manifest = findEntry(entries, MANIFEST_NAME)?.let { parseManifest(String(it.bytes, Charsets.UTF_8)) }
        val checksums = findEntry(entries, CHECKSUMS_NAME)?.let { parseChecksums(String(it.bytes, Charsets.UTF_8)) }.orEmpty()

        for (entry in entries) {
            val name = baseName(entry.name).lowercase()
            val expected = checksums[name]
            if (expected != null) {
                val actual = sha256Hex(entry.bytes)
                if (expected != actual) {
                    val msg = "Checksum mismatch for ${baseName(entry.name)}: expected $expected, got $actual. The download is damaged."
                    Log.e("FirmwareArchive", msg)
                    throw FirmwareException(msg)
                }
            }
        }

        val checkedVariants = mutableListOf<Nk3Variant>()
        if (manifest != null && manifest.images.isNotEmpty()) {
            for (variant in Nk3Variant.entries) {
                if (manifest.images.containsKey(variant.manifestKey)) {
                    checkedVariants.add(variant)
                }
            }
        } else {
            for (variant in Nk3Variant.entries) {
                val candidateCount = entries.count { baseName(it.name).endsWith(variant.imageExtension, ignoreCase = true) }
                if (candidateCount > 0) {
                    checkedVariants.add(variant)
                }
            }
        }

        if (checkedVariants.isEmpty()) {
            val msg = "The archive contains no firmware image for any supported Nitrokey 3 chip."
            Log.e("FirmwareArchive", "Validation failed: $msg")
            throw FirmwareException(msg)
        }

        for (variant in checkedVariants) {
            try {
                read(entries, variant)
            } catch (e: FirmwareException) {
                val msg = e.message ?: "The firmware image in the archive is invalid."
                Log.e("FirmwareArchive", "Validation failed for ${variant.displayName}: $msg", e)
                throw FirmwareException(msg, e)
            }
        }
    }

    fun read(archive: File, variant: Nk3Variant): FirmwareImage {
        return try {
            read(archive.readBytes(), variant)
        } catch (e: FirmwareException) {
            throw e
        } catch (e: Exception) {
            throw FirmwareException("The firmware archive could not be read: ${e.message}", e)
        }
    }

    fun read(archiveBytes: ByteArray, variant: Nk3Variant): FirmwareImage {
        val entries = try {
            readEntries(archiveBytes)
        } catch (e: ZipException) {
            throw FirmwareException("The imported file is not a valid zip archive, or it is damaged (${e.message}).", e)
        } catch (e: IOException) {
            throw FirmwareException("The firmware archive could not be read: ${e.message}", e)
        }
        return read(entries, variant)
    }

    private class InMemEntry(val name: String, val bytes: ByteArray)

    private fun readEntries(archiveBytes: ByteArray): List<InMemEntry> {
        val list = mutableListOf<InMemEntry>()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var count: Int
                    var total = 0
                    while (zis.read(buf).also { count = it } > 0) {
                        total += count
                        if (total > MAX_IMAGE_BYTES) {
                            throw FirmwareException("${baseName(entry.name)} in the archive is unexpectedly large.")
                        }
                        out.write(buf, 0, count)
                    }
                    list.add(InMemEntry(entry.name, out.toByteArray()))
                }
                zis.closeEntry()
            }
        }
        return list
    }

    private fun read(entries: List<InMemEntry>, variant: Nk3Variant): FirmwareImage {
        val manifest = findEntry(entries, MANIFEST_NAME)?.let { parseManifest(String(it.bytes, Charsets.UTF_8)) }
        val checksums = findEntry(entries, CHECKSUMS_NAME)?.let { parseChecksums(String(it.bytes, Charsets.UTF_8)) }.orEmpty()

        val entry = selectImage(entries, manifest, variant)
        val fileName = baseName(entry.name)
        val data = entry.bytes
        if (data.isEmpty()) {
            val msg = "The firmware file $fileName in the archive is empty."
            Log.e("FirmwareArchive", msg)
            throw FirmwareException(msg)
        }

        val sha256 = sha256Hex(data)
        val expected = checksums[fileName.lowercase()]
        if (expected != null && expected != sha256) {
            val msg = "Checksum mismatch for $fileName: expected $expected, got $sha256"
            Log.e("FirmwareArchive", msg)
            throw FirmwareException(
                "The checksum of $fileName does not match the archive's sha256sums. " +
                    "The download is damaged; download the firmware archive again."
            )
        }

        val sb2Header = try {
            when (variant) {
                Nk3Variant.LPC55 -> Sb2Header.parse(data)
                Nk3Variant.NRF52 -> {
                    if (!data.startsWithZipMagic()) {
                        throw FirmwareException("The firmware package $fileName is invalid.")
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("FirmwareArchive", "Failed to parse SB2/DFU header for $fileName: ${e.message}", e)
            throw e
        }
        if (sb2Header != null && sb2Header.productVersion == null) {
            Log.w("FirmwareArchive", "The SB2 header of $fileName has no valid BCD product version; ignoring it.")
        }
        return FirmwareImage(
            variant = variant,
            fileName = fileName,
            data = data,
            sha256 = sha256,
            releaseVersion = manifest?.version,
            checksumVerified = expected != null,
            sb2Header = sb2Header,
        )
    }

    private class Manifest(val version: String?, val images: Map<String, String>)

    private fun selectImage(entries: List<InMemEntry>, manifest: Manifest?, variant: Nk3Variant): InMemEntry {
        if (manifest != null && manifest.images.isNotEmpty()) {
            val name = manifest.images[variant.manifestKey]
                ?: throw FirmwareException("This archive contains no ${variant.displayName} firmware.")
            return findEntry(entries, name)
                ?: throw FirmwareException("The archive's manifest lists ${baseName(name)}, but the file is missing.")
        }
        val candidates = entries.filter { baseName(it.name).endsWith(variant.imageExtension, ignoreCase = true) }
        return when (candidates.size) {
            1 -> candidates[0]
            0 -> throw FirmwareException(
                "The archive does not contain a ${variant.displayName} firmware file (*${variant.imageExtension})."
            )
            else -> throw FirmwareException(
                "The archive contains several *${variant.imageExtension} files; " +
                    "it is unclear which one is the ${variant.displayName} firmware."
            )
        }
    }

    private fun findEntry(entries: List<InMemEntry>, name: String): InMemEntry? {
        val wanted = baseName(name)
        val matches = entries.filter { baseName(it.name).equals(wanted, ignoreCase = true) }
        if (matches.size > 1) throw FirmwareException("The archive contains $wanted more than once.")
        return matches.firstOrNull()
    }

    private fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun parseManifest(text: String): Manifest {
        val root = try {
            MiniJson.parse(text)
        } catch (e: MiniJsonException) {
            throw FirmwareException("The archive's manifest.json is not valid JSON.", e)
        }
        val obj = root as? Map<*, *> ?: throw FirmwareException("The archive's manifest.json is not a JSON object.")
        val version = (obj["version"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val images = HashMap<String, String>()
        (obj["images"] as? Map<*, *>)?.forEach { (key, value) ->
            if (key is String && value is String && value.isNotBlank()) images[key.lowercase()] = value
        }
        return Manifest(version, images)
    }

    private fun parseChecksums(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val match = CHECKSUM_LINE.matchEntire(line.trim()) ?: continue
            result[baseName(match.groupValues[2].trim()).lowercase()] = match.groupValues[1].lowercase()
        }
        return result
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWithZipMagic(): Boolean {
        if (size < 4 || this[0] != 'P'.code.toByte() || this[1] != 'K'.code.toByte()) return false
        return (this[2] == 3.toByte() && this[3] == 4.toByte()) || (this[2] == 5.toByte() && this[3] == 6.toByte())
    }
}
