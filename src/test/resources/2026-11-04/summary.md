# phorm validation test with the Sinabis 1M XRechnung / ZUGFeRD data set

| Setting | Value |
|---|---|
| Started | 2026-11-04T20:54:37.854679Z |
| phorm URL | http://localhost:8090 |
| Threads | 8 |
| Invoices | 1000000 |
| Wall time | 9394.0 s |
| Client | Java 27.0.1.0.2, Mac OS X aarch64, 14 CPUs |

## XML (`/api/dd_and_validate`)

| Metric | Value |
|---|---|
| total | 1000000 |
| valid | 999969 |
| invalid | 0 |
| call_error | 1 |
| file_error | 30 |
| invalid % | 0.0000 |
| throughput | 106.4 / s |

| Latency (ms) | avg | median | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| client (wall clock) | 6.7 | 4.0 | 11.0 | 15.0 | 31.0 | 1259.0 |
| server (reported) | 4.5 | 3.0 | 7.0 | 10.0 | 15.0 | 1188.0 |

Findings by error ID (invalid documents only): 0 distinct, see summary.json

## PDF (`/api/hybrid_validate`)

| Metric | Value |
|---|---|
| total | 1000000 |
| valid | 999966 |
| invalid | 0 |
| call_error | 0 |
| file_error | 34 |
| invalid % | 0.0000 |
| throughput | 106.4 / s |

| Latency (ms) | avg | median | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| client (wall clock) | 51.2 | 41.0 | 96.0 | 115.0 | 153.0 | 1316.0 |
| server (reported) | 49.4 | 39.0 | 93.0 | 112.0 | 149.0 | 1315.0 |

Findings by error ID (invalid documents only): 0 distinct, see summary.json

