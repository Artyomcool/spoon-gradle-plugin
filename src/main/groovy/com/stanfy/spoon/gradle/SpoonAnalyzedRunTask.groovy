package com.stanfy.spoon.gradle
import android.test.InstrumentationTestCase
import com.squareup.spoon.IncrementalSpoonRunner
import com.stanfy.spoon.annotations.Action
import com.stanfy.spoon.annotations.EveryTest
import com.stanfy.spoon.annotations.BeforeTest
import com.stanfy.spoon.annotations.AfterTest
import groovy.transform.PackageScope
import javassist.ClassClassPath
import javassist.ClassPool
import javassist.Modifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.*
import org.junit.Ignore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
/**
 * Task for using SpoonRunner.
 */
class SpoonAnalyzedRunTask extends DefaultTask implements VerificationTask {

  /** All sizes should be run. */
  @PackageScope
  static final String TEST_SIZE_ALL = "all";

  /** Plugin dependency name. */
  private static final String PLUGIN_DEP_NAME = "ru.mail.spoon:spoon-gradle-plugin"
  /** Spoon runner artifact name. */
  private static final String SPOON_RUNNER_ARTIFACT = "spoon-runner"
  /** Spoon dependency name. */
  private static final String SPOON_DEP_NAME = "com.squareup.spoon:$SPOON_RUNNER_ARTIFACT"

  /** Logger. */
  public static final Logger LOG = LoggerFactory.getLogger(SpoonAnalyzedRunTask.class)

  /** A title for the output website. */
  @Input
  String title

  @Input
  String packageName

  /** If true then test failures do not cause a build failure. */
  boolean ignoreFailures

  /** If true, tests will fail if no devices are connected. */
  boolean failIfNoDeviceConnected

  /** Debug logging switcher. */
  boolean debug

  /** Whether or not animations are enabled */
  boolean noAnimations

  /** Instrumentation APK. */
  @InputFile
  File instrumentationApk

  /** Application APK. */
  @InputFile
  File applicationApk

  /** Application APK. */
  @InputDirectory
  File testClasses

  @Input
  List<String> orderedTestClasses

  /** Output directory. */
  @OutputDirectory
  File output

  /** Use all the connected devices flag. */
  boolean allDevices

  /** Devices to run on. */
  Set<String> devices

  @TaskAction
  void runSpoon() {
    LOG.info("Run instrumentation tests $instrumentationApk for app $applicationApk")

    LOG.debug("Title: $title")
    LOG.debug("Output: $output")
    LOG.debug("Classes: $testClasses")

    LOG.debug("Ignore failures: $ignoreFailures")
    LOG.debug("Fail if no device connected: $failIfNoDeviceConnected")
    LOG.debug("Debug mode: $debug")

    LOG.debug("No animations: $noAnimations")

    String cp = getClasspath()
    LOG.debug("Classpath: $cp")

    IncrementalSpoonRunner.Builder runBuilder = new IncrementalSpoonRunner.Builder()
        .setTitle(title)
        .setApplicationApk(applicationApk)
        .setInstrumentationApk(instrumentationApk)
        .setOutputDirectory(output)
        .setFailIfNoDeviceConnected(failIfNoDeviceConnected)
        .setDebug(debug)
        .setAndroidSdk(project.android.sdkDirectory)
        .setClasspath(cp)
        .setNoAnimations(noAnimations)

    if (allDevices) {
      runBuilder.useAllAttachedDevices()
      LOG.info("Using all the attached devices")
    } else {
      if (!devices) {
        throw new GradleException("No devices specified to run the tests on");
      }
      devices.each {
        runBuilder.addDevice(it)
      }
      LOG.info("Using devices $devices")
    }

    boolean success = true

    IncrementalSpoonRunner runner = runBuilder.build()
    try {
      runner.init()

      def pool = ClassPool.default
      def test = pool.makeClass(InstrumentationTestCase.name)
      pool.appendClassPath(new ClassClassPath(InstrumentationTestCase))

      def classesToCheck = [];
      testClasses.eachFileRecurse { def file ->
        if (file.directory) {
          return
        }

        def stream = file.newInputStream()
        try {
          def clazz = pool.makeClass(stream)
          classesToCheck.add(clazz);
        } finally {
          stream.close()
        }
      }

      def foundClasses = [:];
      classesToCheck.each { def clazz ->
        if (!Modifier.isAbstract(clazz.modifiers) && clazz.subclassOf(test)) {
          foundClasses.put(clazz.getName(), clazz)
        }
      }

      def classesToRun = []
      if (orderedTestClasses) {
        orderedTestClasses.each {
          def clazz = foundClasses[it]
          if (!clazz) {
            throw new IllegalArgumentException("No test with name $it found")
          }
          classesToRun << clazz
        }
      } else {
        classesToRun = foundClasses.values()
      }
      logger.info "$classesToRun"
      def lastAction = Action.None
      new TestSorter(classesToRun).tests.each { method ->
        Ignore ignore = method.getAnnotation(Ignore) as Ignore
        def name = method.declaringClass.name
        if (ignore) {
          String reason = ignore.value()
          if (reason) {
            reason = " ($reason)"
          }
          runner.ignoreTests("ignore $name#$method.name$reason")
          return
        }
        EveryTest annotation = method.declaringClass.getAnnotation(EveryTest) as EveryTest
        def before = annotation ? annotation.before() : Action.None
        def after = annotation ? annotation.after() : Action.None
        if (method.hasAnnotation(BeforeTest)) {
          before = method.getAnnotation(BeforeTest).value()
        }
        if (method.hasAnnotation(AfterTest)) {
          after = method.getAnnotation(AfterTest).value()
        }
        if (before.ordinal() > lastAction.ordinal()) {
          switch (before) {
            case Action.ClearData:
              runner.clearData packageName
              break
            case Action.ForceStop:
              runner.forceStop packageName
              break
          }
        }
        runner.runTests(name, method.name)
        switch (after) {
          case Action.ClearData:
            runner.clearData packageName
            break
          case Action.ForceStop:
            runner.forceStop packageName
            break
        }
        lastAction = after
      }

    } finally {
      success &= runner.finish()
    }

    if (!success && !ignoreFailures) {
      throw new GradleException("Tests failed! See ${output}/index.html")
    }
  }

  private String getClasspath() {
    def pluginDep = null
    def classpath = []
    project.buildscript.configurations.each {
      if (pluginDep) { return }

      pluginDep = it.resolvedConfiguration.firstLevelModuleDependencies.find { it.name.startsWith SpoonAnalyzedRunTask.PLUGIN_DEP_NAME }
      if (pluginDep) {
        def spoon = pluginDep.children.find { it.name.startsWith SpoonAnalyzedRunTask.SPOON_DEP_NAME }
        if (!spoon) { throw new IllegalStateException("Cannot find spoon-runner in dependencies") }
        classpath = spoon.allModuleArtifacts.collect { it.file }
        classpath += pluginDep.allModuleArtifacts.find { it.name == SpoonAnalyzedRunTask.SPOON_RUNNER_ARTIFACT }.file
      }

    }

    if (!pluginDep) { throw new IllegalStateException("Could not resolve spoon dependencies") }
    return project.files(classpath).asPath
  }

}
