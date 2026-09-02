package com.gulshan.pocketprint

import android.content.Context
import com.gulshan.pocketprint.data.JobRepository
import com.gulshan.pocketprint.data.PrinterRepository
import com.gulshan.pocketprint.data.SettingsRepository
import com.gulshan.pocketprint.discovery.BluetoothDiscovery
import com.gulshan.pocketprint.discovery.MdnsDiscovery
import com.gulshan.pocketprint.discovery.UsbDiscovery
import com.gulshan.pocketprint.ipp.IppClient
import com.gulshan.pocketprint.print.PrintEngine
import com.gulshan.pocketprint.render.NoOfficeConverter
import com.gulshan.pocketprint.render.OfficeConverter
import com.gulshan.pocketprint.render.RemoteOfficeConverter
import com.gulshan.pocketprint.render.RenderPipeline
import kotlinx.coroutines.runBlocking

/**
 * Hand-rolled singletons. The graph is small enough that a DI framework would
 * cost more than it saves, and the print service needs to reach these from a
 * context where no Activity exists.
 */
object ServiceLocator {

    @Volatile private var printerRepository: PrinterRepository? = null
    @Volatile private var jobRepository: JobRepository? = null
    @Volatile private var settingsRepository: SettingsRepository? = null
    @Volatile private var renderPipeline: RenderPipeline? = null
    @Volatile private var printEngine: PrintEngine? = null
    @Volatile private var ippClient: IppClient? = null

    fun printerRepository(context: Context): PrinterRepository =
        printerRepository ?: synchronized(this) {
            printerRepository ?: PrinterRepository(context.applicationContext)
                .also { printerRepository = it }
        }

    fun jobRepository(context: Context): JobRepository =
        jobRepository ?: synchronized(this) {
            jobRepository ?: JobRepository(context.applicationContext).also { jobRepository = it }
        }

    fun settingsRepository(context: Context): SettingsRepository =
        settingsRepository ?: synchronized(this) {
            settingsRepository ?: SettingsRepository(context.applicationContext)
                .also { settingsRepository = it }
        }

    fun ippClient(): IppClient =
        ippClient ?: synchronized(this) { ippClient ?: IppClient().also { ippClient = it } }

    fun renderPipeline(context: Context): RenderPipeline =
        renderPipeline ?: synchronized(this) {
            renderPipeline ?: RenderPipeline(
                context.applicationContext,
                officeConverter = { officeConverter(context) },
            ).also { renderPipeline = it }
        }

    fun printEngine(context: Context): PrintEngine =
        printEngine ?: synchronized(this) {
            printEngine ?: PrintEngine(
                context.applicationContext,
                renderPipeline(context),
                ippClient(),
            ).also { printEngine = it }
        }

    /**
     * Resolved per call because the user can change the endpoint at any time.
     * The read is blocking but hits an in-memory DataStore cache after startup.
     */
    private fun officeConverter(context: Context): OfficeConverter {
        val url = runCatching {
            runBlocking { settingsRepository(context).current().officeConverterUrl }
        }.getOrDefault("")
        return if (url.isBlank()) NoOfficeConverter else RemoteOfficeConverter(url)
    }

    fun mdnsDiscovery(context: Context) = MdnsDiscovery(context.applicationContext)
    fun bluetoothDiscovery(context: Context) = BluetoothDiscovery(context.applicationContext)
    fun usbDiscovery(context: Context) = UsbDiscovery(context.applicationContext)
}
