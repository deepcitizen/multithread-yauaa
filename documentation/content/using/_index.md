+++
title = "Using the analyzer"
weight = 20
linkTitle = "Using Yauaa"
+++
## Using the analyzer
To use this analyzer you can use it either directly in your Java based applications or use one of
the User Defined Functions that are available for many of Apache bigdata tools (Hive, Flink, Beam, ...) as described [here](../udf/).

## Using in Java applications
To use the library you must first add it as a dependency to your application.
The library has been published to [maven central](https://central.sonatype.com/artifact/nl.basjes.parse.useragent/yauaa/{{%YauaaVersion%}}/jar) so that should work in almost any environment.

If you use a maven based project simply add this dependency to your project.

```xml
<dependency>
    <groupId>nl.basjes.parse.useragent</groupId>
    <artifactId>yauaa</artifactId>
    <version>{{%YauaaVersion%}}</version>
</dependency>
```

To actually use it in your application you need to create an instance of the `UserAgentAnalyzer`.

Please instantiate a new UserAgentAnalyzer as few times as possible because the initialization step for a full UserAgentAnalyzer (i.e. all fields) usually takes something in the range of 2-5 seconds and uses a few hundred MiB of memory to store all the analysis datastructures.

A single instance of the analyzer is threadsafe and can be used by many threads at the same time (the parse method is NOT synchronized).
So in a multithreaded application (like a webserver) you should simply create a single instance and share it between all threads.
When running in a framework (like [Apache Flink](../udf/Apache-Flink.md) or [Apache Beam](../udf/Apache-Beam.md)) the framework may create (or deserialize) an instance per worker which is also fine.

Note that if you need multiple instances of the UserAgentAnalyzer then you MUST create a new Builder instance for each of those (or serialize an instance and deserialize it multiple times).

```java
UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withCache(10000)
            .build();
```

Then for each useragent (or set of request headers) you call the `parse` method to give you the desired result:

```java
UserAgent agent = uaa.parse("Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.8.1.11) Gecko/20071127 Firefox/2.0.0.11");

for (String fieldName: agent.getAvailableFieldNamesSorted()) {
    System.out.println(fieldName + " = " + agent.getValue(fieldName));
}
```
Note that not all fields are available after every parse. So be prepared to receive a `null`, `Unknown` or another field specific default.

## Custom caching implementation
Since version 6.7 you can specify a custom implementation for the cache by providing an instance of the factory interface `CacheInstantiator`.

Do note that Yauaa assumes the caching implementation to be threadsafe.
If you use a non-threadsafe implementation in a multithreaded context it will break.

Yauaa only uses the `get`, `put` and (in exceptional cases) `clear` methods of the cache.
A parse is never done while holding a lock in the cache so a cache miss never blocks the other threads.
If multiple threads parse the same new useragent at the same time they all produce the same (immutable) result.

The default caching implementation uses [Caffeine](https://github.com/ben-manes/caffeine) (since version 6.8).

```java
return Caffeine.newBuilder().maximumSize(cacheSize).<String, ImmutableUserAgent>build().asMap();
```
A custom implementation can be specified via the `Builder` using the `withCacheInstantiator(...)` method:

```java
UserAgentAnalyzer uaa = UserAgentAnalyzer
    .newBuilder()
    .withCacheInstantiator(
        new CacheInstantiator() {
            @Override
            public Map<String, ImmutableUserAgent> instantiateCache(int cacheSize) {
                return new MyMuchBetterCacheImplementation(cacheSize);
            }
        }
    )
    .withClientHintCacheInstantiator(
        (ClientHintsCacheInstantiator<?>) size ->
            Collections.synchronizedMap(new LRUMap<>(size)))
    .withCache(10000)
    .build();
```

## Logging dependencies
The Yauaa engine uses Log4j2 API as the logging framework.

To minimize the complexity of the dependency handling I have chosen to simply not include ANY logging framework and
expect the consuming system to provide what ever fits best. Even the required log4j-api 2.x dependency is configured as `provided` to avoid any needless version conflicts.

To use Yauaa you must provide the desired version of `log4j-api` and either an implementation or a bridge for Apache Log4j2 to route the logging to where you want it.

So it all depends on your exact context (i.e. which logging framework are you going to use) what
the best solution is for you to make all of this logging work as intended.

In case you are using Apache Log4j2 you should have these dependencies in addition to Yauaa in your project

```xml
<!-- The default logging implementation for Yauaa -->
<dependency>
  <groupId>org.apache.logging.log4j</groupId>
  <artifactId>log4j-core</artifactId>
  <version>${log4j2.version}</version>
</dependency>
```

---
## Regarding the Log4J2 issues
The Yauaa analyzer uses the Log4J2 API to do the logging.

TL;DR:
- **The core of Yauaa is safe** as it does not include any logging dependencies and expects the application to provide everything.
- **In normal operations user input is not logged**.
- The **Snowflake UDF is affected** by these problems (due to shading the dependencies in).

### NO batteries included
By design the Yauaa library expects the application in which it is used to provide the actual logging dependencies and configuration.
If you do not provide the needed logging classes it will simply fail at startup.

So by design the Yauaa library expects all of these frameworks to be provided (and configured) and does not include any of them or any configuration for them.

This is true for most of the released artifacts (including the base library) except for the Snowflake UDF which does include almost all dependencies.
So the Snowflake UDF IS affected by this issue and all users are recommended to update.

### Minimal logging
Note that Yauaa does not log any user input and/or analysis results from user input during normal operation.
Only during development and during unit tests the Useragents are logged.

This is because it was designed to run in very large scale batch and streaming situations (very large as in "Let's analyze these 10^10 records").

### Bring your own batteries
To assist in running Yauaa without the logj4-core jar an example was created that only uses [SLF4J 1.x](https://github.com/nielsbasjes/yauaa/tree/main/analyzer/src/it/Examples/java-slf4j) and and example that only uses [SLF4J 2.x](https://github.com/nielsbasjes/yauaa/tree/main/analyzer/src/it/Examples/java-slf4j2).

## Serialization
If your application needs to serialize the instance of the UserAgentAnalyzer then both the standard Java serialization and
Kryo are supported. Note that with Kryo 5.x you need to register all classes and configure Kryo correctly.

To facilitate doing this correctly the static method `configureKryo` was created.
So in general your code should look something like this:

```java
Kryo kryo = new Kryo();
UserAgentAnalyzer.configureKryo(kryo);
```

Note that both the serializing and the deserializing instance of Kryo must be configured in the same way.

## Avoid using it as a static member
If you make the UserAgentAnalyzer a static member of a class then cleaning it up after use may be a problem.
One case where this happens is in the context of something like Tomcat where a webapp is loaded and then unloaded.
If the analyzer is a static member of your servlet then this unloading may retain a lot of the memory used for the internal
data structures.

## Cache size setting
I recommend you leave the cache to a size that is roughly the unique number of useragents your site finds
in a limited timespan. Something like 15/30/60 minutes usually gives you a fine cache size.
On a very busy website I see ~50K-60K distinct useragents per day and ~10K per hour.
So in my opinion a cache size of 5K-10K elements is a good choice.

## Reducing the memory footprint

### Limiting to only certain fields
In some scenarios you only want a specific field and all others are unwanted.
This can be achieved by creating the analyzer in Java like this:

```java
UserAgentAnalyzer uaa = UserAgentAnalyzer
        .newBuilder()
        .withField("DeviceClass")
        .withField("AgentNameVersionMajor")
        .build();
```

One important effect is that this speeds up the system because it will kick any rules that do not help in getting the desired fields.
The above example showed an approximate 40% speed increase (i.e. times dropped from ~1ms to ~0.6ms).

Do note that some fields need other fields as input (i.e. intermediate result). For example the `DeviceBrand` also needs the `AgentInformationEmail` and `AgentInformationUrl` because in some cases the actual brand is derived from for example the domainname that usually is present in those. So if you only ask for the `DomainBrand` you will get those fields also.

In the nl.basjes.parse.useragent.UserAgent many (not all!!) of the provided variables are provided as a constant String.
You can choose to use these and avoid subtle typos in the requested attribute names.

```java
UserAgentAnalyzer uaa = UserAgentAnalyzer
        .newBuilder()
        .withField(DEVICE_CLASS)
        .withField(AGENT_NAME_VERSION_MAJOR)
        .build();
```

### Sharing the config between instances (new in 7.24.0)
In some situations you may want to have multiple instances to handle the load within in a single JVM.
In those cases you'll also want the configuration of all instances to be identical.

If you are in exactly that scenario you can create a duplicate instance with the exact same configuration and save a few MiB of memory.  The currently estimated savings is at about 20-30MiB for each extra instance (depending on the configuration).

```java
UserAgentAnalyzer extraUaa = uaa.cloneWithSharedAnalyzerConfig(false, true);
```

## Building your project with -Xlint:all
If you are trying to get rid of all possible problems in your application and set the compiler flag -Xlint:all you will see warnings relating to the Kryo serialization system.

    [WARNING] COMPILATION WARNING :
    [INFO] -------------------------------------------------------------
    [WARNING] Cannot find annotation method 'value()' in type 'com.esotericsoftware.kryo.DefaultSerializer': class file for com.esotericsoftware.kryo.DefaultSerializer not found
    [WARNING] Cannot find annotation method 'value()' in type 'com.esotericsoftware.kryo.DefaultSerializer'
    [INFO] 2 warnings

The Yauaa analyzer has been prepared in such a way that it can be serialized using both the standard Java serialization and the Kryo serialization library.
The Kryo library has been configured as a "provided" dependency because many do not need it, and (to avoid version conflicts) those who do need it have this dependency already.

If your project does not use Kryo and you have this warning then there are several ways to work around this:

1. Disable the Xlint check that triggers this warning. Apparently this is the "classfile" so try to use **-Xlint:all,-classfile** instead.
2. Add the otherwise needless Kryo library as a "provided" dependency to your project.

```xml
<dependency>
    <groupId>com.esotericsoftware</groupId>
    <artifactId>kryo</artifactId>
    <version>5.4.0</version>
    <scope>provided</scope>
</dependency>
```

## Multithreading
A single instance of the analyzer is threadsafe and lock free: Many threads can parse at the same time using the same instance.

- All the rules (matchers, lookups and the large internal hashmaps) are only read during parsing so they are shared by all threads.
- All the state of a single parse is kept in a small object that is taken from (and returned to) a lock free pool. So the number of these objects is limited by the number of threads that are parsing at the same time (this also works fine with virtual threads).
- Cache hits are lock free and (when parsing a String) do not create any objects.

So you should create only a single instance and share it between all your threads (creating an instance takes several seconds and a lot of memory).

The methods that are intended for debugging the rules (like `getMatches()`, `getUsedMatches(...)` and `getTouchedMatchers()`) show the details of the most recent parse and are only meaningful in a single threaded situation.

## Parsing performance and the worst case
The grammar of useragents is ambiguous. In several places the parser must look ahead (sometimes until the end of the useragent) to decide how to continue (in ANTLR terms: a "full context prediction"). These are also the places where the ambiguity is found that is reported by `hasAmbiguity()` and `getAmbiguityCount()`.

- The outcomes of these predictions are cached in a lock free cache that is shared by all threads and all analyzer instances. This cache is bounded (at most a few MiB) and does not change the results in any way: the parse tree and the reported ambiguity are exactly the same.
- For some malformed (often malicious) useragents these predictions made the parse time grow quadratically with the length: a single useragent of 2KB took about 15 seconds of CPU time. The total amount of lookahead of a single parse is now limited, which makes the worst case about 0.1 seconds. Normal useragents never get anywhere near this limit. When the limit is reached the rest of that useragent is parsed without these predictions, so for these (very rare) useragents the parse result and the reported ambiguity can be different from older versions.
- The parser needs a lot of stack for every level of nested brackets: with the default thread stack (1 MiB) a useragent with about 150 nested brackets made it fail with a `StackOverflowError`. A useragent with brackets that are nested more than 20 levels deep (normal useragents have at most 2 or 3 levels) is therefore not parsed: it is reported as a Hacker with the `HackerAttackVector` `Stack overflow`. At this limit a thread stack of 256 KiB is enough. If the parser does run out of stack (for example in a thread that has very little stack left) the result is the same.

## Eclipse users
Be aware of there is a bug in Eclipse which will show you errors in perfectly valid Java code:
https://bugs.eclipse.org/bugs/show_bug.cgi?id=527475
The errors you see are related to the inheritance model used by the Builders in this project and the fact that Eclipse does not interpret it correctly.

## Scala usage
When using this library from a Scala application the way the Builders have been constructed turns out to be very unfriendly to use.

Starting with Yauaa version 5.14 the rewritten builders (contributed by [Robert Stoll](https://github.com/tegonal))
will become available which will make it a lot easier for Scala users to use:

```scala
val uaa = UserAgentAnalyzer.newBuilder
  .withCache(10000)
  .hideMatcherLoadStats
  .withField("DeviceClass")
  .build
```

## Snapshots

Occasionally I publish a snapshot version.
If you want to use such a version then the repository can be configured in your maven with something like this

```xml
<repositories>
  <repository>
    <name>Central Portal Snapshots</name>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases>
      <enabled>false</enabled>
    </releases>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>
```

See for more information: https://central.sonatype.org/publish/publish-portal-snapshots/#consuming-via-maven
