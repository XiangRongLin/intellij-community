// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("StartupUtil")
@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE", "ReplacePutWithAssignment", "KDocUnresolvedReference")

package com.intellij.idea

import com.intellij.BundleBase
import com.intellij.accessibility.AccessibilityUtils
import com.intellij.diagnostic.*
import com.intellij.diagnostic.opentelemetry.TraceManager
import com.intellij.ide.*
import com.intellij.ide.customize.CommonCustomizeIDEWizardDialog
import com.intellij.ide.gdpr.ConsentOptions
import com.intellij.ide.gdpr.EndUserAgreement
import com.intellij.ide.gdpr.showDataSharingAgreement
import com.intellij.ide.gdpr.showEndUserAndDataSharingAgreements
import com.intellij.ide.instrument.WriteIntentLockInstrumenter
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.ui.html.GlobalStyleSheetHolder
import com.intellij.ide.ui.laf.IdeaLaf
import com.intellij.ide.ui.laf.IntelliJLaf
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.idea.SocketLock.ActivationStatus
import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ConfigImportHelper
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.application.impl.AWTExceptionHandler
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ShutDownTracker
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.win32.IdeaWin32
import com.intellij.openapi.wm.WeakFocusStackManager
import com.intellij.serviceContainer.ComponentManagerImpl
import com.intellij.ui.AppUIUtil
import com.intellij.ui.CoreIconManager
import com.intellij.ui.IconManager
import com.intellij.ui.mac.MacOSApplicationProvider
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.EnvironmentUtil
import com.intellij.util.PlatformUtils
import com.intellij.util.ReflectionUtil
import com.intellij.util.lang.Java11Shim
import com.intellij.util.lang.ZipFilePool
import com.intellij.util.ui.EDT
import com.intellij.util.ui.StartupUiUtil
import com.intellij.util.ui.accessibility.ScreenReader
import kotlinx.coroutines.*
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.io.BuiltInServer
import sun.awt.AWTAutoShutdown
import java.awt.EventQueue
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.dnd.DragSource
import java.io.File
import java.io.IOError
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Function
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import javax.swing.*
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

internal const val IDE_STARTED = "------------------------------------------------------ IDE STARTED ------------------------------------------------------"
private const val IDE_SHUTDOWN = "------------------------------------------------------ IDE SHUTDOWN ------------------------------------------------------"

@JvmField
internal var EXTERNAL_LISTENER: BiFunction<String, Array<String>, Int> = BiFunction { _, _ -> AppExitCodes.ACTIVATE_NOT_INITIALIZED }

private const val IDEA_CLASS_BEFORE_APPLICATION_PROPERTY = "idea.class.before.app"

// see `ApplicationImpl#USE_SEPARATE_WRITE_THREAD`
private const val USE_SEPARATE_WRITE_THREAD_PROPERTY = "idea.use.separate.write.thread"
private const val MAGIC_MAC_PATH = "/AppTranslocation/"

private var socketLock: SocketLock? = null

// checked - using Deferred type doesn't lead to loading this class on StartupUtil init
internal var shellEnvDeferred: Deferred<Boolean?>? = null
  private set

// mainDispatcher is a sequential - use it with care
fun CoroutineScope.startApplication(args: List<String>,
                                    appStarterDeferred: Deferred<AppStarter>,
                                    mainScope: CoroutineScope,
                                    busyThread: Thread) {
  val appInfoDeferred = async(CoroutineName("app info") + Dispatchers.IO) {
    // required for DisabledPluginsState and EUA
    ApplicationInfoImpl.getShadowInstance()
  }
  val euaDocumentDeferred = loadEuaDocument(appInfoDeferred)
  // this must happen before LaF loading
  val checkGraphicsJob = checkGraphics()
  val pathDeferred = async(CoroutineName("config path computing") + Dispatchers.IO) {
    Pair(canonicalPath(PathManager.getConfigPath()), canonicalPath(PathManager.getSystemPath()))
  }

  val isHeadless = AppMode.isHeadless()

  val configImportNeededDeferred = async {
    val (configPath, _) = pathDeferred.await()
    !isHeadless && (!Files.exists(configPath) || Files.exists(configPath.resolve(ConfigImportHelper.CUSTOM_MARKER_FILE_NAME)))
  }

  // this should happen before UI initialization - if we're not going to show UI (in case another IDE instance is already running),
  // we shouldn't initialize AWT toolkit in order to avoid unnecessary focus stealing and space switching on macOS.
  val lockSystemDirsJob = lockSystemDirs(configImportNeededDeferred = configImportNeededDeferred,
                                         pathDeferred = pathDeferred,
                                         args = args,
                                         mainScope = mainScope)
  val consoleLoggerJob = configureJavaUtilLogging()

  launch {
    LoadingState.setStrictMode()
    LoadingState.errorHandler = BiConsumer { message, throwable ->
      logger<LoadingState>().error(message, throwable)
    }
  }

  val preloadLafClassesJob = preloadLafClasses()

  // LookAndFeel type is not specified to avoid class loading
  val initUiDeferred = async {
    checkGraphicsJob?.join()
    lockSystemDirsJob.join()
    initUi(preloadLafClassesJob, busyThread = busyThread)
  }

  val zipFilePoolDeferred = async(Dispatchers.IO) {
    // ZipFilePoolImpl uses Guava for Striped lock - load in parallel
    val result = ZipFilePoolImpl()
    ZipFilePool.POOL = result
    result
  }

  val schedulePluginDescriptorLoading = launch {
    ComponentManagerImpl.mainScope = mainScope

    // plugins cannot be loaded when a config import is needed, because plugins may be added after importing
    Java11Shim.INSTANCE = Java11ShimImpl()
    if (!configImportNeededDeferred.await()) {
      PluginManagerCore.scheduleDescriptorLoading(mainScope, zipFilePoolDeferred)
    }
  }

  // system dirs checking must happen after locking system dirs
  val checkSystemDirJob = checkSystemDirs(lockSystemDirsJob, pathDeferred)

  // log initialization must happen only after locking the system directory
  val logDeferred = setupLogger(consoleLoggerJob, checkSystemDirJob)

  val showEuaIfNeededJob = showEuaIfNeeded(euaDocumentDeferred, initUiDeferred)
  val patchHtmlStyleJob = if (isHeadless) null else patchHtmlStyle(initUiDeferred)

  shellEnvDeferred = async(CoroutineName("environment loading") + Dispatchers.IO) {
    EnvironmentUtil.loadEnvironment()
  }

  if (!isHeadless) {
    showSplashIfNeeded(initUiDeferred, showEuaIfNeededJob, appInfoDeferred, args)

    // must happen after initUi
    updateFrameClassAndWindowIconAndPreloadSystemFonts(initUiDeferred)
  }

  if (java.lang.Boolean.getBoolean("idea.enable.coroutine.dump")) {
    launch(CoroutineName("coroutine debug probes init")) {
      enableCoroutineDump()
    }
  }

  loadSystemLibsAndLogInfoAndInitMacApp(logDeferred, appInfoDeferred, initUiDeferred, args)

  val telemetryInitJob = launch {
    appInfoDeferred.join()
    runActivity("opentelemetry configuration") {
      TraceManager.init()
    }
  }

  val isInternal = java.lang.Boolean.getBoolean(ApplicationManagerEx.IS_INTERNAL_PROPERTY)
  if (isInternal) {
    launch(CoroutineName("assert on missed keys enabling")) {
      BundleBase.assertOnMissedKeys(true)
    }
  }
  launch(CoroutineName("disposer debug mode enabling if needed")) {
    if (isInternal || Disposer.isDebugDisposerOn()) {
      Disposer.setDebugMode(true)
    }
  }

  val appDeferred = async {
    // logging must be initialized before creating application
    val log = logDeferred.await()
    if (!configImportNeededDeferred.await()) {
      runPreAppClass(log, args)
    }

    // we must wait for UI before creating application and before AppStarter.start (some appStarter dialogs) - EDT thread is required
    runActivity("prepare ui waiting") {
      initUiDeferred.join()
    }

    val app = runActivity("app instantiation") {
      ApplicationImpl(isInternal, AppMode.isHeadless(), AppMode.isCommandLine(), EDT.getEventDispatchThread())
    }

    runActivity("telemetry waiting") {
      telemetryInitJob.join()
    }

    app to patchHtmlStyleJob
  }

  mainScope.launch {
    // required for appStarter.prepareStart
    appInfoDeferred.join()

    val appStarter = runActivity("main class loading waiting") {
      appStarterDeferred.await()
    }
    appStarter.prepareStart(args)

    // before config importing and license check
    showEuaIfNeededJob.join()

    if (!isHeadless && configImportNeededDeferred.await()) {
      initUiDeferred.join()
      val imported = importConfig(
        args = args,
        log = logDeferred.await(),
        appStarter = appStarterDeferred.await(),
        agreementShown = showEuaIfNeededJob,
      )
      if (imported) {
        PluginManagerCore.scheduleDescriptorLoading(mainScope, zipFilePoolDeferred)
      }
    }

    // must be scheduled before starting app
    schedulePluginDescriptorLoading.join()

    // with main dispatcher (appStarter uses runBlocking - block main thread and not some coroutine thread)
    appStarter.start(args, appDeferred)
  }
}

private fun CoroutineScope.loadSystemLibsAndLogInfoAndInitMacApp(logDeferred: Deferred<Logger>,
                                                                 appInfoDeferred: Deferred<ApplicationInfoEx>,
                                                                 initUiDeferred: Job,
                                                                 args: List<String>) {
  launch {
    // this must happen after locking system dirs
    val log = logDeferred.await()

    runActivity("system libs setup") {
      setupSystemLibraries()
    }

    withContext(Dispatchers.IO) {
      runActivity("system libs loading") {
        JnaLoader.load(log)
        if (SystemInfoRt.isWindows) {
          IdeaWin32.isAvailable()
        }
      }
    }

    val appInfo = appInfoDeferred.await()
    launch(CoroutineName("essential IDE info logging") + Dispatchers.IO) {
      logEssentialInfoAboutIde(log, appInfo, args)
    }

    if (!AppMode.isHeadless() && SystemInfoRt.isMac) {
      // JNA and Swing are used - invoke only after both are loaded
      initUiDeferred.join()
      launch(CoroutineName("mac app init")) {
        MacOSApplicationProvider.initApplication(log)
      }
    }
  }
}

private fun CoroutineScope.showSplashIfNeeded(initUiDeferred: Deferred<Any>,
                                              showEuaIfNeededJob: Deferred<Boolean>,
                                              appInfoDeferred: Deferred<ApplicationInfoEx>,
                                              args: List<String>) {
  if (AppMode.isLightEdit()) {
    return
  }

  launch {
    // A splash instance must not be created before base LaF is created.
    // It is important on Linux, where GTK LaF must be initialized (to properly set up the scale factor).
    // https://youtrack.jetbrains.com/issue/IDEA-286544
    initUiDeferred.join()

    // before showEuaIfNeededJob to prepare during showing EUA dialog
    val runnable = prepareSplash(appInfoDeferred, args) ?: return@launch
    showEuaIfNeededJob.join()
    withContext(SwingDispatcher) {
      runnable.run()
    }
  }
}

private fun CoroutineScope.patchHtmlStyle(initUiJob: Deferred<Any>): Job {
  return launch {
    initUiJob.join()

    withContext(SwingDispatcher) {
      runActivity("html style patching") {
        // patch html styles
        val uiDefaults = UIManager.getDefaults()
        // create a separate copy for each case
        val globalStyleSheet = GlobalStyleSheetHolder.getGlobalStyleSheet()
        uiDefaults.put("javax.swing.JLabel.userStyleSheet", globalStyleSheet)
        uiDefaults.put("HTMLEditorKit.jbStyleSheet", globalStyleSheet)

        runActivity("global styleSheet updating") {
          GlobalStyleSheetHolder.updateGlobalSwingStyleSheet()
        }
      }
    }
  }
}

private suspend fun prepareSplash(appInfoDeferred: Deferred<ApplicationInfoEx>, args: List<String>): Runnable? {
  var showSplash = false
  for (arg in args) {
    if (CommandLineArgs.SPLASH == arg) {
      showSplash = true
      break
    }
    else if (CommandLineArgs.NO_SPLASH == arg) {
      return null
    }
  }

  // products may specify `splash` VM property; `nosplash` is deprecated and should be checked first
  if (!showSplash &&
      (java.lang.Boolean.getBoolean(CommandLineArgs.NO_SPLASH) || !java.lang.Boolean.getBoolean(CommandLineArgs.SPLASH))) {
    return null
  }

  val appInfo = appInfoDeferred.await()
  return runActivity("splash preparation") {
    SplashManager.scheduleShow(appInfo)
  }
}

private fun CoroutineScope.checkGraphics(): Job? {
  if (AppMode.isHeadless()) {
    return null
  }

  return launch {
    runActivity("graphics environment checking") {
      if (GraphicsEnvironment.isHeadless()) {
        StartupErrorReporter.showMessage(BootstrapBundle.message("bootstrap.error.title.startup.error"),
                                         BootstrapBundle.message("bootstrap.error.message.no.graphics.environment"), true)
        exitProcess(AppExitCodes.NO_GRAPHICS)
      }
    }
  }
}

/** Called via reflection from [WindowsCommandLineProcessor.processWindowsLauncherCommandLine].  */
@Suppress("unused")
fun processWindowsLauncherCommandLine(currentDirectory: String, args: Array<String>): Int {
  return EXTERNAL_LISTENER.apply(currentDirectory, args)
}

internal val isUsingSeparateWriteThread: Boolean
  get() = java.lang.Boolean.getBoolean(USE_SEPARATE_WRITE_THREAD_PROPERTY)

// called by the app after startup
@Synchronized
fun addExternalInstanceListener(processor: Function<List<String>, Future<CliResult>>) {
  if (socketLock == null) {
    throw AssertionError("Not initialized yet")
  }
  socketLock!!.setCommandProcessor(processor)
}

// used externally by TeamCity plugin (as TeamCity cannot use modern API to support old IDE versions)
@Synchronized
@Deprecated("")
fun getServer(): BuiltInServer? = socketLock?.getServer()

@Synchronized
fun getServerFutureAsync(): Deferred<BuiltInServer?> = socketLock?.serverFuture ?: CompletableDeferred(value = null)

// On startup 2 dialogs must be shown:
// - gdpr agreement
// - eu(l)a
private fun CoroutineScope.loadEuaDocument(appInfoDeferred: Deferred<ApplicationInfoEx>): Deferred<Any?>? {
  if (AppMode.isHeadless()) {
    return null
  }

  return async(CoroutineName("eua document") + Dispatchers.IO) {
    val vendorAsProperty = System.getProperty("idea.vendor.name", "")
    if (if (vendorAsProperty.isEmpty()) !appInfoDeferred.await().isVendorJetBrains else vendorAsProperty != "JetBrains") {
      null
    }
    else {
      val document = runActivity("eua getting") { EndUserAgreement.getLatestDocument() }
      if (runActivity("eua is accepted checking") { document.isAccepted }) null else document
    }
  }
}

private fun runPreAppClass(log: Logger, args: List<String>) {
  val classBeforeAppProperty = System.getProperty(IDEA_CLASS_BEFORE_APPLICATION_PROPERTY) ?: return
  runActivity("pre app class running") {
    try {
      val aClass = AppStarter::class.java.classLoader.loadClass(classBeforeAppProperty)
      MethodHandles.lookup()
        .findStatic(aClass, "invoke", MethodType.methodType(Void.TYPE, Array<String>::class.java))
        .invoke(args.toTypedArray())
    }
    catch (e: Exception) {
      log.error("Failed pre-app class init for class $classBeforeAppProperty", e)
    }
  }
}

private suspend fun importConfig(args: List<String>,
                                 log: Logger,
                                 appStarter: AppStarter,
                                 agreementShown: Deferred<Boolean>): Boolean {
  var activity = StartUpMeasurer.startActivity("screen reader checking")
  try {
    withContext(SwingDispatcher) { AccessibilityUtils.enableScreenReaderSupportIfNecessary() }
  }
  catch (e: Throwable) {
    log.error(e)
  }

  activity = activity.endAndStart("config importing")
  appStarter.beforeImportConfigs()
  val newConfigDir = PathManager.getConfigDir()
  val veryFirstStartOnThisComputer = agreementShown.await()
  withContext(SwingDispatcher) {
    if (UIManager.getLookAndFeel() !is IntelliJLaf) {
      UIManager.setLookAndFeel(IntelliJLaf())
    }
    ConfigImportHelper.importConfigsTo(veryFirstStartOnThisComputer, newConfigDir, args, log)
  }
  appStarter.importFinished(newConfigDir)
  activity.end()
  return !PlatformUtils.isRider() || ConfigImportHelper.isConfigImported()
}

// return type (LookAndFeel) is not specified to avoid class loading
private suspend fun initUi(preloadLafClassesJob: Job, busyThread: Thread): Any {
  // calls `sun.util.logging.PlatformLogger#getLogger` - it takes enormous time (up to 500 ms)
  // only non-logging tasks can be executed before `setupLogger`
  val activityQueue = StartUpMeasurer.startActivity("base LaF preparation")

  val isHeadless = AppMode.isHeadless()
  coroutineScope {
    launch {
      checkHiDPISettings()
      blockATKWrapper()

      @Suppress("SpellCheckingInspection")
      System.setProperty("sun.awt.noerasebackground", "true")

      activityQueue.startChild("awt toolkit creating").let { activity ->
        Toolkit.getDefaultToolkit()
        activity.end()
      }
    }

    // IdeaLaF uses AllIcons - icon manager must be activated
    if (!isHeadless) {
      launch(CoroutineName("icon manager activation")) {
        IconManager.activate(CoreIconManager())
      }
    }

    launch(CoroutineName("IdeEventQueue class preloading")) {
      val classLoader = AppStarter::class.java.classLoader
      // preload class not in EDT
      Class.forName(IdeEventQueue::class.java.name, true, classLoader)
    }
  }

  preloadLafClassesJob.join()

  // SwingDispatcher must be used after Toolkit init
  val baseLaF = withContext(SwingDispatcher) {
    activityQueue.end()
    // we don't need Idea LaF to show splash, but we do need some base LaF to compute system font data (see below for what)
    var activity = StartUpMeasurer.startActivity("base LaF creation")
    val baseLaF = DarculaLaf.createBaseLaF()
    activity = activity.endAndStart("base LaF initialization")
    // LaF is useless until initialized (`getDefaults` "should only be invoked ... after `initialize` has been invoked.")
    baseLaF.initialize()

    // to compute the system scale factor on non-macOS (JRE HiDPI is not enabled), we need to know system font data,
    // and to compute system font data we need to know `Label.font` UI default (that's why we compute base LaF first)
    activity = activity.endAndStart("system font data initialization")
    if (!isHeadless) {
      JBUIScale.getSystemFontData {
        runActivity("base LaF defaults getting") { baseLaF.defaults }
      }
      activity = activity.endAndStart("scale initialization")
      JBUIScale.scale(1f)
    }
    StartUpMeasurer.setCurrentState(LoadingState.BASE_LAF_INITIALIZED)
    activity = activity.endAndStart("awt thread busy notification")
    /*
Make EDT to always persist while the main thread is alive. Otherwise, it's possible to have EDT being
terminated by [AWTAutoShutdown], which will break a `ReadMostlyRWLock` instance.
[AWTAutoShutdown.notifyThreadBusy(Thread)] will put the main thread into the thread map,
and thus will effectively disable auto shutdown behavior for this application.
*/
    AWTAutoShutdown.getInstance().notifyThreadBusy(busyThread)
    activity.end()
    patchSystem(isHeadless)
    baseLaF
  }

  if (isUsingSeparateWriteThread) {
    runActivity("Write Intent Lock UI class transformer loading") {
      WriteIntentLockInstrumenter.instrument()
    }
  }

  DarculaLaf.setPreInitializedBaseLaf(baseLaF)
  return baseLaF
}

private fun CoroutineScope.preloadLafClasses(): Job {
  val classLoader = AppStarter::class.java.classLoader
  return launch(CoroutineName("LaF class preloading")) {
    // preload class not in EDT
    Class.forName(DarculaLaf::class.java.name, true, classLoader)
    Class.forName(IdeaLaf::class.java.name, true, classLoader)
    Class.forName(JBUIScale::class.java.name, true, classLoader)
  }
}

/*
 * The method should be called before `Toolkit#initAssistiveTechnologies`, which is called from `Toolkit#getDefaultToolkit`.
 */
private fun blockATKWrapper() {
  // the registry must not be used here, because this method is called before application loading
  @Suppress("SpellCheckingInspection")
  if (!SystemInfoRt.isLinux || !java.lang.Boolean.parseBoolean(System.getProperty("linux.jdk.accessibility.atkwrapper.block", "true"))) {
    return
  }

  val activity = StartUpMeasurer.startActivity("atk wrapper blocking")
  if (ScreenReader.isEnabled(ScreenReader.ATK_WRAPPER)) {
    // Replacing `AtkWrapper` with a dummy `Object`. It'll be instantiated & garbage collected right away, a NOP.
    System.setProperty("javax.accessibility.assistive_technologies", "java.lang.Object")
    Logger.getInstance(StartupUiUtil::class.java).info(ScreenReader.ATK_WRAPPER + " is blocked, see IDEA-149219")
  }
  activity.end()
}

private fun CoroutineScope.showEuaIfNeeded(euaDocumentDeferred: Deferred<Any?>?, initUiJob: Deferred<Any>): Deferred<Boolean> {
  return async {
    if (euaDocumentDeferred == null) {
      return@async true
    }

    val euaDocument = euaDocumentDeferred.await()
    initUiJob.join()
    runActivity("eua showing") {
      val document = euaDocument as EndUserAgreement.Document?
      withContext(Dispatchers.IO) {
        EndUserAgreement.updateCachedContentToLatestBundledVersion()
      }
      if (document != null) {
        withContext(SwingDispatcher) {
          UIManager.setLookAndFeel(IntelliJLaf())
          showEndUserAndDataSharingAgreements(document)
        }
        true
      }
      else if (ConsentOptions.needToShowUsageStatsConsent()) {
        withContext(SwingDispatcher) {
          UIManager.setLookAndFeel(IntelliJLaf())
          showDataSharingAgreement()
        }
        false
      }
      else {
        false
      }
    }
  }
}

/** Do not use it. For start-up code only. */
internal object SwingDispatcher : CoroutineDispatcher() {
  override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()

  override fun dispatch(context: CoroutineContext, block: Runnable): Unit = EventQueue.invokeLater(block)

  override fun toString() = "Swing"
}

private fun CoroutineScope.updateFrameClassAndWindowIconAndPreloadSystemFonts(initUiDeferred: Job) {
  launch {
    initUiDeferred.join()

    launch(CoroutineName("system fonts loading") + Dispatchers.IO) {
      // forces loading of all system fonts; the following statement alone might not do it (see JBR-1825)
      Font("N0nEx1st5ntF0nt", Font.PLAIN, 1).family
      // caches available font family names for the default locale to speed up editor reopening (see `ComplementaryFontsRegistry`)
      GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
    }

    if (!SystemInfoRt.isWindows && !SystemInfoRt.isMac) {
      launch(CoroutineName("frame class updating")) {
        try {
          val toolkit = Toolkit.getDefaultToolkit()
          val aClass = toolkit.javaClass
          if (aClass.name == "sun.awt.X11.XToolkit") {
            val field = ReflectionUtil.findAssignableField(aClass, null, "awtAppClassName")
            field.set(toolkit, AppUIUtil.getFrameClass())
          }
        }
        catch (ignore: Exception) {
        }
      }
    }

    launch(CoroutineName("update window icon")) {
      // `updateWindowIcon` should be called after `initUiJob`, because it uses computed system font data for scale context
      if (!AppUIUtil.isWindowIconAlreadyExternallySet() && !PluginManagerCore.isRunningFromSources()) {
        // most of the time is consumed by loading SVG and can be done in parallel
        AppUIUtil.updateWindowIcon(JOptionPane.getRootFrame())
      }
    }

    // preload cursors used by drag-n-drop AWT subsystem, run on SwingDispatcher to avoid a possible deadlock - see RIDER-80810
    launch(CoroutineName("DnD setup") + SwingDispatcher) {
      DragSource.getDefaultDragSource()
    }

    launch(SwingDispatcher) {
      WeakFocusStackManager.getInstance()
    }
  }
}

private fun CoroutineScope.configureJavaUtilLogging(): Job {
  return launch(CoroutineName("console logger configuration")) {
    val rootLogger = java.util.logging.Logger.getLogger("")
    if (rootLogger.handlers.isEmpty()) {
      rootLogger.level = Level.WARNING
      val consoleHandler = ConsoleHandler()
      consoleHandler.level = Level.WARNING
      rootLogger.addHandler(consoleHandler)
    }
  }
}

@VisibleForTesting
fun checkHiDPISettings() {
  if (!java.lang.Boolean.parseBoolean(System.getProperty("hidpi", "true"))) {
    // suppress JRE-HiDPI mode
    System.setProperty("sun.java2d.uiScale.enabled", "false")
  }
}

private fun CoroutineScope.checkSystemDirs(lockSystemDirJob: Job, pathDeferred: Deferred<Pair<Path, Path>>): Job {
  return launch {
    lockSystemDirJob.join()

    val (configPath, systemPath) = pathDeferred.await()
    runActivity("system dirs checking") {
      if (!doCheckSystemDirs(configPath, systemPath)) {
        exitProcess(AppExitCodes.DIR_CHECK_FAILED)
      }
    }
  }
}

private suspend fun doCheckSystemDirs(configPath: Path, systemPath: Path): Boolean {
  if (configPath == systemPath) {
    StartupErrorReporter.showMessage(BootstrapBundle.message("bootstrap.error.title.invalid.config.or.system.path"),
                                     BootstrapBundle.message("bootstrap.error.message.config.0.and.system.1.paths.must.be.different",
                                                             PathManager.PROPERTY_CONFIG_PATH,
                                                             PathManager.PROPERTY_SYSTEM_PATH), true)
    return false
  }

  return withContext(Dispatchers.IO) {
    val logPath = Path.of(PathManager.getLogPath()).normalize()
    val tempPath = Path.of(PathManager.getTempPath()).normalize()

    listOf(
      async {
        checkDirectory(directory = configPath,
                       kind = "Config",
                       property = PathManager.PROPERTY_CONFIG_PATH,
                       checkWrite = true,
                       checkLock = true,
                       checkExec = false)
      },
      async {
        checkDirectory(directory = systemPath,
                       kind = "System",
                       property = PathManager.PROPERTY_SYSTEM_PATH,
                       checkWrite = true,
                       checkLock = true,
                       checkExec = false)
      },
      async {
        checkDirectory(directory = logPath,
                       kind = "Log",
                       property = PathManager.PROPERTY_LOG_PATH,
                       checkWrite = !logPath.startsWith(systemPath),
                       checkLock = false,
                       checkExec = false)
      },
      async {
        checkDirectory(directory = tempPath,
                       kind = "Temp",
                       property = PathManager.PROPERTY_SYSTEM_PATH,
                       checkWrite = !tempPath.startsWith(systemPath),
                       checkLock = false,
                       checkExec = SystemInfoRt.isUnix && !SystemInfoRt.isMac)

      }
    ).awaitAll().all { it }
  }
}

private fun checkDirectory(directory: Path,
                           kind: String,
                           property: String,
                           checkWrite: Boolean,
                           checkLock: Boolean,
                           checkExec: Boolean): Boolean {
  var problem = "bootstrap.error.message.check.ide.directory.problem.cannot.create.the.directory"
  var reason = "bootstrap.error.message.check.ide.directory.possible.reason.path.is.incorrect"
  var tempFile: Path? = null
  try {
    if (!Files.isDirectory(directory)) {
      problem = "bootstrap.error.message.check.ide.directory.problem.cannot.create.the.directory"
      reason = "bootstrap.error.message.check.ide.directory.possible.reason.directory.is.read.only.or.the.user.lacks.necessary.permissions"
      Files.createDirectories(directory)
    }

    if (checkWrite || checkLock || checkExec) {
      problem = "bootstrap.error.message.check.ide.directory.problem.the.ide.cannot.create.a.temporary.file.in.the.directory"
      reason = "bootstrap.error.message.check.ide.directory.possible.reason.directory.is.read.only.or.the.user.lacks.necessary.permissions"
      tempFile = directory.resolve("ij${Random().nextInt(Int.MAX_VALUE)}.tmp")
      Files.writeString(tempFile, "#!/bin/sh\nexit 0", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      if (checkLock) {
        problem = "bootstrap.error.message.check.ide.directory.problem.the.ide.cannot.create.a.lock.in.directory"
        reason = "bootstrap.error.message.check.ide.directory.possible.reason.the.directory.is.located.on.a.network.disk"
        FileChannel.open(tempFile, EnumSet.of(StandardOpenOption.WRITE)).use { channel ->
          channel.tryLock().use { lock ->
            if (lock == null) {
              throw IOException("File is locked")
            }
          }
        }
      }
      else if (checkExec) {
        problem = "bootstrap.error.message.check.ide.directory.problem.the.ide.cannot.execute.test.script"
        reason = "bootstrap.error.message.check.ide.directory.possible.reason.partition.is.mounted.with.no.exec.option"
        Files.getFileAttributeView(tempFile!!, PosixFileAttributeView::class.java)
          .setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        val exitCode = ProcessBuilder(tempFile.toAbsolutePath().toString()).start().waitFor()
        if (exitCode != 0) {
          throw IOException("Unexpected exit value: $exitCode")
        }
      }
    }
    return true
  }
  catch (e: Exception) {
    val title = BootstrapBundle.message("bootstrap.error.title.invalid.ide.directory.type.0.directory", kind)
    val advice = if (SystemInfoRt.isMac && PathManager.getSystemPath().contains(MAGIC_MAC_PATH)) {
      BootstrapBundle.message("bootstrap.error.message.invalid.ide.directory.trans.located.macos.directory.advice")
    }
    else {
      BootstrapBundle.message("bootstrap.error.message.invalid.ide.directory.ensure.the.modified.property.0.is.correct", property)
    }
    val message = BootstrapBundle.message(
      "bootstrap.error.message.invalid.ide.directory.problem.0.possible.reason.1.advice.2.location.3.exception.class.4.exception.message.5",
      BootstrapBundle.message(problem), BootstrapBundle.message(reason), advice, directory, e.javaClass.name, e.message)
    StartupErrorReporter.showMessage(title, message, true)
    return false
  }
  finally {
    if (tempFile != null) {
      try {
        Files.deleteIfExists(tempFile)
      }
      catch (ignored: Exception) {
      }
    }
  }
}

// returns `true` when `checkConfig` is requested and config import is needed
private fun CoroutineScope.lockSystemDirs(configImportNeededDeferred: Job,
                                          pathDeferred: Deferred<Pair<Path, Path>>,
                                          args: List<String>,
                                          mainScope: CoroutineScope): Job {
  if (socketLock != null) {
    throw AssertionError("Already initialized")
  }

  return launch(Dispatchers.IO) {
    val (configPath, systemPath) = pathDeferred.await()
    configImportNeededDeferred.join()
    runActivity("system dirs locking") {
      // this check must be performed before system directories are locked
      socketLock = SocketLock(configPath, systemPath)
      val status = socketLock!!.lockAndTryActivate(args = args, mainScope = mainScope)
      when (status.first) {
        ActivationStatus.NO_INSTANCE -> {
          ShutDownTracker.getInstance().registerShutdownTask {
            synchronized(AppStarter::class.java) {
              socketLock!!.dispose()
              socketLock = null
            }
          }
        }
        ActivationStatus.ACTIVATED -> {
          val result = status.second!!
          println(result.message ?: "Already running")
          exitProcess(result.exitCode)
        }
        ActivationStatus.CANNOT_ACTIVATE -> {
          val message = BootstrapBundle.message("bootstrap.error.message.only.one.instance.of.0.can.be.run.at.a.time",
                                                ApplicationNamesInfo.getInstance().productName)
          StartupErrorReporter.showMessage(BootstrapBundle.message("bootstrap.error.title.too.many.instances"), message, true)
          exitProcess(AppExitCodes.INSTANCE_CHECK_FAILED)
        }
      }
    }
  }
}

private fun CoroutineScope.setupLogger(consoleLoggerJob: Job, checkSystemDirJob: Job): Deferred<Logger> {
  return async {
    consoleLoggerJob.join()
    checkSystemDirJob.join()

    runActivity("file logger configuration") {
      try {
        Logger.setFactory(LoggerFactory())
      }
      catch (e: Exception) {
        e.printStackTrace()
      }

      val log = Logger.getInstance(AppStarter::class.java)
      log.info(IDE_STARTED)
      ShutDownTracker.getInstance().registerShutdownTask { log.info(IDE_SHUTDOWN) }
      if (java.lang.Boolean.parseBoolean(System.getProperty("intellij.log.stdout", "true"))) {
        System.setOut(PrintStreamLogger("STDOUT", System.out))
        System.setErr(PrintStreamLogger("STDERR", System.err))
      }
      log
    }
  }
}

@Suppress("SpellCheckingInspection")
private fun setupSystemLibraries() {
  val ideTempPath = PathManager.getTempPath()
  if (System.getProperty("jna.tmpdir") == null) {
    // to avoid collisions and work around no-exec /tmp
    System.setProperty("jna.tmpdir", ideTempPath)
  }
  if (System.getProperty("jna.nosys") == null) {
    // prefer bundled JNA dispatcher lib
    System.setProperty("jna.nosys", "true")
  }
  if (SystemInfoRt.isWindows && System.getProperty("winp.folder.preferred") == null) {
    System.setProperty("winp.folder.preferred", ideTempPath)
  }
  if (System.getProperty("pty4j.tmpdir") == null) {
    System.setProperty("pty4j.tmpdir", ideTempPath)
  }
  if (System.getProperty("pty4j.preferred.native.folder") == null) {
    System.setProperty("pty4j.preferred.native.folder", Path.of(PathManager.getLibPath(), "pty4j-native").toAbsolutePath().toString())
  }
}

private fun logEssentialInfoAboutIde(log: Logger, appInfo: ApplicationInfo, args: List<String>) {
  val buildDate = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(appInfo.buildDate.time)
  log.info("IDE: ${ApplicationNamesInfo.getInstance().fullProductName} (build #${appInfo.build.asString()}, $buildDate)")
  log.info("OS: ${SystemInfoRt.OS_NAME} (${SystemInfoRt.OS_VERSION}, ${System.getProperty("os.arch")})")
  log.info("JRE: ${System.getProperty("java.runtime.version", "-")} (${System.getProperty("java.vendor", "-")})")
  log.info("JVM: ${System.getProperty("java.vm.version", "-")} (${System.getProperty("java.vm.name", "-")})")
  log.info("PID: ${ProcessHandle.current().pid()}")
  if (SystemInfoRt.isXWindow) {
    log.info("desktop: ${System.getenv("XDG_CURRENT_DESKTOP")}")
  }

  ManagementFactory.getRuntimeMXBean().inputArguments?.let {
    log.info("JVM options: $it")
  }
  log.info("args: ${args.joinToString(separator = " ")}")
  log.info("library path: ${System.getProperty("java.library.path")}")
  log.info("boot library path: ${System.getProperty("sun.boot.library.path")}")
  logEnvVar(log, "_JAVA_OPTIONS")
  logEnvVar(log, "JDK_JAVA_OPTIONS")
  logEnvVar(log, "JAVA_TOOL_OPTIONS")
  log.info(
    """locale=${Locale.getDefault()} JNU=${System.getProperty("sun.jnu.encoding")} file.encoding=${System.getProperty("file.encoding")}
  ${PathManager.PROPERTY_CONFIG_PATH}=${logPath(PathManager.getConfigPath())}
  ${PathManager.PROPERTY_SYSTEM_PATH}=${logPath(PathManager.getSystemPath())}
  ${PathManager.PROPERTY_PLUGINS_PATH}=${logPath(PathManager.getPluginsPath())}
  ${PathManager.PROPERTY_LOG_PATH}=${logPath(PathManager.getLogPath())}"""
  )
  val cores = Runtime.getRuntime().availableProcessors()
  val pool = ForkJoinPool.commonPool()
  log.info("CPU cores: $cores; ForkJoinPool.commonPool: $pool; factory: ${pool.factory}")
}

private fun logEnvVar(log: Logger, variable: String) {
  val value = System.getenv(variable)
  if (value != null) {
    log.info("$variable=$value")
  }
}

private fun logPath(path: String): String {
  try {
    val configured = Path.of(path)
    val real = configured.toRealPath()
    if (configured != real) {
      return "$path -> $real"
    }
  }
  catch (ignored: IOException) {
  }
  catch (ignored: InvalidPathException) {
  }
  return path
}

fun runStartupWizard() {
  val stepsDialogName = ApplicationInfoImpl.getShadowInstance().welcomeWizardDialog ?: return
  try {
    val dialogClass = Class.forName(stepsDialogName)
    val ctor = dialogClass.getConstructor(AppStarter::class.java)
    (ctor.newInstance(null) as CommonCustomizeIDEWizardDialog).showIfNeeded()
  }
  catch (e: Throwable) {
    StartupErrorReporter.showMessage(BootstrapBundle.message("bootstrap.error.title.configuration.wizard.failed"), e)
    return
  }
  PluginManagerCore.invalidatePlugins()
  PluginManagerCore.scheduleDescriptorLoading(ComponentManagerImpl.mainScope!!)
}

// the method must be called on EDT
private fun patchSystem(isHeadless: Boolean) {
  var activity = StartUpMeasurer.startActivity("event queue replacing")
  // replace system event queue
  IdeEventQueue.getInstance()
  if (!isHeadless && "true" == System.getProperty("idea.check.swing.threading")) {
    activity = activity.endAndStart("repaint manager set")
    RepaintManager.setCurrentManager(AssertiveRepaintManager())
  }

  // do not crash AWT on exceptions
  AWTExceptionHandler.register()
  activity.end()
}

fun canonicalPath(path: String): Path {
  return try {
    // `toRealPath` doesn't restore a canonical file name on case-insensitive UNIX filesystems
    Path.of(File(path).canonicalPath)
  }
  catch (ignore: IOException) {
    val file = Path.of(path)
    try {
      file.toAbsolutePath()
    }
    catch (ignored: IOError) {
    }
    file.normalize()
  }
}

interface AppStarter {
  fun prepareStart(args: List<String>) {}

  /* called from IDE init thread */
  suspend fun start(args: List<String>, appDeferred: Deferred<Any>)

  /* called from IDE init thread */
  fun beforeImportConfigs() {}

  /* called from IDE init thread */
  fun importFinished(newConfigDir: Path) {}
}

class Java11ShimImpl : Java11Shim() {
  override fun <K : Any, V : Any> copyOf(map: Map<out K, V>): Map<K, V> = java.util.Map.copyOf(map)

  override fun <E : Any> copyOf(collection: Set<E>): Set<E> = java.util.Set.copyOf(collection)

  override fun <E : Any> copyOfCollection(collection: Collection<E>): List<E> = java.util.List.copyOf(collection)
}
