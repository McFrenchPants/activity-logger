package com.mcfrenchpants.activityledger.ui.tags

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side tests of [TagsViewModel] over the REAL ledger (in-memory Room), the real
 * [com.mcfrenchpants.activityledger.core.domain.services.TagManagementService] and entries saved
 * through the real orchestrator. Tag meaning is never re-invented here.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModelTest {

    private val world = TagsWorld()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun idle() = scheduler.advanceUntilIdle()

    private fun started(): TagsViewModel = TagsViewModel(world.service).also {
        it.onStart()
        idle()
    }

    private fun TagsViewModel.ui() = state.value

    /** Hot tub + Furnace share "Change filter"; Hot tub is also "Clean". */
    private fun seedSomeTags() {
        world.log("Hot tub", "Change filter")
        world.log("Hot tub", "Clean")
        world.log("Furnace", "Change filter")
    }

    @Test
    fun listsEachKindWithCountsAndAliases() {
        seedSomeTags()
        val vm = started()
        assertTrue(vm.ui().loaded)
        val subjects = vm.ui().subjects.associateBy { it.name }
        assertEquals(2, subjects.getValue("Hot tub").pairCount)
        assertEquals(1, subjects.getValue("Furnace").pairCount)
        val actions = vm.ui().actions.associateBy { it.name }
        assertEquals(2, actions.getValue("Change filter").pairCount)
        assertEquals(1, actions.getValue("Clean").pairCount)
        assertEquals(vm.ui().subjects, vm.ui().tags)
    }

    @Test
    fun kindSwitchShowsTheOtherList() {
        seedSomeTags()
        val vm = started()
        assertEquals(TagKind.SUBJECT, vm.ui().kind)
        vm.selectKind(TagKind.ACTION)
        assertEquals(TagKind.ACTION, vm.ui().kind)
        assertEquals(setOf("Change filter", "Clean"), vm.ui().tags.map { it.name }.toSet())
    }

    @Test
    fun emptyLedgerLoadsEmptyLists() {
        val vm = started()
        assertTrue(vm.ui().loaded)
        assertFalse(vm.ui().loadFailed)
        assertTrue(vm.ui().tags.isEmpty())
    }

    @Test
    fun renameHappyPathRefreshesAndKeepsOldNameAsAlias() {
        seedSomeTags()
        val vm = started()
        val id = world.subjectId("Furnace")
        vm.openRename(id)
        assertEquals("Furnace", vm.ui().rename?.text)
        vm.onRenameText("  Boiler ")
        assertTrue(vm.ui().rename!!.canSave)
        vm.saveRename()
        idle()
        assertNull(vm.ui().rename)
        assertEquals(TagsNotice.Renamed, vm.ui().notice)
        val renamed = vm.ui().subjects.single { it.id == id }
        assertEquals("Boiler", renamed.name)
        assertTrue("Furnace" in renamed.aliases)
        assertEquals(1, world.failing.renameCalls)
    }

    @Test
    fun saveIsDisabledForBlankAndUnchangedNames() {
        seedSomeTags()
        val vm = started()
        vm.openRename(world.subjectId("Furnace"))
        assertFalse(vm.ui().rename!!.canSave)
        vm.onRenameText("   ")
        assertFalse(vm.ui().rename!!.canSave)
        vm.onRenameText(" Furnace  ")
        assertFalse(vm.ui().rename!!.canSave)
        vm.saveRename()
        idle()
        assertEquals(0, world.failing.renameCalls)
        assertNotNull(vm.ui().rename)
    }

    @Test
    fun nameInUseNamesTheOtherTagAndLeadsToMerge() {
        seedSomeTags()
        val vm = started()
        val furnace = world.subjectId("Furnace")
        val hotTub = world.subjectId("Hot tub")
        vm.openRename(furnace)
        vm.onRenameText("hot tub")
        vm.saveRename()
        idle()
        val dialog = assertNotNull(vm.ui().rename)
        assertEquals(TagRef(hotTub, "Hot tub"), dialog.conflict)
        assertNull(vm.ui().message)

        vm.mergeInsteadOfRename()
        assertNull(vm.ui().rename)
        assertEquals(MergeConfirm(TagRef(furnace, "Furnace"), TagRef(hotTub, "Hot tub")), vm.ui().confirm)
        assertEquals(0, world.failing.mergeCalls)

        vm.confirmMerge()
        idle()
        assertNull(vm.ui().confirm)
        assertEquals(listOf("Hot tub"), vm.ui().subjects.map { it.name })
    }

    @Test
    fun editingTheNameClearsTheConflict() {
        seedSomeTags()
        val vm = started()
        vm.openRename(world.subjectId("Furnace"))
        vm.onRenameText("Hot tub")
        vm.saveRename()
        idle()
        assertNotNull(vm.ui().rename?.conflict)
        vm.onRenameText("Hot tub 2")
        assertNull(vm.ui().rename?.conflict)
    }

    @Test
    fun invalidNameKeepsTheDialogWithAPlainMessage() {
        seedSomeTags()
        val vm = started()
        vm.openRename(world.subjectId("Furnace"))
        vm.onRenameText("1234")
        vm.saveRename()
        idle()
        assertNotNull(vm.ui().rename)
        assertEquals(UserMessage(R.string.refusal_name_digits_only), vm.ui().message)
        assertEquals("Furnace", vm.ui().subjects.single { it.id == world.subjectId("Furnace") }.name)
    }

    @Test
    fun renameStorageFailureKeepsTheDialogAndChangesNothing() {
        seedSomeTags()
        val vm = started()
        val id = world.subjectId("Furnace")
        world.failing.failRename = true
        vm.openRename(id)
        vm.onRenameText("Boiler")
        vm.saveRename()
        idle()
        assertNotNull(vm.ui().rename)
        assertEquals(UserMessage(R.string.log_action_failed), vm.ui().message)
        assertFalse(vm.ui().actionInFlight)
        assertEquals("Furnace", world.catalog().subjects.single { it.id == id }.displayName)
    }

    @Test
    fun cancelRenameWritesNothing() {
        seedSomeTags()
        val vm = started()
        vm.openRename(world.subjectId("Furnace"))
        vm.onRenameText("Boiler")
        vm.cancelRename()
        idle()
        assertNull(vm.ui().rename)
        assertEquals(0, world.failing.renameCalls)
    }

    @Test
    fun mergeHappyPathMovesEntriesToTheTargetAndRefreshes() {
        world.log("Hot tub", "Change filter")
        world.log("Spa", "Change filter")
        world.log("Spa", "Clean")
        val vm = started()
        val spa = world.subjectId("Spa")
        val hotTub = world.subjectId("Hot tub")
        vm.openMerge(spa)
        assertEquals(TagRef(spa, "Spa"), vm.ui().chooser?.from)
        vm.chooseMergeTarget(hotTub)
        assertNull(vm.ui().chooser)
        assertEquals(MergeConfirm(TagRef(spa, "Spa"), TagRef(hotTub, "Hot tub")), vm.ui().confirm)
        vm.confirmMerge()
        idle()
        assertNull(vm.ui().confirm)
        assertEquals(TagsNotice.Merged(2), vm.ui().notice)
        assertEquals(listOf("Hot tub"), vm.ui().subjects.map { it.name })
        assertEquals(listOf("Hot tub", "Hot tub", "Hot tub"), world.entrySubjects())
        assertTrue("Spa" in vm.ui().subjects.single().aliases)
    }

    @Test
    fun choosingATargetAloneWritesNothing() {
        seedSomeTags()
        val vm = started()
        vm.openMerge(world.subjectId("Furnace"))
        vm.chooseMergeTarget(world.subjectId("Hot tub"))
        idle()
        assertNotNull(vm.ui().confirm)
        assertEquals(0, world.failing.mergeCalls)
        assertEquals(2, world.catalog().subjects.size)
    }

    @Test
    fun cancellingAtEachStepWritesNothing() {
        seedSomeTags()
        val vm = started()
        val furnace = world.subjectId("Furnace")
        vm.openMerge(furnace)
        vm.cancelMergeChooser()
        assertFalse(vm.ui().dialogOpen)
        vm.openMerge(furnace)
        vm.chooseMergeTarget(world.subjectId("Hot tub"))
        vm.cancelMerge()
        idle()
        assertFalse(vm.ui().dialogOpen)
        assertEquals(0, world.failing.mergeCalls)
        assertEquals(2, world.catalog().subjects.size)
    }

    @Test
    fun theChooserCannotChooseTheTagItself() {
        seedSomeTags()
        val vm = started()
        val furnace = world.subjectId("Furnace")
        vm.openMerge(furnace)
        vm.chooseMergeTarget(furnace)
        assertNotNull(vm.ui().chooser)
        assertNull(vm.ui().confirm)
    }

    @Test
    fun mergeStorageFailureKeepsTheConfirmationAndChangesNothing() {
        seedSomeTags()
        val vm = started()
        world.failing.failMerge = true
        vm.openMerge(world.subjectId("Furnace"))
        vm.chooseMergeTarget(world.subjectId("Hot tub"))
        vm.confirmMerge()
        idle()
        assertNotNull(vm.ui().confirm)
        assertEquals(UserMessage(R.string.log_action_failed), vm.ui().message)
        assertEquals(2, world.catalog().subjects.size)
        assertNull(vm.ui().notice)
    }

    @Test
    fun mergeRefusalForAMissingTagKeepsTheConfirmation() {
        seedSomeTags()
        val vm = started()
        val furnace = world.subjectId("Furnace")
        val hotTub = world.subjectId("Hot tub")
        vm.openMerge(furnace)
        vm.chooseMergeTarget(hotTub)
        // The target is merged away elsewhere (a race) before the owner confirms.
        com.mcfrenchpants.activityledger.core.testing.runSuspend {
            world.ledger.mergeTags(TagKind.SUBJECT, hotTub, furnace)
        }
        vm.confirmMerge()
        idle()
        assertNotNull(vm.ui().confirm)
        assertEquals(UserMessage(R.string.refusal_tag_not_found), vm.ui().message)
    }

    @Test
    fun loadFailureOffersRetry() {
        seedSomeTags()
        world.failing.failCatalog = true
        val vm = started()
        assertTrue(vm.ui().loadFailed)
        assertFalse(vm.ui().loaded)
        world.failing.failCatalog = false
        vm.onStart()
        idle()
        assertFalse(vm.ui().loadFailed)
        assertTrue(vm.ui().loaded)
        assertEquals(2, vm.ui().subjects.size)
    }

    @Test
    fun aSecondTapWhileOneActionRunsIsIgnored() {
        seedSomeTags()
        val vm = started()
        vm.openRename(world.subjectId("Furnace"))
        vm.onRenameText("Boiler")
        vm.saveRename()
        vm.saveRename()
        idle()
        assertEquals(1, world.failing.renameCalls)

        vm.openMerge(world.subjectId("Hot tub"))
        vm.chooseMergeTarget(world.subjectId("Boiler"))
        vm.confirmMerge()
        vm.confirmMerge()
        idle()
        assertEquals(1, world.failing.mergeCalls)
    }

    @Test
    fun noMessageResourceContainsTagNames() {
        // Messages are resource ids with at most numeric arguments; names never travel in them.
        seedSomeTags()
        val vm = started()
        vm.openRename(world.subjectId("Furnace"))
        vm.onRenameText("1234")
        vm.saveRename()
        idle()
        assertTrue(vm.ui().message!!.args.all { it is Int })
    }
}
