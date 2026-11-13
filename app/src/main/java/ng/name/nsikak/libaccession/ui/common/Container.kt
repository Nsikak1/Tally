package ng.name.nsikak.libaccession.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import ng.name.nsikak.libaccession.AppContainer
import ng.name.nsikak.libaccession.TallyApp

/** The app-wide singletons, for composables that need one directly (e.g. the cover loader). */
@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as TallyApp).container
