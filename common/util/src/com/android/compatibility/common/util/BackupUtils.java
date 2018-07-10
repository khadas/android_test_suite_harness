/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.compatibility.common.util;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for backup and restore.
 */
public abstract class BackupUtils {
    public static final String LOCAL_TRANSPORT =
            "android/com.android.internal.backup.LocalTransport";
    public static final String LOCAL_TRANSPORT_TOKEN = "1";

    private static final int BACKUP_PROVISIONING_TIMEOUT_SECONDS = 30;
    private static final int BACKUP_PROVISIONING_POLL_INTERVAL_SECONDS = 1;

    private static final Pattern BACKUP_MANAGER_CURRENTLY_ENABLE_STATUS_PATTERN =
            Pattern.compile("^Backup Manager currently (enabled|disabled)$");
    private static final String MATCH_LINE_BACKUP_MANAGER_IS_NOT_PENDING_INIT =
            "(?s)" + "^Backup Manager is .* not pending init.*";  // DOTALL

    private static final String BACKUP_DUMPSYS_CURRENT_TOKEN_FIELD = "Current:";

    /**
     * Kicks off adb shell {@param command} and return an {@link InputStream} with the command
     * output stream.
     */
    protected abstract InputStream executeShellCommand(String command) throws IOException;

    public void executeShellCommandSync(String command) throws IOException {
        StreamUtil.drainAndClose(new InputStreamReader(executeShellCommand(command)));
    }

    public String getShellCommandOutput(String command) throws IOException {
        return StreamUtil.readInputStream(executeShellCommand(command));
    }

    /** Executes shell command "bmgr backupnow <package>" and assert success. */
    public void backupNowAndAssertSuccess(String packageName) throws IOException {
        assertBackupIsSuccessful(packageName, backupNow(packageName));
    }

    public void backupNowAndAssertBackupNotAllowed(String packageName) throws IOException {
        assertBackupNotAllowed(packageName, getBackupNowOutput(packageName));
    }

    /** Executes shell command "bmgr backupnow <package>" and waits for completion. */
    public void backupNowSync(String packageName) throws IOException {
        StreamUtil.drainAndClose(new InputStreamReader(backupNow(packageName)));
    }

    public String getBackupNowOutput(String packageName) throws IOException {
        return StreamUtil.readInputStream(backupNow(packageName));
    }

    /** Executes shell command "bmgr restore <token> <package>" and assert success. */
    public void restoreAndAssertSuccess(String token, String packageName) throws IOException {
        assertRestoreIsSuccessful(restore(token, packageName));
    }

    public void restoreSync(String token, String packageName) throws IOException {
        StreamUtil.drainAndClose(new InputStreamReader(restore(token, packageName)));
    }

    public String getRestoreOutput(String token, String packageName) throws IOException {
        return StreamUtil.readInputStream(restore(token, packageName));
    }

    public boolean isLocalTransportSelected() throws IOException {
        return getShellCommandOutput("bmgr list transports").contains("* " + LOCAL_TRANSPORT);
    }

    public boolean isBackupEnabled() throws IOException {
        return getShellCommandOutput("bmgr enabled").contains("currently enabled");
    }

    /** Executes "bmgr backupnow <package>" and returns an {@link InputStream} for its output. */
    private InputStream backupNow(String packageName) throws IOException {
        return executeShellCommand("bmgr backupnow " + packageName);
    }

    /**
     * Parses the output of "bmgr backupnow" command and checks that {@code packageName} wasn't
     * allowed to backup.
     *
     * Expected format: "Package <packageName> with result:  Backup is not allowed"
     *
     * TODO: Read input stream instead of string.
     */
    private void assertBackupNotAllowed(String packageName, String backupNowOutput) {
        Scanner in = new Scanner(backupNowOutput);
        boolean found = false;
        while (in.hasNextLine()) {
            String line = in.nextLine();

            if (line.contains(packageName)) {
                String result = line.split(":")[1].trim();
                if ("Backup is not allowed".equals(result)) {
                    found = true;
                }
            }
        }
        in.close();
        assertTrue("Didn't find \'Backup not allowed\' in the output", found);
    }

    /**
     * Parses the output of "bmgr backupnow" command checking that the package {@code packageName}
     * was backed up successfully. Closes the input stream.
     *
     * Expected format: "Package <package> with result: Success"
     */
    private void assertBackupIsSuccessful(String packageName, InputStream backupNowOutput)
            throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(backupNowOutput, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(packageName)) {
                    String result = line.split(":")[1].trim().toLowerCase();
                    if ("success".equals(result)) {
                        return;
                    }
                }
            }
            fail("Couldn't find package in output or backup wasn't successful");
        } finally {
            StreamUtil.drainAndClose(reader);
        }
    }

    /**
     * Executes "bmgr restore <token> <packageName>" and returns an {@link InputStream} for its
     * output.
     */
    private InputStream restore(String token, String packageName) throws IOException {
        return executeShellCommand(String.format("bmgr restore %s %s", token, packageName));
    }

    /**
     * Parses the output of "bmgr restore" command and checks that the package under test
     * was restored successfully. Closes the input stream.
     *
     * Expected format: "restoreFinished: 0"
     */
    private void assertRestoreIsSuccessful(InputStream restoreOutput) throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(restoreOutput, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("restoreFinished: 0")) {
                    return;
                }
            }
            fail("Restore not successful");
        } finally {
            StreamUtil.drainAndClose(reader);
        }
    }

    /** Executes "dumpsys backup" and returns an {@link InputStream} for its output. */
    private InputStream dumpsysBackup() throws IOException {
        return executeShellCommand("dumpsys backup");
    }

    /**
     * Parses the output of "dumpsys backup" command to get token. Closes the input stream finally.
     *
     * Expected format: "Current: token"
     */
    private String getCurrentTokenOrFail(InputStream dumpsysOutput) throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(dumpsysOutput, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(BACKUP_DUMPSYS_CURRENT_TOKEN_FIELD)) {
                    return line.split(BACKUP_DUMPSYS_CURRENT_TOKEN_FIELD)[1].trim();
                }
            }
            throw new AssertionError("Couldn't find token in output");
        } finally {
            StreamUtil.drainAndClose(reader);
        }
    }

    /**
     * Execute shell command and return output from this command.
     */
    public String executeShellCommandAndReturnOutput(String command) throws IOException {
        InputStream in = executeShellCommand(command);
        BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        String str;
        StringBuilder out = new StringBuilder();
        while ((str = br.readLine()) != null) {
            out.append(str).append("\n");
        }
        return out.toString();
    }

    // Copied over from BackupQuotaTest
    public boolean enableBackup(boolean enable) throws Exception {
        boolean previouslyEnabled;
        String output = getLineString(executeShellCommand("bmgr enabled"));
        Matcher matcher = BACKUP_MANAGER_CURRENTLY_ENABLE_STATUS_PATTERN.matcher(output.trim());
        if (matcher.find()) {
            previouslyEnabled = "enabled".equals(matcher.group(1));
        } else {
            throw new RuntimeException("non-parsable output setting bmgr enabled: " + output);
        }

        executeShellCommand("bmgr enable " + enable);
        return previouslyEnabled;
    }

    private String getLineString(InputStream inputStream) throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String str;
        try {
            str = reader.readLine();
        } finally {
            StreamUtil.drainAndClose(reader);
        }
        return str;
    }

    public void waitForBackupInitialization() throws IOException {
        long tryUntilNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(BACKUP_PROVISIONING_TIMEOUT_SECONDS);
        while (System.nanoTime() < tryUntilNanos) {
            String output = getLineString(executeShellCommand("dumpsys backup"));
            if (output.matches(MATCH_LINE_BACKUP_MANAGER_IS_NOT_PENDING_INIT)) {
                return;
            }
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(BACKUP_PROVISIONING_POLL_INTERVAL_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("Timed out waiting for backup initialization");
    }
}

