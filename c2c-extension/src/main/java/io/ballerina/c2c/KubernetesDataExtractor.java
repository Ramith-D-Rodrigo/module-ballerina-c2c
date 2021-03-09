/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.c2c;

import io.ballerina.c2c.diagnostics.Config;
import io.ballerina.c2c.diagnostics.ListenerInfo;
import io.ballerina.c2c.diagnostics.MutualSSLConfig;
import io.ballerina.c2c.diagnostics.ProjectServiceInfo;
import io.ballerina.c2c.diagnostics.SecureSocketConfig;
import io.ballerina.c2c.diagnostics.ServiceInfo;
import io.ballerina.c2c.diagnostics.Task;
import io.ballerina.c2c.exceptions.KubernetesPluginException;
import io.ballerina.c2c.models.DeploymentModel;
import io.ballerina.c2c.models.JobModel;
import io.ballerina.c2c.models.KubernetesContext;
import io.ballerina.c2c.models.KubernetesDataHolder;
import io.ballerina.c2c.models.PodAutoscalerModel;
import io.ballerina.c2c.models.SecretModel;
import io.ballerina.c2c.models.ServiceModel;
import io.ballerina.c2c.utils.KubernetesUtils;
import io.ballerina.projects.Project;
import org.apache.commons.codec.binary.Base64;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.ballerina.c2c.KubernetesConstants.BALLERINA_HOME;
import static io.ballerina.c2c.KubernetesConstants.DOCKER_CERT_PATH;
import static io.ballerina.c2c.KubernetesConstants.DOCKER_HOST;
import static io.ballerina.c2c.KubernetesConstants.SVC_POSTFIX;
import static io.ballerina.c2c.utils.KubernetesUtils.getValidName;

/**
 * Responsible for extracting data from package and storing in KubernetesDataHolder.
 *
 * @since 2.0.0
 */
public class KubernetesDataExtractor {
    private final Project project;
    private final ProjectServiceInfo projectServiceInfo;

    public KubernetesDataExtractor(Project project) {
        this.project = project;
        this.projectServiceInfo = new ProjectServiceInfo(project);
    }

    public void packageAnalysis() throws KubernetesPluginException {
        KubernetesContext.getInstance().setCurrentPackage(KubernetesUtils.getProjectID(project));
        KubernetesDataHolder dataHolder = KubernetesContext.getInstance().getDataHolder();
        dataHolder.setPackageID(KubernetesUtils.getProjectID(project));

        List<ServiceInfo> serviceList = projectServiceInfo.getServiceList();
        if (isServicesExist(serviceList)) {
            addDeployments();
            addHPA();
        }
        addServices(serviceList);
        addJobs();
    }

    private void addJobs() {
        if (projectServiceInfo.getTask().isPresent()) {
            Task task = projectServiceInfo.getTask().get();
            JobModel jobModel = new JobModel();
            jobModel.setSchedule(task.getSchedule());

            String dockerHost = System.getenv(DOCKER_HOST);
            if (!KubernetesUtils.isBlank(dockerHost)) {
                jobModel.setDockerHost(dockerHost);
            }
            String dockerCertPath = System.getenv(DOCKER_CERT_PATH);
            if (!KubernetesUtils.isBlank(dockerCertPath)) {
                jobModel.setDockerCertPath(dockerCertPath);
            }
            KubernetesContext.getInstance().getDataHolder().setJobModel(jobModel);
        }
    }

    private void addServices(List<ServiceInfo> serviceList) throws KubernetesPluginException {
        for (ServiceInfo serviceInfo : serviceList) {
            ServiceModel serviceModel = new ServiceModel();
            if (KubernetesUtils.isBlank(serviceModel.getName())) {
                serviceModel.setName(getValidName(serviceInfo.getServicePath()) + SVC_POSTFIX);
            }

            ListenerInfo listener = serviceInfo.getListener();
            int port = listener.getPort();
            if (serviceModel.getPort() == -1) {
                serviceModel.setPort(port);
            }
            if (serviceModel.getTargetPort() == -1) {
                serviceModel.setTargetPort(port);
            }

            serviceModel.setProtocol("http");

            if (listener.getConfig().isPresent() && listener.getConfig().get().getSecureSocketConfig().isPresent()) {
                Set<SecretModel> secretModels = processSecureSocketAnnotation(listener);
                KubernetesContext.getInstance().getDataHolder().addListenerSecret(listener.getName(), secretModels);
                KubernetesContext.getInstance().getDataHolder().addSecrets(secretModels);
                serviceModel.setProtocol("https");
            }

            KubernetesContext.getInstance().getDataHolder().addServiceModel(serviceModel);
        }
    }

    private void addHPA() {
        PodAutoscalerModel podAutoscalerModel = new PodAutoscalerModel();
        KubernetesContext.getInstance().getDataHolder().setPodAutoscalerModel(podAutoscalerModel);
    }

    private void addDeployments() {
        DeploymentModel deploymentModel = new DeploymentModel();

        String dockerHost = System.getenv(DOCKER_HOST);
        if (!KubernetesUtils.isBlank(dockerHost)) {
            deploymentModel.setDockerHost(dockerHost);
        }
        String dockerCertPath = System.getenv(DOCKER_CERT_PATH);
        if (!KubernetesUtils.isBlank(dockerCertPath)) {
            deploymentModel.setDockerCertPath(dockerCertPath);
        }
        KubernetesContext.getInstance().getDataHolder().setDeploymentModel(deploymentModel);
    }

    private boolean isServicesExist(List<ServiceInfo> serviceList) {
        return serviceList.size() > 0;
    }

    /**
     * Extract key-store/trust-store file location from listener.
     *
     * @param listenerInfo Listener info
     * @return List of @{@link SecretModel} objects
     */
    private Set<SecretModel> processSecureSocketAnnotation(ListenerInfo listenerInfo)
            throws KubernetesPluginException {
        Set<SecretModel> secrets = new HashSet<>();

        Optional<Config> config = listenerInfo.getConfig();
        if (config.isEmpty()) {
            return Collections.emptySet();
        }

        Optional<SecureSocketConfig> secureSocketConfig = config.get().getSecureSocketConfig();
        Optional<MutualSSLConfig> mutualSSLConfig = config.get().getMutualSSLConfig();
        SecretModel secretModel = new SecretModel();
        if (secureSocketConfig.isPresent()) {
            String certFile = secureSocketConfig.get().getCertFile();
            String keyFile = secureSocketConfig.get().getKeyFile();
            String keyFileContent = readSecretFile(keyFile);
            String certFileContent = readSecretFile(certFile);
            if (getMountPath(certFile).equals(getMountPath(keyFile))) {
                // key and cert mount to same path
                secretModel.setName(getValidName(listenerInfo.getName()) + "-secure-socket");
                secretModel.setMountPath(getMountPath(certFile));
                Map<String, String> dataMap = new HashMap<>();
                dataMap.put(String.valueOf(Paths.get(keyFile).getFileName()), keyFileContent);
                dataMap.put(String.valueOf(Paths.get(certFile).getFileName()), certFileContent);
                secretModel.setData(dataMap);
                secrets.add(secretModel);
            } else {
                // key and cert mount different paths
                secretModel.setName(getValidName(listenerInfo.getName()) + "-secure-cert");
                secretModel.setMountPath(getMountPath(certFile));
                Map<String, String> dataMap = new HashMap<>();
                dataMap.put(String.valueOf(Paths.get(certFile).getFileName()), certFileContent);
                secretModel.setData(dataMap);

                SecretModel secretModelKeyFile = new SecretModel();
                secretModelKeyFile.setName(getValidName(listenerInfo.getName()) + "-secure-key");
                secretModelKeyFile.setMountPath(getMountPath(keyFile));
                Map<String, String> dataMapKey = new HashMap<>();
                dataMapKey.put(String.valueOf(Paths.get(keyFile).getFileName()), keyFileContent);
                secretModel.setData(dataMapKey);

            }
        }
        if (mutualSSLConfig.isPresent()) {
            String sslCertPath = mutualSSLConfig.get().getPath();
            String sslCertPathContent = readSecretFile(sslCertPath);
            if (getMountPath(sslCertPath).equals(secretModel.getMountPath())) {
                // Same mount path as key config add data to existing secret.
                secretModel.getData().put(String.valueOf(Paths.get(sslCertPath).getFileName()), sslCertPathContent);
            } else {
                // Different mount Path. Create a new secret.
                SecretModel sslSecretModel = new SecretModel();
                secretModel.setName(getValidName(listenerInfo.getName()) + "-mutual-ssl");
                secretModel.setMountPath(getMountPath(sslCertPath));
                Map<String, String> dataMap = new HashMap<>();
                dataMap.put(String.valueOf(Paths.get(sslCertPath).getFileName()), sslCertPathContent);
                sslSecretModel.setData(dataMap);
                secrets.add(sslSecretModel);
            }
        }
        return secrets;
    }

    private String readSecretFile(String filePath) throws KubernetesPluginException {
        if (filePath.contains("${ballerina.home}")) {
            // Resolve variable locally before reading file.
            String ballerinaHome = System.getProperty("ballerina.home");
            filePath = filePath.replace("${ballerina.home}", ballerinaHome);
        }
        Path dataFilePath = Paths.get(filePath);
        return Base64.encodeBase64String(KubernetesUtils.readFileContent(dataFilePath));
    }

    private String getMountPath(String mountPath) throws KubernetesPluginException {
        Path parentPath = Paths.get(mountPath).getParent();
        if (parentPath != null && ".".equals(parentPath.toString())) {
            // Mounts to the same path overriding the source file.
            throw new KubernetesPluginException("Invalid path: " + mountPath + ". " +
                    "Providing relative path in the same level as source file is not supported with code2cloud." +
                    "Please create a subfolder and provide the relative path. " +
                    "eg: './security/ballerinaKeystore.p12'");
        }
        if (!Paths.get(mountPath).isAbsolute()) {
            mountPath = BALLERINA_HOME + File.separator + mountPath;
        }
        return String.valueOf(Paths.get(mountPath).getParent());
    }
}
