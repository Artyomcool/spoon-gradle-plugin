package com.squareup.spoon;

import com.android.ddmlib.*;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.squareup.spoon.adapters.TestIdentifierAdapter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

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
    private XmlTestRunListener xmlTestRunListener;

    private boolean started;

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

        xmlTestRunListener = new XmlTestRunListener(junitReport);
        xmlTestRunListener.getRunResult().setAggregateMetrics(true);
	}

	public boolean install(IDevice device, boolean allowDowngrade) {
		this.device = device;
		result = new DeviceResult.Builder();
		try {
			// Now install the main application and the instrumentation application.
			String downgradeFlag = allowDowngrade ? "-d" : "";
			String installError = device.installPackage(apk.getAbsolutePath(), true, downgradeFlag);
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
	public boolean run(final String className, final String methodName) {
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
			logInfo("About to actually run tests for [%s]", serial);
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
            xmlTestRunListener.getRunResult().setRunComplete(false);
            runner.run(
                    new SpoonTestListener(result, debug, TestIdentifierAdapter.JUNIT),
                    new ITestRunListener() {
                        @Override
                        public void testRunStarted(String runName, int testCount) {
                            if (!started) {
                                started = true;
                                xmlTestRunListener.testRunStarted(runName, testCount);
                            }
                        }

                        @Override
                        public void testStarted(TestIdentifier test) {
                            xmlTestRunListener.testStarted(test);

                        }

                        @Override
                        public void testFailed(TestIdentifier test, String trace) {
                            xmlTestRunListener.testFailed(test, trace);
                        }

                        @Override
                        public void testAssumptionFailure(TestIdentifier test, String trace) {
                            xmlTestRunListener.testAssumptionFailure(test, trace);
                        }

                        @Override
                        public void testIgnored(TestIdentifier test) {
                            xmlTestRunListener.testIgnored(test);
                        }

                        @Override
                        public void testEnded(TestIdentifier test, Map<String, String> testMetrics) {
                            takeScreenshot(className, methodName);
                            xmlTestRunListener.testEnded(test, testMetrics);
                        }

                        @Override
                        public void testRunFailed(String errorMessage) {
                            xmlTestRunListener.testRunFailed(errorMessage);
                        }

                        @Override
                        public void testRunStopped(long elapsedTime) {
                            xmlTestRunListener.testRunStopped(elapsedTime);
                        }

                        @Override
                        public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
                            xmlTestRunListener.getRunResult().testRunEnded(elapsedTime, runMetrics);
                        }
                    }
            );
        } catch (Exception e) {
			result.addException(e);
			return false;
		}
		return true;
	}

    private void takeScreenshot(String className, String methodName) {
        BufferedImage image = null;
        try {
            image = getScreenshot();
            imageDir.mkdirs();
            File output = getScreenshotFile(className, methodName);
            ImageIO.write(image, "png", output);
        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (AdbCommandRejectedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private BufferedImage getScreenshot() throws TimeoutException, AdbCommandRejectedException, IOException {
        RawImage screenshot = device.getScreenshot();
        BufferedImage image = new BufferedImage(screenshot.width, screenshot.height, BufferedImage.TYPE_INT_ARGB);
        int index=0;
        int bytesPerPixel=screenshot.bpp >> 3;
        for (int y=0; y < screenshot.height; y++) {
            for (int x=0; x < screenshot.width; x++) {
                image.setRGB(x,y,screenshot.getARGB(index) | 0xff000000);
                index+=bytesPerPixel;
            }
        }
        return image;
    }

    public DeviceResult finish() {
        xmlTestRunListener.getRunResult().setRunComplete(false);
        xmlTestRunListener.testRunEnded(0, new HashMap<String, String>());

		String appPackage = instrumentationInfo.getApplicationPackage();

		// Grab all the parsed logs and map them to individual tests.
		Map<DeviceTest, List<LogCatMessage>> logs = deviceLogger.getParsedLogs();
		for (Map.Entry<DeviceTest, List<LogCatMessage>> entry : logs.entrySet()) {
			DeviceTestResult.Builder builder = result.getMethodResultBuilder(entry.getKey());
			if (builder != null) {
				builder.setLog(entry.getValue());
                String className = entry.getKey().getClassName();
                String methodName = entry.getKey().getMethodName();
                File screenshot = getScreenshotFile(className, methodName);
                builder.addScreenshot(screenshot);
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
			logDebug(debug, "Pulling screenshots from [%s] %s", serial, devicePath);

		} catch (Exception e) {
			result.addException(e);
		}

		return result.endTests().build();
	}

    private File getScreenshotFile(String className, String methodName) {
        return new File(imageDir, "screen_" + className + "-" + methodName + ".png");
    }

    private static File getFile(File dir, String... parts) {
		File file = dir;
		for (String part : parts) {
			file = new File(file, part);
		}
		return file;
	}

}
