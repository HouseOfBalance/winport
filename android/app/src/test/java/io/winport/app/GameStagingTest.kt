package io.winport.app

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameStagingTest {
    @get:Rule val temp=TemporaryFolder()
    private val files=listOf(StageFile("boot","EBOOT.PBP",1),StageFile("pak","ID1/PAK0.PAK",3))
    private fun copy(target: File, data: String="pak") = GameStaging.copy(target,files,"EBOOT.PBP",open={ (if(it=="boot") "b" else data).byteInputStream() })

    @Test fun isolatedSlimFolderMustNotLaunchWithoutSharedFiles() {
        assertTrue(isolatedSlimEboot("primary:Download/Kurok/SLIM EBOOT",listOf("EBOOT.PBP")))
        assertFalse(isolatedSlimEboot("primary:Download/Kurok",listOf("EBOOT.PBP","ID1/PAK0.PAK")))
        assertFalse(isolatedSlimEboot("primary:Download/Kurok/SLIM EBOOT",listOf("EBOOT.PBP","ID1/PAK0.PAK")))
    }

    @Test fun restagesSameSizeChangesAndRemovesStaleFiles() {
        val target=File(temp.root,"game")
        val updates=mutableListOf<StageProgress>()
        GameStaging.copy(target,files,"EBOOT.PBP",directories=listOf("empty"),open={ (if(it=="boot") "b" else "pak").byteInputStream() },progress={updates.add(it)})
        assertEquals("pak",File(target,"ID1/PAK0.PAK").readText())
        assertTrue(File(target,"empty").isDirectory)
        assertEquals(4L,updates.last().bytes)
        File(target,"stale").writeText("old")
        copy(target,"new")
        assertEquals("new",File(target,"ID1/PAK0.PAK").readText())
        assertFalse(File(target,"stale").exists())
    }

    @Test fun shortOversizeUnreadableAndChangingSourcesNeverPublish() {
        val target=File(temp.root,"game")
        copy(target)
        for(data in listOf("", "pa", "toolong")) {
            assertThrows(IllegalStateException::class.java) { copy(target,data) }
            assertEquals("pak",File(target,"ID1/PAK0.PAK").readText())
        }
        assertThrows(IOException::class.java) { GameStaging.copy(target,files,"EBOOT.PBP",open={throw IOException("denied")}) }
        assertThrows(IllegalStateException::class.java) { GameStaging.copy(target,files,"EBOOT.PBP",open={ (if(it=="boot") "b" else "pak").byteInputStream() },verifySource={error("changed")}) }
        assertEquals("pak",File(target,"ID1/PAK0.PAK").readText())
        assertEquals(listOf("game"),temp.root.list()!!.toList())
    }

    @Test fun emptyMissingUnknownSizeUnsafeAndCaseCollisionsFail() {
        for(list in listOf(emptyList(),listOf(StageFile("x","data",0)),listOf(StageFile("x","EBOOT.PBP",-1)),listOf(StageFile("x","EBOOT.PBP",0),StageFile("x","../secret",0)),listOf(StageFile("x","EBOOT.PBP",0),StageFile("x","eboot.pbp",0)))) {
            assertThrows(IllegalStateException::class.java) { GameStaging.copy(File(temp.root,"game"),list,"EBOOT.PBP",open={"".byteInputStream()}) }
        }
        assertFalse(File(temp.root,"game").exists())
    }
}
