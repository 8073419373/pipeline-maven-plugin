/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.pipeline.maven.eventspy.handler;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jenkinsci.plugins.pipeline.maven.eventspy.RuntimeIOException;
import org.jenkinsci.plugins.pipeline.maven.eventspy.reporter.MavenEventReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public abstract class AbstractMavenEventHandler<E> implements MavenEventHandler<E> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final MavenEventReporter reporter;

    /**
     * Regex pattern to extract ANSI escape sequences
     */
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\x1b\\[[0-9;]*m");


    protected AbstractMavenEventHandler(MavenEventReporter reporter) {
        this.reporter = reporter;
    }


    @Override
    public boolean handle(Object event) {
        Type type = getSupportedType();
        Class<E> clazz = (Class<E>) type;
        if (clazz.isAssignableFrom(event.getClass())) {
            return _handle((E) event);
        }
        return false;
    }

    private Type getSupportedType() {
        return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }

    protected abstract boolean _handle(E e);

    @Override
    public String toString() {
        return getClass().getName() + "[type=" + getSupportedType() + "]";
    }


    public Xpp3Dom newElement(String name, String value) {
        Xpp3Dom element = new Xpp3Dom(name);
        element.setValue(value);
        return element;
    }

    public Xpp3Dom newElement(@NonNull String name, @Nullable final MavenProject project) {
        Xpp3Dom projectElt = new Xpp3Dom(name);
        if (project == null) {
            return projectElt;
        }

        projectElt.setAttribute("name", project.getName());
        projectElt.setAttribute("groupId", project.getGroupId());
        projectElt.setAttribute("artifactId", project.getArtifactId());
        projectElt.setAttribute("version", project.getVersion());
        projectElt.setAttribute("packaging", project.getPackaging());

        if (project.getBasedir() != null) {
            try {
                projectElt.setAttribute("baseDir", project.getBasedir().getCanonicalPath());
            } catch (IOException e) {
                throw new RuntimeIOException(e);
            }
        }

        if (project.getFile() != null) {
            File projectFile = project.getFile();
            String absolutePath;
            try {
                absolutePath = projectFile.getCanonicalPath();
            } catch (IOException e) {
                throw new RuntimeIOException(e);
            }

            if (absolutePath.endsWith(File.separator + "pom.xml") || absolutePath.endsWith(File.separator + ".flattened-pom.xml")) {
                // JENKINS-43616: flatten-maven-plugin replaces the original pom as artifact with a .flattened-pom.xml
                // no tweak
            } else if (absolutePath.endsWith(File.separator + "dependency-reduced-pom.xml")) {
                // JENKINS-42302: maven-shade-plugin creates a temporary project file dependency-reduced-pom.xml
                // TODO see if there is a better way to implement this "workaround"
                absolutePath = absolutePath.replace(File.separator + "dependency-reduced-pom.xml", File.separator + "pom.xml");
            } else if (absolutePath.endsWith(File.separator + ".git-versioned-pom.xml")) {
                // JENKINS-56666 maven-git-versioning-extension causes warnings due to temporary pom.xml file name '.git-versioned-pom.xml'
                // https://github.com/qoomon/maven-git-versioning-extension/blob/v4.1.0/src/main/java/me/qoomon/maven/gitversioning/VersioningMojo.java#L39
                // TODO see if there is a better way to implement this "workaround"
                absolutePath = absolutePath.replace(File.separator + ".git-versioned-pom.xml", File.separator + "pom.xml");
            } else {
                String flattenedPomFilename = getMavenFlattenPluginFlattenedPomFilename(project);
                if (flattenedPomFilename == null) {
                    logger.warn("[jenkins-event-spy] Unexpected Maven project file name '" + projectFile.getName() + "', problems may occur");
                } else {
                    if (absolutePath.endsWith(File.separator + flattenedPomFilename)) {
                        absolutePath = absolutePath.replace(File.separator + flattenedPomFilename, File.separator + "pom.xml");
                    } else {
                        logger.warn("[jenkins-event-spy] Unexpected Maven project file name '" + projectFile.getName() + "', problems may occur");
                    }
                }
            }
            projectElt.setAttribute("file", absolutePath);
        }

        Build build = project.getBuild();

        if (build != null) {
            Xpp3Dom buildElt = new Xpp3Dom("build");
            projectElt.addChild(buildElt);
            if (build.getOutputDirectory() != null) {
                buildElt.setAttribute("directory", build.getDirectory());
            }
            if (build.getSourceDirectory() != null) {
                buildElt.setAttribute("sourceDirectory", build.getSourceDirectory());
            }
        }

        return projectElt;
    }

    /**
     * If the Maven project uses the "flatten-maven-plugin" and defines the config parameter "flattenedPomFilename", get its value.
     *
     * TODO optimize and keep in cache this result
     *
     * @param project
     * @return the "flattenedPomFilename" defined at the "flatten" execution level or at the plugin definition level. {@code null}
     */
    @Nullable
    protected String getMavenFlattenPluginFlattenedPomFilename(@NonNull MavenProject project) {
        for(Plugin buildPlugin : project.getBuildPlugins()) {
            if ("org.codehaus.mojo:flatten-maven-plugin".equals(buildPlugin.getKey())) {
                String mavenConfigurationElement = "flattenedPomFilename";
                for(PluginExecution execution: buildPlugin.getExecutions()) {
                    if (execution.getGoals().contains("flatten")) {
                        if (execution.getConfiguration() instanceof Xpp3Dom) {
                            Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();
                            Xpp3Dom flattenedPomFilename = configuration.getChild(mavenConfigurationElement);
                            if (flattenedPomFilename != null) {
                                return flattenedPomFilename.getValue();
                            }
                        }
                    }
                }
                if (buildPlugin.getConfiguration() instanceof Xpp3Dom) {
                    Xpp3Dom configuration = (Xpp3Dom) buildPlugin.getConfiguration();
                    Xpp3Dom flattenedPomFilename = configuration.getChild(mavenConfigurationElement);
                    if (flattenedPomFilename != null) {
                        return flattenedPomFilename.getValue();
                    }
                }
            }
        }
        return null;
    }

    private static String removeAnsiColor(String input) {
    	if (input!=null) {
    		input = ANSI_PATTERN.matcher(input).replaceAll("");
    	}
    	return input;
    }
    
    public Xpp3Dom newElement(@NonNull String name, @Nullable Throwable t) {
        Xpp3Dom rootElt = new Xpp3Dom(name);
        if (t == null) {
            return rootElt;
        }
        rootElt.setAttribute("class", t.getClass().getName());

        Xpp3Dom messageElt = new Xpp3Dom("message");
        rootElt.addChild(messageElt);
        messageElt.setValue(removeAnsiColor(t.getMessage()));

        Xpp3Dom stackTraceElt = new Xpp3Dom("stackTrace");
        rootElt.addChild(stackTraceElt);
        StringWriter stackTrace = new StringWriter();
        t.printStackTrace(new PrintWriter(stackTrace, true));
        stackTraceElt.setValue(removeAnsiColor(stackTrace.toString()));
        return rootElt;
    }

    public Xpp3Dom newElement(@NonNull String name, @Nullable File file) {
        Xpp3Dom element = new Xpp3Dom(name);
        try {
            element.setValue(file == null ? null : file.getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }
        return element;
    }

    public Xpp3Dom newElement(@NonNull String name, @Nullable Artifact artifact) {
        Xpp3Dom element = new Xpp3Dom(name);
        if (artifact == null) {
            return element;
        }

        element.setAttribute("groupId", artifact.getGroupId());
        element.setAttribute("artifactId", artifact.getArtifactId());
        element.setAttribute("baseVersion", artifact.getBaseVersion());
        element.setAttribute("version", artifact.getVersion());
        element.setAttribute("snapshot", String.valueOf(artifact.isSnapshot()));
        if (artifact.getClassifier() != null) {
            element.setAttribute("classifier", artifact.getClassifier());
        }
        element.setAttribute("type", artifact.getType());
        element.setAttribute("id", artifact.getId());

        ArtifactHandler artifactHandler = artifact.getArtifactHandler();
        String extension;
        if (artifactHandler == null) {
            extension = artifact.getType();
        } else {
            extension = artifactHandler.getExtension();
        }
        element.setAttribute("extension", extension);

        return element;
    }
}
