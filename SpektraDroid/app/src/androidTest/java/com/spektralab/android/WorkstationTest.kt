package com.spektralab.android

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.android.tether.TetherViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkstationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(40)
        assertTrue(label, condition())
    }

    @Test fun librarySurvivesReloadAndAssociatesRecipesAndOriginals() {
        val storage = "library-test-${UUID.randomUUID()}"
        try {
            val library = MediaLibrary(context, storage)
            val original = Uri.parse("content://test/original")
            val state = EditState(exposureEv = 1.3f, grainAmount = .4f,
                filmEdge = FilmEdgeSettings(active = true), dateBack = DateBackSettings(active = true))
            val item = library.add(original, "original.ARW", MediaSource.MONITOR, recipe = state)
            assertEquals(item, library.add(original, "other", MediaSource.IMPORT))
            assertEquals(1, library.items.value.size)
            library.add(Uri.parse("content://test/result"), "result.jpg", MediaSource.EXPORT, recipe = state, parentUri = original.toString())
            val reload = MediaLibrary(context, storage)
            assertEquals(state, reload.items.value.last().recipe)
            assertEquals(original.toString(), reload.items.value.first().parentUri)
            assertEquals(MediaSource.MONITOR, reload.items.value.last().source)
            reload.rememberEdit(original, state.copy(printBrightness = .7f))
            assertEquals(.7f, MediaLibrary(context, storage).items.value.last().recipe!!.printBrightness, 0f)
            val restored = Uri.parse("content://test/recovered")
            reload.replaceUri(original, restored)
            assertEquals(item.id, reload.items.value.last().id)
            assertEquals(restored.toString(), MediaLibrary(context, storage).items.value.last().uri)
            assertEquals("Exports must keep pointing to the recovered original", restored.toString(),
                MediaLibrary(context, storage).items.value.first().parentUri)
        } finally { context.getSharedPreferences(storage, 0).edit().clear().commit() }
    }

    @Test fun namedRecipesSurviveReloadAndRejectAccidentalOverwrite() {
        val storage = "recipes-test-${UUID.randomUUID()}"
        try {
            val store = RecipeStore(context, storage)
            val state = EditState(exposureEv = 1.2f, printBrightness = -.6f,
                filmEdge = FilmEdgeSettings(active = true, cameraSeed = 482),
                dateBack = DateBackSettings(active = true))
            val recipe = store.save("  Weekend  ", state)
            assertEquals("Weekend", recipe.name)
            assertEquals(recipe, RecipeStore(context, storage).recipes.value.single())
            assertTrue(runCatching { store.save("weekend", EditState()) }.isFailure)
            assertTrue(runCatching { store.save("   ", state) }.isFailure)
            assertTrue(runCatching { store.save("x".repeat(41), state) }.isFailure)
            assertEquals(state, store.recipes.value.single().state)
            val second = store.save("Night", state.copy(printBrightness = -1f))
            assertTrue(runCatching { store.rename(second.id, "WEEKEND") }.isFailure)
            store.rename(recipe.id, "Daylight")
            assertEquals("Daylight", RecipeStore(context, storage).recipes.value.last().name)
            store.remove(second.id)
            assertEquals(recipe.copy(name = "Daylight"), RecipeStore(context, storage).recipes.value.single())
        } finally { context.getSharedPreferences(storage, 0).edit().clear().commit() }
    }

    @Test fun mediaSearchCombinesSourceFormatAndFilenameWithoutLosingOriginals() {
        val photo = MediaItem("1", "content://test/raw", "DSC0001.ARW", MediaSource.MONITOR, false, 0, null)
        val tiff = photo.copy(id = "2", name = "Print.tiff", source = MediaSource.IMPORT)
        val jpeg = photo.copy(id = "3", name = "DSC0001_film.jpg", source = MediaSource.EXPORT)
        val video = photo.copy(id = "4", name = "Live.mp4", video = true)
        val items = listOf(photo, tiff, jpeg, video)
        assertEquals(listOf(photo), items.matching("  dsc0001  ", MediaSource.MONITOR, MediaKind.RAW))
        assertEquals(listOf(photo, tiff), items.matching(kind = MediaKind.RAW))
        assertEquals(listOf(photo, tiff, jpeg), items.matching(kind = MediaKind.PHOTO))
        assertEquals(listOf(video), items.matching(source = MediaSource.MONITOR, kind = MediaKind.VIDEO))
        assertEquals(listOf(jpeg), items.matching("冲印成片"))
        assertTrue(items.matching("missing").isEmpty())
    }

    @Test fun captureLookDoesNotOverwriteWorkbenchOrItsSavedPhotoRecipe() {
        val store = ViewModelStore()
        val prefsName = "mode-regression-${UUID.randomUUID()}"
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefsName, mode)
        }
        val file = java.io.File(context.filesDir, "mode-regression-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff7694ad.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val library = MediaLibrary.get(context)
        lateinit var vm: EditorViewModel
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[EditorViewModel::class.java]
            }
            waitFor("Editor ready") { vm.engineReady.value }
            val workbench = vm.state.value.copy(exposureEv = 1.3f, printBrightness = .4f)
            instrumentation.runOnMainSync { vm.loadPhoto(Uri.fromFile(file), workbench) }
            waitFor("Original loaded") { vm.photo.value?.uri == Uri.fromFile(file) && !vm.loadingPhoto.value }
            val capture = workbench.copy(exposureEv = -1.7f, printBrightness = -1f)
            instrumentation.runOnMainSync { vm.rememberPhotoEdits = false; vm.update { capture } }
            waitFor("Original edit persisted") { library.items.value.first { it.uri == Uri.fromFile(file).toString() }.recipe == workbench }
            // Wait past the deferred edit write to catch capture settings leaking to the photo.
            Thread.sleep(600)
            assertEquals(workbench, library.items.value.first { it.uri == Uri.fromFile(file).toString() }.recipe)
            instrumentation.runOnMainSync { vm.rememberPhotoEdits = true }
            assertEquals("Workbench restores its own look", workbench, vm.state.value)
            instrumentation.runOnMainSync { vm.rememberPhotoEdits = false }
            assertEquals("Capture and monitor reuse their look", capture, vm.state.value)
            instrumentation.runOnMainSync { vm.rememberPhotoEdits = false }
            assertEquals(capture, vm.state.value)
            instrumentation.runOnMainSync { vm.rememberPhotoEdits = true; vm.exporting.value = true; vm.update { capture } }
            assertEquals("Saving locks adjustments", workbench, vm.state.value)
            instrumentation.runOnMainSync { vm.exporting.value = false }
            assertNotNull("Unsupported stock is explained to the recipe dialog", vm.applyRecipe(workbench.copy(filmStock = "missing-stock")))
            assertEquals(workbench, vm.state.value)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            context.deleteSharedPreferences(prefsName)
            library.items.value.filter { it.uri == Uri.fromFile(file).toString() }.forEach { library.remove(it.id) }
            file.delete()
        }
    }

    @Test fun monitorRecipeChangesAffectFramesAndSavedMediaWithoutReconnect() {
        val store = ViewModelStore()
        lateinit var vm: TetherViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[TetherViewModel::class.java]
        }
        val library = MediaLibrary.get(context)
        val existing = library.items.value.map { it.id }.toSet()
        val state = EditState(autoExposure = false)
        try {
            instrumentation.runOnMainSync { vm.filmEnabled.value = true; vm.demo(state) }
            waitFor("Filtered monitor starts") { vm.frame.value != null }
            val before = vm.frame.value!!.getPixel(15, 200)
            val changed = state.copy(printBrightness = -2f, exposureEv = -2f)
            instrumentation.runOnMainSync { vm.updateLook(changed) }
            waitFor("New recipe reaches the live stream") { vm.frame.value!!.getPixel(15, 200) != before }
            vm.demoCapture = true
            waitFor("Photo appears in library") { library.items.value.any { it.id !in existing && !it.video } }
            val photo = library.items.value.first { it.id !in existing && !it.video }
            assertEquals(changed, photo.recipe)
            assertEquals(MediaSource.MONITOR, photo.source)
            vm.recordRequested.value = true
            waitFor("Recording starts") { vm.recording.value }
            Thread.sleep(500)
            instrumentation.runOnMainSync { vm.stop() }
            waitFor("Stop finalizes video") { !vm.connected.value && !vm.busy.value }
            val video = library.items.value.first { it.id !in existing && it.video }
            assertEquals(changed, video.recipe)
            assertFalse(vm.recording.value)
        } finally {
            instrumentation.runOnMainSync { vm.stop() }
            waitFor("Cleanup") { !vm.connected.value && !vm.busy.value }
            instrumentation.runOnMainSync { store.clear() }
            library.items.value.filter { it.id !in existing }.forEach {
                context.contentResolver.delete(Uri.parse(it.uri), null, null)
                library.remove(it.id)
            }
        }
    }

    @Test fun rawThumbnailsCannotEvictTheEditorsActiveOriginal() {
        val file = java.io.File.createTempFile("library-source-", ".dng", context.cacheDir)
        val uris = mutableListOf<Uri>()
        var pinned: String? = null
        try {
            instrumentation.context.assets.open("import/synthetic.dng").use { input -> file.outputStream().use { input.copyTo(it) } }
            repeat(4) { index -> uris += com.spektralab.android.tether.TetherMedia.save(context, file,
                "cache-regression-${UUID.randomUUID()}-$index.dng", false) }
            pinned = PhotoIO.nativePath(context, uris[0])
            assertEquals(pinned, PhotoIO.nativePath(context, uris[0])) // Two editor owners during a reload.
            PhotoIO.decode(context, uris[1], 16)!!.recycle()
            assertTrue(java.io.File(pinned!!).isFile)
            PhotoIO.releaseNativeSource(pinned)
            PhotoIO.decode(context, uris[2], 16)!!.recycle()
            assertTrue("Retiring the previous photo must not unpin the replacement", java.io.File(pinned).isFile)
            PhotoIO.releaseNativeSource(pinned)
            PhotoIO.decode(context, uris[3], 16)!!.recycle()
            assertFalse("Released sources may be evicted", java.io.File(pinned).isFile)
        } finally {
            PhotoIO.releaseNativeSource(pinned)
            PhotoIO.releaseNativeSource(pinned)
            uris.forEach { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }
}
