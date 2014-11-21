package com.squareup.spoon;

import com.android.ddmlib.FileListingService;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.squareup.spoon.adapters.TestIdentifierAdapter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.squareup.spoon.Spoon.SPOON_SCREENSHOTS;
import static com.squareup.spoon.SpoonLogger.*;
import static com.squareup.spoon.SpoonUtils.createAnimatedGif;
import static com.squareup.spoon.SpoonUtils.obtainDirectoryFileEntry;

public class IncrementalSpoonDeviceRunner {
	private static final String FILE_EXECUTION = "execution.json";
	private static final String FILE_RESULT = "result.json";
	static final String TEMP_DIR = "work";
	static final String JUNIT_DIR = "junit-reports";
	static final String IMAGE_DIR = "image";

	private final File sdk;
	private final File apk;
	private final File testApk;
	private final String serial;
	private final boolean debug;
	private final boolean noAnimations;
	private final int adbTimeout;
	private final IRemoteAndroidTestRunner.TestSize testSize;
	private final File work;
	private final File junitReport;
	private final File imageDir;
	private final String classpath;
	private final SpoonInstrumentationInfo instrumentationInfo;
	private DeviceResult.Builder result;
	private SpoonDeviceLogger deviceLogger;
	private IDevice device;

	/**
	 * Create a test runner for a single device.
	 *
	 * @param sdk Path to the local Android SDK directory.
	 * @param apk Path to application APK.
	 * @param testApk Path to test application APK.
	 * @param output Path to output directory.
	 * @param serial Device to run the test on.
	 * @param debug Whether or not debug logging is enabled.
	 * @param adbTimeout time in ms for longest test execution
	 * @param classpath Custom JVM classpath or {@code null}.
	 * @param instrumentationInfo Test apk manifest information.
	 */
	IncrementalSpoonDeviceRunner(File sdk, File apk, File testApk, File output, String serial, boolean debug,
					  boolean noAnimations, int adbTimeout, String classpath,
					  SpoonInstrumentationInfo instrumentationInfo,
					  IRemoteAndroidTestRunner.TestSize testSize) {
		this.sdk = sdk;
		this.apk = apk;
		this.testApk = testApk;
		this.serial = serial;
		this.debug = debug;
		this.noAnimations = noAnimations;
		this.adbTimeout = adbTimeout;
		this.testSize = testSize;
		this.classpath = classpath;
		this.instrumentationInfo = instrumentationInfo;

		serial = SpoonUtils.sanitizeSerial(serial);
		this.work = getFile(output, TEMP_DIR, serial);
		this.junitReport = getFile(output, JUNIT_DIR, serial + ".xml");
		this.imageDir = getFile(output, IMAGE_DIR, serial);
	}

	public boolean install(IDevice device) {
		this.device = device;
		result = new DeviceResult.Builder();
		try {
			// Now install the main application and the instrumentation application.
			String installError = device.installPackage(apk.getAbsolutePath(), true);
			if (installError != null) {
				logInfo("[%s] app apk install failed.  Error [%s]", serial, installError);
				result.markInstallAsFailed("Unable to install application APK.");
				return false;
			}
			installError = device.installPackage(testApk.getAbsolutePath(), true);
			if (installError != null) {
				logInfo("[%s] test apk install failed.  Error [%s]", serial, installError);
				result.markInstallAsFailed("Unable to install instrumentation APK.");
				return false;
			}
		} catch (InstallException e) {
			logInfo("InstallException on device [%s]", serial);
			e.printStackTrace(System.out);
			result.markInstallAsFailed(e.getMessage());
			return false;
		}

		// Initiate device logging.
		deviceLogger = new SpoonDeviceLogger(device);

		result.startTests();

		return true;
	}


	/** Execute instrumentation on the target device and return a result summary.
	 * @param className
	 * @param methodName*/
	public boolean run(String className, String methodName) {
		String testPackage = instrumentationInfo.getInstrumentationPackage();
		String testRunner = instrumentationInfo.getTestRunnerClass();
		TestIdentifierAdapter testIdentifierAdapter = TestIdentifierAdapter.fromTestRunner(testRunner);

		logDebug(debug, "InstrumentationInfo: [%s]", instrumentationInfo);

		if (debug) {
			SpoonUtils.setDdmlibInternalLoggingLevel();
		}

		// Get relevant device information.
		final DeviceDetails deviceDetails = DeviceDetails.createForDevice(device);
		result.setDeviceDetails(deviceDetails);
		logDebug(debug, "[%s] setDeviceDetails %s", serial, deviceDetails);

		// Run all the tests! o/
		try {
			logDebug(debug, "About to actually run tests for [%s]", serial);
			RemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(testPackage, testRunner, device);
			runner.setMaxtimeToOutputResponse(adbTimeout);
			if (!Strings.isNullOrEmpty(className)) {
				if (Strings.isNullOrEmpty(methodName)) {
					runner.setClassName(className);
				} else {
					runner.setMethodName(className, methodName);
				}
			}
			if (testSize != null) {
				runner.setTestSize(testSize);
			}
			runner.run(
					new SpoonTestListener(result, debug, TestIdentifierAdapter.JUNIT),
					new XmlTestRunListener(junitReport)
			);
		} catch (Exception e) {
			result.addException(e);
			return false;
		}
		return true;
	}

	public DeviceResult finish() {

		String appPackage = instrumentationInfo.getApplicationPackage();

		// Grab all the parsed logs and map them to individual tests.
		Map<DeviceTest, List<LogCatMessage>> logs = deviceLogger.getParsedLogs();
		for (Map.Entry<DeviceTest, List<LogCatMessage>> entry : logs.entrySet()) {
			DeviceTestResult.Builder builder = result.getMethodResultBuilder(entry.getKey());
			if (builder != null) {
				builder.setLog(entry.getValue());
			}
		}

		try {
			logDebug(debug, "About to grab screenshots and prepare output for [%s]", serial);

			// Sync device screenshots, if any, to the local filesystem.
			String dirName = "app_" + SPOON_SCREENSHOTS;
			// Create the output directory, if it does not already exist.
			work.mkdirs();
			String localDirName = work.getAbsolutePath();
			logInfo(localDirName);
			final String devicePath = "/data/data/" + appPackage + "/" + dirName;
			FileListingService.FileEntry deviceDir = obtainDirectoryFileEntry(devicePath);
			logDebug(debug, "Pulling screenshots from [%s] %s", serial, devicePath);

			device.getSyncService()
					.pull(new FileListingService.FileEntry[] {deviceDir}, localDirName, SyncService.getNullProgressMonitor());

			File screenshotDir = new File(work, dirName);
			if (screenshotDir.exists()) {
				imageDir.mkdirs();

				// Move all children of the screenshot directory into the image folder.
				File[] classNameDirs = screenshotDir.listFiles();
				if (classNameDirs != null) {
					Multimap<DeviceTest, File> testScreenshots = ArrayListMultimap.create();
					for (File classNameDir : classNameDirs) {
						String className = classNameDir.getName();
						File destDir = new File(imageDir, className);
						FileUtils.copyDirectory(classNameDir, destDir);

						// Get a sorted list of all screenshots from the device run.
						List<File> screenshots = new ArrayList<File>(
								FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
						Collections.sort(screenshots);

						// Iterate over each screenshot and associate it with its corresponding method result.
						for (File screenshot : screenshots) {
							String methodName = screenshot.getParentFile().getName();

							DeviceTest testIdentifier = new DeviceTest(className, methodName);
							DeviceTestResult.Builder builder = result.getMethodResultBuilder(testIdentifier);
							if (builder != null) {
								builder.addScreenshot(screenshot);
								testScreenshots.put(testIdentifier, screenshot);
							} else {
								logError("Unable to find test for %s", testIdentifier);
							}
						}
					}

					// Don't generate animations if the switch is present
					if (!noAnimations) {
						// Make animated GIFs for all the tests which have screenshots.
						for (DeviceTest deviceTest : testScreenshots.keySet()) {
							List<File> screenshots = new ArrayList<File>(testScreenshots.get(deviceTest));
							if (screenshots.size() == 1) {
								continue; // Do not make an animated GIF if there is only one screenshot.
							}
							File animatedGif = getFile(imageDir, deviceTest.getClassName(),
									deviceTest.getMethodName() + ".gif");
							createAnimatedGif(screenshots, animatedGif);
							result.getMethodResultBuilder(deviceTest).setAnimatedGif(animatedGif);
						}
					}
				}
				FileUtils.deleteDirectory(screenshotDir);
			}
		} catch (Exception e) {
			result.addException(e);
		}

		return result.endTests().build();
	}

	private static File getFile(File dir, String... parts) {
		File file = dir;
		for (String part : parts) {
			file = new File(file, part);
		}
		return file;
	}

}
