/*
 *    Copyright (c) 2017 Red Hat, Inc.
 *
 *    Red Hat licenses this file to you under the Apache License, version
 *    2.0 (the "License"); you may not use this file except in compliance
 *    with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *    implied.  See the License for the specific language governing
 *    permissions and limitations under the License.
 */

package io.fabric8.maven.enricher.fabric8;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.extensions.DeploymentFluent;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpec;
import io.fabric8.maven.core.config.ResourceConfig;
import io.fabric8.maven.core.handler.DeploymentHandler;
import io.fabric8.maven.core.handler.HandlerHub;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;

import java.util.*;

/**
 * This enricher takes care of adding <a href="https://isito.io">Istio</a> related enrichments to the Kubernetes Deployment
 * <p>
 * <a href="https://istio.io/docs/reference/commands/istioctl.html#istioctl-kube-inject">istioctl-kube-inject</a>
 *
 * @author kameshs
 */
public class IstioKubeInjectEnricher extends BaseEnricher {

  private static final String ISTIO_ANNOTATION_STATUS = "injected-version-releng@0d29a2c0d15f-0.2.12-998e0e00d375688bcb2af042fc81a60ce5264009";
  private final DeploymentHandler deployHandler;

  // Available configuration keys
  private enum Config implements Configs.Key {
    name,
    istioNamespace {{
      d = "istio-system";
    }},
    proxyName {{
      d = "istio-proxy";
    }},
    proxyImage {{
      d = "docker.io/istio/proxy_debug:0.2.12";
    }},
    initImage {{
      d = "docker.io/istio/proxy_init:0.2.12";
    }},
    coreDumpImage {{
      d = "docker.io/alpine";
    }},
    proxyArgs {{
      d = "proxy,sidecar,-v,2,--configPath,/etc/istio/proxy,--binaryPath,/usr/local/bin/envoy,--serviceCluster,app-cluster-name," +
              "--drainDuration,45s,--parentShutdownDuration,1m0s,--discoveryAddress,istio-pilot.istio-system:8080,--discoveryRefreshDelay," +
              "1s,--zipkinAddress,zipkin.istio-system:9411,--connectTimeout,10s,--statsdUdpAddress,istio-mixer.istio-system:9125,--proxyAdminPort,15000";
    }},
    imagePullPolicy {{
      d = "IfNotPresent";
    }},
    replicaCount {{
      d = "1";
    }};

    public String def() {
      return d;
    }

    protected String d;
  }

  public IstioKubeInjectEnricher(EnricherContext buildContext) {
    super(buildContext, "fmp-istio-kubeinject");
    HandlerHub handlerHub = new HandlerHub(buildContext.getProject());
    deployHandler = handlerHub.getDeploymentHandler();

  }

  //TODO - need to check if istio proxy side car is already there
  //TODO - adding init-containers to template spec
  //TODO - find out the other container  liveliness/readiness probes
  @Override
  public void addMissingResources(KubernetesListBuilder builder) {

    //getContext().getImages().addAll(istioImages());

    final String name = getConfig(Config.name, MavenUtil.createDefaultResourceName(getProject()));

    final ResourceConfig config = new ResourceConfig.Builder()
            .controllerName(name)
            .imagePullPolicy(getConfig(Config.imagePullPolicy))
            .withReplicas(Configs.asInt(getConfig(Config.replicaCount)))
            .build();

    final List<ImageConfiguration> images = getImages();

    String[] proxyArgs = getConfig(Config.proxyArgs).split(",");
    final List<String> sidecarArgs = new ArrayList<>();
    for (int i = 0; i < proxyArgs.length; i++) {
      //cluster name defaults to app name a.k.a controller name
      if ("app-cluster-name".equalsIgnoreCase(proxyArgs[i])) {
        sidecarArgs.add(name);
      } else {
        sidecarArgs.add(proxyArgs[i]);
      }
    }

    final DeploymentSpec spec = deployHandler.getDeployment(config, images).getSpec();

    if (spec != null) {
      builder.accept(new TypedVisitor<DeploymentBuilder>() {
        @Override
        public void visit(DeploymentBuilder deploymentBuilder) {
          log.info("Adding Istio Annotations");
          deploymentBuilder.editOrNewSpec().editOrNewTemplate()
                  // MetaData
                  .editOrNewMetadata()
                  .addToAnnotations("sidecar.istio.io/status", ISTIO_ANNOTATION_STATUS)
                  .endMetadata()
                  .editOrNewSpec()
                  .endSpec()
                  .endTemplate()
                  .endSpec();
          mergeDeploymentSpec(deploymentBuilder, spec);
        }
      });

      if (spec.getTemplate() != null && spec.getTemplate().getSpec() != null) {
        final PodSpec podSpec = spec.getTemplate().getSpec();
        builder.accept(new TypedVisitor<PodSpecBuilder>() {
          @Override
          public void visit(PodSpecBuilder podSpecBuilder) {
            log.info("Adding Istio Sidecar Proxy");
            podSpecBuilder
                    // Add Istio Proxy, Volumes and Secret
                    .addNewContainer()
                    .withName(getConfig(Config.proxyName))
                    .withResources(new ResourceRequirements())
                    .withTerminationMessagePath("/dev/termination-log")
                    .withImage(getConfig(Config.proxyImage))
                    .withImagePullPolicy(getConfig(Config.imagePullPolicy))
                    .withArgs(sidecarArgs)
                    .withEnv(proxyEnvVars())
                    .withSecurityContext(new SecurityContextBuilder()
                            .withRunAsUser(1337l)
                            .withPrivileged(true)
                            .withReadOnlyRootFilesystem(false)
                            .build())
                    .withVolumeMounts(istioVolumeMounts())
                    .endContainer()
                    .withVolumes(istioVolumes())
                    .withDnsPolicy("ClusterFirst")
                    // Add Istio Init container and Core Dump
                    .withInitContainers(istioInitContainer(), coreDumpInitContainer());
            KubernetesResourceUtil.mergePodSpec(podSpecBuilder, podSpec, name);
          }
        });
      }
    }
  }

  protected Container istioInitContainer() {
    return new ContainerBuilder()
            .withName("istio-init")
            .withImage(getConfig(Config.initImage))
            .withImagePullPolicy("IfNotPresent")
            .withTerminationMessagePath("/dev/termination-log")
            .withTerminationMessagePolicy("File")
            .withArgs("-p", "15001", "-u", "1337")
            .withSecurityContext(new SecurityContextBuilder()
                    .withPrivileged(true)
                    .withCapabilities(new CapabilitiesBuilder()
                            .addToAdd("NET_ADMIN")
                            .build())
                    .build())
            .withResources(new ResourceRequirements())
            .build();
  }

  protected Container coreDumpInitContainer() {
    return new ContainerBuilder()
            .withName("enable-core-dump")
            .withImage(getConfig(Config.coreDumpImage))
            .withImagePullPolicy("IfNotPresent")
            .withCommand("/bin/sh")
            .withArgs("-c", "sysctl -w kernel.core_pattern=/etc/istio/proxy/core.%e.%p.%t && ulimit -c unlimited")
            .withTerminationMessagePath("/dev/termination-log")
            .withTerminationMessagePolicy("File")
            .withSecurityContext(new SecurityContextBuilder()
                    .withPrivileged(true)
                    .build())
            .build();
  }


  /**
   * Generate the volumes to be mounted
   *
   * @return - list of {@link VolumeMount}
   */
  protected List<VolumeMount> istioVolumeMounts() {
    List<VolumeMount> volumeMounts = new ArrayList<>();

    VolumeMountBuilder istioProxyVolume = new VolumeMountBuilder();
    istioProxyVolume
            .withMountPath("/etc/istio/proxy")
            .withName("istio-envoy")
            .build();

    VolumeMountBuilder istioCertsVolume = new VolumeMountBuilder();
    istioCertsVolume
            .withMountPath("/etc/certs")
            .withName("istio-certs")
            .withReadOnly(true)
            .build();

    volumeMounts.add(istioProxyVolume.build());
    volumeMounts.add(istioCertsVolume.build());
    return volumeMounts;
  }

  /**
   * Generate the volumes
   *
   * @return - list of {@link Volume}
   */
  protected List<Volume> istioVolumes() {
    List<Volume> volumes = new ArrayList<>();

    VolumeBuilder empTyVolume = new VolumeBuilder();
    empTyVolume.withEmptyDir(new EmptyDirVolumeSourceBuilder()
            .withMedium("Memory")
            .withSizeLimit(new QuantityBuilder().withAmount("0").build())
            .build())
            .withName("istio-envoy")
            .build();

    VolumeBuilder secretVolume = new VolumeBuilder();
    secretVolume
            .withName("istio-certs")
            .withSecret(new SecretVolumeSourceBuilder()
                    .withSecretName("istio.default")
                    .withDefaultMode(420)
                    .withOptional(true)
                    .build())
            .build();

    volumes.add(empTyVolume.build());
    volumes.add(secretVolume.build());
    return volumes;
  }

  /**
   * The method to return list of environment variables that will be needed for Istio proxy
   *
   * @return - list of {@link EnvVar}
   */
  protected List<EnvVar> proxyEnvVars() {
    List<EnvVar> envVars = new ArrayList<>();

    //POD_NAME
    EnvVarSource podNameVarSource = new EnvVarSource();
    podNameVarSource.setFieldRef(new ObjectFieldSelector(null, "metadata.name"));
    envVars.add(new EnvVar("POD_NAME", null, podNameVarSource));

    //POD_NAMESPACE
    EnvVarSource podNamespaceVarSource = new EnvVarSource();
    podNamespaceVarSource.setFieldRef(new ObjectFieldSelector(null, "metadata.namespace"));
    envVars.add(new EnvVar("POD_NAMESPACE", null, podNamespaceVarSource));

    //INSTANCE_IP
    EnvVarSource podIpVarSource = new EnvVarSource();
    podIpVarSource.setFieldRef(new ObjectFieldSelector(null, "status.podIP"));
    envVars.add(new EnvVar("INSTANCE_IP", null, podIpVarSource));

    return envVars;
  }

  private void mergeDeploymentSpec(DeploymentBuilder builder, DeploymentSpec spec) {
    DeploymentFluent.SpecNested<DeploymentBuilder> specBuilder = builder.editSpec();
    KubernetesResourceUtil.mergeSimpleFields(specBuilder, spec);
    specBuilder.endSpec();
  }

  private List<ImageConfiguration> istioImages() {
    List<ImageConfiguration> images = new ArrayList<>();
    ImageConfiguration.Builder builder = new ImageConfiguration.Builder();
    builder.name(getConfig(Config.proxyName))
            .registry("docker.io/istio");
    images.add(builder.build());

    builder = new ImageConfiguration.Builder();
    builder.name(getConfig(Config.initImage));
    images.add(builder.build());

    builder = new ImageConfiguration.Builder();
    builder.name(getConfig(Config.proxyImage));
    images.add(builder.build());

    return images;
  }

}
