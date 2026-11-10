package app.gamenative.ui.screen.wow

import com.winlator.core.WineRegistryEditor
import java.io.File
import java.security.SecureRandom
import java.util.Properties
import java.util.UUID
import timber.log.Timber

/**
 * The Windows machine identity the WoW container presents.
 *
 * The prefix pack ships one fixed MachineGuid, ProductId and InstallDate, so every installation of the app looks like
 * the same Windows PC. The DNS shim also reported the device's IP address as the hostname, which wineboot turns into
 * the Windows ComputerName, so that PC's name changed with every network. Battle.net's anti-fraud blocks sign-ins
 * from that pattern with BLZ51914400 (status 14400).
 *
 * Each installation now generates its own identity once, keeps it across restarts and updates, and writes it into the
 * prefix before every launch. The shim reports [computerName] through [HOSTNAME_ENV].
 */
data class WineMachineIdentity(
    val machineGuid: String,
    val computerName: String,
    val volumeSerial: String,
    val productId: String,
    val installDate: Long,
) {
    companion object {
        const val FILE_NAME = "wow_identity.properties"

        /** Read by libgamenative_dns_v4mapped.so's gethostname(). */
        const val HOSTNAME_ENV = "GAMENATIVE_HOSTNAME"

        private const val KEY_MACHINE_GUID = "machineGuid"
        private const val KEY_COMPUTER_NAME = "computerName"
        private const val KEY_VOLUME_SERIAL = "volumeSerial"
        private const val KEY_PRODUCT_ID = "productId"
        private const val KEY_INSTALL_DATE = "installDate"

        private const val NAME_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private val COMPUTER_NAME = Regex("DESKTOP-[A-Z0-9]{7}")
        private val VOLUME_SERIAL = Regex("[0-9A-F]{8}")
        private val PRODUCT_ID = Regex("\\d{5}-\\d{5}-\\d{5}-AA\\d{3}")
        private val GUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        // Install dates between 2023-01-01 and 2026-01-01, like a PC set up a while ago.
        private const val INSTALL_DATE_FROM = 1_672_531_200L
        private const val INSTALL_DATE_TO = 1_767_225_600L

        /** The identity saved in [dir], generating and saving any value that is missing or malformed. */
        fun loadOrCreate(dir: File, random: SecureRandom = SecureRandom()): WineMachineIdentity {
            val file = File(dir, FILE_NAME)
            val props = Properties()
            if (file.isFile) {
                runCatching { file.inputStream().use(props::load) }.onFailure { Timber.w(it, "Unreadable $FILE_NAME") }
            }
            val identity = WineMachineIdentity(
                machineGuid = props.getProperty(KEY_MACHINE_GUID)?.takeIf(GUID::matches)
                    ?: UUID(random.nextLong(), random.nextLong()).let(::randomUuid),
                computerName = props.getProperty(KEY_COMPUTER_NAME)?.takeIf(COMPUTER_NAME::matches)
                    ?: "DESKTOP-" + (1..7).map { NAME_CHARS[random.nextInt(NAME_CHARS.length)] }.joinToString(""),
                volumeSerial = props.getProperty(KEY_VOLUME_SERIAL)?.takeIf(VOLUME_SERIAL::matches)
                    ?: String.format("%08X", random.nextInt()),
                productId = props.getProperty(KEY_PRODUCT_ID)?.takeIf(PRODUCT_ID::matches)
                    ?: String.format(
                        "%05d-%05d-%05d-AA%03d",
                        random.nextInt(100_000),
                        random.nextInt(100_000),
                        random.nextInt(100_000),
                        random.nextInt(1_000),
                    ),
                installDate = props.getProperty(KEY_INSTALL_DATE)?.toLongOrNull()
                    ?.takeIf { it in INSTALL_DATE_FROM until INSTALL_DATE_TO }
                    ?: (INSTALL_DATE_FROM + (random.nextDouble() * (INSTALL_DATE_TO - INSTALL_DATE_FROM)).toLong()),
            )
            val saved = Properties().apply {
                setProperty(KEY_MACHINE_GUID, identity.machineGuid)
                setProperty(KEY_COMPUTER_NAME, identity.computerName)
                setProperty(KEY_VOLUME_SERIAL, identity.volumeSerial)
                setProperty(KEY_PRODUCT_ID, identity.productId)
                setProperty(KEY_INSTALL_DATE, identity.installDate.toString())
            }
            if (saved != props) {
                dir.mkdirs()
                file.outputStream().use { saved.store(it, "Windows identity of the WoW container. Keep it stable.") }
            }
            return identity
        }

        /** A version 4 UUID built from [bits], as Windows generates for MachineGuid. */
        private fun randomUuid(bits: UUID): String {
            val msb = (bits.mostSignificantBits and 0xF000L.inv()) or 0x4000L
            val lsb = (bits.leastSignificantBits and (0x3L shl 62).inv()) or (0x2L shl 62)
            return UUID(msb, lsb).toString()
        }
    }

    /** Writes this identity into the Wine prefix at [wineDir] (the container's .wine). Wine must not be running. */
    fun applyTo(wineDir: File) {
        val systemReg = File(wineDir, "system.reg")
        if (!systemReg.isFile) {
            Timber.w("No system.reg in $wineDir, machine identity not applied")
            return
        }
        WineRegistryEditor(systemReg).use { reg ->
            reg.setStringValue("Software\\Microsoft\\Cryptography", "MachineGuid", machineGuid)
            for (prefix in listOf("Software", "Software\\Wow6432Node")) {
                reg.setStringValue("$prefix\\Microsoft\\Windows\\CurrentVersion", "ProductId", productId)
                reg.setStringValue("$prefix\\Microsoft\\Windows NT\\CurrentVersion", "ProductId", productId)
                reg.setDwordValue("$prefix\\Microsoft\\Windows NT\\CurrentVersion", "InstallDate", installDate.toInt())
            }
            // wineboot refreshes these from gethostname(), which the shim answers with the same name.
            for (key in listOf("ComputerName", "ActiveComputerName")) {
                reg.setStringValue("System\\ControlSet001\\Control\\ComputerName\\$key", "ComputerName", computerName)
            }
            reg.setStringValue("System\\ControlSet001\\Services\\Tcpip\\Parameters", "Hostname", computerName)
            reg.setStringValue("System\\ControlSet001\\Services\\Tcpip\\Parameters", "NV Hostname", computerName)
        }
        // Wine reads a drive's volume serial number from .windows-serial in its root.
        File(wineDir, "drive_c/.windows-serial").apply {
            parentFile?.mkdirs()
            writeText("${volumeSerial.lowercase()}\n")
        }
    }
}
