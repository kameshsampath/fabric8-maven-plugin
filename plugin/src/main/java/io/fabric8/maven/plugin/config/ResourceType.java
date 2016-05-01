package io.fabric8.maven.plugin.config;
/*
 * 
 * Copyright 2016 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Type od resource supported
 * @author roland
 * @since 07/04/16
 */
public enum ResourceType {

    json {
        @Override
        public ObjectMapper getObjectMapper() {
            return new ObjectMapper();
        }
    },

    yaml {
        @Override
        public ObjectMapper getObjectMapper() {
            return new ObjectMapper(new YAMLFactory());
        }
    };

    public abstract ObjectMapper getObjectMapper();

    public File addExtension(File file) {
        String path = file.getAbsolutePath();
        return new File(path + "." + name().toLowerCase());
    }
}