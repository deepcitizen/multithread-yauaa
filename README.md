Yauaa: Yet Another UserAgent Analyzer (multithreaded fork)
==========================================================

**English** | [Русский](README.ru.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.deepcitizen/yauaa.svg?label=Maven%20central)](https://central.sonatype.com/artifact/io.github.deepcitizen/yauaa)
[![License](https://img.shields.io/:license-apache-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

This is a fork of [Yauaa](https://github.com/nielsbasjes/yauaa) by Niels Basjes: a Java library that parses the
useragent string (and when available the User-Agent Client Hints) and extracts as many relevant attributes as possible.

All the detection rules come from the original project. This fork changes how the analyzer runs:

- **One instance for all threads.** The analyzer is threadsafe and lock free. A pool of analyzers is no longer needed.
- **Faster.** A single thread parses about twice as fast. On 8 threads one shared instance parses 22 times as
  fast as the original.
- **A bounded worst case.** A malformed useragent of 2 KB takes at most about 0.1 s of CPU instead of up to 15 s.
  Deeply nested brackets no longer crash the parser with a `StackOverflowError`.
- **Your own analysis.** An application can analyze some useragents itself with a `UserAgentHandler` and add data of
  its own (meta) to the results. The meta is cached together with the result.

Using it
========
The fork is published on Maven Central as `io.github.deepcitizen:yauaa`:

```xml
<dependency>
  <groupId>io.github.deepcitizen</groupId>
  <artifactId>yauaa</artifactId>
  <version>8.4.0</version>
</dependency>
```

The Java packages are unchanged (`nl.basjes.parse.useragent`), so an existing application only needs the new groupId.
It needs Java 17 or newer, just like the original. Only the analyzer (`yauaa` and `yauaa-parent`) is published.
For the UDFs, the webservlet and the Docker images use the original project.

Create one instance and share it between all threads:

```java
// One instance for the whole application: creating it is expensive (about a second and 80 MB of heap).
private static final UserAgentAnalyzer UAA = UserAgentAnalyzer
    .newBuilder()
    .withCache(10000)
    .build();

// Called by many threads at the same time.
UserAgent userAgent = UAA.parse(request.getHeader("User-Agent"));
```

If your application keeps a pool of analyzers (for example with commons-pool), one analyzer per thread, or a separate
executor only for parsing useragents, you can remove it. In the [load test](#load-test-a-web-service-on-4-cores)
parsing directly in the request threads with one shared instance gave the best results.

Differences from the original
=============================

| | Original (8.3.0) | This fork (8.4.0) |
|---|---|---|
| Parsing in many threads with one instance | No: `parse` is `synchronized` | Yes, lock free |
| Using all CPU cores | A pool of instances, each with its own copy of the rules (114 MB of heap) | One instance |
| Cache miss | Holds the lock of the instance during the whole parse | Never blocks other threads |
| Retained heap of one instance (all fields) | 114 MB | 81 MB |
| Malformed useragent of 2 KB | 3.9 to 15.3 s of CPU | 61 to 112 ms |
| 186 levels of nested brackets in a thread with a 1 MiB stack | `StackOverflowError` | `Hacker` with `HackerAttackVector` = `Stack overflow` |
| `" "`, `"()"`, `"''"` | `Yauaa Exploit` | The same result as an empty useragent |
| Walk steps `<<`, `<<<`, `<<<<` with 20 or more siblings (custom rules only) | The whole result is `Yauaa Exploit` | Work |
| Analyzing some useragents yourself | Not possible | `UserAgentHandler` before or after the built-in analysis |
| Data of your own in the result | Not possible | Meta (`getMeta()`), cached together with the result |
| Maven coordinates | `nl.basjes.parse.useragent:yauaa` | `io.github.deepcitizen:yauaa` |

The numbers in this table come from the [benchmarks](#benchmarks) below.

One instance for all threads
----------------------------
In the original all state of a parse is stored in the rules themselves (the matchers, the actions and the parse tree
flattener), so `parse` is `synchronized` and one instance parses one useragent at a time.

In this fork the rules are immutable after the initialization and are shared by all threads. All state of a single
parse lives in a small `ParseState` object that is taken from a lock free pool and returned to it afterwards. The
number of these objects is limited by the number of threads that parse at the same time, and this also works with
virtual threads.

- Cache hits are lock free and (when parsing a `String`) do not create any objects.
- The cache uses `get` and `put` instead of `computeIfAbsent`, so a parse never runs inside a lock of the cache.
  When several threads parse the same new useragent at the same time they all produce the same immutable result.
- Two places where the original changed shared data during a parse (a buffer of the `<<` walk steps and
  `getRequiredInformRanges`, which used `computeIfAbsent`) no longer do that. Under the lock of the original this was
  harmless, without it these would be races.
- The debugging methods (`getMatches()`, `getUsedMatches(...)`, `getTouchedMatchers()`) show the details of the most
  recent parse and only make sense in a single thread.

Faster parsing
--------------
The grammar of useragents is ambiguous, so in several places the ANTLR parser has to look ahead (sometimes up to the
end of the useragent) to decide how to continue. These full context (LL) predictions took about 40 % of the parse
time.

The fork caches the outcomes of these predictions in a lock free cache that is shared by all threads and all
instances. The cache is bounded (a few MiB) and does not change the results: the parse tree and the reported
ambiguity (`hasAmbiguity()`, `getAmbiguityCount()`) are exactly the same. This makes a single thread about twice as fast
and removes the contention inside ANTLR that limited the throughput on many threads.

A bounded worst case
--------------------
- **Malformed useragents.** For some malformed (usually malicious) useragents the predictions made the parse time grow
  quadratically with the length: one useragent of 2 KB took up to 15 s of CPU. In the original this also held the lock
  of the instance for those 15 s. The total amount of lookahead of a single parse is now limited, which makes the worst
  case about 0.1 s. Normal useragents never get anywhere near this limit. When the limit is reached the rest of that
  useragent is parsed without these predictions, so for these (very rare) useragents the reported ambiguity and
  sometimes the result can differ from the original.
- **Nested brackets.** The parser needs a lot of stack for every level of nested brackets. With the default thread
  stack of 1 MiB about 160 or more levels made it fail with a `StackOverflowError`. A useragent with brackets nested
  more than 20 levels deep (normal useragents have at most 2 or 3) is now reported as a `Hacker` with the
  `HackerAttackVector` `Stack overflow` without parsing it. At this limit a thread stack of 256 KiB is enough. If the
  parser runs out of stack anyway (for example in a thread with very little stack left) the result is the same.

Bug fixes
---------
- A useragent that the cleanup reduces to an empty string (like `" "`, `"()"` or `"''"`) was reported as a
  `Yauaa Exploit`. It now gets the same result as an empty useragent.
- The walk steps `<<`, `<<<` and `<<<<` failed (making the whole result a `Yauaa Exploit`) when there were 20 or more
  siblings before the starting point. Only custom rules can reach this.

Analyzing some useragents yourself
----------------------------------
A `UserAgentHandler` gets the request and returns either the result or `null` ("not mine": the request is passed on).

- `addHandlerBeforeAnalysis(...)`: called for **every** parse before anything else, before the cache and before the
  built-in checks. A useragent that it handles is not analyzed by the rules and its result is **not** cached.
- `addHandlerAfterAnalysis(...)`: gets the result of the built-in analysis and can replace it. It is only called on a
  cache miss and its result is cached.

A typical use is an app of your own that puts unique ids in its useragent (like `MyApp/1.2.3 did(<uuid>)`). Without
a handler each of these useragents is a cache miss: a full analysis that takes tens of microseconds and a cache entry
that is never used again. With a handler before the analysis it costs about a microsecond and the cache is not touched.
For all other useragents such a handler adds a few nanoseconds.

```java
UserAgentAnalyzer uaa = UserAgentAnalyzer
    .newBuilder()
    .addHandlerBeforeAnalysis(request -> {
        String userAgent = request.getUserAgentString();
        if (userAgent == null || !userAgent.startsWith("MyApp/")) {
            return null; // Not ours: the next handler (or the normal analysis) gets it.
        }
        MutableUserAgent result = request.newResult();
        result.set(DEVICE_CLASS, "Phone", 1000);
        result.set(AGENT_NAME,   "MyApp", 1000);
        return result;
    })
    .build();
```

A handler can also add data of its own to the result: the meta (key/value strings that are not fields). It sets the
meta on the result it returns or on the request (also when it returns `null`); the handlers that are called later
read it and the application gets it with `getMeta(key)`. The meta of the handlers after the analysis is always
cached together with the result.

```java
    .addHandlerAfterAnalysis(request -> {
        request.setMeta("checkedBy", "MyRules");  // Cached with the result
        return null;                              // The analysis result stays as it is
    })
```

The details are in the [documentation](documentation/content/using/_index.md#analyzing-some-useragents-yourself-handlers)
(and [about the meta](documentation/content/using/_index.md#data-of-your-own-in-the-result-meta)).

What stays the same
-------------------
- The rules, the fields and their values. The output of the fork was compared with the output of the original for all
  useragents of the test cases, each with 3 different sets of fields. They are identical, except for the 3 useragents
  that consist only of whitespace (the bug fix above).
- The public API, the Java packages, Java 17 as the minimum, Java and Kryo serialization and JPMS.

All changes are listed in [CHANGELOG.md](CHANGELOG.md).

Benchmarks
==========
The benchmarks compare the original Yauaa 8.3.0 from Maven Central with this fork (8.4.0).

Hardware: AMD Ryzen 9 7950X. The benchmarks ran on 8 of its physical cores (`taskset -c 8-15`,
`-XX:ActiveProcessorCount=8`) with JDK 25 and a heap of 2 GiB. The machine was not idle (other processes used 2 to 3
cores elsewhere), so treat the numbers as indicative.

One shared instance on many threads (JMH)
-----------------------------------------
[`MultiThreadedBenchmarks`](benchmarks/src/main/java/nl/basjes/parse/useragent/benchmarks/MultiThreadedBenchmarks.java):
one instance with all fields, shared by all threads. Every thread loops over all distinct useragents of the test cases
(each thread starts at a random position). JMH 1.37, 5 × 5 s warmup and 5 × 5 s measurement, 1 fork. Parses per
second, higher is better.

| Threads | Original 8.3.0 | This fork 8.4.0 | Fork vs original |
|---:|---:|---:|---:|
| 1 | 5 244 ± 186 | 11 102 ± 1 430 | × 2.1 |
| 2 | 4 652 ± 197 | 24 340 ± 490 | × 5.2 |
| 4 | 4 108 ± 1 637 | 48 318 ± 4 238 | × 11.8 |
| 8 | 4 050 ± 1 740 | 90 784 ± 5 773 | × 22.4 |
| 8, cache hits only | 41.9 million ± 3.6 million | 237.5 million ± 31.0 million | × 5.7 |

The original parses one useragent at a time, so with more threads it does not get faster (it even gets a little
slower). To use more cores you need several instances, each with its own memory. In the fork one instance scales
with the number of cores. Cache hits got faster too: in the fork they are a plain lookup that creates no objects.

The worst case and memory
-------------------------
A small program, run once for each version, with a single instance with all fields and no cache:

| | Original 8.3.0 | This fork 8.4.0 |
|---|---:|---:|
| Building the instance | 0.9 s | 1.1 s |
| Retained heap of the instance | 114 MB | 81 MB |
| Malformed useragent of 2 KB: `invalid../../…` + `/.` repeated | 15 251 ms | 86 ms |
| … `//./.` repeated | 9 442 ms | 61 ms |
| … `aa/a/` repeated | 9 830 ms | 64 ms |
| … `a-a/` repeated | 4 024 ms | 112 ms |
| … `/.--` repeated | 3 852 ms | 83 ms |
| `Mozilla/5.0 ` + `/1[(` × 93 (186 levels of brackets) in a thread with a 1 MiB stack | `StackOverflowError` | `Hacker` in < 1 ms |

The malformed useragents are the patterns from `TestErrorHandling.pathologicalUseragentsAreParsedQuickly`. Each one was
parsed once, after a warmup with normal useragents.

Load test: a web service on 4 cores
-----------------------------------
The JMH numbers above only show the analyzer. The load test shows what happens to a web service that parses the
useragent of every request, under normal traffic and under attack. Its code is not part of this repository.

The model:

- 200 request threads (like Tomcat). When all of them are busy, requests wait in a queue of 8192; beyond that a request
  is rejected and lost.
- A Caffeine cache of 200 000 entries in front of the analyzer, 6 fields.
- The useragents come from the test cases of this project (4172 distinct ones), with a Zipf distribution of
  popularity. A "new" useragent is a real one with one number replaced by a unique value, so it is always a cache miss.
- The service, the load generator and the measurements run in one JVM (JDK 21, heap 2 GiB, G1) on 4 cores
  (`taskset` and `-XX:ActiveProcessorCount=4`). The latency is measured from the planned arrival time of a request, so
  a slow service cannot hide its delays (no coordinated omission).

Two ways to use the analyzer are compared:

- **Original 7.30.0 with a pool**: the usual way to use a `synchronized` analyzer on 4 cores. A pool of 4 analyzers
  (a request that gets no analyzer within 1 s gets no device and browser fields) and 4 worker threads that parse
  asynchronously (queue of 10 000; when it is full the request thread parses itself).
- **This fork with one instance**: one shared analyzer, the parse runs in the request thread. No pool and no worker
  threads. (The load test ran a build of this fork with the same parser as 8.4.0.)

### Without an attack

| Scenario | Metric | Original 7.30.0 with a pool | This fork with one instance |
|---|---|---:|---:|
| Maximum load, 5 % new useragents | Requests per second | 268 010 | 828 684 |
| | Parses per second (cache misses) | 13 345 | 41 439 |
| | Time until the useragent is parsed, p99 | 1 592 ms | 0.11 ms |
| Maximum load, all useragents new | Parses per second | 12 554 | 40 860 |
| | Time until the useragent is parsed, p99 | 32.6 s | 42 ms |
| | Requests without device and browser fields (no free analyzer) | 0.83 % | 0 |
| A normal day: 3000 requests per second, 5 % new | Time until the useragent is parsed, p99 | 0.56 ms | 0.38 ms |
| | CPU (cores) | 0.12 | 0.09 |
| Start of the service | Building the analyzers | 4.1 to 4.4 s | 2.4 to 2.8 s |
| | Heap after the start | 477 MB | 117 MB |

On a normal day the response of a request itself gets a little slower (p99 0.16 ms → 0.38 ms) because the
useragent is now parsed in the request thread instead of a worker thread. In exchange the parsed result is available
sooner.

### Under attack
Normal traffic of 2000 requests per second (5 % new useragents) plus 40 seconds of attack. The numbers are about the
normal requests that arrived during the attack.

| Attack | Metric | Original 7.30.0 with a pool | This fork with one instance |
|---|---|---:|---:|
| 20 000 requests per second, all useragents unique, 30 % longer than 2048 characters | Normal requests rejected | 6.3 % | 0 |
| | Time until the useragent is parsed, p99 | 18.4 s | 13 ms |
| | CPU (out of 4 cores) | 3.87 | 1.83 |
| 60 000 requests per second: more than either can handle | Normal requests rejected | 11.0 % | 0.56 % |
| | Time until the useragent is parsed, p99 | 21.7 s | 219 ms |
| | Parses per second | 12 931 | 55 226 |
| 20 000 requests per second plus 5 per second malformed useragents of 2 KB and 5 per second with 186 levels of brackets | Normal requests rejected | 75.3 % | 0 |
| | Time until the useragent is parsed, p99 | 56.1 s | 13.4 ms |
| | `StackOverflowError` | 12 | 0 |
| | Normal requests rejected 30 s after the attack | 4.3 % | 0 |

With the original a few malformed useragents per second are enough to stop the service: each one holds one of the 4
analyzers for seconds. With the fork each takes about 0.1 s of CPU in one of the 200 request threads and does not
block the other requests.

Running the benchmarks
----------------------
The JMH benchmarks are in the `benchmarks` module. Build it from the root of the repository (JDK 25):

```bash
./mvnw -PskipQuality install -pl devtools/sort,benchmarks -am
taskset -c 8-15 java -jar benchmarks/target/benchmarks.jar MultiThreadedBenchmarks -e uncachedThreadsMax \
    -jvmArgs "-Xms2g -Xmx2g -XX:ActiveProcessorCount=8"
```

`taskset` and `-XX:ActiveProcessorCount` limit the run to 8 cores (then `uncachedThreadsMax` is the same as
`uncachedThreads08`, so it is excluded). Without them `uncachedThreadsMax` and `cachedThreadsMax` use all cores.

The `benchmarks` module always uses this fork. To measure the original, compile the same class against
`nl.basjes.parse.useragent:yauaa:8.3.0`.

About the original project
==========================
[![Github actions Build status](https://img.shields.io/github/actions/workflow/status/nielsbasjes/yauaa/build.yml?branch=main)](https://github.com/nielsbasjes/yauaa/actions)
[![Coverage Status](https://img.shields.io/codecov/c/github/nielsbasjes/yauaa)](https://app.codecov.io/gh/nielsbasjes/yauaa)
[![Maven Central](https://img.shields.io/maven-central/v/nl.basjes.parse.useragent/yauaa-parent.svg?label=Maven%20central)](https://central.sonatype.com/namespace/nl.basjes.parse.useragent)
[![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/nl/basjes/parse/useragent/yauaa/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/nl/basjes/parse/useragent/yauaa/README.md)
[![GitHub stars](https://img.shields.io/github/stars/nielsbasjes/yauaa?label=GitHub%20stars)](https://github.com/nielsbasjes/yauaa/stargazers)
[![Docker Image](https://img.shields.io/docker/v/nielsbasjes/yauaa/latest?arch=amd64&label=Docker%20image)](https://hub.docker.com/r/nielsbasjes/yauaa)
[![Docker Hub](https://img.shields.io/docker/pulls/nielsbasjes/yauaa?label=Docker%20pulls)](https://hub.docker.com/r/nielsbasjes/yauaa)
[![If this project has business value for you then don't hesitate to support me with a small donation.](https://img.shields.io/badge/Sponsor%20me-via%20Github-darkgreen.svg)](https://github.com/sponsors/nielsbasjes)
[![Website](https://img.shields.io/badge/https://-yauaa.basjes.nl-blue.svg)](https://yauaa.basjes.nl/)

Yauaa works with Java, Scala, Kotlin and provides ready for use UDFs for several processing systems.

The full documentation of the original project can be found here [https://yauaa.basjes.nl](https://yauaa.basjes.nl).
The parts about threads and performance do not apply to this fork: see above and
[the documentation in this repository](documentation/content/using/_index.md).

Try it!
-------
You can try the original online with your own browser here: [https://try.yauaa.basjes.nl/](https://try.yauaa.basjes.nl/).

**NOTES**
1. This runs on a very slow and rate limited machine.
2. If you really like this then run it on your local systems. It's much faster that way.
   A Kubernetes ready Docker image is provided. See this page about the [WebServlet](https://yauaa.basjes.nl/using/webservlet) for more information.

License
=======
The words of the author of the original project, Niels Basjes:

> I'm publishing this under the Apache 2.0 license because I believe it is the best way to make these kinds of projects available to the world.
>
> But do not underestimate how much work went into this. I have spent over a decade tuning this project to what you have now.
>
> All I want to see in return is a little bit of gratitude from the people who use this.
> If you are a home user/hobbyist/small business then a simple star on the project is enough for me. Seeing that people use and like the things I create is what I'm doing this for.
> What also really helps are bug reports, headers from real/new devices/browsers that I have net yet seen before and discussions on things you think can be done better.
>
> Despite there not being any obligation (because of the Apache 2.0 license); If you are a big corporation where my code really adds value to the products you make/sell then I would really appreciate it if you could do a small sponsor thing. Buy me lunch (€10), Buy me a game (€100) or what ever you think is the right way to say thank you for the work I have done.

[![If this project has business value for you then don't hesitate to support me with a small donation.](https://img.shields.io/badge/Sponsor%20me-via%20Github-darkgreen.svg)](https://github.com/sponsors/nielsbasjes)

    Yet Another UserAgent Analyzer
    Copyright (C) 2013-2026 Niels Basjes

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
