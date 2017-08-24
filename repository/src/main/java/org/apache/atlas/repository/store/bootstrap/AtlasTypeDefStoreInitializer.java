/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.store.bootstrap;

import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.AtlasException;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.ha.HAConfiguration;
import org.apache.atlas.listener.ActiveStateChangeHandler;
import org.apache.atlas.model.typedef.AtlasBaseTypeDef;
import org.apache.atlas.model.typedef.AtlasClassificationDef;
import org.apache.atlas.model.typedef.AtlasEntityDef;
import org.apache.atlas.model.typedef.AtlasEnumDef;
import org.apache.atlas.model.typedef.AtlasEnumDef.AtlasEnumElementDef;
import org.apache.atlas.model.typedef.AtlasRelationshipDef;
import org.apache.atlas.model.typedef.AtlasStructDef;
import org.apache.atlas.model.typedef.AtlasStructDef.AtlasAttributeDef;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.apache.atlas.store.AtlasTypeDefStore;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.codehaus.jackson.annotate.JsonAutoDetect.Visibility.NONE;
import static org.codehaus.jackson.annotate.JsonAutoDetect.Visibility.PUBLIC_ONLY;


/**
 * Class that handles initial loading of models and patches into typedef store
 */
@Service
public class AtlasTypeDefStoreInitializer implements ActiveStateChangeHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AtlasTypeDefStoreInitializer.class);

    private final AtlasTypeDefStore atlasTypeDefStore;
    private final AtlasTypeRegistry atlasTypeRegistry;
    private final Configuration     conf;


    @Inject
    public AtlasTypeDefStoreInitializer(AtlasTypeDefStore atlasTypeDefStore, AtlasTypeRegistry atlasTypeRegistry, Configuration conf) {
        this.atlasTypeDefStore = atlasTypeDefStore;
        this.atlasTypeRegistry = atlasTypeRegistry;
        this.conf              = conf;
    }

    @PostConstruct
    public void init() throws AtlasBaseException {
        LOG.info("==> AtlasTypeDefStoreInitializer.init()");

        if (!HAConfiguration.isHAEnabled(conf)) {
            atlasTypeDefStore.init();

            loadBootstrapTypeDefs();
        } else {
            LOG.info("AtlasTypeDefStoreInitializer.init(): deferring type loading until instance activation");
        }

        LOG.info("<== AtlasTypeDefStoreInitializer.init()");
    }

    private void loadBootstrapTypeDefs() {
        LOG.info("==> AtlasTypeDefStoreInitializer.loadBootstrapTypeDefs()");

        String atlasHomeDir = System.getProperty("atlas.home");
        String typesDirName = (StringUtils.isEmpty(atlasHomeDir) ? "." : atlasHomeDir) + File.separator + "models";
        File   typesDir     = new File(typesDirName);
        File[] typeDefFiles = typesDir.exists() ? typesDir.listFiles() : null;

        if (typeDefFiles == null || typeDefFiles.length == 0) {
            LOG.info("Types directory {} does not exist or not readable or has no typedef files", typesDirName);

            return;
        }

        // sort the files by filename
        Arrays.sort(typeDefFiles);

        for (File typeDefFile : typeDefFiles) {
            if (!typeDefFile.isFile()) {
                continue;
            }

            try {
                String jsonStr = new String(Files.readAllBytes(typeDefFile.toPath()), StandardCharsets.UTF_8);
                AtlasTypesDef typesDef = AtlasType.fromJson(jsonStr, AtlasTypesDef.class);

                if (typesDef == null || typesDef.isEmpty()) {
                    LOG.info("No type in file {}", typeDefFile.getAbsolutePath());

                    continue;
                }

                AtlasTypesDef typesToCreate = getTypesToCreate(typesDef, atlasTypeRegistry);
                AtlasTypesDef typesToUpdate = getTypesToUpdate(typesDef, atlasTypeRegistry);

                if (!typesToCreate.isEmpty() || !typesToUpdate.isEmpty()) {
                    atlasTypeDefStore.createUpdateTypesDef(typesToCreate, typesToUpdate);

                    LOG.info("Created/Updated types defined in file {}", typeDefFile.getAbsolutePath());
                } else {
                    LOG.info("No new type in file {}", typeDefFile.getAbsolutePath());
                }

            } catch (Throwable t) {
                LOG.error("error while registering types in file {}", typeDefFile.getAbsolutePath(), t);
            }
        }

        applyTypePatches(typesDirName);

        LOG.info("<== AtlasTypeDefStoreInitializer.loadBootstrapTypeDefs()");
    }

    public static AtlasTypesDef getTypesToCreate(AtlasTypesDef typesDef, AtlasTypeRegistry typeRegistry) {
        AtlasTypesDef typesToCreate = new AtlasTypesDef();

        if (CollectionUtils.isNotEmpty(typesDef.getEnumDefs())) {
            for (AtlasEnumDef enumDef : typesDef.getEnumDefs()) {
                if (!typeRegistry.isRegisteredType(enumDef.getName())) {
                    typesToCreate.getEnumDefs().add(enumDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getStructDefs())) {
            for (AtlasStructDef structDef : typesDef.getStructDefs()) {
                if (!typeRegistry.isRegisteredType(structDef.getName())) {
                    typesToCreate.getStructDefs().add(structDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getClassificationDefs())) {
            for (AtlasClassificationDef classificationDef : typesDef.getClassificationDefs()) {
                if (!typeRegistry.isRegisteredType(classificationDef.getName())) {
                    typesToCreate.getClassificationDefs().add(classificationDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getEntityDefs())) {
            for (AtlasEntityDef entityDef : typesDef.getEntityDefs()) {
                if (!typeRegistry.isRegisteredType(entityDef.getName())) {
                    typesToCreate.getEntityDefs().add(entityDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getRelationshipDefs())) {
            for (AtlasRelationshipDef relationshipDef : typesDef.getRelationshipDefs()) {
                if (!typeRegistry.isRegisteredType(relationshipDef.getName())) {
                    typesToCreate.getRelationshipDefs().add(relationshipDef);
                }
            }
        }

        return typesToCreate;
    }

    public static AtlasTypesDef getTypesToUpdate(AtlasTypesDef typesDef, AtlasTypeRegistry typeRegistry) {
        AtlasTypesDef typesToUpdate = new AtlasTypesDef();

        if (CollectionUtils.isNotEmpty(typesDef.getStructDefs())) {
            for (AtlasStructDef newStructDef : typesDef.getStructDefs()) {
                AtlasStructDef  oldStructDef = typeRegistry.getStructDefByName(newStructDef.getName());

                if (oldStructDef == null) {
                    continue;
                }

                if (updateTypeAttributes(oldStructDef, newStructDef)) {
                    typesToUpdate.getStructDefs().add(newStructDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getClassificationDefs())) {
            for (AtlasClassificationDef newClassifDef : typesDef.getClassificationDefs()) {
                AtlasClassificationDef  oldClassifDef = typeRegistry.getClassificationDefByName(newClassifDef.getName());

                if (oldClassifDef == null) {
                    continue;
                }

                if (updateTypeAttributes(oldClassifDef, newClassifDef)) {
                    typesToUpdate.getClassificationDefs().add(newClassifDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getEntityDefs())) {
            for (AtlasEntityDef newEntityDef : typesDef.getEntityDefs()) {
                AtlasEntityDef  oldEntityDef = typeRegistry.getEntityDefByName(newEntityDef.getName());

                if (oldEntityDef == null) {
                    continue;
                }

                if (updateTypeAttributes(oldEntityDef, newEntityDef)) {
                    typesToUpdate.getEntityDefs().add(newEntityDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getEnumDefs())) {
            for (AtlasEnumDef newEnumDef : typesDef.getEnumDefs()) {
                AtlasEnumDef  oldEnumDef = typeRegistry.getEnumDefByName(newEnumDef.getName());

                if (oldEnumDef == null) {
                    continue;
                }

                if (isTypeUpdateApplicable(oldEnumDef, newEnumDef)) {
                    if (CollectionUtils.isNotEmpty(oldEnumDef.getElementDefs())) {
                        for (AtlasEnumElementDef oldEnumElem : oldEnumDef.getElementDefs()) {
                            if (!newEnumDef.hasElement(oldEnumElem.getValue())) {
                                newEnumDef.addElement(oldEnumElem);
                            }
                        }
                    }

                    typesToUpdate.getEnumDefs().add(newEnumDef);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(typesDef.getRelationshipDefs())) {
            for (AtlasRelationshipDef relationshipDef : typesDef.getRelationshipDefs()) {
                AtlasRelationshipDef  oldRelationshipDef = typeRegistry.getRelationshipDefByName(relationshipDef.getName());

                if (oldRelationshipDef == null) {
                    continue;
                }

                if (updateTypeAttributes(oldRelationshipDef, relationshipDef)) {
                    typesToUpdate.getRelationshipDefs().add(relationshipDef);
                }
            }
        }

        return typesToUpdate;
    }

    @Override
    public void instanceIsActive() throws AtlasException {
        LOG.info("==> AtlasTypeDefStoreInitializer.instanceIsActive()");

        try {
            atlasTypeDefStore.init();

            loadBootstrapTypeDefs();
        } catch (AtlasBaseException e) {
            LOG.error("Failed to init after becoming active", e);
        }

        LOG.info("<== AtlasTypeDefStoreInitializer.instanceIsActive()");
    }

    @Override
    public void instanceIsPassive() throws AtlasException {
        LOG.info("==> AtlasTypeDefStoreInitializer.instanceIsPassive()");

        LOG.info("<== AtlasTypeDefStoreInitializer.instanceIsPassive()");
    }

    private static boolean updateTypeAttributes(AtlasStructDef oldStructDef, AtlasStructDef newStructDef) {
        boolean ret = isTypeUpdateApplicable(oldStructDef, newStructDef);

        if (ret) {
            // make sure that all attributes in oldDef are in newDef as well
            if (CollectionUtils.isNotEmpty(oldStructDef.getAttributeDefs())){
                for (AtlasAttributeDef oldAttrDef : oldStructDef.getAttributeDefs()) {
                    if (!newStructDef.hasAttribute(oldAttrDef.getName())) {
                        newStructDef.addAttribute(oldAttrDef);
                    }
                }
            }
        }

        return ret;
    }

    private static boolean isTypeUpdateApplicable(AtlasBaseTypeDef oldTypeDef, AtlasBaseTypeDef newTypeDef) {
        String oldTypeVersion = oldTypeDef.getTypeVersion();
        String newTypeVersion = newTypeDef.getTypeVersion();

        return ObjectUtils.compare(newTypeVersion, oldTypeVersion) > 0;
    }

    private void applyTypePatches(String typesDirName) {
        String typePatchesDirName = typesDirName + File.separator + "patches";
        File   typePatchesDir     = new File(typePatchesDirName);
        File[] typePatchFiles     = typePatchesDir.exists() ? typePatchesDir.listFiles() : null;

        if (typePatchFiles == null || typePatchFiles.length == 0) {
            LOG.info("Type patches directory {} does not exist or not readable or has no patches", typePatchesDirName);

            return;
        }

        // sort the files by filename
        Arrays.sort(typePatchFiles);

        PatchHandler[] patchHandlers = new PatchHandler[] {
                new AddAttributePatchHandler(atlasTypeDefStore, atlasTypeRegistry),
                new UpdateTypeDefOptionsPatchHandler(atlasTypeDefStore, atlasTypeRegistry),
                new UpdateAttributePatchHandler(atlasTypeDefStore, atlasTypeRegistry)
        };

        Map<String, PatchHandler> patchHandlerRegistry = new HashMap<>();

        for (PatchHandler patchHandler : patchHandlers) {
            for (String supportedAction : patchHandler.getSupportedActions()) {
                patchHandlerRegistry.put(supportedAction, patchHandler);
            }
        }

        for (File typePatchFile : typePatchFiles) {
            if (!typePatchFile.isFile()) {
                continue;
            }

            LOG.info("Applying patches in file {}", typePatchFile.getAbsolutePath());

            try {
                String         jsonStr = new String(Files.readAllBytes(typePatchFile.toPath()), StandardCharsets.UTF_8);
                TypeDefPatches patches = AtlasType.fromJson(jsonStr, TypeDefPatches.class);

                if (patches == null || CollectionUtils.isEmpty(patches.getPatches())) {
                    LOG.info("No patches in file {}", typePatchFile.getAbsolutePath());

                    continue;
                }

                for (TypeDefPatch patch : patches.getPatches()) {
                    PatchHandler patchHandler = patchHandlerRegistry.get(patch.getAction());

                    if (patchHandler == null) {
                        LOG.error("Unknown patch action {} in file {}. Ignored",
                                  patch.getAction(), typePatchFile.getAbsolutePath());

                        continue;
                    }

                    try {
                        patchHandler.applyPatch(patch);
                    } catch (AtlasBaseException excp) {
                        LOG.error("Failed to apply {} patch in file {}. Ignored", patch.getAction(), typePatchFile.getAbsolutePath(), excp);
                    }
                }
            } catch (Throwable t) {
                LOG.error("Failed to apply patches in file {}. Ignored", typePatchFile.getAbsolutePath(), t);
            }
        }
    }

    /**
     * typedef patch details
     */
    @JsonAutoDetect(getterVisibility = PUBLIC_ONLY, setterVisibility = PUBLIC_ONLY, fieldVisibility = NONE)
    @JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @XmlRootElement
    @XmlAccessorType(XmlAccessType.PROPERTY)
    static class TypeDefPatch {
        private String                  action;
        private String                  typeName;
        private String                  applyToVersion;
        private String                  updateToVersion;
        private Map<String, Object>     params;
        private List<AtlasAttributeDef> attributeDefs;
        private Map<String, String>     typeDefOptions;

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getTypeName() {
            return typeName;
        }

        public void setTypeName(String typeName) {
            this.typeName = typeName;
        }

        public String getApplyToVersion() {
            return applyToVersion;
        }

        public void setApplyToVersion(String applyToVersion) {
            this.applyToVersion = applyToVersion;
        }

        public String getUpdateToVersion() {
            return updateToVersion;
        }

        public void setUpdateToVersion(String updateToVersion) {
            this.updateToVersion = updateToVersion;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params;
        }

        public List<AtlasAttributeDef> getAttributeDefs() {
            return attributeDefs;
        }

        public void setAttributeDefs(List<AtlasAttributeDef> attributeDefs) {
            this.attributeDefs = attributeDefs;
        }

        public Map<String, String> getTypeDefOptions() {
            return typeDefOptions;
        }

        public void setTypeDefOptions(Map<String, String> typeDefOptions) {
            this.typeDefOptions = typeDefOptions;
        }
    }

    /**
     * list of typedef patches
     */
    @JsonAutoDetect(getterVisibility = PUBLIC_ONLY, setterVisibility = PUBLIC_ONLY, fieldVisibility = NONE)
    @JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @XmlRootElement
    @XmlAccessorType(XmlAccessType.PROPERTY)
    static class TypeDefPatches {
        private List<TypeDefPatch> patches;

        public List<TypeDefPatch> getPatches() {
            return patches;
        }

        public void setPatches(List<TypeDefPatch> patches) {
            this.patches = patches;
        }
    }

    abstract class PatchHandler {
        protected final AtlasTypeDefStore typeDefStore;
        protected final AtlasTypeRegistry typeRegistry;
        protected final String[]          supportedActions;

        protected PatchHandler(AtlasTypeDefStore typeDefStore, AtlasTypeRegistry typeRegistry, String[] supportedActions) {
            this.typeDefStore     = typeDefStore;
            this.typeRegistry     = typeRegistry;
            this.supportedActions = supportedActions;
        }

        public String[] getSupportedActions() { return supportedActions; }

        public abstract void applyPatch(TypeDefPatch patch) throws AtlasBaseException;

        protected boolean isPatchApplicable(TypeDefPatch patch, AtlasBaseTypeDef currentTypeDef) {
            String currentVersion = currentTypeDef.getTypeVersion();
            String applyToVersion = patch.getApplyToVersion();

            return currentVersion == null ||
                   currentVersion.equalsIgnoreCase(applyToVersion) ||
                   currentVersion.startsWith(applyToVersion + ".");
        }
    }

    class AddAttributePatchHandler extends PatchHandler {
        public AddAttributePatchHandler(AtlasTypeDefStore typeDefStore, AtlasTypeRegistry typeRegistry) {
            super(typeDefStore, typeRegistry, new String[] { "ADD_ATTRIBUTE" });
        }

        @Override
        public void applyPatch(TypeDefPatch patch) throws AtlasBaseException {
            String           typeName = patch.getTypeName();
            AtlasBaseTypeDef typeDef  = typeRegistry.getTypeDefByName(typeName);

            if (typeDef == null) {
                throw new AtlasBaseException(AtlasErrorCode.PATCH_FOR_UNKNOWN_TYPE, patch.getAction(), typeName);
            }

            if (isPatchApplicable(patch, typeDef)) {
                if (typeDef.getClass().equals(AtlasEntityDef.class)) {
                    AtlasEntityDef updatedDef = new AtlasEntityDef((AtlasEntityDef)typeDef);

                    for (AtlasAttributeDef attributeDef : patch.getAttributeDefs()) {
                        updatedDef.addAttribute(attributeDef);
                    }
                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateEntityDefByName(typeName, updatedDef);
                } else if (typeDef.getClass().equals(AtlasClassificationDef.class)) {
                    AtlasClassificationDef updatedDef = new AtlasClassificationDef((AtlasClassificationDef)typeDef);

                    for (AtlasAttributeDef attributeDef : patch.getAttributeDefs()) {
                        updatedDef.addAttribute(attributeDef);
                    }
                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateClassificationDefByName(typeName, updatedDef);
                } else if (typeDef.getClass().equals(AtlasStructDef.class)) {
                    AtlasStructDef updatedDef = new AtlasStructDef((AtlasStructDef)typeDef);

                    for (AtlasAttributeDef attributeDef : patch.getAttributeDefs()) {
                        updatedDef.addAttribute(attributeDef);
                    }
                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateStructDefByName(typeName, updatedDef);
                } else {
                    throw new AtlasBaseException(AtlasErrorCode.PATCH_NOT_APPLICABLE_FOR_TYPE,
                            patch.getAction(), typeDef.getClass().getSimpleName());
                }
            } else {
                LOG.info("patch skipped: typeName={}; applyToVersion={}; updateToVersion={}",
                        patch.getTypeName(), patch.getApplyToVersion(), patch.getUpdateToVersion());
            }
        }
    }

    class UpdateAttributePatchHandler extends PatchHandler {
        public UpdateAttributePatchHandler(AtlasTypeDefStore typeDefStore, AtlasTypeRegistry typeRegistry) {
            super(typeDefStore, typeRegistry, new String[] { "UPDATE_ATTRIBUTE" });
        }

        @Override
        public void applyPatch(TypeDefPatch patch) throws AtlasBaseException {
            String           typeName = patch.getTypeName();
            AtlasBaseTypeDef typeDef  = typeRegistry.getTypeDefByName(typeName);

            if (typeDef == null) {
                throw new AtlasBaseException(AtlasErrorCode.PATCH_FOR_UNKNOWN_TYPE, patch.getAction(), typeName);
            }

            if (isPatchApplicable(patch, typeDef)) {
                if (typeDef.getClass().equals(AtlasEntityDef.class)) {
                    AtlasEntityDef updatedDef = new AtlasEntityDef((AtlasEntityDef)typeDef);

                    addOrUpdateAttributes(updatedDef, patch.getAttributeDefs());

                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateEntityDefByName(typeName, updatedDef);
                } else if (typeDef.getClass().equals(AtlasClassificationDef.class)) {
                    AtlasClassificationDef updatedDef = new AtlasClassificationDef((AtlasClassificationDef)typeDef);

                    addOrUpdateAttributes(updatedDef, patch.getAttributeDefs());

                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateClassificationDefByName(typeName, updatedDef);
                } else if (typeDef.getClass().equals(AtlasStructDef.class)) {
                    AtlasStructDef updatedDef = new AtlasStructDef((AtlasStructDef)typeDef);

                    addOrUpdateAttributes(updatedDef, patch.getAttributeDefs());

                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateStructDefByName(typeName, updatedDef);

                } else {
                    throw new AtlasBaseException(AtlasErrorCode.PATCH_NOT_APPLICABLE_FOR_TYPE,
                                                 patch.getAction(), typeDef.getClass().getSimpleName());
                }
            } else {
                LOG.info("patch skipped: typeName={}; applyToVersion={}; updateToVersion={}",
                          patch.getTypeName(), patch.getApplyToVersion(), patch.getUpdateToVersion());
            }
        }

        private void addOrUpdateAttributes(AtlasStructDef structDef, List<AtlasAttributeDef> attributesToUpdate) {
            for (AtlasAttributeDef attributeToUpdate : attributesToUpdate) {
                String attrName = attributeToUpdate.getName();

                if (structDef.hasAttribute(attrName)) {
                    structDef.removeAttribute(attrName);
                }

                structDef.addAttribute(attributeToUpdate);
            }
        }
    }

    class UpdateTypeDefOptionsPatchHandler extends PatchHandler {
        public UpdateTypeDefOptionsPatchHandler(AtlasTypeDefStore typeDefStore, AtlasTypeRegistry typeRegistry) {
            super(typeDefStore, typeRegistry, new String[] { "UPDATE_TYPEDEF_OPTIONS" });
        }

        @Override
        public void applyPatch(TypeDefPatch patch) throws AtlasBaseException {
            String           typeName = patch.getTypeName();
            AtlasBaseTypeDef typeDef  = typeRegistry.getTypeDefByName(typeName);

            if (typeDef == null) {
                throw new AtlasBaseException(AtlasErrorCode.PATCH_FOR_UNKNOWN_TYPE, patch.getAction(), typeName);
            }

            if (MapUtils.isEmpty(patch.getTypeDefOptions())) {
                throw new AtlasBaseException(AtlasErrorCode.PATCH_INVALID_DATA, patch.getAction(), typeName);
            }

            if (isPatchApplicable(patch, typeDef)) {
                if (typeDef.getClass().equals(AtlasEntityDef.class)) {
                    AtlasEntityDef updatedDef = new AtlasEntityDef((AtlasEntityDef)typeDef);

                    if (updatedDef.getOptions() == null) {
                        updatedDef.setOptions(patch.getTypeDefOptions());
                    } else {
                        updatedDef.getOptions().putAll(patch.getTypeDefOptions());
                    }
                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateEntityDefByName(typeName, updatedDef);
                } else if (typeDef.getClass().equals(AtlasClassificationDef.class)) {
                    AtlasClassificationDef updatedDef = new AtlasClassificationDef((AtlasClassificationDef)typeDef);

                    if (updatedDef.getOptions() == null) {
                        updatedDef.setOptions(patch.getTypeDefOptions());
                    } else {
                        updatedDef.getOptions().putAll(patch.getTypeDefOptions());
                    }
                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateClassificationDefByName(typeName, updatedDef);
                } else if (typeDef.getClass().equals(AtlasStructDef.class)) {
                    AtlasStructDef updatedDef = new AtlasStructDef((AtlasStructDef)typeDef);

                    if (updatedDef.getOptions() == null) {
                        updatedDef.setOptions(patch.getTypeDefOptions());
                    } else {
                        updatedDef.getOptions().putAll(patch.getTypeDefOptions());
                    }
                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateStructDefByName(typeName, updatedDef);
                } else if (typeDef.getClass().equals(AtlasEnumDef.class)) {
                    AtlasEnumDef updatedDef = new AtlasEnumDef((AtlasEnumDef)typeDef);

                    if (updatedDef.getOptions() == null) {
                        updatedDef.setOptions(patch.getTypeDefOptions());
                    } else {
                        updatedDef.getOptions().putAll(patch.getTypeDefOptions());
                    }
                    updatedDef.setTypeVersion(patch.getUpdateToVersion());

                    typeDefStore.updateEnumDefByName(typeName, updatedDef);
                } else {
                    throw new AtlasBaseException(AtlasErrorCode.PATCH_NOT_APPLICABLE_FOR_TYPE,
                                                 patch.getAction(), typeDef.getClass().getSimpleName());
                }
            } else {
                LOG.info("patch skipped: typeName={}; applyToVersion={}; updateToVersion={}",
                         patch.getTypeName(), patch.getApplyToVersion(), patch.getUpdateToVersion());
            }
        }
    }
}