package org.jfrog.build.extractor.npm.extractor;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.Artifact;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.Module;
import org.jfrog.build.api.builder.ArtifactBuilder;
import org.jfrog.build.api.builder.ModuleBuilder;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.client.ArtifactoryUploadResponse;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryBuildInfoClientBuilder;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.build.util.VersionException;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Yahav Itzhak
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class NpmPublish extends NpmCommand {
    private ArrayListMultimap<String, String> properties;
    private Artifact deployedArtifact;
    private boolean tarballProvided;

    /**
     * Publish npm package.
     *
     * @param clientBuilder        - Build Info client builder.
     * @param properties           - The Artifact properties to set (Build name, Build number, etc...).
     * @param executablePath       - Npm executable path.
     * @param path                 - Path to directory contains package.json or path to '.tgz' file.
     * @param deploymentRepository - The repository it'll deploy to.
     * @param logger               - The logger.
     * @param env                  - Environment variables to use during npm execution.
     */
    public NpmPublish(ArtifactoryBuildInfoClientBuilder clientBuilder, ArrayListMultimap<String, String> properties, String executablePath, Path path, String deploymentRepository, Log logger, Map<String, String> env) {
        super(clientBuilder, executablePath, deploymentRepository, logger, path, env);
        this.properties = properties;
    }

    public Build execute() {
        try (ArtifactoryBuildInfoClient dependenciesClient = (ArtifactoryBuildInfoClient) clientBuilder.build()) {
            client = dependenciesClient;
            preparePrerequisites();
            if (!tarballProvided) {
                pack();
            }
            deploy();
            if (!tarballProvided) {
                deleteCreatedTarball();
            }
            return createBuild();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }

    private void preparePrerequisites() throws InterruptedException, VersionException, IOException {
        validateArtifactoryVersion();
        validateNpmVersion();
        validateRepoExists("Target repo must be specified");
        setPackageInfo();
    }

    private void setPackageInfo() throws IOException {
        if (Files.isDirectory(path)) {
            try (FileInputStream fis = new FileInputStream(path.resolve("package.json").toFile())) {
                npmPackageInfo.readPackageInfo(fis);
            }
        } else {
            readPackageInfoFromTarball(); // The provided path is not a directory, we're assuming this is a compressed npm package
        }
    }

    private void readPackageInfoFromTarball() throws IOException {
        if (!StringUtils.endsWith(path.toString(), ".tgz")) {
            throw new IOException("Publish path must be a '.tgz' file or a directory containing package.json");
        }
        try (TarArchiveInputStream inputStream = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(path.toFile()))))) {
            TarArchiveEntry entry;
            while ((entry = inputStream.getNextTarEntry()) != null) {
                if (StringUtils.endsWith(entry.getName(), "package.json")) {
                    npmPackageInfo.readPackageInfo(inputStream);
                    tarballProvided = true;
                    return;
                }
            }
        }
        throw new IOException("Couldn't find package.json in " + path.toString());
    }

    private void pack() throws IOException {
        logger.info(npmDriver.pack(workingDir.toFile(), new ArrayList<>()));
        path = path.resolve(npmPackageInfo.getExpectedPackedFileName());
    }

    private void deploy() throws IOException {
        readPackageInfoFromTarball();
        doDeploy();
    }

    private void doDeploy() throws IOException {
        DeployDetails deployDetails = new DeployDetails.Builder()
                .file(path.toFile())
                .targetRepository(repo)
                .addProperties(properties)
                .artifactPath(npmPackageInfo.getDeployPath())
                .build();

        ArtifactoryUploadResponse response = ((ArtifactoryBuildInfoClient) client).deployArtifact(deployDetails);

        deployedArtifact = new ArtifactBuilder(npmPackageInfo.getModuleId())
                .md5(response.getChecksums().getMd5())
                .sha1(response.getChecksums().getSha1())
                .build();
    }

    private void deleteCreatedTarball() throws IOException {
        Files.deleteIfExists(path);
    }

    private Build createBuild() {
        List<Artifact> artifactList = Lists.newArrayList(deployedArtifact);
        Module module = new ModuleBuilder().id(npmPackageInfo.toString()).artifacts(artifactList).build();
        List<Module> modules = Lists.newArrayList(module);
        Build build = new Build();
        build.setModules(modules);
        return build;
    }
}
