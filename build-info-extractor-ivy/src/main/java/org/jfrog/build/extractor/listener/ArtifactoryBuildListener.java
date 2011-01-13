package org.jfrog.build.extractor.listener;

import org.apache.commons.lang.StringUtils;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.IvyContext;
import org.apache.ivy.plugins.trigger.Trigger;
import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Ant;
import org.jfrog.build.api.Agent;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildAgent;
import org.jfrog.build.api.BuildRetention;
import org.jfrog.build.api.BuildType;
import org.jfrog.build.api.LicenseControl;
import org.jfrog.build.api.builder.BuildInfoBuilder;
import org.jfrog.build.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.build.client.ClientProperties;
import org.jfrog.build.client.DeployDetails;
import org.jfrog.build.client.IncludeExcludePatterns;
import org.jfrog.build.client.PatternMatcher;
import org.jfrog.build.context.BuildContext;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.util.IvyBuildInfoLog;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;
import java.util.Set;

import static org.jfrog.build.api.BuildInfoProperties.BUILD_INFO_PROP_PREFIX;


/**
 * A listener which listens to the {@link Ant} builds, and is invoking different events during the build of {@code Ant}
 * itself! This is not to be confused with {@code Ivy} {@link Trigger} which is called during Ivy related events
 *
 * @author Tomer Cohen
 */
public class ArtifactoryBuildListener extends BuildListenerAdapter {
    private final BuildContext ctx = new BuildContext();
    private static boolean isDidDeploy;

    @Override
    public void buildStarted(BuildEvent event) {
        IvyContext context = IvyContext.getContext();
        context.set(BuildContext.CONTEXT_NAME, ctx);
        ctx.setBuildStartTime(System.currentTimeMillis());
        super.buildStarted(event);
    }

    /**
     * Called when the build has ended, this is the time where we will assemble the build-info object that its
     * information was collected by the {@link org.jfrog.build.extractor.trigger.ArtifactoryBuildInfoTrigger} it will
     * serialize the build-info object into a senadble JSON object to be used by the {@link ArtifactoryBuildInfoClient}
     *
     * @param event The build event.
     */
    @Override
    public void buildFinished(BuildEvent event) {
        if (event.getException() != null) {
            return;
        }
        if (!isDidDeploy) {
            Project project = event.getProject();
            project.log("Build finished triggered", Project.MSG_INFO);
            BuildContext ctx = (BuildContext) IvyContext.getContext().get(BuildContext.CONTEXT_NAME);
            Set<DeployDetails> deployDetails = ctx.getDeployDetails();
            BuildInfoBuilder builder = new BuildInfoBuilder(project.getName()).modules(ctx.getModules())
                    .number("0").durationMillis(System.currentTimeMillis() - ctx.getBuildStartTime())
                    .startedDate(new Date(ctx.getBuildStartTime()))
                    .buildAgent(new BuildAgent("Ivy", Ivy.getIvyVersion()))
                    .agent(new Agent("Ivy", Ivy.getIvyVersion()));
            // This is here for backwards compatibility.
            builder.type(BuildType.IVY);
            Properties envProps = new Properties();
            envProps.putAll(System.getenv());
            Properties mergedProps = BuildInfoExtractorUtils.mergePropertiesWithSystemAndPropertyFile(envProps);
            ArtifactoryClientConfiguration clientConf =
                    new ArtifactoryClientConfiguration(new IvyBuildInfoLog(project));
            clientConf.fillFromProperties(mergedProps);
            String agentName = clientConf.info.getAgentName();
            String agentVersion = clientConf.info.getAgentVersion();
            if (StringUtils.isNotBlank(agentName) && StringUtils.isNotBlank(agentVersion)) {
                builder.agent(new Agent(agentName, agentVersion));
            }
            String buildAgentName = clientConf.info.getBuildAgentName();
            String buildAgentVersion = clientConf.info.getBuildAgentVersion();
            if (StringUtils.isNotBlank(buildAgentName) && StringUtils.isNotBlank(buildAgentVersion)) {
                builder.buildAgent(new BuildAgent(buildAgentName, buildAgentVersion));
            }
            String buildName = clientConf.info.getBuildName();
            if (StringUtils.isNotBlank(buildName)) {
                builder.name(buildName);
            }
            String buildNumber = clientConf.info.getBuildNumber();
            if (StringUtils.isNotBlank(buildNumber)) {
                builder.number(buildNumber);
            }
            String buildUrl = clientConf.info.getBuildUrl();
            if (StringUtils.isNotBlank(buildUrl)) {
                builder.url(buildUrl);
            }
            String principal = clientConf.info.getPrincipal();
            if (StringUtils.isNotBlank(principal)) {
                builder.principal(principal);
            }
            String parentBuildName = clientConf.info.getParentBuildName();
            if (StringUtils.isNotBlank(parentBuildName)) {
                builder.parentName(parentBuildName);
            }
            String parentBuildNumber = clientConf.info.getParentBuildNumber();
            if (StringUtils.isNotBlank(parentBuildNumber)) {
                builder.parentNumber(parentBuildNumber);
            }
            LicenseControl licenseControl = new LicenseControl(clientConf.info.licenseControl.isRunChecks());
            String notificationRecipients = clientConf.info.licenseControl.getViolationRecipients();
            if (StringUtils.isNotBlank(notificationRecipients)) {
                licenseControl.setLicenseViolationsRecipientsList(notificationRecipients);
            }
            licenseControl.setIncludePublishedArtifacts(clientConf.info.licenseControl.isIncludePublishedArtifacts());
            String scopes = clientConf.info.licenseControl.getScopes();
            if (StringUtils.isNotBlank(scopes)) {
                licenseControl.setScopesList(scopes);
            }
            licenseControl.setAutoDiscover(clientConf.info.licenseControl.isAutoDiscover());
            builder.licenseControl(licenseControl);
            BuildRetention buildRetention = new BuildRetention();
            if (clientConf.info.getBuildRetentionDays() != null) {
                buildRetention.setCount(clientConf.info.getBuildRetentionDays());
            }
            String buildRetentionMinimumDays = clientConf.info.getBuildRetentionMinimumDate();
            if (StringUtils.isNotBlank(buildRetentionMinimumDays)) {
                int minimumDays = Integer.parseInt(buildRetentionMinimumDays);
                if (minimumDays > -1) {
                    Calendar calendar = Calendar.getInstance();
                    calendar.roll(Calendar.DAY_OF_YEAR, -minimumDays);
                    buildRetention.setMinimumBuildDate(calendar.getTime());
                }
            }
            builder.buildRetention(buildRetention);
            Properties props = BuildInfoExtractorUtils.getEnvProperties(mergedProps);
            Properties propsFromSys = BuildInfoExtractorUtils
                    .filterDynamicProperties(mergedProps, BuildInfoExtractorUtils.BUILD_INFO_PROP_PREDICATE);
            props.putAll(propsFromSys);
            props = BuildInfoExtractorUtils.stripPrefixFromProperties(props, BUILD_INFO_PROP_PREFIX);
            builder.properties(props);
            Build build = builder.build();
            String contextUrl = mergedProps.getProperty(ClientProperties.PROP_CONTEXT_URL);
            String username = clientConf.publisher.getUserName();
            String password = clientConf.publisher.getPassword();
            try {
                ArtifactoryBuildInfoClient client =
                        new ArtifactoryBuildInfoClient(contextUrl, username, password, new IvyBuildInfoLog(project));
                if (clientConf.publisher.isPublishArtifacts()) {
                    IncludeExcludePatterns patterns = new IncludeExcludePatterns(
                            clientConf.publisher.getIncludePatterns(), clientConf.publisher.getExcludePatterns());

                    deployArtifacts(project, client, deployDetails, patterns);
                }
                if (clientConf.publisher.isPublishBuildInfo()) {
                    deployBuildInfo(client, build);
                }
                isDidDeploy = true;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void deployArtifacts(Project project, ArtifactoryBuildInfoClient client, Set<DeployDetails> deployDetails,
            IncludeExcludePatterns patterns) throws IOException {
        for (DeployDetails deployDetail : deployDetails) {
            String artifactPath = deployDetail.getArtifactPath();
            if (PatternMatcher.pathConflicts(artifactPath, patterns)) {
                project.log("Skipping the deployment of '" + artifactPath +
                        "' due to the defined include-exclude patterns.", Project.MSG_INFO);
                continue;
            }
            client.deployArtifact(deployDetail);
        }
    }

    private void deployBuildInfo(ArtifactoryBuildInfoClient client, Build build) throws IOException {
        client.sendBuildInfo(build);
    }
}
