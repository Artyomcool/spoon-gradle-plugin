package com.squareup.spoon;

import com.android.ddmlib.MultiLineReceiver;

public class LauncherComponentReceiver extends MultiLineReceiver {
	private boolean gotPreviousLine;
	private String launcherComponent;

	@Override
	public void processNewLines(String[] lines) {
		if (gotPreviousLine) {
			String line = lines[0];
			launcherComponent = parseLauncherComponent(line);
			return;
		}
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.trim().equals("android.intent.action.MAIN:")) {
				gotPreviousLine = true;
				if (i < lines.length - 2) {
					launcherComponent = parseLauncherComponent(lines[i + 1]);
				}
			}
		}
	}

	private String parseLauncherComponent(String line) {
        String[] words = line.trim().split(" ");
        if (words.length < 2) {
            throw new IllegalArgumentException("Wrong launcher string format: " + line);
        }
        return words[1];
    }

	@Override
	public boolean isCancelled() {
		return launcherComponent != null;
	}

	public String getLauncherComponent() {
		return launcherComponent;
	}
}
