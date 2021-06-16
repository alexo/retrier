package io.github.alexo.retrier;

import org.mockito.Mock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.Callable;
import java.util.function.Function;

import static io.github.alexo.retrier.Retrier.Strategies.retryOn;
import static io.github.alexo.retrier.Retrier.Strategies.stopAfter;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.testng.Assert.fail;

/**
 * @author Alex Objelean
 */
public class RetrierTest {
    private static final int MAX_ATTEPTS = 10;
    @Mock
    private Callable<Object> callable;
    private Retrier<Object> victim;

    @BeforeMethod
    public void setUp() {
        // Reset interrupted flag for this thread
        Thread.interrupted();
        initMocks(this);
        victim = createDefaultBuilder().build();
    }

    private Retrier.Builder<Object> createDefaultBuilder() {
        return new Retrier.Builder<Object>().withStopStrategy(stopAfter(MAX_ATTEPTS));
    }

    private Retrier<Object> withCustomExceptionFailedStrategy() {
        return createDefaultBuilder().withFailedRetryStrategy(CustomException.class::isInstance).build();
    }

    private static class CustomException extends Exception {
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void cannotBuildWithNullFailedRetryStrategy() {
        new Retrier.Builder<>().withFailedRetryStrategy(null);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void cannotBuildWithNullResultRetryStrategy() {
        new Retrier.Builder<>().withResultRetryStrategy(null);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void cannotBuildWithNullStopStrategy() {
        new Retrier.Builder<>().withStopStrategy(null);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void cannotBuildWithNullWaitStrategy() {
        new Retrier.Builder<>().withWaitStrategy(null);
    }

    @Test
    public void shouldStopFailingAfterMaxAttemptsReached() throws Exception {
        doThrow(Exception.class).when(callable).call();

        try {
            victim.execute(callable);
            fail("Did not throw an exception after last attempt exhausted");
        } catch (Exception e) {

        }

        verify(callable, times(MAX_ATTEPTS)).call();
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void shouldPropagateWithNoRetryWhenNotCustomExceptionThrown() throws Exception {
        victim = withCustomExceptionFailedStrategy();
        doThrow(RuntimeException.class).when(callable).call();
        try {
            victim.execute(callable);
        } catch (final Exception e) {
            verify(callable, times(1)).call();
            throw e;
        }
    }

    @Test
    public void shouldRetryOnRecoverableException() throws Exception {
        victim = withCustomExceptionFailedStrategy();
        willThrow(CustomException.class).willAnswer(i -> 1).given(callable).call();

        victim.execute(callable);

        verify(callable, times(2)).call();
    }

    @Test(expectedExceptions = InterruptedException.class)
    public void shouldRetryOnRecoverableExceptionAndStopOnInterruptedException() throws Exception {
        try {
            victim = withCustomExceptionFailedStrategy();
            willThrow(CustomException.class).willThrow(InterruptedException.class).given(callable).call();

            victim.execute(callable);
        } finally {
            verify(callable, times(2)).call();
        }
    }

    @Test
    public void shouldInvokeOnlyOnceWhenFirstCallDoesNotFail() throws Exception {
        victim = withCustomExceptionFailedStrategy();
        willAnswer(i -> 1).willThrow(CustomException.class).given(callable).call();

        victim.execute(callable);

        verify(callable, times(1)).call();
    }

    @Test
    public void shouldRetryWhenUsingCustomRetryStrategy() throws Exception {
        final int retryResult = 0;
        // retry as long as result is 0
        victim = new Retrier.Builder<>().withResultRetryStrategy(i -> i.equals(retryResult))
                .withStopStrategy(stopAfter(MAX_ATTEPTS)).build();
        doAnswer(i -> retryResult).doAnswer(i -> retryResult).doAnswer(i -> retryResult + 1)
                .when(callable).call();

        victim.execute(callable);

        verify(callable, times(3)).call();
    }

    @Test
    public void shouldInvokeWaitStrategyForEveryRetry() throws Exception {
        willThrow(Exception.class).given(callable).call();
        final Function<Integer, Long> waitStrategy = mock(Function.class);
        when(waitStrategy.apply(anyInt())).thenReturn(1L);
        victim = createDefaultBuilder().withWaitStrategy(waitStrategy).withFailedRetryStrategy(retryOn(Exception.class))
                .build();

        try {
            victim.execute(callable);
        } catch (Exception e) {

        }

        verify(callable, times(MAX_ATTEPTS)).call();
        verify(waitStrategy, times(MAX_ATTEPTS - 1)).apply(anyInt());
    }
}
