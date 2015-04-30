/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.transforms;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Combine.CombineFn;
import com.google.cloud.dataflow.sdk.transforms.DoFn.DelegatingAggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn.KeyedState;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.WindowingInternals;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.reflect.TypeToken;

import org.joda.time.Duration;
import org.joda.time.Instant;

import java.io.Serializable;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.Map;

/**
 * The argument to {@link ParDo} providing the code to use to process
 * elements of the input
 * {@link com.google.cloud.dataflow.sdk.values.PCollection}.
 *
 * <p> See {@link ParDo} for more explanation, examples of use, and
 * discussion of constraints on {@code DoFnWithContext}s, including their
 * serializability, lack of access to global shared mutable state,
 * requirements for failure tolerance, and benefits of optimization.
 *
 * <p> {@code DoFnWithContext}s can be tested in a particular
 * {@code Pipeline} by running that {@code Pipeline} on sample input
 * and then checking its output.  Unit testing of a {@code DoFnWithContext},
 * separately from any {@code ParDo} transform or {@code Pipeline},
 * can be done via the {@link DoFnTester} harness.
 *
 * <p>Implementations must define a method annotated with {@link ProcessElement}
 * that satisfies the requirements described there. See the {@link ProcessElement}
 * for details.
 *
 * <p> This functionality is experimental and likely to change.
 *
 * <p> Example usage:
 *
 * <pre> {@code
 * PCollection<String> lines = ... ;
 * PCollection<String> words =
 *     lines.apply(ParDo.of(new DoFnWithContext<String, String>() {
 *         @ProcessElement
 *         public void processElement(ProcessContext c, BoundedWindow window) {
 *
 *         }}));
 * } </pre>
 *
 * @param <I> the type of the (main) input elements
 * @param <O> the type of the (main) output elements
 */
@Experimental
@SuppressWarnings("serial")
public abstract class DoFnWithContext<I, O> implements Serializable {

  /** Information accessible to all methods in this {@code DoFnWithContext}. */
  public abstract class Context {

    /**
     * Returns the {@code PipelineOptions} specified with the
     * {@link com.google.cloud.dataflow.sdk.runners.PipelineRunner}
     * invoking this {@code DoFnWithContext}.  The {@code PipelineOptions} will
     * be the default running via {@link DoFnTester}.
     */
    public abstract PipelineOptions getPipelineOptions();

    /**
     * Adds the given element to the main output {@code PCollection}.
     *
     * <p> If invoked from {@link ProcessElement}, the output
     * element will have the same timestamp and be in the same windows
     * as the input element passed to {@link @ProcessElement}).
     *
     * <p> If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element. The output element
     * will have a timestamp of negative infinity.
     */
    public abstract void output(O output);

    /**
     * Adds the given element to the main output {@code PCollection},
     * with the given timestamp.
     *
     * <p> If invoked from {@link ProcessElement}), the timestamp
     * must not be older than the input element's timestamp minus
     * {@link DoFn#getAllowedTimestampSkew}.  The output element will
     * be in the same windows as the input element.
     *
     * <p> If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element except for the
     * timestamp.
     */
    public abstract void outputWithTimestamp(O output, Instant timestamp);

    /**
     * Adds the given element to the side output {@code PCollection} with the
     * given tag.
     *
     * <p> The caller of {@code ParDo} uses {@link ParDo#withOutputTags} to
     * specify the tags of side outputs that it consumes. Non-consumed side
     * outputs, e.g., outputs for monitoring purposes only, don't necessarily
     * need to be specified.
     *
     * <p> The output element will have the same timestamp and be in the same
     * windows as the input element passed to {@link ProcessElement}).
     *
     * <p> If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element. The output element
     * will have a timestamp of negative infinity.
     *
     * @throws IllegalArgumentException if the number of outputs exceeds
     * the limit of 1,000 outputs per DoFn
     * @see ParDo#withOutputTags
     */
    public abstract <T> void sideOutput(TupleTag<T> tag, T output);

    /**
     * Adds the given element to the specified side output {@code PCollection},
     * with the given timestamp.
     *
     * <p> If invoked from {@link ProcessElement}), the timestamp
     * must not be older than the input element's timestamp minus
     * {@link DoFn#getAllowedTimestampSkew}.  The output element will
     * be in the same windows as the input element.
     *
     * <p> If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element except for the
     * timestamp.
     *
     * @throws IllegalArgumentException if the number of outputs exceeds
     * the limit of 1,000 outputs per DoFn
     * @see ParDo#withOutputTags
     */
    public abstract <T> void sideOutputWithTimestamp(
        TupleTag<T> tag, T output, Instant timestamp);
  }

  /**
   * Information accessible when running {@link DoFn#processElement}.
   */
  public abstract class ProcessContext extends Context {

    /**
     * Returns the input element to be processed.
     */
    public abstract I element();


    /**
     * Returns the value of the side input.
     *
     * @throws IllegalArgumentException if this is not a side input
     * @see ParDo#withSideInputs
     */
    public abstract <T> T sideInput(PCollectionView<T> view);

    /**
     * Returns the timestamp of the input element.
     *
     * <p> See {@link com.google.cloud.dataflow.sdk.transforms.windowing.Window}
     * for more information.
     */
    public abstract Instant timestamp();
  }

  /**
   * Returns the allowed timestamp skew duration, which is the maximum
   * duration that timestamps can be shifted backward in
   * {@link DoFnWithContext.Context#outputWithTimestamp}.
   *
   * The default value is {@code Duration.ZERO}, in which case
   * timestamps can only be shifted forward to future.  For infinite
   * skew, return {@code Duration.millis(Long.MAX_VALUE)}.
   */
  public Duration getAllowedTimestampSkew() {
    return Duration.ZERO;
  }

  /////////////////////////////////////////////////////////////////////////////

  Map<String, DelegatingAggregator<?, ?>> aggregators = new HashMap<>();

  /**
   * Returns a {@link TypeToken} capturing what is known statically
   * about the input type of this {@code DoFnWithContext} instance's most-derived
   * class.
   *
   * <p> See {@link #getOutputTypeToken} for more discussion.
   */
  protected TypeToken<I> getInputTypeToken() {
    return new TypeToken<I>(getClass()) {};
  }

  /**
   * Returns a {@link TypeToken} capturing what is known statically
   * about the output type of this {@code DoFnWithContext} instance's
   * most-derived class.
   *
   * <p> In the normal case of a concrete {@code DoFnWithContext} subclass with
   * no generic type parameters of its own (including anonymous inner
   * classes), this will be a complete non-generic type, which is good
   * for choosing a default output {@code Coder<O>} for the output
   * {@code PCollection<O>}.
   */
  protected TypeToken<O> getOutputTypeToken() {
    return new TypeToken<O>(getClass()) {};
  }

  /**
   * Interface for runner implementors to provide implementations of extra context information.
   *
   * <p>The methods on this interface are called by {@link DoFnReflector} before invoking an
   * annotated {@link StartBundle}, {@link ProcessElement} or {@link FinishBundle} method that
   * has indicated it needs the given extra context.
   *
   * <p>In the case of {@link ProcessElement} it is called once per invocation of
   * {@link ProcessElement}.
   */
  public interface ExtraContextFactory<I, O> {
    /**
     * Construct the {@link KeyedState} interface for use within a {@link DoFnWithContext} that
     * needs it. This is called if the {@link ProcessElement} method has a parameter of type
     * {@link KeyedState}.
     *
     * @return {@link KeyedState} interface for interacting with keyed state.
     */
    KeyedState keyedState();

    /**
     * Construct the {@link BoundedWindow} to use within a {@link DoFnWithContext} that
     * needs it. This is called if the {@link ProcessElement} method has a parameter of type
     * {@link BoundedWindow}.
     *
     * @return {@link BoundedWindow} of the element currently being processed.
     */
    BoundedWindow window();

    /**
     * Construct the {@link WindowingInternals} to use within a {@link DoFnWithContext} that
     * needs it. This is called if the {@link ProcessElement} method has a parameter of type
     * {@link WindowingInternals}.
     */
    WindowingInternals<I, O> windowingInternals();
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Annotation for the method to use to prepare an instance for processing a batch of elements.
   * The method annotated with this must satisfy the following constraints:
   * <ul>
   *   <li>It must have at least one argument.
   *   <li>Its first (and only) argument must be a {@link DoFnWithContext.Context}.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface StartBundle {}

  /**
   * Annotation for the method to use for processing elements. A subclass of
   * {@link DoFnWithContext} must have a method with this annotation satisfying
   * the following constraints in order for it to be executable:
   * <ul>
   *   <li>It must have at least one argument.
   *   <li>Its first argument must be a {@link DoFnWithContext.ProcessContext}.
   *   <li>Its remaining arguments must be {@link KeyedState}, {@link BoundedWindow}, or
   *   {@link WindowingInternals WindowingInternals<I, O>}.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface ProcessElement {}

  /**
   * Annotation for the method to use to prepare an instance for processing a batch of elements.
   * The method annotated with this must satisfy the following constraints:
   * <ul>
   *   <li>It must have at least one argument.
   *   <li>Its first (and only) argument must be a {@link DoFnWithContext.Context}.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface FinishBundle {}

  /**
   * Returns an {@link Aggregator} with aggregation logic specified by the
   * {@link CombineFn} argument. The name provided must be unique across
   * {@link Aggregator}s created within the DoFn.
   *
   * @param name the name of the aggregator
   * @param combiner the {@link CombineFn} to use in the aggregator
   * @return an aggregator for the provided name and combiner in the scope of
   *         this DoFn
   * @throws NullPointerException if the name or combiner is null
   * @throws IllegalArgumentException if the given name collides with another
   *         aggregator in this scope
   */
  public final <VI, VO> Aggregator<VI, VO> createAggregator(
      String name, Combine.CombineFn<? super VI, ?, VO> combiner) {
    checkNotNull(name, "name cannot be null");
    checkNotNull(combiner, "combiner cannot be null");
    checkArgument(!aggregators.containsKey(name),
        "Cannot create aggregator with name %s."
        + " An Aggregator with that name already exists within this scope.",
        name);
    DelegatingAggregator<VI, VO> aggregator =
        new DelegatingAggregator<>(name, combiner);
    aggregators.put(name, aggregator);
    return aggregator;
  }

  /**
   * Returns an {@link Aggregator} with the aggregation logic specified by the
   * {@link SerializableFunction} argument. The name provided must be unique
   * across {@link Aggregator}s created within the DoFn.
   *
   * @param name the name of the aggregator
   * @param combiner the {@link SerializableFunction} to use in the aggregator
   * @return an aggregator for the provided name and combiner in the scope of
   *         this DoFn
   * @throws NullPointerException if the name or combiner is null
   * @throws IllegalArgumentException if the given name collides with another
   *         aggregator in this scope
   */
  public final <VI> Aggregator<VI, VI> createAggregator(
      String name, SerializableFunction<Iterable<VI>, VI> combiner) {
    checkNotNull(combiner, "combiner cannot be null.");
    return createAggregator(name, Combine.SimpleCombineFn.of(combiner));
  }
}