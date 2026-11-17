package ng.name.nsikak.libaccession

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.data.api.ApiFactory
import ng.name.nsikak.libaccession.data.api.ApiProvider
import ng.name.nsikak.libaccession.data.cover.CoverLoader
import ng.name.nsikak.libaccession.data.db.TallyDatabase
import ng.name.nsikak.libaccession.data.discovery.ServerDiscovery
import ng.name.nsikak.libaccession.data.ocr.OcrSampleStore
import ng.name.nsikak.libaccession.data.ocr.PaddleRecognizer
import ng.name.nsikak.libaccession.data.ocr.TextRecognizerService
import ng.name.nsikak.libaccession.data.repo.BookRepository
import ng.name.nsikak.libaccession.data.repo.HoldingsRepository
import ng.name.nsikak.libaccession.data.repo.ServerHoldingsRepository
import ng.name.nsikak.libaccession.data.settings.SettingsStore
import ng.name.nsikak.libaccession.data.update.UpdateRepository
import ng.name.nsikak.libaccession.domain.AccessionFormat

/** Hand-rolled DI: one place that owns the app-wide singletons. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context.applicationContext)
    val apiFactory = ApiFactory(context.applicationContext)
    val apiProvider = ApiProvider(settings, apiFactory)
    val updateRepository = UpdateRepository(context.applicationContext, apiFactory)
    val discovery = ServerDiscovery(context.applicationContext)
    val recognizer = TextRecognizerService()
    val paddle = PaddleRecognizer(context.applicationContext)
    val samples = OcrSampleStore(context.applicationContext)

    private val database: TallyDatabase =
        Room.databaseBuilder(context.applicationContext, TallyDatabase::class.java, "tally.db")
            // The database only caches looked-up books, so on a version change it is simply emptied.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    val books = BookRepository(api = apiProvider, dao = database.books())
    val holdings: HoldingsRepository = ServerHoldingsRepository(apiProvider)
    val covers = CoverLoader(apiFactory.httpClient)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Settings that other classes read without going through the settings screen.
        appScope.launch { settings.ocrModel.collect { paddle.useModel(it) } }
        appScope.launch { settings.accessionFormats.collect { AccessionFormat.patterns = it } }
    }
}

class TallyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
