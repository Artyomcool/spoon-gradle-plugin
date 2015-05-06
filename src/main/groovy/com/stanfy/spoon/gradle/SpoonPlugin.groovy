package com.stanfy.spoon.gradle

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.builder.model.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaBasePlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Gradle plugin for Spoon.
 */
class SpoonPlugin implements Plugin<Project> {

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger(SpoonPlugin.class)

  /** Task name prefix. */
  private static final String TASK_PREFIX = "spoon"

  @Override
  void apply(final Project project) {

    if (!project.plugins.withType(AppPlugin)) {
      throw new IllegalStateException("Android plugin is not found")
    }

    project.extensions.add "spoon", SpoonExtension

    def spoonTask = project.task("spoon") {
      group = JavaBasePlugin.VERIFICATION_GROUP
      description = "Runs all the instrumentation test variations on all the connected devices"
    }

    def spoonAnalyzedTask = project.task("spoonAnalyzed") {
      group = JavaBasePlugin.VERIFICATION_GROUP
      description = "Runs all the instrumentation test variations on all the connected devices with analyzing classes" +
              "for optionally clearing data and killing the app"
    }

    AppExtension android = project.android
    android.testVariants.all { TestVariant variant ->

      String taskName = "${TASK_PREFIX}${variant.name.capitalize()}"
      Task task = createTask(taskName, variant, project)

      task.configure {
        title = "$project.name $variant.name"
        description = "Runs instrumentation tests on all the connected devices for '${variant.name}' variation and generates a report with screenshots"
      }

      spoonTask.dependsOn task

      //def dir = variant.javaCompile.destinationDir
      String analyzedTaskName = "${TASK_PREFIX}Analyzed${variant.name.capitalize()}"
      Task analyzedTask = createAnalyzedTask(analyzedTaskName, variant, project)
      analyzedTask.configure {
        title = "$project.name $variant.name"
        description = "Runs instrumentation tests on all the connected devices for '${variant.name}' variation and generates a report with screenshots, performs analyze of test allowing to clear data or kill the app"
      }

      spoonAnalyzedTask.dependsOn analyzedTask

      project.tasks.addRule(patternString(taskName)) { String ruleTaskName ->
        if (ruleTaskName.startsWith(taskName)) {
          String size = (ruleTaskName - taskName).toLowerCase(Locale.US)
          if (isValidSize(size)) {
            SpoonRunTask sizeTask = createTask(ruleTaskName, variant, project)
            sizeTask.configure {
              title = "$project.name $variant.name - $size tests"
              testSize = size
            }
          }
        }
      }
    }

    project.tasks.addRule(patternString("spoon")) { String ruleTaskName ->
      if (ruleTaskName.startsWith("spoon")) {
        String suffix = lowercase(ruleTaskName - "spoon")
        if (android.testVariants.find { suffix.startsWith(it.name) } != null) {
          // variant specific, not our case
          return
        }
        String size = suffix.toLowerCase(Locale.US)
        if (isValidSize(size)) {
          def variantTaskNames = spoonTask.taskDependencies.getDependencies(spoonTask).collect() { it.name }
          project.task(ruleTaskName, dependsOn: variantTaskNames.collect() { "${it}${size}" })
        }
      }
    }

    def orderedTests = project.container(SpoonOrderedTests)
    project.extensions.add "orderedTests", orderedTests

    orderedTests.all { SpoonOrderedTests tests ->
      def name = tests.name
      def spoonOrderedTask = project.task("spoonOrdered${name.capitalize()}")
      spoonOrderedTask.configure {
        description = "Runs instrumentation tests on all the connected devices and generates a report with screenshots with specified order, performs analyze of test allowing to clear data or kill the app"
      }
      android.testVariants.all { TestVariant variant ->
        Task task = createAnalyzedTask("spoonOrdered${name.capitalize()}${variant.name.capitalize()}", variant, project)
        task.configure {
          title = "$project.name $name ${variant.name}"
          description = "Runs instrumentation tests on all the connected devices and generates a report with screenshots with specified order, performs analyze of test allowing to clear data or kill the app"
        }
        task.orderedTestClasses = tests.classes.collect { "${tests.suffix}.${it}" }
        spoonOrderedTask.dependsOn task
      }
    }

  }

  private static boolean isValidSize(String size) {
    return size in ['small', 'medium', 'large']
  }

  private static String lowercase(final String s) {
    return s[0].toLowerCase(Locale.US) + s.substring(1)
  }

  private static String patternString(final String taskName) {
    return "Pattern: $taskName<TestSize>: run instrumentation tests of particular size"
  }

  private static SpoonRunTask createTask(final String name, final TestVariant variant, final Project project) {
    SpoonRunTask task = createBaseTask(name, variant, project, SpoonRunTask)

    task.configure {
      if (project.spoon.className) {
        className = project.spoon.className
        if (project.spoon.methodName) {
          methodName = project.spoon.methodName
        }
      }
    }
  }

  private static SpoonAnalyzedRunTask createAnalyzedTask(String name, TestVariant variant, Project project) {
    SpoonExtension config = project.spoon
    SpoonAnalyzedRunTask task = createBaseTask(name, variant, project, SpoonAnalyzedRunTask)

    task.outputs.upToDateWhen { false }
    task.configure {
      testClasses = variant.javaCompile.destinationDir
      packageName = variant.testedVariant.applicationId
      orderedTestClasses = []
      devices = config.devices
      allDevices = !config.devices
    }
  }

  private static <E extends Task> E createBaseTask(String name, TestVariant variant, Project project, Class<E> clazz) {
    SpoonExtension config = project.spoon
    E task = project.tasks.create(name, clazz)

    task.configure {
      group = JavaBasePlugin.VERIFICATION_GROUP
      applicationApk = firstApk(variant.testedVariant)
      instrumentationApk = firstApk(variant)

      File outputBase = config.baseOutputDir
      if (!outputBase) {
        outputBase = new File(project.buildDir, "spoon")
      }
      output = new File(outputBase, variant.testedVariant.dirName)

      debug = config.debug
      ignoreFailures = config.ignoreFailures
      devices = config.devices
      allDevices = !config.devices
      noAnimations = config.noAnimations
      failIfNoDeviceConnected = project.spoon.failIfNoDeviceConnected

      dependsOn variant.assemble, variant.testedVariant.assemble
    }
  }

  private static File firstApk(BaseVariant variant) {
    def file = null;
    variant.outputs.each { output ->
      def outputFile = output.outputFile
      if (outputFile != null && outputFile.name.endsWith('.apk')) {
        file = outputFile
      }
    }
    file
  }

}
