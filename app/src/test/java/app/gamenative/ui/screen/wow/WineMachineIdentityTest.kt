package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineMachineIdentityTest {

    private val dir: File = Files.createTempDirectory("identity").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun generatedValues_lookLikeAWindowsPc() {
        val id = WineMachineIdentity.loadOrCreate(dir)
        assertTrue(id.machineGuid, Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id.machineGuid))
        assertTrue(id.computerName, Regex("DESKTOP-[A-Z0-9]{7}").matches(id.computerName))
        assertEquals(15, id.computerName.length)
        assertTrue(id.volumeSerial, Regex("[0-9A-F]{8}").matches(id.volumeSerial))
        assertTrue(id.productId, Regex("\\d{5}-\\d{5}-\\d{5}-AA\\d{3}").matches(id.productId))
        assertNotEquals(SHIPPED_GUID, id.machineGuid)
    }

    @Test
    fun identity_isStableAcrossLoads() {
        val first = WineMachineIdentity.loadOrCreate(dir)
        assertEquals(first, WineMachineIdentity.loadOrCreate(dir))
    }

    @Test
    fun installations_getDifferentIdentities() {
        val other = Files.createTempDirectory("identity2").toFile()
        try {
            val a = WineMachineIdentity.loadOrCreate(dir)
            val b = WineMachineIdentity.loadOrCreate(other)
            assertNotEquals(a.machineGuid, b.machineGuid)
            assertNotEquals(a.computerName, b.computerName)
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun malformedValue_isReplacedAndOthersKept() {
        val first = WineMachineIdentity.loadOrCreate(dir)
        val file = File(dir, WineMachineIdentity.FILE_NAME)
        file.writeText(file.readText().replace(Regex("computerName=.*"), "computerName=192.168.1.20"))
        val second = WineMachineIdentity.loadOrCreate(dir)
        assertEquals(first.machineGuid, second.machineGuid)
        assertNotEquals("192.168.1.20", second.computerName)
        assertEquals(second, WineMachineIdentity.loadOrCreate(dir))
    }

    @Test
    fun applyTo_replacesTheShippedIdentity() {
        val wine = File(dir, ".wine").apply { mkdirs() }
        File(wine, "system.reg").writeText(SYSTEM_REG)
        val id = WineMachineIdentity.loadOrCreate(dir)

        id.applyTo(wine)

        val reg = File(wine, "system.reg").readText()
        assertFalse(reg.contains(SHIPPED_GUID))
        assertFalse(reg.contains("00330-50000-00000-AAOEM"))
        assertFalse(reg.contains("dword:4be5019a"))
        assertFalse(reg.contains("\"ComputerName\"=\"LOCALHOST\""))
        assertTrue(reg.contains("\"MachineGuid\"=\"${id.machineGuid}\""))
        assertEquals(4, Regex("\"ProductId\"=\"${id.productId}\"").findAll(reg).count())
        assertEquals(2, Regex("\"InstallDate\"=dword:%08x".format(id.installDate)).findAll(reg).count())
        assertTrue(reg.contains("\"ComputerName\"=\"${id.computerName}\""))
        assertTrue(reg.contains("\"Hostname\"=\"${id.computerName}\""))
        assertEquals(id.volumeSerial.lowercase(), File(wine, "drive_c/.windows-serial").readText().trim())
    }

    @Test
    fun applyTo_withoutPrefix_doesNothing() {
        val wine = File(dir, ".wine")
        WineMachineIdentity.loadOrCreate(dir).applyTo(wine)
        assertFalse(wine.exists())
    }

    private companion object {
        const val SHIPPED_GUID = "87447f37-0be9-4ca4-8034-20645c186c64"

        // The identity keys as they appear in the prefix pack's system.reg.
        val SYSTEM_REG = """
            WINE REGISTRY Version 2
            ;; All keys relative to \\Machine

            #arch=win64

            [Software\\Microsoft\\Cryptography] 1753725033
            #time=1dbffb5c9f0b46e
            "MachineGuid"="$SHIPPED_GUID"

            [Software\\Microsoft\\Windows\\CurrentVersion] 1753725046
            #time=1dbffb5ca6eb8b8
            "ProductId"="00330-50000-00000-AAOEM"

            [Software\\Microsoft\\Windows NT\\CurrentVersion] 1753725046
            #time=1dbffb5ca6eb8b8
            "InstallDate"=dword:4be5019a
            "ProductId"="00330-50000-00000-AAOEM"

            [Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion] 1753725046
            #time=1dbffb5ca6eb8b8
            "ProductId"="00330-50000-00000-AAOEM"

            [Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion] 1753725044
            #time=1dbffb5ca4c2f1c
            "InstallDate"=dword:4be5019a
            "ProductId"="00330-50000-00000-AAOEM"

            [System\\ControlSet001\\Control\\ComputerName\\ComputerName] 1753725024
            #time=1dbffb5c967f9e8
            "ComputerName"="LOCALHOST"

            [System\\ControlSet001\\Services\\Tcpip\\Parameters] 1753725033
            #time=1dbffb5c9f0b46e
            "Hostname"="localhost"

        """.trimIndent()
    }
}
