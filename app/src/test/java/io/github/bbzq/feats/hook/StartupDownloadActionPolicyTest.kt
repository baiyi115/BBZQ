package io.github.bbzq.feats.hook

import org.junit.Assert.*
import org.junit.Test

class StartupDownloadActionPolicyTest {
    private interface Action
    private class Automatic : Action
    private class Manual : Action

    @Test fun mixedBatchPreservesArrayTypeIdentityAndOrderOfOtherActions() {
        val first = Manual()
        val last = Manual()
        val original = arrayOf<Action?>(first, Automatic(), null, last, Automatic())
        val retained = StartupDownloadActionPolicy.filter(original, Automatic::class.java.name)
        assertEquals(original.javaClass, retained.javaClass)
        assertArrayEquals(arrayOf<Action?>(first, null, last), retained)
        assertSame(first, retained[0])
        assertSame(last, retained[2])
        assertEquals(5, original.size)
        assertTrue(original[1] is Automatic)
    }

    @Test fun unrelatedAndEmptyBatchesAreReturnedWithoutCopying() {
        val manual = arrayOf<Action>(Manual())
        val empty = emptyArray<Action>()
        assertSame(manual, StartupDownloadActionPolicy.filter(manual, Automatic::class.java.name))
        assertSame(empty, StartupDownloadActionPolicy.filter(empty, Automatic::class.java.name))
    }

    @Test fun automaticOnlyBatchBecomesEmptyWithOriginalComponentType() {
        val original = arrayOf<Action>(Automatic(), Automatic())
        val retained = StartupDownloadActionPolicy.filter(original, Automatic::class.java.name)
        assertTrue(retained.isEmpty())
        assertEquals(original.javaClass, retained.javaClass)
    }
}
