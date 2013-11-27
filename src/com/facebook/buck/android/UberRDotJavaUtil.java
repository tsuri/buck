/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.java.JavacInMemoryStep;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.step.Step;
import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

/**
 * Creates the {@link Step}s needed to generate an uber {@code R.java} file.
 * <p>
 * Buck builds two types of {@code R.java} files: temporary ones and uber ones. A temporary
 * {@code R.java} file's values are garbage and correspond to a single Android libraries. An uber
 * {@code R.java} file represents the transitive closure of Android libraries that are being
 * packaged into an APK and has the real values for that APK.
 */
public class UberRDotJavaUtil {

  private static final ImmutableSet<BuildRuleType> TRAVERSABLE_TYPES = ImmutableSet.of(
      BuildRuleType.ANDROID_BINARY,
      BuildRuleType.ANDROID_INSTRUMENTATION_APK,
      BuildRuleType.ANDROID_LIBRARY,
      BuildRuleType.ANDROID_RESOURCE,
      BuildRuleType.APK_GENRULE,
      BuildRuleType.JAVA_LIBRARY,
      BuildRuleType.JAVA_TEST,
      BuildRuleType.ROBOLECTRIC_TEST
  );


  /** Utility class: do not instantiate. */
  private UberRDotJavaUtil() {}

  /**
   * Finds the transitive set of {@code rule}'s {@link AndroidResourceRule} dependencies with
   * non-null {@code res} directories, which can also include {@code rule} itself.
   * This set will be returned as an {@link ImmutableList} with the rules topologically sorted.
   * Rules will be ordered from least dependent to most dependent.
   */
  public static ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(BuildRule rule) {
    return getAndroidResourceDeps(Collections.singleton(rule));
  }

  /**
   * Finds the transitive set of {@code rules}' {@link AndroidResourceRule} dependencies with
   * non-null {@code res} directories, which can also include any of the {@code rules} themselves.
   * This set will be returned as an {@link ImmutableList} with the rules topologically sorted.
   * Rules will be ordered from least dependent to most dependent.
   */
  public static ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(
      Collection<BuildRule> rules) {
    // This visitor finds all AndroidResourceRules that are reachable from the specified rules via
    // rules with types in the TRAVERSABLE_TYPES collection. It also builds up the dependency graph
    // that was traversed to find the AndroidResourceRules.
    final MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<>();

    final ImmutableSet.Builder<HasAndroidResourceDeps> androidResources = ImmutableSet.builder();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(rules) {

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof HasAndroidResourceDeps) {
          HasAndroidResourceDeps androidResourceRule = (HasAndroidResourceDeps)rule;
          if (androidResourceRule.getRes() != null) {
            androidResources.add(androidResourceRule);
          }
        }

        // Only certain types of rules should be considered as part of this traversal.
        BuildRuleType type = rule.getType();
        ImmutableSet<BuildRule> depsToVisit = maybeVisitAllDeps(rule,
            TRAVERSABLE_TYPES.contains(type));
        mutableGraph.addNode(rule);
        for (BuildRule dep : depsToVisit) {
          mutableGraph.addEdge(rule, dep);
        }
        return depsToVisit;
      }

    };
    visitor.start();

    final Set<HasAndroidResourceDeps> allAndroidResourceRules = androidResources.build();

    // Now that we have the transitive set of AndroidResourceRules, we need to return them in
    // topologically sorted order. This is critical because the order in which -S flags are passed
    // to aapt is significant and must be consistent.
    Predicate<BuildRule> inclusionPredicate = new Predicate<BuildRule>() {
      @Override
      public boolean apply(BuildRule rule) {
        return allAndroidResourceRules.contains(rule);
      }
    };
    ImmutableList<BuildRule> sortedAndroidResourceRules = TopologicalSort.sort(mutableGraph,
        inclusionPredicate);

    // TopologicalSort.sort() returns rules in leaves-first order, which is the opposite of what we
    // want, so we must reverse the list and cast BuildRules to AndroidResourceRules.
    return ImmutableList.copyOf(
        Iterables.transform(
            sortedAndroidResourceRules.reverse(),
            CAST_TO_ANDROID_RESOURCE_RULE)
        );
  }

  public static Set<HasAndroidResourceDeps> getAndroidResourceDepsUnsorted(Collection<BuildRule> rules) {
    final Set<HasAndroidResourceDeps> androidResources = Sets.newHashSet();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(rules) {

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof HasAndroidResourceDeps) {
          HasAndroidResourceDeps androidResourceRule = (HasAndroidResourceDeps)rule;
          if (androidResourceRule.getRes() != null) {
            androidResources.add(androidResourceRule);
          }
        }

        // Only certain types of rules should be considered as part of this traversal.
        BuildRuleType type = rule.getType();
        ImmutableSet<BuildRule> depsToVisit = maybeVisitAllDeps(rule,
            TRAVERSABLE_TYPES.contains(type));
        return depsToVisit;
      }

    };
    visitor.start();

    return androidResources;
  }

  private static Function<BuildRule, HasAndroidResourceDeps> CAST_TO_ANDROID_RESOURCE_RULE =
      new Function<BuildRule, HasAndroidResourceDeps>() {
        @Override
        public HasAndroidResourceDeps apply(BuildRule rule) {
          return (HasAndroidResourceDeps)rule;
        }
      };

  /**
   * Aggregate information about a list of {@link AndroidResourceRule}s.
   */
  public static class AndroidResourceDetails {
    /**
     * The {@code res} directories associated with the {@link AndroidResourceRule}s.
     * <p>
     * An {@link Iterator} over this collection will reflect the order of the original list of
     * {@link AndroidResourceRule}s that were specified.
     */
    public final ImmutableSet<String> resDirectories;

    public final ImmutableSet<String> whitelistedStringDirs;

    public final ImmutableSet<String> rDotJavaPackages;

    @Beta
    public AndroidResourceDetails(ImmutableList<HasAndroidResourceDeps> androidResourceDeps) {
      ImmutableSet.Builder<String> resDirectoryBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<String> rDotJavaPackageBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<String> whitelistedStringDirsBuilder = ImmutableSet.builder();
      for (HasAndroidResourceDeps androidResource : androidResourceDeps) {
        String resDirectory = androidResource.getRes();
        if (resDirectory != null) {
          resDirectoryBuilder.add(resDirectory);
          rDotJavaPackageBuilder.add(androidResource.getRDotJavaPackage());
          if (androidResource.hasWhitelistedStrings()) {
            whitelistedStringDirsBuilder.add(resDirectory);
          }
        }
      }
      resDirectories = resDirectoryBuilder.build();
      rDotJavaPackages = rDotJavaPackageBuilder.build();
      whitelistedStringDirs = whitelistedStringDirsBuilder.build();
    }
  }

  static JavacInMemoryStep createJavacInMemoryCommandForRDotJavaFiles(
      Set<String> javaSourceFilePaths, String outputDirectory) {
    return createJavacInMemoryCommandForRDotJavaFiles(
        javaSourceFilePaths, outputDirectory, Optional.<String>absent());
  }

  static JavacInMemoryStep createJavacInMemoryCommandForRDotJavaFiles(
      Set<String> javaSourceFilePaths,
      String outputDirectory,
      Optional<String> pathToOutputAbiFile) {

    ImmutableSet<String> classpathEntries = ImmutableSet.of();
    return new JavacInMemoryStep(
        outputDirectory,
        javaSourceFilePaths,
        ImmutableSet.<String>of(),
        classpathEntries,
        JavacOptions.DEFAULTS,
        pathToOutputAbiFile,
        Optional.<String>absent(),
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacInMemoryStep.SuggestBuildRules>absent(),
        /* pathToSrcsList */ Optional.<Path>absent());
  }
}
