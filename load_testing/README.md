# LiteWebView HLS Extraction Load Testing Suite 🦗

A realistic load testing and stress benchmark suite built with **[Locust](https://locust.io/)** and Python to evaluate the performance, throughput, and stability of the LiteWebView HLS extraction endpoint:

```http
GET http://10.244.21.31:8080/extract?url=https://vidfast.vc/movie/{tmdb_id}&timeout=5
```

---

## 📂 Project Structure

```text
load_testing/
├── locustfile.py        # Core Locust test scenarios modeling realistic user behaviors
├── tmdb_fetcher.py      # Dynamic TMDB movie ID scraper & seed provider
├── run_load_test.py     # Unified CLI launcher (Web UI, headless mode, standalone benchmark)
├── tmdb_ids.json        # Cached TMDB IDs (e.g. 550, 1265609, 27205, etc.)
├── requirements.txt     # Python dependencies (locust, requests)
└── README.md            # Usage and documentation
```

---

## ⚡ Quick Start

### 1. Install Dependencies
```bash
pip install -r requirements.txt
```
*(Or install locally: `pip install --user locust requests`)*

---

### 2. Run with Interactive Locust Web UI (Recommended)

Start the interactive Locust dashboard on `http://localhost:8089`:

```bash
python3 run_load_test.py --web
```
or directly with the `locust` CLI:
```bash
locust -f locustfile.py --host http://10.244.21.31:8080
```

1. Open **[http://localhost:8089](http://localhost:8089)** in your web browser.
2. Enter the number of concurrent users (e.g. `5` or `10`) and spawn rate (e.g. `1` user/sec).
3. Click **Start Swarming** to view live charts:
   * Requests per second (RPS)
   * Response time percentiles (50th, 95th, 99th)
   * Success vs Failure counts
   * Downloadable CSV reports and request statistics

---

### 3. Run Headless Load Test in Terminal

To run automated CI-style load tests without launching a web browser:

```bash
# Run for 30 seconds with 5 virtual users:
python3 run_load_test.py --headless --users 5 --spawn-rate 1 --run-time 30s

# Generate an HTML performance report:
python3 run_load_test.py --headless --users 10 --spawn-rate 2 --run-time 1m --html report.html
```

---

### 4. Fast Standalone Multi-Threaded Benchmark

If you want a quick check without launching Locust:

```bash
python3 run_load_test.py --benchmark --requests 20 --concurrency 3
```

This will run 20 concurrent requests across different TMDB IDs and output:
* HTTP status codes
* Successful HLS playlist detections
* Average, P50 (median), and P95 latency metrics

---

## 🎬 TMDB Movie IDs

The suite includes 60+ verified TMDB movie IDs including:
* `1265609` (Target movie specified by user)
* `550` (Fight Club)
* `27205` (Inception)
* `157336` (Interstellar)
* `155` (The Dark Knight)
* `299536` (Avengers: Infinity War)
* `603` (The Matrix)
* `19995` (Avatar)
* `693134` (Dune: Part Two)
* `872585` (Oppenheimer)
* and many more!

To automatically refresh and scrape the latest trending and popular movie IDs from the web:
```bash
python3 run_load_test.py --fetch-web-ids
```
or:
```bash
python3 tmdb_fetcher.py
```

---

## ⚙️ Configuration & Customization

You can configure the target server and parameters via CLI options or environment variables:

| Parameter | Environment Variable | Default Value | Description |
| :--- | :--- | :--- | :--- |
| Target Server | `TARGET_HOST` | `http://10.244.21.31:8080` | Base URL of LiteWebView server |
| Timeout | `EXTRACT_TIMEOUT` | `5` | Extraction timeout in seconds |
| Movie Provider | `MOVIE_PROVIDER_BASE` | `https://vidfast.vc/movie` | Base URL of movie streaming provider |
| Web UI Port | - | `8089` | Locust web dashboard port |

Example with custom host and timeout:
```bash
TARGET_HOST="http://192.168.1.50:8080" EXTRACT_TIMEOUT="10" locust -f locustfile.py
```
