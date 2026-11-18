# Documentation

Two kinds of page, deliberately separated:

* **Guides (01-07)** - read in order, once. They explain *why* this project is built the way it is,
  and each one is short enough to finish.
* **Reference (`reference/`)** - look things up. Dense, tabular, trap-first, and cross-referenced to
  the file in this repo that uses each thing.

## Guides

| # | Guide | Read it when |
| --- | --- | --- |
| 01 | [Basics worth re-reading](01-basics.md) | you want the parts of "getting started" that still matter after five years |
| 02 | [Customizing the framework](02-customization.md) | you are writing a starter, or wondering which extension point to use |
| 03 | [Database connectivity](03-database.md) | pools, replicas, Flyway, mapping and query decisions |
| 04 | [Transactions](04-transactions.md) | propagation, rollback rules, savepoints, the outbox |
| 05 | [Outbound HTTP resilience](05-http-resilience.md) | timeouts, retry, backoff, breakers, idempotency |
| 06 | [Testing](06-testing.md) | choosing a test scope, and why the suite is slow |
| 08 | [The five HTTP clients](08-http-clients.md) | RestTemplate vs RestClient vs WebClient vs @HttpExchange vs Feign - and where each one hides its timeouts |
| 09 | [Extension points and filters](09-extension-points.md) | the BeanPostProcessor family, writing your own @Enable*, and the filter chain |
| 07 | [Production checklist](07-production-checklist.md) | before you ship |

## Reference

| Page | Covers | Shape |
| --- | --- | --- |
| [annotations.md](reference/annotations.md) | the mental model for *who processes an annotation*, then every family with its traps, a look-alikes table and the proxy-defeated cheat sheet | prose, read it |
| [annotations-index.md](reference/annotations-index.md) | 258 annotations A-Z: exact package, one line, and whether this project uses it. Plus the 24 names that are ambiguous on this classpath, and a "I want to... / use this" finder | table, look it up ([generated](reference/_generator/README.md)) |

## How the code and the docs line up

```
Guide 02  <->  platform-starter/**                       (auto-configuration, conditions, AOP, endpoints)
Guide 03  <->  config/DataSourceConfig, config/JpaConfig, domain/**, repo/**, db/migration/**
Guide 04  <->  service/**, service/tx/**, events/**      (+ TransactionPropagationTest, 18 assertions)
Guide 05  <->  payments/**, config/RetryConfig           (+ PaymentRetryIntegrationTest, 9 assertions)
Guide 06  <->  src/test/**
Guide 08  <->  clients/**, config/ClientsConfig              (+ CatalogClientComparisonTest, 4 assertions)
Guide 09  <->  support/extension/**, web/filter/**           (+ ExtensionPointsTest, FilterChainTest)
```

## Reading order if you have an hour

1. [01-basics](01-basics.md) - 10 minutes, mostly to recalibrate.
2. [reference/annotations](reference/annotations.md) section 0, "The mental model" - 5 minutes, and
   it is the highest-value part of this repo.
3. [04-transactions](04-transactions.md) and [05-http-resilience](05-http-resilience.md) - 25
   minutes, the two topics where mistakes cost real money.
4. The "Bugs this project found in itself" list in the [root README](../README.md) - 10 minutes, ten
   defects that all fail silently.
5. Run the app and drive `order-service/http/api.http`.
