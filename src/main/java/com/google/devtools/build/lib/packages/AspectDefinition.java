// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.packages;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.MissingFragmentPolicy;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.packages.Type.LabelVisitor;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkSubruleApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

/**
 * The definition of an aspect (see {@link Aspect} for more information).
 *
 * <p>Contains enough information to build up the configured target graph except for the actual way
 * to build the Skyframe node (that is the territory of {@link com.google.devtools.build.lib.view
 * AspectFactory}). In particular:
 *
 * <ul>
 *   <li>The condition that must be fulfilled for an aspect to be able to operate on a configured
 *       target
 *   <li>The (implicit or late-bound) attributes of the aspect that denote dependencies the aspect
 *       itself needs (e.g. runtime libraries for a new language for protocol buffers)
 *   <li>The aspects this aspect requires from its direct dependencies
 * </ul>
 *
 * <p>The way to build the Skyframe node is not here because this data needs to be accessible from
 * the {@code .packages} package and that one requires references to the {@code .view} package.
 */
@Immutable
public final class AspectDefinition {
  private final AspectClass aspectClass;
  private final AdvertisedProviderSet advertisedProviders;
  private final RequiredProviders requiredProviders;
  private final RequiredProviders requiredProvidersForAspects;
  private final ImmutableMap<String, Attribute> attributes;
  private final ImmutableSet<ToolchainTypeRequirement> toolchainTypes;

  /** A supplier of the attributes to which the aspect will propagate. */
  private final AspectPropagationEdgesSupplier<String> restrictToAttributes;

  /**
   * A supplier of the toolchains types for which the aspect will propagate to matching resolved
   * toolchains.
   */
  private final AspectPropagationEdgesSupplier<Label> propagateToToolchainsTypes;

  @Nullable private final ConfigurationFragmentPolicy configurationFragmentPolicy;
  private final boolean applyToFiles;
  private final boolean applyToGeneratingRules;

  private final ImmutableSet<AspectClass> requiredAspectClasses;

  @Nullable private final AspectPropagationPredicate propagationPredicate;

  private final ImmutableSet<Label> execCompatibleWith;
  private final ImmutableMap<String, DeclaredExecGroup> execGroups;
  private final ImmutableSet<? extends StarlarkSubruleApi> subrules;

  public AdvertisedProviderSet getAdvertisedProviders() {
    return advertisedProviders;
  }

  private AspectDefinition(
      AspectClass aspectClass,
      AdvertisedProviderSet advertisedProviders,
      RequiredProviders requiredProviders,
      RequiredProviders requiredProvidersForAspects,
      ImmutableMap<String, Attribute> attributes,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes,
      AspectPropagationEdgesSupplier<String> restrictToAttributes,
      AspectPropagationEdgesSupplier<Label> propagateToToolchainsTypes,
      @Nullable ConfigurationFragmentPolicy configurationFragmentPolicy,
      boolean applyToFiles,
      boolean applyToGeneratingRules,
      ImmutableSet<AspectClass> requiredAspectClasses,
      @Nullable AspectPropagationPredicate propagationPredicate,
      ImmutableSet<Label> execCompatibleWith,
      ImmutableMap<String, DeclaredExecGroup> execGroups,
      ImmutableSet<? extends StarlarkSubruleApi> subrules) {
    this.aspectClass = aspectClass;
    this.advertisedProviders = advertisedProviders;
    this.requiredProviders = requiredProviders;
    this.requiredProvidersForAspects = requiredProvidersForAspects;
    this.attributes = attributes;
    this.toolchainTypes = toolchainTypes;
    this.restrictToAttributes = restrictToAttributes;
    this.propagateToToolchainsTypes = propagateToToolchainsTypes;
    this.configurationFragmentPolicy = configurationFragmentPolicy;
    this.applyToFiles = applyToFiles;
    this.applyToGeneratingRules = applyToGeneratingRules;
    this.requiredAspectClasses = requiredAspectClasses;
    this.propagationPredicate = propagationPredicate;
    this.execCompatibleWith = execCompatibleWith;
    this.execGroups = execGroups;
    this.subrules = subrules;
  }

  public String getName() {
    return aspectClass.getName();
  }

  /**
   * Returns the attributes of the aspect in the form of a String -&gt; {@link Attribute} map.
   *
   * <p>All attributes are either implicit or late-bound.
   */
  public ImmutableMap<String, Attribute> getAttributes() {
    return attributes;
  }

  /** Returns the required toolchains declared by this aspect. */
  public ImmutableSet<ToolchainTypeRequirement> getToolchainTypes() {
    return toolchainTypes;
  }

  /**
   * Returns the constraint values that must be present on an execution platform for this aspect.
   */
  public ImmutableSet<Label> execCompatibleWith() {
    return execCompatibleWith;
  }

  /** Returns the execution groups that this aspect can use when creating actions. */
  public ImmutableMap<String, DeclaredExecGroup> execGroups() {
    return execGroups;
  }

  /** Returns the subrules declared by this aspect. */
  public ImmutableSet<? extends StarlarkSubruleApi> getSubrules() {
    return subrules;
  }

  /**
   * Returns {@link RequiredProviders} that a configured target must have so that this aspect can be
   * applied to it.
   *
   * <p>If a configured target does not satisfy required providers, the aspect is silently not
   * created for it.
   */
  public RequiredProviders getRequiredProviders() {
    return requiredProviders;
  }

  /**
   * Aspects do not depend on other aspects applied to the same target <em>unless</em> the other
   * aspect satisfies the {@link RequiredProviders} this method returns
   */
  public RequiredProviders getRequiredProvidersForAspects() {
    return requiredProvidersForAspects;
  }

  /** Returns the supplier of the attributes to which the aspect will propagate. */
  public AspectPropagationEdgesSupplier<String> getAttributeAspects() {
    return restrictToAttributes;
  }

  /**
   * Returns the supplier of the toolchains types for which the aspect will propagate to matching
   * resolved toolchains.
   */
  public AspectPropagationEdgesSupplier<Label> getToolchainsAspects() {
    return propagateToToolchainsTypes;
  }

  /** Returns the set of configuration fragments required by this Aspect. */
  public ConfigurationFragmentPolicy getConfigurationFragmentPolicy() {
    return configurationFragmentPolicy;
  }

  /**
   * Returns whether this aspect applies to (output) files.
   *
   * <p>Currently only supported for top-level aspects and targets, and only for output files.
   */
  public boolean applyToFiles() {
    return applyToFiles;
  }

  /**
   * Returns whether this aspect should, when it would be applied to an output file, instead apply
   * to the generating rule of that output file.
   */
  public boolean applyToGeneratingRules() {
    return applyToGeneratingRules;
  }

  public static boolean satisfies(Aspect aspect, AdvertisedProviderSet advertisedProviderSet) {
    return aspect.getDefinition().requiredProviders.isSatisfiedBy(advertisedProviderSet);
  }

  /** Checks if the given {@code maybeRequiredAspect} is required by this aspect definition */
  public boolean requires(Aspect maybeRequiredAspect) {
    return requiredAspectClasses.contains(maybeRequiredAspect.getAspectClass());
  }

  @Nullable
  public AspectPropagationPredicate getPropagationPredicate() {
    return propagationPredicate;
  }

  /** Collects all attribute labels from the specified aspectDefinition. */
  public static void addAllAttributesOfAspect(
      Multimap<Attribute, Label> labelBuilder, Aspect aspect, DependencyFilter dependencyFilter) {
    forEachLabelDepFromAllAttributesOfAspect(aspect, dependencyFilter, labelBuilder::put);
  }

  public static void forEachLabelDepFromAllAttributesOfAspect(
      Aspect aspect,
      DependencyFilter dependencyFilter,
      BiConsumer<Attribute, Label> consumer) {
    LabelVisitor labelVisitor =
        (label, aspectAttribute) -> {
          if (label == null) {
            return;
          }
          consumer.accept(aspectAttribute, label);
        };
    for (Attribute aspectAttribute : aspect.getDefinition().attributes.values()) {
      if (!dependencyFilter.test(aspect, aspectAttribute)) {
        continue;
      }
      Type<?> type = aspectAttribute.getType();
      if (type.getLabelClass() != LabelClass.DEPENDENCY) {
        continue;
      }
      visitSingleAttribute(aspectAttribute, aspectAttribute.getType(), labelVisitor);
    }
  }

  private static <T> void visitSingleAttribute(
      Attribute attribute, Type<T> type, LabelVisitor labelVisitor) {
    type.visitLabels(labelVisitor, type.cast(attribute.getDefaultValue(null)), attribute);
  }

  public static Builder builder(AspectClass aspectClass) {
    return new Builder(aspectClass);
  }

  /** Builder class for {@link AspectDefinition}. */
  public static final class Builder {
    private final AspectClass aspectClass;
    private final Map<String, Attribute> attributes = new LinkedHashMap<>();
    private final AdvertisedProviderSet.Builder advertisedProviders =
        AdvertisedProviderSet.builder();
    private final RequiredProviders.Builder requiredProviders =
        RequiredProviders.acceptAnyBuilder();
    private final RequiredProviders.Builder requiredAspectProviders =
        RequiredProviders.acceptNoneBuilder();
    private AspectPropagationEdgesSupplier<String> propagateAlongAttributes =
        AspectPropagationEdgesSupplier.DEFAULT_ATTR_ASPECTS_SUPPLIER;
    private AspectPropagationEdgesSupplier<Label> propagateToToolchainsTypes =
        AspectPropagationEdgesSupplier.DEFAULT_TOOLCHAINS_ASPECTS_SUPPLIER;
    private final ConfigurationFragmentPolicy.Builder configurationFragmentPolicy =
        new ConfigurationFragmentPolicy.Builder();
    private boolean applyToFiles = false;
    private boolean applyToGeneratingRules = false;
    private final Set<ToolchainTypeRequirement> toolchainTypes = new HashSet<>();
    private ImmutableSet<AspectClass> requiredAspectClasses = ImmutableSet.of();
    private AspectPropagationPredicate propagationPredicate = null;
    private ImmutableSet<Label> execCompatibleWith = ImmutableSet.of();
    private ImmutableMap<String, DeclaredExecGroup> execGroups = ImmutableMap.of();
    private ImmutableSet<? extends StarlarkSubruleApi> subrules = ImmutableSet.of();

    public Builder(AspectClass aspectClass) {
      this.aspectClass = aspectClass;
    }

    @CanIgnoreReturnValue
    public Builder requireProviders(RequiredProviders requiredProviders) {
      this.requireStarlarkProviderSets(requiredProviders.getStarlarkProviders());
      return this;
    }

    /**
     * Asserts that this aspect can only be evaluated for rules that supply all of the providers
     * from at least one set of required providers.
     */
    @CanIgnoreReturnValue
    public Builder requireStarlarkProviderSets(
        Iterable<ImmutableSet<StarlarkProviderIdentifier>> providerSets) {
      for (ImmutableSet<StarlarkProviderIdentifier> providerSet : providerSets) {
        if (!providerSet.isEmpty()) {
          requiredProviders.addStarlarkSet(providerSet);
        }
      }
      return this;
    }

    /**
     * Asserts that this aspect can only be evaluated for rules that supply all of the specified
     * Starlark providers.
     */
    @CanIgnoreReturnValue
    public Builder requireStarlarkProviders(StarlarkProviderIdentifier... starlarkProviders) {
      requiredProviders.addStarlarkSet(ImmutableSet.copyOf(starlarkProviders));
      return this;
    }

    /**
     * Asserts that this aspect requires a list of aspects to be applied before it on the configured
     * target.
     */
    @CanIgnoreReturnValue
    public Builder requiredAspectClasses(ImmutableSet<AspectClass> requiredAspectClasses) {
      this.requiredAspectClasses = requiredAspectClasses;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder propagationPredicate(AspectPropagationPredicate propagationPredicate) {
      this.propagationPredicate = propagationPredicate;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder requireAspectsWithProviders(
        Iterable<ImmutableSet<StarlarkProviderIdentifier>> providerSets) {
      for (ImmutableSet<StarlarkProviderIdentifier> providerSet : providerSets) {
        if (!providerSet.isEmpty()) {
          requiredAspectProviders.addStarlarkSet(providerSet);
        }
      }
      return this;
    }

    /** State that the aspect being built provides given providers. */
    @CanIgnoreReturnValue
    public Builder advertiseProvider(ImmutableList<StarlarkProviderIdentifier> providers) {
      for (StarlarkProviderIdentifier provider : providers) {
        advertisedProviders.addStarlark(provider);
      }
      return this;
    }

    /** Sets the supplier of the attributes to which the aspect will propagate. */
    @CanIgnoreReturnValue
    public Builder propagateToAttributes(AspectPropagationEdgesSupplier<String> attributes) {
      this.propagateAlongAttributes = attributes;
      return this;
    }

    /**
     * Sets the supplier of the toolchains types for which the aspect will propagate to matching
     * resolved toolchains.
     */
    @CanIgnoreReturnValue
    public Builder propagateToToolchainsTypes(
        AspectPropagationEdgesSupplier<Label> toolchainsTypes) {
      this.propagateToToolchainsTypes = toolchainsTypes;
      return this;
    }

    /**
     * Adds an attribute to the aspect.
     */
    public <TYPE> Builder add(Attribute.Builder<TYPE> attr) {
      Attribute attribute = attr.build();
      return add(attribute);
    }

    /**
     * Adds an attribute to the aspect.
     *
     * <p>Aspects attributes can be of any data type if they are not public, i.e. implicit (starting
     * with '$') or late-bound (starting with ':'). While public attributes can only be of types
     * string, integer or boolean.
     *
     * <p>Aspect definition currently cannot handle {@link ComputedDefault} dependencies (type LABEL
     * or LABEL_LIST), because all the dependencies are resolved from the aspect definition and the
     * defining rule.
     */
    @CanIgnoreReturnValue
    public Builder add(Attribute attribute) {
      Preconditions.checkArgument(
          attribute.isImplicit()
              || attribute.isLateBound()
              || (attribute.getType() == Type.STRING && attribute.checkAllowedValues())
              || (attribute.getType() == Type.INTEGER && attribute.checkAllowedValues())
              || attribute.getType() == Type.BOOLEAN,
          "%s: Invalid attribute '%s' (%s)",
          aspectClass.getName(),
          attribute.getName(),
          attribute.getType());

      // Attributes specifying dependencies using ComputedDefault value are currently not supported.
      // The limitation is in place because:
      //  - blaze query requires that all possible values are knowable without BuildConguration
      //  - aspects can attach to any rule
      // Current logic in #forEachLabelDepFromAllAttributesOfAspect is not enough,
      // however {Conservative,Precise}AspectResolver can probably be improved to make that work.
      Preconditions.checkArgument(
          !(attribute.getType().getLabelClass() == LabelClass.DEPENDENCY
              && (attribute.getDefaultValueUnchecked() instanceof ComputedDefault)),
          "%s: Invalid attribute '%s' (%s) with computed default dependencies",
          aspectClass.getName(),
          attribute.getName(),
          attribute.getType());
      Preconditions.checkArgument(
          !attributes.containsKey(attribute.getName()),
          "%s: An attribute with the name '%s' already exists.",
          aspectClass.getName(),
          attribute.getName());
      attributes.put(attribute.getName(), attribute);
      return this;
    }

    /**
     * Declares that the implementation of the associated aspect definition requires the given
     * fragments to be present in this rule's exec and target configurations.
     *
     * <p>The value is inherited by subclasses.
     */
    @CanIgnoreReturnValue
    public Builder requiresConfigurationFragments(
        Class<? extends Fragment>... configurationFragments) {
      configurationFragmentPolicy.requiresConfigurationFragments(
          ImmutableSet.copyOf(configurationFragments));
      return this;
    }

    /**
     * Declares the configuration fragments that are required by this rule for the target
     * configuration.
     *
     * <p>In contrast to {@link #requiresConfigurationFragments(Class...)}, this method takes the
     * Starlark module names of fragments instead of their classes.
     */
    @CanIgnoreReturnValue
    public Builder requiresConfigurationFragmentsByStarlarkBuiltinName(
        Collection<String> configurationFragmentNames) {
      configurationFragmentPolicy.requiresConfigurationFragmentsByStarlarkBuiltinName(
          configurationFragmentNames);
      return this;
    }

    /**
     * Sets the policy for the case where the configuration is missing the required fragment class
     * (see {@link #requiresConfigurationFragments}).
     */
    @CanIgnoreReturnValue
    public Builder setMissingFragmentPolicy(
        Class<?> fragmentClass, MissingFragmentPolicy missingFragmentPolicy) {
      configurationFragmentPolicy.setMissingFragmentPolicy(fragmentClass, missingFragmentPolicy);
      return this;
    }

    /**
     * Sets whether this aspect should apply to files.
     *
     * <p>Default is <code>false</code>. Currently only supported for top-level aspects and targets,
     * and only for output files.
     */
    @CanIgnoreReturnValue
    public Builder applyToFiles(boolean propagateOverGeneratedFiles) {
      this.applyToFiles = propagateOverGeneratedFiles;
      return this;
    }

    /**
     * Sets whether this aspect should, when it would be applied to an output file, instead apply to
     * the generating rule of that output file.
     *
     * <p>Default is <code>false</code>. Currently only supported for aspects which do not have a
     * "required providers" list.
     */
    @CanIgnoreReturnValue
    public Builder applyToGeneratingRules(boolean applyToGeneratingRules) {
      this.applyToGeneratingRules = applyToGeneratingRules;
      return this;
    }

    /** Adds the given toolchains as requirements for this aspect. */
    public Builder addToolchainTypes(ToolchainTypeRequirement... toolchainTypes) {
      return this.addToolchainTypes(ImmutableSet.copyOf(toolchainTypes));
    }

    /** Adds the given toolchains as requirements for this aspect. */
    @CanIgnoreReturnValue
    public Builder addToolchainTypes(Collection<ToolchainTypeRequirement> toolchainTypes) {
      this.toolchainTypes.addAll(toolchainTypes);
      return this;
    }

    /**
     * Adds the given constraint values to the set required for execution platforms for this aspect.
     */
    @CanIgnoreReturnValue
    public Builder execCompatibleWith(ImmutableSet<Label> execCompatibleWith) {
      this.execCompatibleWith = execCompatibleWith;
      return this;
    }

    /** Sets the execution groups that are available for actions created by this aspect. */
    @CanIgnoreReturnValue
    public Builder execGroups(ImmutableMap<String, DeclaredExecGroup> execGroups) {
      // TODO(b/230337573): validate names
      // TODO(b/230337573): handle copy_from_default
      this.execGroups = execGroups;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder subrules(ImmutableSet<? extends StarlarkSubruleApi> subrules) {
      this.subrules = subrules;
      return this;
    }

    /**
     * Builds the aspect definition.
     *
     * <p>The builder object is reusable afterwards.
     */
    public AspectDefinition build() {
      RequiredProviders requiredProviders = this.requiredProviders.build();
      if (applyToGeneratingRules) {
        if (!requiredProviders.acceptsAny()) {
          throw new IllegalStateException(
              "An aspect cannot simultaneously have required providers "
                  + "and apply to generating rules.");
        }

        if (propagationPredicate != null) {
          throw new IllegalStateException(
              "An aspect cannot simultaneously have a propagation predicate and apply to generating"
                  + " rules.");
        }
      }

      if (applyToFiles) {
        if (!requiredProviders.acceptsAny()) {
          throw new IllegalStateException(
              "An aspect cannot simultaneously have required providers and apply to files.");
        }
        if (propagationPredicate != null) {
          throw new IllegalStateException(
              "An aspect cannot simultaneously have a propagation predicate and apply to files.");
        }
      }

      if (applyToFiles && !requiredProviders.acceptsAny()) {
        throw new IllegalStateException(
            "An aspect cannot simultaneously have required providers and apply to files.");
      }

      return new AspectDefinition(
          aspectClass,
          advertisedProviders.build(),
          requiredProviders,
          requiredAspectProviders.build(),
          ImmutableMap.copyOf(attributes),
          ImmutableSet.copyOf(toolchainTypes),
          propagateAlongAttributes,
          propagateToToolchainsTypes,
          configurationFragmentPolicy.build(),
          applyToFiles,
          applyToGeneratingRules,
          requiredAspectClasses,
          propagationPredicate,
          execCompatibleWith,
          execGroups,
          subrules);
    }
  }
}
