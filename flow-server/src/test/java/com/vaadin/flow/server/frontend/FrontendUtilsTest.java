/*
 * Copyright 2000-2020 Vaadin Ltd.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.jcip.annotations.NotThreadSafe;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.frontend.installer.Platform;
import com.vaadin.flow.server.frontend.installer.ProxyConfig;

import static com.vaadin.flow.server.Constants.SERVLET_PARAMETER_STATISTICS_JSON;
import static com.vaadin.flow.server.Constants.STATISTICS_JSON_DEFAULT;
import static com.vaadin.flow.server.Constants.VAADIN_SERVLET_RESOURCES;
import static com.vaadin.flow.server.frontend.FrontendUtils.checkForFaultyNpmVersion;
import static com.vaadin.flow.server.frontend.NodeUpdateTestUtil.createStubNode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

@NotThreadSafe
public class FrontendUtilsTest {

    private static final String USER_HOME = "user.home";

    public static final String DEFAULT_NODE = FrontendUtils.isWindows()
            ? "node\\node.exe"
            : "node/node";

    public static final String NPM_CLI_STRING = Stream
            .of("node", "node_modules", "npm", "bin", "npm-cli.js")
            .collect(Collectors.joining(File.separator));

    public static final String PNPM_INSTALL_LOCATION = Stream
            .of("node_modules", "pnpm", "bin", "pnpm.js")
            .collect(Collectors.joining(File.separator));

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Rule
    public final TemporaryFolder tmpDir = new TemporaryFolder();

    @Rule
    public final TemporaryFolder tmpDirWithNpmrc = new TemporaryFolder();

    private String baseDir;

    @Before
    public void setup() {
        baseDir = tmpDir.getRoot().getAbsolutePath();
    }

    @Test
    public synchronized void should_useProjectNodeFirst() throws Exception {
        Assume.assumeFalse(
                "Skipping test on windows until a fake node.exe that isn't caught by Window defender can be created.",
                FrontendUtils.isWindows());
        createStubNode(true, true, baseDir);

        assertNodeCommand(() -> baseDir);
    }

    @Test
    public synchronized void should_useHomeFirst() throws Exception {
        Assume.assumeFalse(
                "Skipping test on windows until a fake node.exe that isn't caught by Window defender can be created.",
                FrontendUtils.isWindows());
        assertNodeCommand(() -> getVaadinHomeDir());
    }

    @Test
    public synchronized void should_useProjectNpmFirst() throws Exception {
        Assume.assumeFalse(
                "Skipping test on windows until a fake node.exe that isn't caught by Window defender can be created.",
                FrontendUtils.isWindows());
        createStubNode(false, true, baseDir);

        assertNpmCommand(() -> baseDir);
    }

    @Test
    public synchronized void should_useHomeNpmFirst() throws Exception {
        Assume.assumeFalse(
                "Skipping test on windows until a fake node.exe that isn't caught by Window defender can be created.",
                FrontendUtils.isWindows());
        assertNpmCommand(() -> getVaadinHomeDir());
    }

    @Test
    @Ignore("Ignored to lessen PRs hitting the server too often")
    public void installNode_NodeIsInstalledToTargetDirectory()
            throws FrontendUtils.UnknownVersionException {
        File targetDir = new File(baseDir + "/installation");

        Assert.assertFalse(
                "Clean test should not contain a installation folder",
                targetDir.exists());

        String nodeExecutable = FrontendUtils.installNode(baseDir, targetDir,
                "v12.16.0", null);
        Assert.assertNotNull(nodeExecutable);

        List<String> nodeVersionCommand = new ArrayList<>();
        nodeVersionCommand.add(nodeExecutable);
        nodeVersionCommand.add("--version");
        FrontendVersion node = FrontendUtils.getVersion("node",
                nodeVersionCommand);
        Assert.assertEquals("12.16.0", node.getFullVersion());

        List<String> npmVersionCommand = new ArrayList<>(
                FrontendUtils.getNpmExecutable(targetDir.getPath()));
        npmVersionCommand.add("--version");
        FrontendVersion npm = FrontendUtils.getVersion("npm",
                npmVersionCommand);
        Assert.assertEquals("6.13.4", npm.getFullVersion());

    }

    @Test
    public void installNodeFromFileSystem_NodeIsInstalledToTargetDirectory()
            throws IOException {
        Platform platform = Platform.guess();
        String nodeExec = platform.isWindows() ? "node.exe" : "node";
        String prefix = "node-v12.16.0-" + platform.getNodeClassifier();

        File targetDir = new File(baseDir + "/installation");

        Assert.assertFalse(
                "Clean test should not contain a installation folder",
                targetDir.exists());
        File downloadDir = tmpDir.newFolder("v12.16.0");
        File archiveFile = new File(downloadDir,
                prefix + "." + platform.getArchiveExtension());
        archiveFile.createNewFile();
        Path tempArchive = archiveFile.toPath();

        if (platform.getArchiveExtension().equals("zip")) {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(
                    Files.newOutputStream(tempArchive))) {
                zipOutputStream
                        .putNextEntry(new ZipEntry(prefix + "/" + nodeExec));
                zipOutputStream.closeEntry();
                zipOutputStream.putNextEntry(
                        new ZipEntry(prefix + "/node_modules/npm/bin/npm"));
                zipOutputStream.closeEntry();
                zipOutputStream.putNextEntry(
                        new ZipEntry(prefix + "/node_modules/npm/bin/npm.cmd"));
                zipOutputStream.closeEntry();
            }
        } else {
            try (OutputStream fo = Files.newOutputStream(tempArchive);
                    OutputStream gzo = new GzipCompressorOutputStream(fo);
                    ArchiveOutputStream o = new TarArchiveOutputStream(gzo)) {
                o.putArchiveEntry(o.createArchiveEntry(
                        new File(prefix + "/bin/" + nodeExec),
                        prefix + "/bin/" + nodeExec));
                o.closeArchiveEntry();
                o.putArchiveEntry(o.createArchiveEntry(
                        new File(prefix + "/bin/npm"), prefix + "/bin/npm"));
                o.closeArchiveEntry();
                o.putArchiveEntry(o.createArchiveEntry(
                        new File(prefix + "/lib/node_modules/npm/bin/npm"),
                        prefix + "/lib/node_modules/npm/bin/npm"));
                o.closeArchiveEntry();
                o.putArchiveEntry(o.createArchiveEntry(
                        new File(prefix + "/lib/node_modules/npm/bin/npm.cmd"),
                        prefix + "/lib/node_modules/npm/bin/npm.cmd"));
                o.closeArchiveEntry();
            }
        }

        String nodeExecutable = FrontendUtils.installNode(baseDir, targetDir,
                "v12.16.0", new File(baseDir).toPath().toUri());
        Assert.assertNotNull(nodeExecutable);

        Assert.assertTrue("npm should have been copied to node_modules",
                new File(targetDir, "node/node_modules/npm/bin/npm").exists());
    }

    @Test
    public void should_useSystemNode() {
        assertThat(FrontendUtils.getNodeExecutable(baseDir),
                containsString("node"));
        assertThat(FrontendUtils.getNodeExecutable(baseDir),
                not(containsString(DEFAULT_NODE)));
        assertThat(FrontendUtils.getNodeExecutable(baseDir),
                not(containsString(NPM_CLI_STRING)));

        assertEquals(3, FrontendUtils.getNpmExecutable(baseDir).size());
        assertThat(FrontendUtils.getNpmExecutable(baseDir).get(0),
                containsString("npm"));
        assertThat(FrontendUtils.getNpmExecutable(baseDir).get(1),
                containsString("--no-update-notifier"));
        assertThat(FrontendUtils.getNpmExecutable(baseDir).get(2),
                containsString("--no-audit"));
    }

    @Test
    public void getNpmExecutable_removesPnpmLock() throws IOException {
        File file = new File(baseDir, "pnpm-lock.yaml");
        file.createNewFile();

        FrontendUtils.getNpmExecutable(baseDir);

        Assert.assertFalse(file.exists());
    }

    @Test
    public void parseValidVersions() {
        FrontendVersion sixPointO = new FrontendVersion(6, 0);

        FrontendVersion requiredVersionTen = new FrontendVersion(10, 0);
        assertFalse(
                FrontendUtils.isVersionAtLeast(sixPointO, requiredVersionTen));
        assertFalse(FrontendUtils.isVersionAtLeast(sixPointO,
                new FrontendVersion(6, 1)));
        assertTrue(FrontendUtils.isVersionAtLeast(new FrontendVersion("10.0.0"),
                requiredVersionTen));
        assertTrue(FrontendUtils.isVersionAtLeast(new FrontendVersion("10.0.2"),
                requiredVersionTen));
        assertTrue(FrontendUtils.isVersionAtLeast(new FrontendVersion("10.2.0"),
                requiredVersionTen));
    }

    @Test
    public void validateLargerThan_passesForNewVersion() {
        FrontendUtils.validateToolVersion("test", new FrontendVersion("10.0.2"),
                new FrontendVersion(10, 0), new FrontendVersion(10, 0));
        FrontendUtils.validateToolVersion("test", new FrontendVersion("10.1.2"),
                new FrontendVersion(10, 0), new FrontendVersion(10, 0));
        FrontendUtils.validateToolVersion("test", new FrontendVersion("11.0.2"),
                new FrontendVersion(10, 0), new FrontendVersion(10, 0));
    }

    @Test
    public void validateLargerThan_logsForSlightlyOldVersion()
            throws UnsupportedEncodingException {
        PrintStream orgErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setErr(new PrintStream(out));
        try {
            FrontendUtils.validateToolVersion("test",
                    new FrontendVersion(9, 0, 0), new FrontendVersion(10, 0),
                    new FrontendVersion(8, 0));
            String logged = out.toString("utf-8")
                    // fix for windows
                    .replace("\r", "");
            Assert.assertTrue(logged.contains(
                    "Your installed 'test' version (9.0.0) is not supported but should still work. Supported versions are 10.0+\n"));
        } finally {
            System.setErr(orgErr);
        }
    }

    @Test
    public void validateLargerThan_throwsForOldVersion() {
        try {
            FrontendUtils.validateToolVersion("test",
                    new FrontendVersion(7, 5, 0), new FrontendVersion(10, 0),
                    new FrontendVersion(8, 0));
            Assert.fail("No exception was thrown for old version");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains(
                    "Your installed 'test' version (7.5.0) is too old. Supported versions are 10.0+"));
        }
    }

    @Test
    public void validateLargerThan_ignoredWithProperty() {
        try {
            System.setProperty("vaadin.ignoreVersionChecks", "true");
            FrontendUtils.validateToolVersion("test", new FrontendVersion(0, 0),
                    new FrontendVersion(10, 2), new FrontendVersion(10, 2));
        } finally {
            System.clearProperty("vaadin.ignoreVersionChecks");
        }
    }

    @Test
    public void parseValidToolVersions() throws IOException {
        Assert.assertEquals("10.11.12",
                FrontendUtils.parseVersionString("v10.11.12"));
        Assert.assertEquals("8.0.0",
                FrontendUtils.parseVersionString("v8.0.0"));
        Assert.assertEquals("8.0.0", FrontendUtils.parseVersionString("8.0.0"));
        Assert.assertEquals("6.9.0", FrontendUtils.parseVersionString(
                "Aktive Codepage: 1252\n" + "6.9.0\n" + ""));
    }

    @Test(expected = IOException.class)
    public void parseEmptyToolVersions() throws IOException {
        FrontendUtils.parseVersionString(" \n");
    }

    @Test
    public void knownFaultyNpmVersionThrowsException() {
        assertFaultyNpmVersion(new FrontendVersion(6, 11, 0));
        assertFaultyNpmVersion(new FrontendVersion(6, 11, 1));
        assertFaultyNpmVersion(new FrontendVersion(6, 11, 2));
    }

    private void assertFaultyNpmVersion(FrontendVersion version) {
        try {
            checkForFaultyNpmVersion(version);
            Assert.fail("No exception was thrown for bad npm version");
        } catch (IllegalStateException e) {
            Assert.assertTrue(
                    "Faulty version " + version.getFullVersion()
                            + " returned wrong exception message",
                    e.getMessage()
                            .contains("Your installed 'npm' version ("
                                    + version.getFullVersion()
                                    + ") is known to have problems."));
        }
    }

    @Test
    public void assetsByChunkIsCorrectlyParsedFromStats() throws IOException {
        VaadinService service = setupStatsAssetMocks("ValidStats.json");

        String statsAssetsByChunkName = FrontendUtils
                .getStatsAssetsByChunkName(service);

        Assert.assertEquals("{" + "\"index\": \"build/index-1111.cache.js\","
                + "\"index.es5\": \"build/index.es5-2222.cache.js\"" + "}",
                statsAssetsByChunkName);
    }

    @Test
    public void formattingError_assetsByChunkIsCorrectlyParsedFromStats()
            throws IOException {
        VaadinService service = setupStatsAssetMocks("MissFormatStats.json");

        String statsAssetsByChunkName = FrontendUtils
                .getStatsAssetsByChunkName(service);

        Assert.assertEquals("{" + "\"index\": \"build/index-1111.cache.js\","
                + "\"index.es5\": \"build/index.es5-2222.cache.js\"" + "}",
                statsAssetsByChunkName);
    }

    @Test
    public void noStatsFile_assetsByChunkReturnsNull() throws IOException {
        VaadinService service = getServiceWithResource(null);

        String statsAssetsByChunkName = FrontendUtils
                .getStatsAssetsByChunkName(service);

        Assert.assertNull(statsAssetsByChunkName);
    }
    
    @Test
    public void faultyStatsFileReturnsNull() throws IOException {
        VaadinService service = setupStatsAssetMocks("InvalidStats.json");

        String statsAssetsByChunkName = FrontendUtils
                .getStatsAssetsByChunkName(service);

        Assert.assertNull(statsAssetsByChunkName);
    }

    /**
     * This test doesn't do anything if pnpm is already installed (globally)
     * which is true e.g. for or CI servers (TC/bender).
     */
    @Test
    public void ensurePnpm_requestInstall_keepPackageJson_removePackageLock_ignoredPnpmExists_localPnpmIsRemoved()
            throws IOException {
        Assume.assumeTrue(
                FrontendUtils.getPnpmExecutable(baseDir, false).isEmpty());
        File packageJson = new File(baseDir, "package.json");
        FileUtils.writeStringToFile(packageJson, "{}", StandardCharsets.UTF_8);

        File packageLockJson = new File(baseDir, "package-lock.json");
        FileUtils.writeStringToFile(packageLockJson, "{}",
                StandardCharsets.UTF_8);

        FrontendUtils.ensurePnpm(baseDir);
        Assert.assertFalse(
                FrontendUtils.getPnpmExecutable(baseDir, false).isEmpty());

        // locally installed pnpm (via npm/pnpm) is removed
        Assert.assertFalse(new File("node_modules/pnpm").exists());

        Assert.assertEquals("{}", FileUtils.readFileToString(packageJson,
                StandardCharsets.UTF_8));
        Assert.assertFalse(packageLockJson.exists());
    }

    @Test
    public void getPnpmExecutable_executableIsAvailable() {
        List<String> executable = FrontendUtils.getPnpmExecutable(baseDir);
        // command line should contain --shamefully-hoist=true option
        Assert.assertTrue(executable.contains("--shamefully-hoist=true"));
        Assert.assertTrue(
                executable.stream().anyMatch(cmd -> cmd.contains("pnpm")));
    }

    @Test
    public synchronized void getVaadinHomeDirectory_noVaadinFolder_folderIsCreated()
            throws IOException {
        String originalHome = System.getProperty(USER_HOME);
        File home = tmpDir.newFolder();
        System.setProperty(USER_HOME, home.getPath());
        try {
            File vaadinDir = new File(home, ".vaadin");
            if (vaadinDir.exists()) {
                FileUtils.deleteDirectory(vaadinDir);
            }
            File vaadinHomeDirectory = FrontendUtils.getVaadinHomeDirectory();
            Assert.assertTrue(vaadinHomeDirectory.exists());
            Assert.assertTrue(vaadinHomeDirectory.isDirectory());

            // access it one more time
            vaadinHomeDirectory = FrontendUtils.getVaadinHomeDirectory();
            Assert.assertEquals(".vaadin", vaadinDir.getName());
        } finally {
            System.setProperty(USER_HOME, originalHome);
        }
    }

    @Test(expected = FileNotFoundException.class)
    public synchronized void getVaadinHomeDirectory_vaadinFolderIsAFile_throws()
            throws IOException {
        String originalHome = System.getProperty(USER_HOME);
        File home = tmpDir.newFolder();
        System.setProperty(USER_HOME, home.getPath());
        try {
            File vaadinDir = new File(home, ".vaadin");
            if (vaadinDir.exists()) {
                FileUtils.deleteDirectory(vaadinDir);
            }
            vaadinDir.createNewFile();
            FrontendUtils.getVaadinHomeDirectory();
        } finally {
            System.setProperty(USER_HOME, originalHome);
        }
    }

    @Test
    public void commandToString_longCommand_resultIsWrapped() {
        List<String> command = Arrays.asList("./node/node",
                "./node_modules/webpack-dev-server/bin/webpack-dev-server.js",
                "--config", "./webpack.config.js", "--port 57799",
                "--watchDogPort=57798", "-d", "--inline=false",
                "--progress", "--colors");
        String wrappedCommand = FrontendUtils.commandToString(".", command);
        Assert.assertEquals("\n" + "./node/node \\ \n"
                + "    ./node_modules/webpack-dev-server/bin/webpack-dev-server.js \\ \n"
                + "    --config ./webpack.config.js --port 57799 \\ \n"
                + "    --watchDogPort=57798 -d --inline=false --progress \\ \n"
                + "    --colors \n", wrappedCommand);
    }

    @Test
    public void commandToString_commandContainsBaseDir_baseDirIsReplaced() {
        List<String> command = Arrays.asList("./node/node",
                "/somewhere/not/disclosable/node_modules/webpack-dev-server/bin/webpack-dev-server.js");
        String wrappedCommand = FrontendUtils.commandToString("/somewhere/not/disclosable", command);
        Assert.assertEquals("\n" + "./node/node \\ \n"
                + "    ./node_modules/webpack-dev-server/bin/webpack-dev-server.js \n", wrappedCommand);
    }

    @Test
    public void validateNodeAndNpmVersion_pnpmLockIsNotRemoved()
            throws IOException {
        File file = new File(baseDir, "pnpm-lock.yaml");
        file.createNewFile();

        FrontendUtils.validateNodeAndNpmVersion(baseDir);

        Assert.assertTrue(file.exists());
    }

    @Test(expected = IllegalStateException.class)
    public synchronized void ensureNodeExecutableInHome_vaadinHomeNodeIsAFolder_throws()
            throws IOException {
            String originalHome = System.getProperty(USER_HOME);
            File home = tmpDir.newFolder();
            System.setProperty(USER_HOME, home.getPath());
            try {
                File homeDir = FrontendUtils.getVaadinHomeDirectory();
                File node = new File(homeDir,
                        FrontendUtils.isWindows() ? "node/node.exe" : "node/node");
                FileUtils.forceMkdir(node);

            FrontendUtils.ensureNodeExecutableInHome(baseDir);

        } finally {
            System.setProperty(USER_HOME, originalHome);
        }
    }

    private VaadinService setupStatsAssetMocks(String statsFile)
            throws IOException {
        String stats = IOUtils.toString(FrontendUtilsTest.class.getClassLoader()
                .getResourceAsStream(statsFile), StandardCharsets.UTF_8);

        return getServiceWithResource(
                new ByteArrayInputStream(stats.getBytes()));
    }

    private void assertNodeCommand(Supplier<String> path) throws IOException {
        String home = tmpDir.newFolder().getAbsolutePath();
        String originalHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME, home);
        try {
            createStubNode(true, true,
                    FrontendUtils.getVaadinHomeDirectory().getAbsolutePath());

            assertThat(FrontendUtils.getNodeExecutable(baseDir),
                    containsString(DEFAULT_NODE));
            assertThat(FrontendUtils.getNodeExecutable(baseDir),
                    containsString(path.get()));
            List<String> npmExecutable = FrontendUtils
                    .getNpmExecutable(baseDir);
            assertThat(npmExecutable.get(0), containsString(path.get()));
            assertThat(npmExecutable.get(0), containsString(DEFAULT_NODE));
            assertThat(npmExecutable.get(1), containsString(NPM_CLI_STRING));
        } finally {
            System.setProperty(USER_HOME, originalHome);
        }
    }

    private void assertNpmCommand(Supplier<String> path) throws IOException {
        String home = tmpDir.newFolder().getAbsolutePath();
        String originalHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME, home);
        try {
            createStubNode(false, true,
                    FrontendUtils.getVaadinHomeDirectory().getAbsolutePath());

            assertThat(FrontendUtils.getNodeExecutable(baseDir),
                    containsString("node"));
            assertThat(FrontendUtils.getNodeExecutable(baseDir),
                    not(containsString(DEFAULT_NODE)));
            List<String> npmExecutable = FrontendUtils
                    .getNpmExecutable(baseDir);
            assertThat(npmExecutable.get(0), containsString("node"));
            assertThat(npmExecutable.get(1), containsString(NPM_CLI_STRING));
            assertThat(npmExecutable.get(1), containsString(path.get()));
        } finally {
            System.setProperty(USER_HOME, originalHome);
        }
    }

    private String getVaadinHomeDir() {
        try {
            return FrontendUtils.getVaadinHomeDirectory().getAbsolutePath();
        } catch (FileNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private VaadinService getServiceWithResource(InputStream stats) {
        VaadinService service = Mockito.mock(VaadinService.class);
        ClassLoader classLoader = Mockito.mock(ClassLoader.class);
        DeploymentConfiguration deploymentConfiguration = Mockito
                .mock(DeploymentConfiguration.class);

        Mockito.when(service.getClassLoader()).thenReturn(classLoader);
        Mockito.when(service.getDeploymentConfiguration())
                .thenReturn(deploymentConfiguration);
        Mockito.when(deploymentConfiguration.getStringProperty(
                SERVLET_PARAMETER_STATISTICS_JSON,
                VAADIN_SERVLET_RESOURCES + STATISTICS_JSON_DEFAULT))
                .thenReturn(VAADIN_SERVLET_RESOURCES + STATISTICS_JSON_DEFAULT);
        Mockito.when(classLoader.getResourceAsStream(
                VAADIN_SERVLET_RESOURCES + STATISTICS_JSON_DEFAULT))
                .thenReturn(stats);
        return service;
    }

    @Test
    public void getProxies_npmrcWithProxySetting_shouldReturnProxiesList()
            throws IOException {
        File npmrc = new File(tmpDirWithNpmrc.newFolder("test1"), ".npmrc");
        Properties properties = new Properties();
        properties.put(FrontendUtils.NPMRC_PROXY_PROPERTY_KEY,
                "http://httpuser:httppassword@httphost:8080");
        properties.put(FrontendUtils.NPMRC_HTTPS_PROXY_PROPERTY_KEY,
                "http://httpsuser:httpspassword@httpshost:8081");
        properties.put(FrontendUtils.NPMRC_NOPROXY_PROPERTY_KEY,
                "192.168.1.1,vaadin.com,mycompany.com");
        try (FileOutputStream fileOutputStream = new FileOutputStream(npmrc)) {
            properties.store(fileOutputStream, null);
        }

        List<ProxyConfig.Proxy> proxyList = FrontendUtils.getProxies(
                tmpDirWithNpmrc.getRoot().getAbsolutePath() + "/test1");
        Assert.assertEquals(2, proxyList.size());
        ProxyConfig.Proxy httpsProxy = proxyList.get(0).id.startsWith(
                "https-proxy") ? proxyList.get(0) : proxyList.get(1);
        ProxyConfig.Proxy httpProxy = proxyList.get(0).id.startsWith(
                "https-proxy") ? proxyList.get(1) : proxyList.get(0);

        Assert.assertEquals("http", httpProxy.protocol);
        Assert.assertEquals("httpuser", httpProxy.username);
        Assert.assertEquals("httppassword", httpProxy.password);
        Assert.assertEquals("httphost", httpProxy.host);
        Assert.assertEquals(8080, httpProxy.port);
        Assert.assertEquals("192.168.1.1|vaadin.com|mycompany.com",
                httpProxy.nonProxyHosts);

        Assert.assertEquals("http", httpsProxy.protocol);
        Assert.assertEquals("httpsuser", httpsProxy.username);
        Assert.assertEquals("httpspassword", httpsProxy.password);
        Assert.assertEquals("httpshost", httpsProxy.host);
        Assert.assertEquals(8081, httpsProxy.port);
        Assert.assertEquals("192.168.1.1|vaadin.com|mycompany.com",
                httpsProxy.nonProxyHosts);
    }

    @Test
    public void getProxies_npmrcWithProxySettingNoNoproxy_shouldReturnNullNoproxy()
            throws IOException {
        File npmrc = new File(tmpDirWithNpmrc.newFolder("test1"), ".npmrc");
        Properties properties = new Properties();
        properties.put(FrontendUtils.NPMRC_PROXY_PROPERTY_KEY,
                "http://httpuser:httppassword@httphost:8080");
        properties.put(FrontendUtils.NPMRC_HTTPS_PROXY_PROPERTY_KEY,
                "http://httpsuser:httpspassword@httpshost:8081");
        try (FileOutputStream fileOutputStream = new FileOutputStream(npmrc)) {
            properties.store(fileOutputStream, null);
        }

        List<ProxyConfig.Proxy> proxyList = FrontendUtils.getProxies(
                tmpDirWithNpmrc.getRoot().getAbsolutePath() + "/test1");
        Assert.assertEquals(2, proxyList.size());
        ProxyConfig.Proxy httpsProxy = proxyList.get(0).id.startsWith(
                "https-proxy") ? proxyList.get(0) : proxyList.get(1);
        ProxyConfig.Proxy httpProxy = proxyList.get(0).id.startsWith(
                "https-proxy") ? proxyList.get(1) : proxyList.get(0);

        Assert.assertEquals("http", httpProxy.protocol);
        Assert.assertEquals("httpuser", httpProxy.username);
        Assert.assertEquals("httppassword", httpProxy.password);
        Assert.assertEquals("httphost", httpProxy.host);
        Assert.assertEquals(8080, httpProxy.port);
        Assert.assertNull(httpProxy.nonProxyHosts);

        Assert.assertEquals("http", httpsProxy.protocol);
        Assert.assertEquals("httpsuser", httpsProxy.username);
        Assert.assertEquals("httpspassword", httpsProxy.password);
        Assert.assertEquals("httpshost", httpsProxy.host);
        Assert.assertEquals(8081, httpsProxy.port);
        Assert.assertNull(httpsProxy.nonProxyHosts);
    }

    @Test
    public void getProxies_systemPropertiesAndNpmrcWithProxySetting_shouldReturnAllProxies()
            throws IOException {
        File npmrc = new File(tmpDirWithNpmrc.newFolder("test2"), ".npmrc");
        Properties properties = new Properties();
        properties.put(FrontendUtils.NPMRC_PROXY_PROPERTY_KEY,
                "http://httpuser:httppassword@httphost:8080");
        properties.put(FrontendUtils.NPMRC_HTTPS_PROXY_PROPERTY_KEY,
                "http://httpsuser:httpspassword@httpshost:8081");
        properties.put(FrontendUtils.NPMRC_NOPROXY_PROPERTY_KEY,
                "192.168.1.1,vaadin.com,mycompany.com");
        try (FileOutputStream fileOutputStream = new FileOutputStream(npmrc)) {
            properties.store(fileOutputStream, null);
        }

        List<ProxyConfig.Proxy> proxyList = null;
        try {
            System.setProperty(FrontendUtils.SYSTEM_NOPROXY_PROPERTY_KEY,
                    "somethingelse,someotherip,75.41.41.33");
            System.setProperty(FrontendUtils.SYSTEM_HTTP_PROXY_PROPERTY_KEY,
                    "http://anotheruser:anotherpassword@aanotherhost:9090");
            System.setProperty(FrontendUtils.SYSTEM_HTTPS_PROXY_PROPERTY_KEY,
                    "http://anotherusers:anotherpasswords@aanotherhosts:9091");

            proxyList = FrontendUtils.getProxies(
                    tmpDirWithNpmrc.getRoot().getAbsolutePath() + "/test2");
        } finally {
            System.clearProperty(FrontendUtils.SYSTEM_NOPROXY_PROPERTY_KEY);
            System.clearProperty(FrontendUtils.SYSTEM_HTTP_PROXY_PROPERTY_KEY);
            System.clearProperty(FrontendUtils.SYSTEM_HTTPS_PROXY_PROPERTY_KEY);
        }

        Assert.assertEquals(4, proxyList.size());

        // The first two items should be system proxies
        ProxyConfig.Proxy systemHttpsProxy = proxyList.get(0).id.startsWith(
                "https-proxy") ? proxyList.get(0) : proxyList.get(1);
        ProxyConfig.Proxy systemProxy = proxyList.get(0).id.startsWith(
                "https-proxy") ? proxyList.get(1) : proxyList.get(0);

        // Items 2 and 3 should be npmrc proxies
        ProxyConfig.Proxy npmrcHttpsProxy = proxyList.get(2).id.startsWith(
                "https-proxy") ? proxyList.get(2) : proxyList.get(3);
        ProxyConfig.Proxy npmrcProxy = proxyList.get(2).id.startsWith(
                "https-proxy") ? proxyList.get(3) : proxyList.get(2);

        Assert.assertEquals("http", systemProxy.protocol);
        Assert.assertEquals("anotheruser", systemProxy.username);
        Assert.assertEquals("anotherpassword", systemProxy.password);
        Assert.assertEquals("aanotherhost", systemProxy.host);
        Assert.assertEquals(9090, systemProxy.port);
        Assert.assertEquals("somethingelse|someotherip|75.41.41.33",
                systemProxy.nonProxyHosts);

        Assert.assertEquals("http", systemHttpsProxy.protocol);
        Assert.assertEquals("anotherusers", systemHttpsProxy.username);
        Assert.assertEquals("anotherpasswords", systemHttpsProxy.password);
        Assert.assertEquals("aanotherhosts", systemHttpsProxy.host);
        Assert.assertEquals(9091, systemHttpsProxy.port);
        Assert.assertEquals("somethingelse|someotherip|75.41.41.33",
                systemHttpsProxy.nonProxyHosts);

        Assert.assertEquals("http", npmrcHttpsProxy.protocol);
        Assert.assertEquals("httpsuser", npmrcHttpsProxy.username);
        Assert.assertEquals("httpspassword", npmrcHttpsProxy.password);
        Assert.assertEquals("httpshost", npmrcHttpsProxy.host);
        Assert.assertEquals(8081, npmrcHttpsProxy.port);
        Assert.assertEquals("192.168.1.1|vaadin.com|mycompany.com",
                npmrcHttpsProxy.nonProxyHosts);

        Assert.assertEquals("http", npmrcProxy.protocol);
        Assert.assertEquals("httpuser", npmrcProxy.username);
        Assert.assertEquals("httppassword", npmrcProxy.password);
        Assert.assertEquals("httphost", npmrcProxy.host);
        Assert.assertEquals(8080, npmrcProxy.port);
        Assert.assertEquals("192.168.1.1|vaadin.com|mycompany.com",
                npmrcProxy.nonProxyHosts);
    }

    @Test
    public void getProxies_noNpmrc_shouldReturnEmptyList() {
        File npmrc = new File(baseDir + "/.npmrc");
        if (npmrc.exists())
            npmrc.delete();

        List<ProxyConfig.Proxy> proxyList = FrontendUtils.getProxies(baseDir);
        Assert.assertTrue(proxyList.isEmpty());
    }
}
