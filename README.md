# Welcome to retrier
[![Build Status](https://api.travis-ci.org/alexo/retrier.svg)](http://travis-ci.org/alexo/retrier)
[<img src="https://badges.gitter.im/alexo/spinner.svg" class="copy-button view" data-copy-text="[![Gitter chat](https://badges.gitter.im/alexo/spinnerj.svg)]">](https://gitter.im/alexo/retrier)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.alexo/retrier/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.alexo/retrier)

Retrier is a zero dependency utility for java 8 which simplifies a common requirements related to retry mechanism.

## Usage
Add Retrier dependency:

```xml
<dependency>
    <groupId>io.github.alexo</groupId>
    <artifactId>retrier</artifactId>
    <version>0.1</version>
</dependency>
```

Once the Retrier is available in the classpath, it can be used as following:
```java
Retrier retrier = new Retrier.Builder().build();
retrier.execute(() -> {
  return doSomething();
});
```

### Customization
Retrier can be instantiated using a builder or with a factory method for a frequently used use-cases (ex: singleAttempt);

```java
Retrier retrier = Retrier.singleAttempt();
```
The same retrier can be created with a more verbose version:
```java
Retrier retrier = new Retrier.Builder().withStopStrategy(Retrier.Strategies.stopAfter(1)).build();
```

Similarly, it is possible to create a customer retrier which will call at most 2 times:
```java
Retrier retrier = new Retrier.Builder().withStopStrategy(Retrier.Strategies.stopAfter(2)).build();
```

The builder allows to customize the following strategies:
* stopStrategy
* waitStrategy
* resultRetryStrategy
* failedRetryStrategy

#### stopStrategy
This strategy is used to check if the retry should be stopped given the provided number of attempts already performed.
Useful to limit the total number of attempts to a bounded value. By default this value is unbounded.

An example of custom stopStrategy:
```java
//The strategy is a Predicate<Integer>
Retrier retrier = new Retrier.Builder().withStopStrategy(x -> x == 1).build();
// same as
Retrier retrier = new Retrier.Builder().withStopStrategy(Retrier.Strategies.stopAfter(1)).build();
// same as
Retrier retrier = Retrier.singleAttempt();
```

#### waitStrategy
This strategy is used to decide how much time (in milliseconds) to wait between retry attempts. Any result less or equal to zero => no wait. By default zero value is returned.

An example of custom waitStrategy:
```java
//The strategy is a Function<Integer, Long>
//wait 100ms between retries
Retrier retrier = new Retrier.Builder().withWaitStrategy(i -> 100).build();
//same as
Retrier retrier = new Retrier.Builder().withWaitStrategy(Retrier.Strategies.waitConstantly(100)).build();
```
Alternatively, it is possible to use a provided exponential wait strategy which increases the wait between retries:
```java
Retrier retrier = new Retrier.Builder().withWaitStrategy(Retrier.Strategies.waitExponential()).build();
//same as
Retrier retrier = new Retrier.Builder().withWaitStrategy(Retrier.Strategies.waitExponential(1, 2)).build();
//wait 1s, 2s, 4s, ... between retries
Retrier retrier = new Retrier.Builder().withWaitStrategy(Retrier.Strategies.waitExponential(1000, 2)).build();
```

#### resultRetryStrategy
Assuming that not all failed execution can be identified by catching an exception, this strategy is used to check if the retry is required given a result value returned by the callable.
By default, this strategy is always returning false, meaning that no retry will be performed no matter what result is returned.
Another way to look at this strategy is the following question: "what result is considered an exception which should be retried?"
A real world example is invocation of an http request and inspecting the response status code to decide if the request should be retried.

An example of custom resultRetryStrategy:
```java
//The strategy is a Predicate<Integer>
//As long as the invocation result is 42, a new retry will be attempted.
Retrier retrier = new Retrier.Builder().withResultRetryStrategy(i -> i == 42).build();
```

#### failedRetryStrategy
This strategy is used to check if the retry is required given a failed execution.

An example of custom withFailedRetryStrategy:
```java
//The strategy is a Predicate<Exception>
//As long as the invocation result is 42, a new retry will be attempted.
Retrier retrier = new Retrier.Builder().withFailedRetryStrategy(e -> e instanceof TimeoutException).build();
//same as
Retrier retrier = new Retrier.Builder().withFailedRetryStrategy(Retrier.Strategies.retryOn(TimeoutException.class).build();
```

#### Combined strategies
It is possible to combine any of these strategies to build a custom instance of retrier. Example:
```java
Retrier retrier = new Retrier.Builder()
                .withFailedRetryStrategy(retryOn(OptimisticLockingFailureException.class, DataIntegrityViolationException.class))
                .withWaitStrategy(waitExponential())
                .withStopStrategy(stopAfter(maxRetryAttempts))
                .build();
```
