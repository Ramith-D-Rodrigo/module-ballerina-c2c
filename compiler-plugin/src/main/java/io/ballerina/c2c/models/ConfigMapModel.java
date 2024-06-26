/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.c2c.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Model class to hold kubernetes config map data.
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ConfigMapModel extends KubernetesModel {
    private Map<String, String> data;
    private String mountPath;
    private boolean readOnly;
    private boolean isBallerinaConf;
    private int defaultMode;
    private boolean dir;

    public ConfigMapModel() {
        this.readOnly = true;
        this.dir = false;
    }
}
