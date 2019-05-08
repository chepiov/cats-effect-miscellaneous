## Cats Effect Miscellaneous

* (__WIP__) Source code of the examples and exercises of Cats Effect [tutorial](https://typelevel.org/cats-effect/tutorial/tutorial.html)
* Solutions for [Cats Effect Scala exercises](https://olegpy.com/cats-effect-exercises/)

***
### [Race](src/main/scala/org/chepiov/olegpy/Race.scala) 
[Original description](https://olegpy.com/cats-effect-exercises/#race-for-success)

#### Objective
Quickly obtain data which can be requested from multiple sources of unknown latency (databases, caches, network services, etc.).

#### Requirements
* The function should run requests in parallel.
* The function should wait for the first request to complete successfuly.
* Once a first request has completed, everything that is still in-flight must be cancelled.
* If all requests have failed, all errors should be reported for better debugging.

Assume that there will be <= 32 providers and they all don’t block OS threads for I/O.

#### Bonus
* Avoid using runtime checking for CompositeException (including pattern matching on it).
* If returned IO is cancelled, all in-flight requests should be properly cancelled as well.
* Refactor function to allow generic effect type to be used, not only cats’ IO. (e.g. anything with Async or Concurrent instances).
* Refactor function to allow generic container type to be used (e.g. anything with Traverse or NonEmptyTraverse instances).
* Don’t use toList. If you have to work with lists anyway, might as well push the conversion responsibility to the caller.
* If you want to support collections that might be empty (List, Vector, Option), the function must result in a failing IO/F when passed an empty value.

***
### [Worker pool](src/main/scala/org/chepiov/olegpy/WorkerPool.scala) 
[Original description](https://olegpy.com/cats-effect-exercises/#worker-pool-with-load-balancing)

#### Objective
Do parallel processing, distributed over a limited number of workers, each with its own state (counters, DB connections, etc.).

#### Requirements
* Processing jobs must run in parallel
* Submitting a processing request must wait if all workers are busy.
* Submission should do load balancing: wait for the first worker to finish, not for a certain one.
* Worker should become available whenever a job is completed successfully, with an exception or cancelled.

Assume the number of workers is not very large (<= 100).

#### Bonus
* Generalize for any F using Concurrent typeclass.
* Add methods to WorkerPool interface for adding workers on the fly and removing all workers. If all workers are removed, submitted jobs must wait until one is added.