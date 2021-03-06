/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.language.cpp.plugins;

import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Named;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppExecutable;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.internal.DefaultCppApplication;
import org.gradle.language.cpp.internal.DefaultUsageContext;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.BuildType;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;

import javax.inject.Inject;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;

/**
 * <p>A plugin that produces a native application from C++ source.</p>
 *
 * <p>Assumes the source files are located in `src/main/cpp` and header files are located in `src/main/headers`.</p>
 *
 * <p>Adds a {@link CppApplication} extension to the project to allow configuration of the application.</p>
 *
 * @since 4.5
 */
@Incubating
public class CppApplicationPlugin implements Plugin<ProjectInternal> {
    private final NativeComponentFactory componentFactory;
    private final ToolChainSelector toolChainSelector;
    private final ImmutableAttributesFactory attributesFactory;

    @Inject
    public CppApplicationPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);

        final ObjectFactory objectFactory = project.getObjects();

        // Add the application and extension
        final DefaultCppApplication application = componentFactory.newInstance(CppApplication.class, DefaultCppApplication.class, "main");
        project.getExtensions().add(CppApplication.class, "application", application);
        project.getComponents().add(application);

        // Configure the component
        application.getBaseName().set(project.getName());

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                application.getOperatingSystems().finalizeValue();
                Set<OperatingSystemFamily> operatingSystemFamilies = application.getOperatingSystems().get();
                if (operatingSystemFamilies.isEmpty()) {
                    throw new IllegalArgumentException("An operating system needs to be specified for the application.");
                }

                Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);

                for (BuildType buildType : BuildType.DEFAULT_BUILD_TYPES) {
                    for (OperatingSystemFamily operatingSystem : operatingSystemFamilies) {
                        String operatingSystemSuffix = createDimensionSuffix(operatingSystem, operatingSystemFamilies);
                        String variantName = buildType.getName() + operatingSystemSuffix;

                        Provider<String> group = project.provider(new Callable<String>() {
                            @Override
                            public String call() throws Exception {
                                return project.getGroup().toString();
                            }
                        });

                        Provider<String> version = project.provider(new Callable<String>() {
                            @Override
                            public String call() throws Exception {
                                return project.getVersion().toString();
                            }
                        });

                        AttributeContainer runtimeAttributes = attributesFactory.mutable();
                        runtimeAttributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);
                        runtimeAttributes.attribute(DEBUGGABLE_ATTRIBUTE, buildType.isDebuggable());
                        runtimeAttributes.attribute(OPTIMIZED_ATTRIBUTE, buildType.isOptimized());
                        runtimeAttributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, operatingSystem);

                        NativeVariantIdentity variantIdentity = new NativeVariantIdentity(variantName, application.getBaseName(), group, version, buildType.isDebuggable(), buildType.isOptimized(), operatingSystem,
                            null,
                            new DefaultUsageContext(variantName + "Runtime", runtimeUsage, runtimeAttributes));

                        if (DefaultNativePlatform.getCurrentOperatingSystem().toFamilyName().equals(operatingSystem.getName())) {
                            ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class);

                            CppExecutable executable = application.addExecutable(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());

                            // Use the debug variant as the development binary
                            if (buildType == BuildType.DEBUG) {
                                application.getDevelopmentBinary().set(executable);
                            }

                            application.getMainPublication().addVariant(executable);

                        } else {
                            // Known, but not buildable
                            application.getMainPublication().addVariant(variantIdentity);
                        }
                    }
                }
                application.getBinaries().realizeNow();
            }
        });
    }

    private String createDimensionSuffix(Named dimensionValue, Collection<? extends Named> multivalueProperty) {
        if (isDimensionVisible(multivalueProperty)) {
            return StringUtils.capitalize(dimensionValue.getName().toLowerCase());
        }
        return "";
    }

    private boolean isDimensionVisible(Collection<? extends Named> multivalueProperty) {
        return multivalueProperty.size() > 1;
    }
}
