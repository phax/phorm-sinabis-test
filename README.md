# phorm-sinabis-test

> **Demo / playground project.**
> It is not under active development and not published to Maven Central.
> If it saved you some time, a star would mean a lot - it helps others find it too.

Runs the files of the Sinabis 1M synthetic XRechnung 3.0 / ZUGFeRD data set against a [phorm](https://github.com/phax/phorm) instance.
The data set is published on Hugging Face: [sinabis-group/sinabis-1m-synthetic-zugferd-xrechnung](https://huggingface.co/datasets/sinabis-group/sinabis-1m-synthetic-zugferd-xrechnung).
Download and extract its ZIP files first; `--data-dir` must point to the folder containing `manifest/` and `zugferd/`.

It serves as a conformance check (Sinabis claims every file is valid, so every file phorm rejects is a mismatch) and as a performance test.

* Uses [phorm-client](https://github.com/phax/phorm-client) and reads the `manifest/*.jsonl` files with ph-json
* XML files go to `POST /api/dd_and_validate`, PDF files to `POST /api/hybrid_validate?country=DE`
* Files are read before the timer starts, so the reported latency is network + server + client side response parsing

# Build

```
mvn clean package
```

# Run

Start phorm first (e.g. `docker run -d -p 8080:8080 phelger/phorm`), then:

```
java -jar target/phorm-sinabis-test-1.0.0-SNAPSHOT.jar \
  --data-dir /Volumes/helger.com/huggingface/sinabis-1m-synthetic-zugferd-xrechnung \
  --url http://localhost:8080 --token phorm-dev-token \
  --threads 8 --mode both --warmup 200 --limit 1000
```

Omit `--limit` for the full 127,500 invoices. Run `java -jar ... ` without arguments for all options.

# Coverage analysis

A second tool extracts the covered invoice aspects from the XML files - similar to the [XRechnung test suite overview](https://github.com/itplr-kosit/xrechnung-testsuite/blob/master/src/doc/test-overview.md).
It reads the XML itself (not the manifest metadata) and needs no running phorm:

```
java -cp target/phorm-sinabis-test-1.0.0-SNAPSHOT.jar com.helger.phorm.sinabis.coverage.CoverageMain \
  --data-dir /Volumes/helger.com/huggingface/sinabis-1m-synthetic-zugferd-xrechnung --out-dir coverage
```

| File | Content |
|---|---|
| `coverage.md` | Per group (document, lines, VAT, payment, references, parties, delivery, period, amounts) the number and share of invoices that contain an aspect, plus the first example file |
| `invoices.csv` | One line per invoice with a KoSIT-style description, e.g. `Standard invoice (380) with 2-5 lines, category S, means code 58, due date, ...` |

Options: `--manifest`, `--skip`, `--limit`, `--threads`.

# Output of the validation run (in `--out-dir`, default `./results`)

| File | Content |
|---|---|
| `results.csv` | One line per call: kind, idx, file, outcome (`VALID`, `INVALID`, `CALL_ERROR`, `FILE_ERROR`), client ms, server ms, error IDs |
| `summary.json` | Counts, throughput, latency (avg, min, median, p90, p95, p99, max), findings by error ID, determined VESIDs |
| `summary.md` | The same in a form to paste |

Latencies are given as wall clock time measured by the client and as the duration reported by phorm itself.

# Interpreting the results

Sinabis claims that all files are valid, so every `INVALID` outcome is either a defect in the data set or a defect in phorm or the phive rules - the tool does not decide which.
`CALL_ERROR` means phorm produced no verdict at all (timeout, HTTP error, rejected token).

# License

Apache License 2.0, see [LICENSE](LICENSE).
Copyright (C) 2026 Philip Helger.

---

My personal [Coding Styleguide](https://github.com/phax/meta/blob/master/CodingStyleguide.md) |
It is appreciated if you star the GitHub project if you like it.
