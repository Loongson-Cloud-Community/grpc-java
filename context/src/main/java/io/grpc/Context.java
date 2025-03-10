/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import io.grpc.Context.CheckReturnValue;
import io.grpc.PersistentHashArrayMappedTrie.Node;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A context propagation mechanism which can carry scoped-values across API boundaries and between
 * threads. Examples of state propagated via context include:
 * <ul>
 *   <li>Security principals and credentials.</li>
 *   <li>Local and distributed tracing information.</li>
 * </ul>
 *
 * <p>A Context object can be {@link #attach attached} to the {@link Storage}, which effectively
 * forms a <b>scope</b> for the context.  The scope is bound to the current thread.  Within a scope,
 * its Context is accessible even across API boundaries, through {@link #current}.  The scope is
 * later exited by {@link #detach detaching} the Context.
 *
 * <p>Context objects are immutable and inherit state from their parent. To add or overwrite the
 * current state a new context object must be created and then attached, replacing the previously
 * bound context. For example:
 *
 * <pre>
 *   Context withCredential = Context.current().withValue(CRED_KEY, cred);
 *   withCredential.run(new Runnable() {
 *     public void run() {
 *        readUserRecords(userId, CRED_KEY.get());
 *     }
 *   });
 * </pre>
 *
 * <p>Contexts are also used to represent a scoped unit of work. When the unit of work is done the
 * context must be cancelled. This cancellation will cascade to all descendant contexts. You can
 * add a {@link CancellationListener} to a context to be notified when it or one of its ancestors
 * has been cancelled. Cancellation does not release the state stored by a context and it's
 * perfectly valid to {@link #attach()} an already cancelled context to make it current. To cancel a
 * context (and its descendants) you first create a {@link CancellableContext} and when you need to
 * signal cancellation call {@link CancellableContext#cancel} or {@link
 * CancellableContext#detachAndCancel}.
 *
 * <p>Contexts can also be created with a timeout relative to the system nano clock which will
 * cause it to automatically cancel at the desired time.
 *
 *
 * <p>Notes and cautions on use:
 * <ul>
 *    <li>Every {@code attach()} should have a {@code detach()} in the same method. And every
 * CancellableContext should be cancelled at some point. Breaking these rules may lead to memory
 * leaks.
 *    <li>While Context objects are immutable they do not place such a restriction on the state
 * they store.</li>
 *    <li>Context is not intended for passing optional parameters to an API and developers should
 * take care to avoid excessive dependence on context when designing an API.</li>
 *    <li>Do not mock this class.  Use {@link #ROOT} for a non-null instance.
 * </ul>
 */
/* @DoNotMock("Use ROOT for a non-null Context") // commented out to avoid dependencies  */
@CheckReturnValue
public class Context {

  static final Logger log = Logger.getLogger(Context.class.getName());

  // Long chains of contexts are suspicious and usually indicate a misuse of Context.
  // The threshold is arbitrarily chosen.
  // VisibleForTesting
  static final int CONTEXT_DEPTH_WARN_THRESH = 1000;

  /**
   * The logical root context which is the ultimate ancestor of all contexts. This context
   * is not cancellable and so will not cascade cancellation or retain listeners.
   *
   * <p>Never assume this is the default context for new threads, because {@link Storage} may define
   * a default context that is different from ROOT.
   */
  public static final Context ROOT = new Context();

  // Visible For testing
  static Storage storage() {
    return LazyStorage.storage;
  }

  // Lazy-loaded storage. Delaying storage initialization until after class initialization makes it
  // much easier to avoid circular loading since there can still be references to Context as long as
  // they don't depend on storage, like key() and currentContextExecutor(). It also makes it easier
  // to handle exceptions.
  private static final class LazyStorage {
    static final Storage storage;

    static {
      AtomicReference<Throwable> deferredStorageFailure = new AtomicReference<>();
      storage = createStorage(deferredStorageFailure);
      Throwable failure = deferredStorageFailure.get();
      // Logging must happen after storage has been set, as loggers may use Context.
      if (failure != null) {
        log.log(Level.FINE, "Storage override doesn't exist. Using default", failure);
      }
    }

    private static Storage createStorage(
        AtomicReference<? super ClassNotFoundException> deferredStorageFailure) {
      try {
        Class<?> clazz = Class.forName("io.grpc.override.ContextStorageOverride");
        // The override's constructor is prohibited from triggering any code that can loop back to
        // Context
        return clazz.asSubclass(Storage.class).getConstructor().newInstance();
      } catch (ClassNotFoundException e) {
        deferredStorageFailure.set(e);
        return new ThreadLocalContextStorage();
      } catch (Exception e) {
        throw new RuntimeException("Storage override failed to initialize", e);
      }
    }
  }

  /**
   * Create a {@link Key} with the given debug name.
   *
   * @param debugString a name intended for debugging purposes and does not impact behavior.
   *                    Multiple different keys may have the same debugString.
   *                    The value should be not null.
   */
  public static <T> Key<T> key(String debugString) {
    return new Key<>(debugString);
  }

  /**
   * Create a {@link Key} with the given debug name and default value.
   *
   * @param debugString a name intended for debugging purposes and does not impact behavior.
   *                    Multiple different keys may have the same debugString.
   *                    The value should be not null.
   */
  public static <T> Key<T> keyWithDefault(String debugString, T defaultValue) {
    return new Key<>(debugString, defaultValue);
  }

  /**
   * Return the context associated with the current scope, will never return {@code null}.
   *
   * <p>Will never return {@link CancellableContext} even if one is attached, instead a
   * {@link Context} is returned with the same properties and lifetime. This is to avoid
   * code stealing the ability to cancel arbitrarily.
   */
  public static Context current() {
    Context current = storage().current();
    if (current == null) {
      return ROOT;
    }
    return current;
  }

  final CancellableContext cancellableAncestor;
  final Node<Key<?>, Object> keyValueEntries;
  // The number parents between this context and the root context.
  final int generation;

  /**
   * Construct a context that cannot be cancelled and will not cascade cancellation from its parent.
   */
  private Context(Node<Key<?>, Object> keyValueEntries, int generation) {
    this.cancellableAncestor = null;
    this.keyValueEntries = keyValueEntries;
    this.generation = generation;
    validateGeneration(generation);
  }

  /**
   * Construct a context that cannot be cancelled but will cascade cancellation from its parent if
   * it is cancellable.
   */
  private Context(Context parent, Node<Key<?>, Object> keyValueEntries) {
    this.cancellableAncestor = cancellableAncestor(parent);
    this.keyValueEntries = keyValueEntries;
    this.generation = parent.generation + 1;
    validateGeneration(generation);
  }

  /**
   * Construct for {@link #ROOT}.
   */
  private Context() {
    this.cancellableAncestor = null;
    this.keyValueEntries = null;
    this.generation = 0;
    validateGeneration(generation);
  }

  /**
   * Create a new context which is independently cancellable and also cascades cancellation from
   * its parent. Callers <em>must</em> ensure that either {@link
   * CancellableContext#cancel(Throwable)} or {@link CancellableContext#detachAndCancel(Context,
   * Throwable)} are called at a later point, in order to allow this context to be garbage
   * collected.
   *
   * <p>Sample usage:
   * <pre>
   *   Context.CancellableContext withCancellation = Context.current().withCancellation();
   *   try {
   *     withCancellation.run(new Runnable() {
   *       public void run() {
   *         Context current = Context.current();
   *         while (!current.isCancelled()) {
   *           keepWorking();
   *         }
   *       }
   *     });
   *   } finally {
   *     withCancellation.cancel(null);
   *   }
   * </pre>
   */
  public CancellableContext withCancellation() {
    return new CancellableContext(this);
  }

  /**
   * Create a new context which will cancel itself after the given {@code duration} from now.
   * The returned context will cascade cancellation of its parent. Callers may explicitly cancel
   * the returned context prior to the deadline just as for {@link #withCancellation()}. If the unit
   * of work completes before the deadline, the context should be explicitly cancelled to allow
   * it to be garbage collected.
   *
   * <p>Sample usage:
   * <pre>
   *   Context.CancellableContext withDeadline = Context.current()
   *       .withDeadlineAfter(5, TimeUnit.SECONDS, scheduler);
   *   try {
   *     withDeadline.run(new Runnable() {
   *       public void run() {
   *         Context current = Context.current();
   *         while (!current.isCancelled()) {
   *           keepWorking();
   *         }
   *       }
   *     });
   *   } finally {
   *     withDeadline.cancel(null);
   *   }
   * </pre>
   */
  public CancellableContext withDeadlineAfter(long duration, TimeUnit unit,
                                              ScheduledExecutorService scheduler) {
    return withDeadline(Deadline.after(duration, unit), scheduler);
  }

  /**
   * Create a new context which will cancel itself at the given {@link Deadline}.
   * The returned context will cascade cancellation of its parent. Callers may explicitly cancel
   * the returned context prior to the deadline just as for {@link #withCancellation()}. If the unit
   * of work completes before the deadline, the context should be explicitly cancelled to allow
   * it to be garbage collected.
   *
   * <p>Sample usage:
   * <pre>
   *   Context.CancellableContext withDeadline = Context.current()
   *      .withDeadline(someReceivedDeadline, scheduler);
   *   try {
   *     withDeadline.run(new Runnable() {
   *       public void run() {
   *         Context current = Context.current();
   *         while (!current.isCancelled() &amp;&amp; moreWorkToDo()) {
   *           keepWorking();
   *         }
   *       }
   *     });
   *   } finally {
   *     withDeadline.cancel(null);
   *   }
   * </pre>
   */
  public CancellableContext withDeadline(Deadline newDeadline, ScheduledExecutorService scheduler) {
    checkNotNull(newDeadline, "deadline");
    checkNotNull(scheduler, "scheduler");
    Deadline existingDeadline = getDeadline();
    boolean scheduleDeadlineCancellation = true;
    if (existingDeadline != null && existingDeadline.compareTo(newDeadline) <= 0) {
      // The new deadline won't have an effect, so ignore it
      newDeadline = existingDeadline;
      scheduleDeadlineCancellation = false;
    }
    CancellableContext newCtx = new CancellableContext(this, newDeadline);
    if (scheduleDeadlineCancellation) {
      newCtx.setUpDeadlineCancellation(newDeadline, scheduler);
    }
    return newCtx;
  }

  /**
   * Create a new context with the given key value set. The new context will cascade cancellation
   * from its parent.
   *
   <pre>
   *   Context withCredential = Context.current().withValue(CRED_KEY, cred);
   *   withCredential.run(new Runnable() {
   *     public void run() {
   *        readUserRecords(userId, CRED_KEY.get());
   *     }
   *   });
   * </pre>
   *
   * <p>Note that multiple calls to {@link #withValue} can be chained together.
   * That is,
   *
   * <pre>
   * context.withValues(K1, V1, K2, V2);
   * // is the same as
   * context.withValue(K1, V1).withValue(K2, V2);
   * </pre>
   *
   * <p>Nonetheless, {@link Context} should not be treated like a general purpose
   * map with a large number of keys and values — combine multiple related items
   * together into a single key instead of separating them. But if the items
   * are unrelated, have separate keys for them.
   */
  public <V> Context withValue(Key<V> k1, V v1) {
    Node<Key<?>, Object> newKeyValueEntries =
        PersistentHashArrayMappedTrie.put(keyValueEntries, k1, v1);
    return new Context(this, newKeyValueEntries);
  }

  /**
   * Create a new context with the given key value set. The new context will cascade cancellation
   * from its parent.
   */
  public <V1, V2> Context withValues(Key<V1> k1, V1 v1, Key<V2> k2, V2 v2) {
    Node<Key<?>, Object> newKeyValueEntries =
        PersistentHashArrayMappedTrie.put(keyValueEntries, k1, v1);
    newKeyValueEntries = PersistentHashArrayMappedTrie.put(newKeyValueEntries, k2, v2);
    return new Context(this, newKeyValueEntries);
  }

  /**
   * Create a new context with the given key value set. The new context will cascade cancellation
   * from its parent.
   */
  public <V1, V2, V3> Context withValues(Key<V1> k1, V1 v1, Key<V2> k2, V2 v2, Key<V3> k3, V3 v3) {
    Node<Key<?>, Object> newKeyValueEntries =
        PersistentHashArrayMappedTrie.put(keyValueEntries, k1, v1);
    newKeyValueEntries = PersistentHashArrayMappedTrie.put(newKeyValueEntries, k2, v2);
    newKeyValueEntries = PersistentHashArrayMappedTrie.put(newKeyValueEntries, k3, v3);
    return new Context(this, newKeyValueEntries);
  }

  /**
   * Create a new context with the given key value set. The new context will cascade cancellation
   * from its parent.
   *
   * <p>For more than 4 key-value pairs, note that multiple calls to
   * {@link #withValue} can be chained together. That is,
   *
   * <pre>
   * context.withValues(K1, V1, K2, V2);
   * // is the same as
   * context.withValue(K1, V1).withValue(K2, V2);
   * </pre>
   *
   * <p>Nonetheless, {@link Context} should not be treated like a general purpose
   * map with a large number of keys and values — combine multiple related items
   * together into a single key instead of separating them. But if the items
   * are unrelated, have separate keys for them.
   */
  public <V1, V2, V3, V4> Context withValues(Key<V1> k1, V1 v1, Key<V2> k2, V2 v2,
      Key<V3> k3, V3 v3, Key<V4> k4, V4 v4) {
    Node<Key<?>, Object> newKeyValueEntries =
        PersistentHashArrayMappedTrie.put(keyValueEntries, k1, v1);
    newKeyValueEntries = PersistentHashArrayMappedTrie.put(newKeyValueEntries, k2, v2);
    newKeyValueEntries = PersistentHashArrayMappedTrie.put(newKeyValueEntries, k3, v3);
    newKeyValueEntries = PersistentHashArrayMappedTrie.put(newKeyValueEntries, k4, v4);
    return new Context(this, newKeyValueEntries);
  }

  /**
   * Create a new context which propagates the values of this context but does not cascade its
   * cancellation.
   */
  public Context fork() {
    return new Context(keyValueEntries, generation + 1);
  }

  /**
   * Attach this context, thus enter a new scope within which this context is {@link #current}.  The
   * previously current context is returned. It is allowed to attach contexts where {@link
   * #isCancelled()} is {@code true}.
   *
   * <p>Instead of using {@code attach()} and {@link #detach(Context)} most use-cases are better
   * served by using the {@link #run(Runnable)} or {@link #call(java.util.concurrent.Callable)} to
   * execute work immediately within a context's scope. If work needs to be done in other threads it
   * is recommended to use the 'wrap' methods or to use a propagating executor.
   *
   * <p>All calls to {@code attach()} should have a corresponding {@link #detach(Context)} within
   * the same method:
   * <pre>{@code Context previous = someContext.attach();
   * try {
   *   // Do work
   * } finally {
   *   someContext.detach(previous);
   * }}</pre>
   */
  public Context attach() {
    Context prev = storage().doAttach(this);
    if (prev == null) {
      return ROOT;
    }
    return prev;
  }

  /**
   * Reverse an {@code attach()}, restoring the previous context and exiting the current scope.
   *
   * <p>This context should be the same context that was previously {@link #attach attached}.  The
   * provided replacement should be what was returned by the same {@link #attach attach()} call.  If
   * an {@code attach()} and a {@code detach()} meet above requirements, they match.
   *
   * <p>It is expected that between any pair of matching {@code attach()} and {@code detach()}, all
   * {@code attach()}es and {@code detach()}es are called in matching pairs.  If this method finds
   * that this context is not {@link #current current}, either you or some code in-between are not
   * detaching correctly, and a SEVERE message will be logged but the context to attach will still
   * be bound.  <strong>Never</strong> use {@code Context.current().detach()}, as this will
   * compromise this error-detecting mechanism.
   */
  public void detach(Context toAttach) {
    checkNotNull(toAttach, "toAttach");
    storage().detach(this, toAttach);
  }

  // Visible for testing
  boolean isCurrent() {
    return current() == this;
  }

  /**
   * Is this context cancelled.
   */
  public boolean isCancelled() {
    if (cancellableAncestor == null) {
      return false;
    } else {
      return cancellableAncestor.isCancelled();
    }
  }

  /**
   * If a context {@link #isCancelled()} then return the cause of the cancellation or
   * {@code null} if context was cancelled without a cause. If the context is not yet cancelled
   * will always return {@code null}.
   *
   * <p>The cancellation cause is provided for informational purposes only and implementations
   * should generally assume that it has already been handled and logged properly.
   */
  public Throwable cancellationCause() {
    if (cancellableAncestor == null) {
      return null;
    } else {
      return cancellableAncestor.cancellationCause();
    }
  }

  /**
   * A context may have an associated {@link Deadline} at which it will be automatically cancelled.
   * @return A {@link io.grpc.Deadline} or {@code null} if no deadline is set.
   */
  public Deadline getDeadline() {
    if (cancellableAncestor == null) {
      return null;
    }
    return cancellableAncestor.getDeadline();
  }

  /**
   * Add a listener that will be notified when the context becomes cancelled.
   */
  public void addListener(final CancellationListener cancellationListener,
                          final Executor executor) {
    checkNotNull(cancellationListener, "cancellationListener");
    checkNotNull(executor, "executor");
    if (cancellableAncestor == null) {
      return;
    }
    cancellableAncestor.addListenerInternal(
        new ExecutableListener(executor, cancellationListener, this));
  }

  /**
   * Remove a {@link CancellationListener}.
   */
  public void removeListener(CancellationListener cancellationListener) {
    if (cancellableAncestor == null) {
      return;
    }
    cancellableAncestor.removeListenerInternal(cancellationListener, this);
  }

  // Used in tests to ensure that listeners are defined and released when cancellation cascades.
  // It's very important to ensure that we do not accidentally retain listeners.
  int listenerCount() {
    if (cancellableAncestor == null) {
      return 0;
    }
    return cancellableAncestor.listenerCount();
  }

  /**
   * Immediately run a {@link Runnable} with this context as the {@link #current} context.
   * @param r {@link Runnable} to run.
   */
  public void run(Runnable r) {
    Context previous = attach();
    try {
      r.run();
    } finally {
      detach(previous);
    }
  }

  /**
   * Immediately call a {@link Callable} with this context as the {@link #current} context.
   * @param c {@link Callable} to call.
   * @return result of call.
   */
  @CanIgnoreReturnValue
  public <V> V call(Callable<V> c) throws Exception {
    Context previous = attach();
    try {
      return c.call();
    } finally {
      detach(previous);
    }
  }

  /**
   * Wrap a {@link Runnable} so that it executes with this context as the {@link #current} context.
   */
  public Runnable wrap(final Runnable r) {
    return new Runnable() {
      @Override
      public void run() {
        Context previous = attach();
        try {
          r.run();
        } finally {
          detach(previous);
        }
      }
    };
  }

  /**
   * Wrap a {@link Callable} so that it executes with this context as the {@link #current} context.
   */
  public <C> Callable<C> wrap(final Callable<C> c) {
    return new Callable<C>() {
      @Override
      public C call() throws Exception {
        Context previous = attach();
        try {
          return c.call();
        } finally {
          detach(previous);
        }
      }
    };
  }

  /**
   * Wrap an {@link Executor} so that it always executes with this context as the {@link #current}
   * context. It is generally expected that {@link #currentContextExecutor(Executor)} would be
   * used more commonly than this method.
   *
   * <p>One scenario in which this executor may be useful is when a single thread is sharding work
   * to multiple threads.
   *
   * @see #currentContextExecutor(Executor)
   */
  public Executor fixedContextExecutor(final Executor e) {
    final class FixedContextExecutor implements Executor {
      @Override
      public void execute(Runnable r) {
        e.execute(wrap(r));
      }
    }

    return new FixedContextExecutor();
  }

  /**
   * Create an executor that propagates the {@link #current} context when {@link Executor#execute}
   * is called as the {@link #current} context of the {@code Runnable} scheduled. <em>Note that this
   * is a static method.</em>
   *
   * @see #fixedContextExecutor(Executor)
   */
  public static Executor currentContextExecutor(final Executor e) {
    final class CurrentContextExecutor implements Executor {
      @Override
      public void execute(Runnable r) {
        e.execute(Context.current().wrap(r));
      }
    }

    return new CurrentContextExecutor();
  }

  /**
   * A context which inherits cancellation from its parent but which can also be independently
   * cancelled and which will propagate cancellation to its descendants. To avoid leaking memory,
   * every CancellableContext must have a defined lifetime, after which it is guaranteed to be
   * cancelled.
   *
   * <p>This class must be cancelled by either calling {@link #close} or {@link #cancel}.
   * {@link #close} is equivalent to calling {@code cancel(null)}. It is safe to call the methods
   * more than once, but only the first call will have any effect. Because it's safe to call the
   * methods multiple times, users are encouraged to always call {@link #close} at the end of
   * the operation, and disregard whether {@link #cancel} was already called somewhere else.
   *
   * <p>Blocking code can use the try-with-resources idiom:
   * <pre>
   * try (CancellableContext c = Context.current()
   *     .withDeadlineAfter(100, TimeUnit.MILLISECONDS, executor)) {
   *   Context toRestore = c.attach();
   *   try {
   *     // do some blocking work
   *   } finally {
   *     c.detach(toRestore);
   *   }
   * }</pre>
   *
   * <p>Asynchronous code will have to manually track the end of the CancellableContext's lifetime,
   * and cancel the context at the appropriate time.
   */
  public static final class CancellableContext extends Context implements Closeable {

    private final Deadline deadline;
    private final Context uncancellableSurrogate;

    private ArrayList<ExecutableListener> listeners;
    // parentListener is initialized when listeners is initialized (only if there is a
    // cancellable ancestor), and uninitialized when listeners is uninitialized.
    private CancellationListener parentListener;
    private Throwable cancellationCause;
    private ScheduledFuture<?> pendingDeadline;
    private boolean cancelled;

    /**
     * Create a cancellable context that does not have a deadline.
     */
    private CancellableContext(Context parent) {
      super(parent, parent.keyValueEntries);
      deadline = parent.getDeadline();
      // Create a surrogate that inherits from this to attach so that you cannot retrieve a
      // cancellable context from Context.current()
      uncancellableSurrogate = new Context(this, keyValueEntries);
    }

    /**
     * Create a cancellable context that has a deadline.
     */
    private CancellableContext(Context parent, Deadline deadline) {
      super(parent, parent.keyValueEntries);
      this.deadline = deadline;
      this.uncancellableSurrogate = new Context(this, keyValueEntries);
    }

    private void setUpDeadlineCancellation(Deadline deadline, ScheduledExecutorService scheduler) {
      if (!deadline.isExpired()) {
        final class CancelOnExpiration implements Runnable {
          @Override
          public void run() {
            try {
              cancel(new TimeoutException("context timed out"));
            } catch (Throwable t) {
              log.log(Level.SEVERE, "Cancel threw an exception, which should not happen", t);
            }
          }
        }

        synchronized (this) {
          pendingDeadline = deadline.runOnExpiration(new CancelOnExpiration(), scheduler);
        }
      } else {
        // Cancel immediately if the deadline is already expired.
        cancel(new TimeoutException("context timed out"));
      }
    }

    @Override
    public Context attach() {
      return uncancellableSurrogate.attach();
    }

    @Override
    public void detach(Context toAttach) {
      uncancellableSurrogate.detach(toAttach);
    }

    @Override
    public void addListener(
        final CancellationListener cancellationListener, final Executor executor) {
      checkNotNull(cancellationListener, "cancellationListener");
      checkNotNull(executor, "executor");
      addListenerInternal(new ExecutableListener(executor, cancellationListener, this));
    }

    private void addListenerInternal(ExecutableListener executableListener) {
      synchronized (this) {
        if (isCancelled()) {
          executableListener.deliver();
        } else {
          if (listeners == null) {
            // Now that we have a listener we need to listen to our parent so
            // we can cascade listener notification.
            listeners = new ArrayList<>();
            listeners.add(executableListener);
            if (cancellableAncestor != null) {
              parentListener =
                  new CancellationListener() {
                    @Override
                    public void cancelled(Context context) {
                      CancellableContext.this.cancel(context.cancellationCause());
                    }
                  };
              cancellableAncestor.addListenerInternal(
                  new ExecutableListener(DirectExecutor.INSTANCE, parentListener, this));
            }
          } else {
            listeners.add(executableListener);
          }
        }
      }
    }

    @Override
    public void removeListener(CancellationListener cancellationListener) {
      removeListenerInternal(cancellationListener, this);
    }

    private void removeListenerInternal(CancellationListener cancellationListener,
        Context context) {
      synchronized (this) {
        if (listeners != null) {
          for (int i = listeners.size() - 1; i >= 0; i--) {
            ExecutableListener executableListener = listeners.get(i);
            if (executableListener.listener == cancellationListener
                && executableListener.context == context) {
              listeners.remove(i);
              // Just remove the first matching listener, given that we allow duplicate
              // adds we should allow for duplicates after remove.
              break;
            }
          }
          // We have no listeners so no need to listen to our parent
          if (listeners.isEmpty()) {
            if (cancellableAncestor != null) {
              cancellableAncestor.removeListener(parentListener);
            }
            parentListener = null;
            listeners = null;
          }
        }
      }
    }

    /**
     * Returns true if the Context is the current context.
     *
     * @deprecated This method violates some GRPC class encapsulation and should not be used.
     *     If you must know whether a Context is the current context, check whether it is the same
     *     object returned by {@link Context#current()}.
     */
    //TODO(spencerfang): The superclass's method is package-private, so this should really match.
    @Override
    @Deprecated
    public boolean isCurrent() {
      return uncancellableSurrogate.isCurrent();
    }

    /**
     * Cancel this context and optionally provide a cause (can be {@code null}) for the
     * cancellation. This will trigger notification of listeners. It is safe to call this method
     * multiple times. Only the first call will have any effect.
     *
     * <p>Calling {@code cancel(null)} is the same as calling {@link #close}.
     *
     * @return {@code true} if this context cancelled the context and notified listeners,
     *    {@code false} if the context was already cancelled.
     */
    @CanIgnoreReturnValue
    public boolean cancel(Throwable cause) {
      boolean triggeredCancel = false;
      ScheduledFuture<?> localPendingDeadline = null;
      synchronized (this) {
        if (!cancelled) {
          cancelled = true;
          if (pendingDeadline != null) {
            // If we have a scheduled cancellation pending attempt to cancel it.
            localPendingDeadline = pendingDeadline;
            pendingDeadline = null;
          }
          this.cancellationCause = cause;
          triggeredCancel = true;
        }
      }
      if (localPendingDeadline != null) {
        localPendingDeadline.cancel(false);
      }
      if (triggeredCancel) {
        notifyAndClearListeners();
      }
      return triggeredCancel;
    }

    /**
     * Notify all listeners that this context has been cancelled and immediately release
     * any reference to them so that they may be garbage collected.
     */
    private void notifyAndClearListeners() {
      ArrayList<ExecutableListener> tmpListeners;
      CancellationListener tmpParentListener;
      synchronized (this) {
        if (listeners == null) {
          return;
        }
        tmpParentListener = parentListener;
        parentListener = null;
        tmpListeners = listeners;
        listeners = null;
      }
      // Deliver events to this context listeners before we notify child contexts. We do this
      // to cancel higher level units of work before child units. This allows for a better error
      // handling paradigm where the higher level unit of work knows it is cancelled and so can
      // ignore errors that bubble up as a result of cancellation of lower level units.
      for (ExecutableListener tmpListener : tmpListeners) {
        if (tmpListener.context == this) {
          tmpListener.deliver();
        }
      }
      for (ExecutableListener tmpListener : tmpListeners) {
        if (!(tmpListener.context == this)) {
          tmpListener.deliver();
        }
      }
      if (cancellableAncestor != null) {
        cancellableAncestor.removeListener(tmpParentListener);
      }
    }

    @Override
    int listenerCount() {
      synchronized (this) {
        return listeners == null ? 0 : listeners.size();
      }
    }

    /**
     * Cancel this context and detach it as the current context.
     *
     * @param toAttach context to make current.
     * @param cause of cancellation, can be {@code null}.
     */
    public void detachAndCancel(Context toAttach, Throwable cause) {
      try {
        detach(toAttach);
      } finally {
        cancel(cause);
      }
    }

    @Override
    public boolean isCancelled() {
      synchronized (this) {
        if (cancelled) {
          return true;
        }
      }
      // Detect cancellation of parent in the case where we have no listeners and
      // record it.
      if (super.isCancelled()) {
        cancel(super.cancellationCause());
        return true;
      }
      return false;
    }

    @Override
    public Throwable cancellationCause() {
      if (isCancelled()) {
        return cancellationCause;
      }
      return null;
    }

    @Override
    public Deadline getDeadline() {
      return deadline;
    }

    /**
     * Cleans up this object by calling {@code cancel(null)}.
     */
    @Override
    public void close() {
      cancel(null);
    }
  }

  /**
   * A listener notified on context cancellation.
   */
  public interface CancellationListener {
    /**
     * Notifies that a context was cancelled.
     *
     * @param context the newly cancelled context.
     */
    void cancelled(Context context);
  }

  /**
   * Key for indexing values stored in a context. Keys use reference equality and Context does not
   * provide a mechanism to loop over Keys. This means there is no way to access a Key's value from
   * a Context without having access to the Key instance itself. This allows strong control over
   * what code can get/set a key in the Context. For example, you might manage access to Key similar
   * to a ThreadLocal using Java visibility (private/protected). Generally Keys are stored in static
   * fields.
   */
  public static final class Key<T> {
    private final String name;
    private final T defaultValue;

    Key(String name) {
      this(name, null);
    }

    Key(String name, T defaultValue) {
      this.name = checkNotNull(name, "name");
      this.defaultValue = defaultValue;
    }

    /**
     * Get the value from the {@link #current()} context for this key.
     */
    public T get() {
      return get(Context.current());
    }

    /**
     * Get the value from the specified context for this key.
     */
    @SuppressWarnings("unchecked")
    public T get(Context context) {
      T value = (T) PersistentHashArrayMappedTrie.get(context.keyValueEntries, this);
      return value == null ? defaultValue : value;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /**
   * Defines the mechanisms for attaching and detaching the "current" context. The constructor for
   * extending classes <em>must not</em> trigger any activity that can use Context, which includes
   * logging, otherwise it can trigger an infinite initialization loop. Extending classes must not
   * assume that only one instance will be created; Context guarantees it will only use one
   * instance, but it may create multiple and then throw away all but one.
   *
   * <p>The default implementation will put the current context in a {@link ThreadLocal}.  If an
   * alternative implementation named {@code io.grpc.override.ContextStorageOverride} exists in the
   * classpath, it will be used instead of the default implementation.
   *
   * <p>This API is <a href="https://github.com/grpc/grpc-java/issues/2462">experimental</a> and
   * subject to change.
   */
  public abstract static class Storage {
    /**
     * @deprecated This is an old API that is no longer used.
     */
    @Deprecated
    public void attach(Context toAttach) {
      throw new UnsupportedOperationException("Deprecated. Do not call.");
    }

    /**
     * Implements {@link io.grpc.Context#attach}.
     *
     * <p>Caution: {@link Context#attach()} interprets a return value of {@code null} to mean
     * the same thing as {@link Context#ROOT}.
     *
     * <p>See also: {@link #current()}.

     * @param toAttach the context to be attached
     * @return A {@link Context} that should be passed back into {@link #detach(Context, Context)}
     *        as the {@code toRestore} parameter. {@code null} is a valid return value, but see
     *        caution note.
     */
    public Context doAttach(Context toAttach) {
      // This is a default implementation to help migrate existing Storage implementations that
      // have an attach() method but no doAttach() method.
      Context current = current();
      attach(toAttach);
      return current;
    }

    /**
     * Implements {@link io.grpc.Context#detach}
     *
     * @param toDetach the context to be detached. Should be, or be equivalent to, the current
     *        context of the current scope
     * @param toRestore the context to be the current.  Should be, or be equivalent to, the context
     *        of the outer scope
     */
    public abstract void detach(Context toDetach, Context toRestore);

    /**
     * Implements {@link io.grpc.Context#current}.
     *
     * <p>Caution: {@link Context} interprets a return value of {@code null} to mean the same
     * thing as {@link Context#ROOT}.
     *
     * <p>See also {@link #doAttach(Context)}.
     *
     * @return The context of the current scope. {@code null} is a valid return value, but see
     *        caution note.
     */
    public abstract Context current();
  }

  /**
   * Stores listener and executor pair.
   */
  private static final class ExecutableListener implements Runnable {
    private final Executor executor;
    final CancellationListener listener;
    private final Context context;

    ExecutableListener(Executor executor, CancellationListener listener, Context context) {
      this.executor = executor;
      this.listener = listener;
      this.context = context;
    }

    void deliver() {
      try {
        executor.execute(this);
      } catch (Throwable t) {
        log.log(Level.INFO, "Exception notifying context listener", t);
      }
    }

    @Override
    public void run() {
      listener.cancelled(context);
    }
  }

  @CanIgnoreReturnValue
  static <T> T checkNotNull(T reference, Object errorMessage) {
    if (reference == null) {
      throw new NullPointerException(String.valueOf(errorMessage));
    }
    return reference;
  }

  private enum DirectExecutor implements Executor {
    INSTANCE;

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public String toString() {
      return "Context.DirectExecutor";
    }
  }

  /**
   * Returns {@code parent} if it is a {@link CancellableContext}, otherwise returns the parent's
   * {@link #cancellableAncestor}.
   */
  static CancellableContext cancellableAncestor(Context parent) {
    if (parent instanceof CancellableContext) {
      return (CancellableContext) parent;
    }
    // The parent simply cascades cancellations.
    // Bypass the parent and reference the ancestor directly (may be null).
    return parent.cancellableAncestor;
  }

  /**
   * If the ancestry chain length is unreasonably long, then print an error to the log and record
   * the stack trace.
   */
  private static void validateGeneration(int generation) {
    if (generation == CONTEXT_DEPTH_WARN_THRESH) {
      log.log(
          Level.SEVERE,
          "Context ancestry chain length is abnormally long. "
              + "This suggests an error in application code. "
              + "Length exceeded: " + CONTEXT_DEPTH_WARN_THRESH,
          new Exception());
    }
  }

  // Not using the standard com.google.errorprone.annotations.CheckReturnValue because that will
  // introduce dependencies that some io.grpc.Context API consumers may not want.
  @interface CheckReturnValue {}

  @interface CanIgnoreReturnValue {}
}
