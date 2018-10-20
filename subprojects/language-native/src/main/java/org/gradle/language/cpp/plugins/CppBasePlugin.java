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

import com.google.common.io.Files;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.NonNullApi;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.transform.ArtifactTransform;
import org.gradle.api.artifacts.transform.VariantTransform;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.internal.FeaturePreviews;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.DefaultProjectPublication;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectPublicationRegistry;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.TaskContainerInternal;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.cpp.internal.DefaultCppBinary;
import org.gradle.language.cpp.internal.DefaultCppComponent;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.language.nativeplatform.internal.Names;
import org.gradle.language.plugins.NativeBasePlugin;
import org.gradle.nativeplatform.HeaderFormat;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.nativeplatform.toolchain.internal.ToolType;
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;
import org.gradle.swiftpm.internal.SwiftPmTarget;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.gradle.api.internal.FeaturePreviews.Feature.GRADLE_METADATA;

/**
 * A common base plugin for the C++ executable and library plugins
 *
 * @since 4.1
 */
@Incubating
@NonNullApi
public class CppBasePlugin implements Plugin<ProjectInternal> {
    private final ProjectPublicationRegistry publicationRegistry;

    @Inject
    public CppBasePlugin(ProjectPublicationRegistry publicationRegistry) {
        this.publicationRegistry = publicationRegistry;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(NativeBasePlugin.class);
        project.getPluginManager().apply(StandardToolChainsPlugin.class);

        final TaskContainerInternal tasks = project.getTasks();
        final DirectoryProperty buildDirectory = project.getLayout().getBuildDirectory();

        // Enable the use of Gradle metadata. This is a temporary opt-in switch until available by default
        project.getGradle().getServices().get(FeaturePreviews.class).enableFeature(GRADLE_METADATA);

        project.getDependencies().registerTransform(new Action<VariantTransform>() {
            @Override
            public void execute(VariantTransform transform) {
                AttributeContainer from = transform.getFrom();
                from.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.C_PLUS_PLUS_API));
                from.attribute(HeaderFormat.ATTRIBUTE, project.getObjects().named(HeaderFormat.class, HeaderFormat.ZIP));

                AttributeContainer to = transform.getTo();
                to.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.C_PLUS_PLUS_API));
                to.attribute(HeaderFormat.ATTRIBUTE, project.getObjects().named(HeaderFormat.class, HeaderFormat.DIRECTORY));

                transform.artifactTransform(UnzipToDirectory.class);
            }
        });

        // Create the tasks for each C++ binary that is registered
        project.getComponents().withType(DefaultCppBinary.class, new Action<DefaultCppBinary>() {
            @Override
            public void execute(final DefaultCppBinary binary) {
                final Names names = binary.getNames();

                String language = "cpp";
                final NativePlatform currentPlatform = binary.getTargetPlatform();
                // TODO - make this lazy
                final NativeToolChainInternal toolChain = binary.getToolChain();

                final Callable<List<File>> systemIncludes = new Callable<List<File>>() {
                    @Override
                    public List<File> call() {
                        PlatformToolProvider platformToolProvider = binary.getPlatformToolProvider();
                        return platformToolProvider.getSystemLibraries(ToolType.CPP_COMPILER).getIncludeDirs();
                    }
                };

                TaskProvider<CppCompile> compile = tasks.register(names.getCompileTaskName(language), CppCompile.class, new Action<CppCompile>() {
                    @Override
                    public void execute(CppCompile compile) {
                        compile.includes(binary.getCompileIncludePath());
                        compile.getSystemIncludes().from(systemIncludes);
                        compile.source(binary.getCppSource());
                        if (binary.isDebuggable()) {
                            compile.setDebuggable(true);
                        }
                        if (binary.isOptimized()) {
                            compile.setOptimized(true);
                        }
                        compile.getTargetPlatform().set(currentPlatform);
                        compile.getToolChain().set(toolChain);
                        compile.getObjectFileDir().set(buildDirectory.dir("obj/" + names.getDirName()));

                        if (binary instanceof CppSharedLibrary) {
                            compile.setPositionIndependentCode(true);
                        }
                    }
                });

                binary.getObjectsDir().set(compile.flatMap(new Transformer<Provider<? extends Directory>, CppCompile>() {
                    @Override
                    public Provider<? extends Directory> transform(CppCompile cppCompile) {
                        return cppCompile.getObjectFileDir();
                    }
                }));
                binary.getCompileTask().set(compile);
            }
        });

        project.getComponents().withType(ProductionCppComponent.class, new Action<ProductionCppComponent>() {
            @Override
            public void execute(final ProductionCppComponent component) {
                project.afterEvaluate(new Action<Project>() {
                    @Override
                    public void execute(Project project) {
                        DefaultCppComponent componentInternal = (DefaultCppComponent) component;
                        publicationRegistry.registerPublication(project.getPath(), new DefaultProjectPublication(componentInternal.getDisplayName(), new SwiftPmTarget(component.getBaseName().get()), false));
                    }
                });
            }
        });
    }

    public static class UnzipToDirectory extends ArtifactTransform {
        @Override
        public List<File> transform(File headersZip) {
            try {
                unzipTo(headersZip);
            } catch (IOException e) {
                throw new UncheckedIOException("extracting header zip", e);
            }
            return Collections.singletonList(getOutputDirectory());
        }

        // TODO: Find a better reusable unzip infrastructure
        private void unzipTo(File headersZip) throws IOException {
            ZipInputStream inputStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(headersZip)));
            try {
                ZipEntry entry;
                while ((entry = inputStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    File outFile = new File(getOutputDirectory(), entry.getName());
                    Files.createParentDirs(outFile);
                    FileOutputStream outputStream = new FileOutputStream(outFile);
                    try {
                        IOUtils.copyLarge(inputStream, outputStream);
                    } finally {
                        outputStream.close();
                    }
                }
            } finally {
                inputStream.close();
            }
        }
    }
}
