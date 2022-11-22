/*
 * Copyright 2000-2022 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.server.frontend;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.server.ExecutionFailedException;
import com.vaadin.flow.server.frontend.installer.NodeInstaller;
import com.vaadin.flow.shared.util.SharedUtil;

import elemental.json.Json;
import elemental.json.JsonObject;

/**
 * Compiles the dev mode bundle if it is out of date.
 * <p>
 * Only used when running in dev mode without a dev server.
 * <p>
 * For internal use only. May be renamed or removed in a future release.
 *
 * @since 2.0
 */
public class TaskRunDevBundleBuild extends TaskRunNpmInstall {

    private final boolean requireHomeNodeExec;
    private final boolean autoUpdate;

    private final String nodeVersion;
    private final URI nodeDownloadRoot;
    private final boolean useGlobalPnpm;

    private File npmFolder;

    /**
     * Create an instance of the command.
     *
     * @param requireHomeNodeExec
     *            whether vaadin home node executable has to be used
     * @param nodeVersion
     *            The node.js version to be used when node.js is installed
     *            automatically by Vaadin, for example <code>"v16.0.0"</code>.
     *            Use {@value FrontendTools#DEFAULT_NODE_VERSION} by default.
     * @param nodeDownloadRoot
     *            Download node.js from this URL. Handy in heavily firewalled
     *            corporate environments where the node.js download can be
     *            provided from an intranet mirror. Use
     *            {@link NodeInstaller#DEFAULT_NODEJS_DOWNLOAD_ROOT} by default.
     * @param autoUpdate
     *            {@code true} to automatically update to a new node version
     * @param additionalPostinstallPackages
     */
    TaskRunDevBundleBuild(NodeUpdater packageUpdater, boolean enablePnpm,
            boolean requireHomeNodeExec, String nodeVersion,
            URI nodeDownloadRoot, boolean useGlobalPnpm, boolean autoUpdate,
            List<String> additionalPostinstallPackages) {
        super(packageUpdater, enablePnpm, requireHomeNodeExec, nodeVersion,
                nodeDownloadRoot, useGlobalPnpm, autoUpdate,
                additionalPostinstallPackages);
        this.npmFolder = packageUpdater.npmFolder;
        this.requireHomeNodeExec = requireHomeNodeExec;
        this.nodeVersion = Objects.requireNonNull(nodeVersion);
        this.nodeDownloadRoot = Objects.requireNonNull(nodeDownloadRoot);
        this.useGlobalPnpm = useGlobalPnpm;
        this.autoUpdate = autoUpdate;
    }

    @Override
    public void execute() throws ExecutionFailedException {
        if (!needsBuild()) {
            getLogger().debug("No development frontend bundle build is needed");
            return;
        }

        getLogger().info(
                "Creating a new development frontend bundle. This can take a while but will only run when the project setup is changed, addons are added or frontend files are modified");

        super.execute();
        runFrontendBuildTool("Vite", "vite/bin/vite.js", Collections.emptyMap(),
                "build");
    }

    public boolean needsBuild() {
        getLogger().info(
                "Checking if a development frontend bundle build is needed");

        try {
            boolean needsBuild = needsBuildInternal(npmFolder);
            if (needsBuild) {
                getLogger()
                        .info("A development frontend bundle build is needed");
            } else {
                getLogger().info(
                        "A development frontend bundle build is not needed");
            }
            return needsBuild;
        } catch (Exception e) {
            getLogger().error("Error when checking if a build is needed", e);
            return true;
        }
    }

    private static boolean needsBuildInternal(File npmFolder)
            throws IOException {

        File devBundleFolder = new File(npmFolder, "dev-bundle");
        if (!devBundleFolder.exists()) {
            getLogger().info("There is no bundle in the "
                    + devBundleFolder.getAbsolutePath() + " folder");
            return true;
        }

        File packageJson = new File(npmFolder, "package.json");
        File bundleStatsJson = new File(new File(devBundleFolder, "config"),
                "stats.json");

        String packageJsonHash = null;
        String bundlePackageJsonHash = null;

        if (packageJson.exists()) {
            JsonObject json = Json.parse(FileUtils.readFileToString(packageJson,
                    StandardCharsets.UTF_8));
            if (json.hasKey("vaadin")
                    && json.getObject("vaadin").hasKey("hash")) {
                packageJsonHash = json.getObject("vaadin").getString("hash");
            }
        }

        if (bundleStatsJson.exists()) {
            JsonObject json = Json.parse(FileUtils
                    .readFileToString(bundleStatsJson, StandardCharsets.UTF_8));
            if (json.hasKey("packageJsonHash")) {
                bundlePackageJsonHash = json.getString("packageJsonHash");
            }
        }
        if (packageJsonHash != null && !packageJsonHash.isEmpty()) {
            if (!packageJsonHash.equals(bundlePackageJsonHash)) {
                getLogger().info(
                        "The package.json file has changed since the bundle was built (package.json hash "
                                + packageJsonHash + ", dev bundle hash "
                                + bundlePackageJsonHash + ")");
                return true;
            }
        }

        return false;
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(TaskRunDevBundleBuild.class);
    }

    private void runFrontendBuildTool(String toolName, String executable,
            Map<String, String> environment, String... params)
            throws ExecutionFailedException {
        Logger logger = getLogger();

        FrontendToolsSettings settings = new FrontendToolsSettings(
                npmFolder.getAbsolutePath(),
                () -> FrontendUtils.getVaadinHomeDirectory().getAbsolutePath());
        settings.setNodeDownloadRoot(nodeDownloadRoot);
        settings.setForceAlternativeNode(requireHomeNodeExec);
        settings.setUseGlobalPnpm(useGlobalPnpm);
        settings.setAutoUpdate(autoUpdate);
        settings.setNodeVersion(nodeVersion);
        FrontendTools frontendTools = new FrontendTools(settings);

        File buildExecutable = new File(npmFolder,
                "node_modules/" + executable);
        if (!buildExecutable.isFile()) {
            throw new IllegalStateException(String.format(
                    "Unable to locate %s executable by path '%s'. Double"
                            + " check that the plugin is executed correctly",
                    toolName, buildExecutable.getAbsolutePath()));
        }

        String nodePath;
        if (requireHomeNodeExec) {
            nodePath = frontendTools.forceAlternativeNodeExecutable();
        } else {
            nodePath = frontendTools.getNodeExecutable();
        }

        List<String> command = new ArrayList<>();
        command.add(nodePath);
        command.add(buildExecutable.getAbsolutePath());
        command.addAll(Arrays.asList(params));

        String commandString = command.stream()
                .collect(Collectors.joining(" "));

        ProcessBuilder builder = FrontendUtils.createProcessBuilder(command);

        Process process = null;
        try {
            builder.directory(npmFolder);
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);

            process = builder.start();

            // This will allow to destroy the process which does IO regardless
            // whether it's executed in the same thread or another (may be
            // daemon) thread
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(process::destroyForcibly));

            logger.debug("Output of `{}`:", commandString);
            StringBuilder toolOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String stdoutLine;
                while ((stdoutLine = reader.readLine()) != null) {
                    logger.debug(stdoutLine);
                    toolOutput.append(stdoutLine)
                            .append(System.lineSeparator());
                }
            }

            int errorCode = process.waitFor();

            if (errorCode != 0) {
                logger.error("Command `{}` failed:\n{}", commandString,
                        toolOutput);
                throw new ExecutionFailedException(
                        SharedUtil.capitalize(toolName)
                                + " build exited with a non zero status");
            } else {
                logger.info("Development frontend bundle built");
            }
        } catch (InterruptedException | IOException e) {
            logger.error("Error when running `{}`", commandString, e);
            if (e instanceof InterruptedException) {
                // Restore interrupted state
                Thread.currentThread().interrupt();
            }
            throw new ExecutionFailedException(
                    "Command '" + commandString + "' failed to finish", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        // Check License
        // validateLicenses(adapter);
    }

}
