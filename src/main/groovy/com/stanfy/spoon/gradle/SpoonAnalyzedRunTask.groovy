package com.stanfy.spoon.gradle
import android.test.InstrumentationTestCase
import com.squareup.spoon.IncrementalSpoonRunner
import com.stanfy.spoon.annotations.Analyze
import com.stanfy.spoon.annotations.ClearData
import com.stanfy.spoon.annotations.ForceStop
import groovy.transform.PackageScope
import javassist.ClassClassPath
import javassist.ClassPool
import javassist.CtClass
import javassist.Modifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.*
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
  private static final String PLUGIN_DEP_NAME = "com.stanfy.spoon:spoon-gradle-plugin"
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

      def classesToRun = new ArrayList<CtClass>();
      testClasses.eachFileRecurse { def file ->
        if (file.directory) {
          return
        }

        def stream = file.newInputStream()
        try {
          def clazz = pool.makeClass(stream)
          if (!Modifier.isAbstract(clazz.modifiers) && clazz.subclassOf(test)) {
            classesToRun.add(clazz)
          }
        } finally {
          stream.close()
        }
      }

      classesToRun.sort { o1,o2 ->
        if (orderedTestClasses) {
          int pos1 = orderedTestClasses.findIndexOf { it.contentEquals(o1.name) }
            runner.classTests("Classes to run sort1 $o1.name")
            int pos2 = orderedTestClasses.findIndexOf { it.contentEquals(o2.name) }
            runner.classTests("Classes to run sort2 $o2.name")
            if (pos1 != pos2) {
            return Integer.compare(pos1 == -1 ? Integer.MAX_VALUE : pos1, pos2 == -1 ? Integer.MAX_VALUE : pos2)
          }
        }

        Analyze analyze1 = o1.getAnnotation(Analyze) as Analyze
        Analyze analyze2 = o2.getAnnotation(Analyze) as Analyze
        if (analyze1 == null) {
          return analyze2 == null ? 0 : 1
        }
        if (analyze2 == null) {
          return -1
        }

        def clear1 = analyze1.clearAllTests()
        def clear2 = analyze2.clearAllTests()
        if (clear1 != clear2) {
          return -Boolean.compare(clear1, clear2)
        }

        def stop1 = analyze1.forceStopAllTests()
        def stop2 = analyze2.forceStopAllTests()
        return -Boolean.compare(stop1, stop2)
      }

        runner.classTests("classes $classesToRun")

      classesToRun.each {
        def name = it.name
        if (orderedTestClasses) {
          if (!orderedTestClasses.find { it.contentEquals(name) }) {
            return
          }
        }

        Analyze analyze = it.getAnnotation(Analyze) as Analyze
        if (!analyze) {
          runner.runTests(name)
          return
        }

        def methods = it.methods
        if (!analyze.respectTestsOrder()) {
          methods = methods.sort { a, b ->
            def res = -Boolean.compare(a.hasAnnotation(ClearData), b.hasAnnotation(ClearData))
            if (res != 0) {
              return res
            }
            return -Boolean.compare(a.hasAnnotation(ForceStop), b.hasAnnotation(ForceStop))
          }
        }

        methods.each { method ->
          if (method.name.startsWith('test')) {
            if (analyze.clearAllTests() || method.hasAnnotation(ClearData)) {
              runner.clearData packageName
            } else if (analyze.forceStopAllTests() || method.hasAnnotation(ForceStop)) {
              runner.forceStop packageName
            }
            runner.runTests(name, method.name)
          }
           else if (method.name.startsWith('bug')) {
              runner.ignoreTests(name + "#" + method.name)
          }
            else if (method.name.startsWith('future')) {
              runner.ignoreTests(name + "#" + method.name)
          }
        }
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
