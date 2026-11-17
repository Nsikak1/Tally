package ng.name.nsikak.libaccession.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ng.name.nsikak.libaccession.ui.addcopy.AddCopyScreen
import ng.name.nsikak.libaccession.ui.holdings.HoldingEditScreen
import ng.name.nsikak.libaccession.ui.holdings.HoldingsScreen
import ng.name.nsikak.libaccession.ui.holdings.Prefill
import ng.name.nsikak.libaccession.ui.home.HomeScreen
import ng.name.nsikak.libaccession.ui.scanner.FillStep
import ng.name.nsikak.libaccession.ui.scanner.ScannerScreen
import ng.name.nsikak.libaccession.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HOLDINGS = "holdings"

    /** The add-a-copy page for one ISBN-13: not-found confirmation, details, then the typed accession number. */
    const val ADD_COPY = "addcopy/{isbn}"

    /** `scanner?steps=title,author` = the camera fills those form fields one by one and returns; plain `scanner` = free scanning. */
    const val SCANNER = "scanner?steps={steps}"

    /**
     * One screen for both cases: `holding?accession=LIB-1` edits that copy; without `accession` it adds a copy,
     * starting from the optional isbn / title / author / acc (an accession number read by the camera).
     */
    const val HOLDING = "holding?accession={accession}&isbn={isbn}&title={title}&author={author}&acc={acc}"

    fun scanner(steps: String? = null) = if (steps == null) "scanner" else "scanner?steps=${Uri.encode(steps)}"

    fun addCopy(isbn13: String) = "addcopy/${Uri.encode(isbn13)}"

    fun editHolding(accession: String) = "holding?accession=${Uri.encode(accession)}"

    fun newHolding(
        isbn: String? = null,
        title: String? = null,
        author: String? = null,
        acc: String? = null,
    ): String {
        val parts = listOfNotNull(
            isbn?.let { "isbn=${Uri.encode(it)}" },
            title?.let { "title=${Uri.encode(it)}" },
            author?.let { "author=${Uri.encode(it)}" },
            acc?.let { "acc=${Uri.encode(it)}" },
        )
        return if (parts.isEmpty()) "holding" else "holding?" + parts.joinToString("&")
    }
}

private fun stringArg(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenScanner = { nav.navigate(Routes.scanner()) },
                onOpenHoldings = { nav.navigate(Routes.HOLDINGS) },
                onAddHolding = { nav.navigate(Routes.newHolding()) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SCANNER, arguments = listOf(stringArg("steps"))) { entry ->
            val steps = entry.arguments?.getString("steps")?.let { FillStep.fromKeys(it) }?.takeIf { it.isNotEmpty() }
            val savedCopy by entry.savedStateHandle.getStateFlow<String?>("copy_saved", null).collectAsStateWithLifecycle()
            ScannerScreen(
                savedAccession = savedCopy,
                onSavedHandled = { entry.savedStateHandle["copy_saved"] = null },
                onBack = { nav.popBackStack() },
                onAddCopy = { isbn -> nav.navigate(Routes.addCopy(isbn)) },
                onOpenHolding = { accession -> nav.navigate(Routes.editHolding(accession)) },
                fillSteps = steps,
                onGuidedDone = { values ->
                    if (steps != null) {
                        // Opened from the copy form: hand the values back to it.
                        val handle = nav.previousBackStackEntry?.savedStateHandle
                        values.forEach { (key, value) -> handle?.set("fill_$key", value) }
                        nav.popBackStack()
                    } else {
                        // Opened from the scanner (book not found anywhere): continue in a new copy form.
                        nav.navigate(
                            Routes.newHolding(
                                isbn = values["isbn"],
                                title = values["title"],
                                author = values["author"],
                                acc = values["accession"],
                            ),
                        )
                    }
                },
            )
        }
        composable(Routes.ADD_COPY, arguments = listOf(navArgument("isbn") { type = NavType.StringType })) { entry ->
            AddCopyScreen(
                isbn = entry.arguments?.getString("isbn").orEmpty(),
                onBack = { nav.popBackStack() },
                onSaved = { accession ->
                    // The scanner underneath clears itself and waits for the next book.
                    nav.previousBackStackEntry?.savedStateHandle?.set("copy_saved", accession)
                    nav.popBackStack()
                },
                onEditAllFields = { isbn, title, author, accession ->
                    // Replace this page by the full form; saving there also returns to the scanner.
                    nav.navigate(Routes.newHolding(isbn = isbn, title = title, author = author, acc = accession)) {
                        popUpTo(Routes.ADD_COPY) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOLDINGS) {
            HoldingsScreen(
                onBack = { nav.popBackStack() },
                onOpen = { accession -> nav.navigate(Routes.editHolding(accession)) },
                onAdd = { nav.navigate(Routes.newHolding()) },
            )
        }
        composable(
            route = Routes.HOLDING,
            arguments = listOf(
                stringArg("accession"),
                stringArg("isbn"),
                stringArg("title"),
                stringArg("author"),
                stringArg("acc"),
            ),
        ) { entry ->
            val args = entry.arguments
            HoldingEditScreen(
                accession = args?.getString("accession"),
                prefill = Prefill(
                    isbn = args?.getString("isbn"),
                    title = args?.getString("title"),
                    author = args?.getString("author"),
                    accession = args?.getString("acc"),
                ),
                savedState = entry.savedStateHandle,
                onBack = { nav.popBackStack() },
                onFillWithCamera = { steps -> nav.navigate(Routes.scanner(steps)) },
                onSaved = { accession ->
                    // The scanner underneath clears itself and waits for the next book.
                    nav.previousBackStackEntry?.savedStateHandle?.set("copy_saved", accession)
                    nav.popBackStack()
                },
            )
        }
    }
}
