package io.github.alexo.retrier;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Responsible for executing a call and retrying until it succeeds based on configured strategies.
 *
 * @author Alex Objelean
 */
public class Retrier<T> {

    @FunctionalInterface
    public interface GiveUpStrategy<T> {
        T whenNoMoreAttempts(T lastResult, Exception lastException) throws Exception;
    }

    private static final Retrier<Object> SINGLE_ATTEMPT = new Retrier.Builder<Object>().withStopStrategy(Strategies.stopAfter(1))
            .build();

    /**
     * Retrier that executes every operation exactly once. The operation is never retried. All exceptions are propagated
     * to the caller.
     */
    public static Retrier<Object> singleAttempt() {
        return SINGLE_ATTEMPT;
    }

    /**
     * Strategies used to check if the retry is required given a failed execution.
     */
    private final Predicate<Exception> exceptionRetryStrategy;
    /**
     * Strategies used to check if the retry is required given a successful execution with a result.
     */
    private final Predicate<T> resultRetryStrategy;
    /**
     * Strategies used to check if the retry should be stopped given the provided number of attempts already performed.
     * Useful to limit the total number of attempts to a bounded value. By default this value is unbounded.
     */
    private final Predicate<Integer> stopStrategy;
    /**
     * How much time (in milliseconds) to wait between retry attempts. Any result less or equal to zero => no wait.
     */
    private final Function<Integer, Long> waitStrategy;

    /**
     * What to do when all attempts have been exhausted and the retrier still wasn't able to perform the operation.
     */
    private final GiveUpStrategy<T> giveUpStrategy;

    /**
     * Utility class responsible for creating several useful types of wait strategy used by Retrier.
     *
     * @author Alex Objelean
     */
    public static class Strategies {
        public static Function<Integer, Long> waitExponential(final long startWaitMillis, final double backoffBase) {
            return attempts -> {
                if (attempts > 0) {
                    final double backoffMillis = startWaitMillis * Math.pow(backoffBase, attempts);
                    return Math.min(1000L, Math.round(backoffMillis));
                }
                return 0L;
            };
        }

        /**
         * @param delay in millis to wait between retries.
         * @return a wait strategy which always return the constant delay.
         */
        public static Function<Integer, Long> waitConstantly(final long delay) {
            return a -> delay;
        }

        public static Function<Integer, Long> waitExponential() {
            return waitExponential(2.0D);
        }

        public static Function<Integer, Long> waitExponential(final double backoffBase) {
            return waitExponential(1L, 2.0D);
        }

        /**
         * Limit the number of attempts to a fixed value.
         */
        public static Predicate<Integer> stopAfter(final int maxAttempts) {
            return attempts -> attempts >= maxAttempts;
        }

        /**
         * Retry only if any of the provided exceptions was thrown
         */
        public static Predicate<Exception> retryOn(Class<? extends Throwable>... exceptions) {
            return exception -> Arrays.stream(exceptions).anyMatch(clazz -> clazz.isInstance(exception));
        }
    }

    /**
     * Default builder will retry on any exception for unlimited number of times without waiting between executions.
     */
    public static class Builder<T> {
        private Predicate<Exception> failedRetryStrategy = e -> true;
        private final GiveUpStrategy<T> giveUpStrategy = (lastResult, lastException) -> {
            if (lastException != null) {
                throw lastException;
            } else {
                return lastResult;
            }
        };

        private Predicate<T> resultRetryStrategy = e -> false;
        private Predicate<Integer> stopStrategy = attempt -> false;
        private Function<Integer, Long> waitStrategy = attempt -> 0L;

        public Retrier<T> build() {
            return new Retrier<T>(failedRetryStrategy, resultRetryStrategy, stopStrategy, waitStrategy, giveUpStrategy);
        }

        public Builder<T> withFailedRetryStrategy(final Predicate<Exception> failedRetryStrategy) {
            this.failedRetryStrategy = requireNonNull(failedRetryStrategy);
            return this;
        }

        public Builder<T> withResultRetryStrategy(final Predicate<T> resultRetryStrategy) {
            this.resultRetryStrategy = requireNonNull(resultRetryStrategy);
            return this;
        }

        public Builder<T> withStopStrategy(final Predicate<Integer> stopStrategy) {
            this.stopStrategy = requireNonNull(stopStrategy);
            return this;
        }

        public Builder<T> withWaitStrategy(final Function<Integer, Long> waitStrategy) {
            this.waitStrategy = requireNonNull(waitStrategy);
            return this;
        }
    }

    private Retrier(final Predicate<Exception> exceptionRetryStrategy, final Predicate<T> resultRetryStrategy,
            final Predicate<Integer> stopStrategy, final Function<Integer, Long> waitStrategy,
            final GiveUpStrategy<T> giveUpStrategy) {
        this.exceptionRetryStrategy = exceptionRetryStrategy;
        this.resultRetryStrategy = resultRetryStrategy;
        this.stopStrategy = stopStrategy;
        this.waitStrategy = waitStrategy;
        this.giveUpStrategy = giveUpStrategy;
    }

    /**
     * Invokes the provided {@link Callable} and retries the execution if required based on how this {@link Retrier} is
     * configured.
     *
     * @param callable to execute in context of {@link Retrier}
     *
     * @return the result of callable invocation.
     * @throws Exception if the original callback execution failed and {@link Retrier} has decided to stop retrying.
     */
    public T execute(final Callable<T> callable) throws Exception {
        int attempts = 0;
        boolean shouldRetry;
        boolean attemptFailed = false;
        boolean interrupted;
        T result = null;
        Exception error = null;

        do {
            try {
                attempts++;
                error = null;
                attemptFailed = false;

                result = callable.call();
                attemptFailed = resultRetryStrategy.test(result);
            } catch (final Exception e) {
                attemptFailed = exceptionRetryStrategy.test(e);
                error = e;
            } finally {
                interrupted = Thread.interrupted() || isInterruptedException(error);

                shouldRetry = !interrupted && attemptFailed && !stopStrategy.test(attempts);
                if (shouldRetry) {
                    final long waitMillis = waitStrategy.apply(attempts);
                    if (waitMillis > 0) {
                        try {
                            Thread.sleep(waitMillis);
                        } catch (final InterruptedException e) {
                            shouldRetry = false;
                            interrupted = true;
                        }
                    }
                }
            }
        } while (shouldRetry);

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        if (attemptFailed && !interrupted) {
            // Number of attempts exhausted, cannot retry anymore, let the retry strategy
            return giveUpStrategy.whenNoMoreAttempts(result, error);
        }

        if (error != null) {
            throw error;
        }

        return result;
    }

    private static boolean isInterruptedException(final Throwable root) {
        Throwable current = root;
        while (current != null && !(current instanceof InterruptedException)) {
            current = current.getCause();
        }
        return current != null;
    }

}
