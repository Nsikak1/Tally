package ng.name.nsikak.libaccession.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ng.name.nsikak.libaccession.AppContainer
import ng.name.nsikak.libaccession.TallyApp
import ng.name.nsikak.libaccession.ui.addcopy.AddCopyViewModel
import ng.name.nsikak.libaccession.ui.holdings.HoldingEditViewModel
import ng.name.nsikak.libaccession.ui.holdings.HoldingsViewModel
import ng.name.nsikak.libaccession.ui.home.HomeViewModel
import ng.name.nsikak.libaccession.ui.ocr.OcrViewModel
import ng.name.nsikak.libaccession.ui.scanner.ScannerViewModel
import ng.name.nsikak.libaccession.ui.settings.SettingsViewModel

private fun CreationExtras.container(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TallyApp).container

/** One factory for every ViewModel. Each milestone adds an `initializer` line here. */
object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer { HomeViewModel(container()) }
        initializer { SettingsViewModel(container()) }
        initializer { ScannerViewModel(container().books, container().settings) }
        initializer { OcrViewModel(container().paddle, container().recognizer, container().samples) }
        initializer { HoldingsViewModel(container().holdings) }
        initializer { HoldingEditViewModel(container().holdings, container().books) }
        initializer { AddCopyViewModel(container().holdings, container().books, container().settings) }
    }
}
