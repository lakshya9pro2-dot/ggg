#!/usr/bin/env python3
"""
Load Test Runner & Benchmark Tool for LiteWebView HLS Extraction Server.

Usage examples:
  # 1. Run Locust with interactive Web UI (http://localhost:8089):
  python3 run_load_test.py --web

  # 2. Run Locust in headless mode for 30 seconds with 5 users:
  python3 run_load_test.py --headless --users 5 --spawn-rate 1 --run-time 30s

  # 3. Fast multi-threaded Python benchmark without Web UI:
  python3 run_load_test.py --benchmark --requests 20 --concurrency 3

  # 4. Target a custom host/port:
  python3 run_load_test.py --host http://10.244.21.31:8080 --users 10
"""

import argparse
import concurrent.futures
import json
import os
import shutil
import subprocess
import sys
import time
from urllib.parse import quote

import requests

from tmdb_fetcher import get_all_movie_ids, fetch_tmdb_ids_from_web

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
LOCUSTFILE = os.path.join(SCRIPT_DIR, "locustfile.py")


def find_locust_executable() -> str:
    """Finds the locust command in PATH or local user directory."""
    locust_path = shutil.which("locust")
    if locust_path:
        return locust_path

    user_locust = os.path.expanduser("~/.local/bin/locust")
    if os.path.exists(user_locust):
        return user_locust

    return "locust"


def run_locust_web(host: str, web_port: int):
    """Starts Locust with interactive Web UI."""
    locust_cmd = find_locust_executable()
    cmd = [
        locust_cmd,
        "-f", LOCUSTFILE,
        "--host", host,
        "--web-port", str(web_port),
    ]
    print("=" * 65)
    print(f"🚀 Starting Locust Web UI on: http://127.0.0.1:{web_port}")
    print(f"🎯 Target host: {host}")
    print("Open the link in your browser to configure and run the load test.")
    print("Press Ctrl+C to terminate the test server.")
    print("=" * 65)
    try:
        subprocess.run(cmd)
    except KeyboardInterrupt:
        print("\nLocust web server stopped.")


def run_locust_headless(host: str, users: int, spawn_rate: float, run_time: str, html_report: str = None):
    """Runs Locust in headless mode."""
    locust_cmd = find_locust_executable()
    cmd = [
        locust_cmd,
        "-f", LOCUSTFILE,
        "--host", host,
        "--headless",
        "-u", str(users),
        "-r", str(spawn_rate),
        "--run-time", run_time,
    ]
    if html_report:
        cmd.extend(["--html", html_report])

    print("=" * 65)
    print("🚀 Starting Locust Headless Load Test")
    print(f"🎯 Target Host  : {host}")
    print(f"👥 Users        : {users}")
    print(f"📈 Spawn Rate   : {spawn_rate} users/sec")
    print(f"⏱️  Run Time     : {run_time}")
    print("=" * 65)
    subprocess.run(cmd)


def run_standalone_benchmark(host: str, total_requests: int = 15, concurrency: int = 3, timeout: int = 6):
    """
    Direct multi-threaded benchmark using Python requests.
    Useful for immediate validation and latency percentiles.
    """
    movie_ids = get_all_movie_ids()
    print("=" * 65)
    print(f"🚀 Running Standalone Benchmark on {host}")
    print(f"📊 Total Requests: {total_requests} | Concurrency: {concurrency}")
    print(f"🎬 Available TMDB IDs: {len(movie_ids)}")
    print("=" * 65)

    # First check /status
    try:
        status_resp = requests.get(f"{host.rstrip('/')}/status", timeout=4)
        print(f"Server check /status: {status_resp.status_code} -> {status_resp.text}")
    except Exception as e:
        print(f"⚠️  Warning: Unable to reach {host}/status: {e}")
        print("Continuing with extraction tests...\n")

    latencies = []
    success_count = 0
    hls_found_count = 0
    discovered_hls = []

    def single_extract(idx, movie_id):
        target_url = f"https://vidfast.vc/movie/{movie_id}"
        encoded = quote(target_url, safe="")
        endpoint = f"{host.rstrip('/')}/extract?url={encoded}&timeout={timeout}"

        start_t = time.perf_counter()
        try:
            r = requests.get(endpoint, timeout=timeout + 3)
            elapsed = time.perf_counter() - start_t
            if r.status_code == 200:
                data = r.json()
                hls = data.get("url")
                is_success = data.get("success", False)
                headers_info = data.get("headers") or {}
                return {
                    "index": idx,
                    "movie_id": movie_id,
                    "status_code": r.status_code,
                    "elapsed": elapsed,
                    "success": is_success,
                    "hls_url": hls,
                    "headers": headers_info,
                    "error": data.get("error")
                }
            else:
                return {
                    "index": idx,
                    "movie_id": movie_id,
                    "status_code": r.status_code,
                    "elapsed": elapsed,
                    "success": False,
                    "hls_url": None,
                    "headers": {},
                    "error": f"HTTP {r.status_code}"
                }
        except Exception as e:
            elapsed = time.perf_counter() - start_t
            return {
                "index": idx,
                "movie_id": movie_id,
                "status_code": 0,
                "elapsed": elapsed,
                "success": False,
                "hls_url": None,
                "headers": {},
                "error": str(e)
            }

    import random
    tasks = [(i + 1, random.choice(movie_ids)) for i in range(total_requests)]

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(single_extract, idx, mid) for idx, mid in tasks]
        for f in concurrent.futures.as_completed(futures):
            res = f.result()
            latencies.append(res["elapsed"])
            if res["status_code"] == 200:
                success_count += 1
            if res["hls_url"]:
                hls_found_count += 1
                discovered_hls.append(res["hls_url"])
                headers = res.get("headers") or {}
                orig = headers.get("Origin", "None")
                ref = headers.get("Referer", "None")
                print(f"✅ [{res['index']:02d}/{total_requests:02d}] TMDB {res['movie_id']} in {res['elapsed']:.2f}s -> {res['hls_url'][:50]}... | Origin: {orig} | Referer: {ref}")
            else:
                print(f"ℹ️ [{res['index']:02d}/{total_requests:02d}] TMDB {res['movie_id']} in {res['elapsed']:.2f}s -> {res['error']}")

    print("\n" + "=" * 65)
    print("📊 Benchmark Summary:")
    print(f"Total Requests        : {total_requests}")
    print(f"HTTP 200 Responses    : {success_count}/{total_requests} ({success_count/total_requests*100:.1f}%)")
    print(f"HLS Streams Extracted : {hls_found_count}")
    if latencies:
        latencies.sort()
        avg_lat = sum(latencies) / len(latencies)
        p50 = latencies[int(len(latencies) * 0.50)]
        p95 = latencies[min(int(len(latencies) * 0.95), len(latencies) - 1)]
        print(f"Latency Avg           : {avg_lat:.2f}s")
        print(f"Latency P50 (median)  : {p50:.2f}s")
        print(f"Latency P95           : {p95:.2f}s")
    print("=" * 65)


def main():
    parser = argparse.ArgumentParser(
        description="Locust & Python Load Testing Suite for LiteWebView HLS Extractor"
    )
    parser.add_argument(
        "--host",
        default="http://10.244.21.31:8080",
        help="Target base URL of LiteWebView server (default: http://10.244.21.31:8080)"
    )
    parser.add_argument(
        "--web",
        action="store_true",
        help="Start Locust with interactive Web UI (default web port: 8089)"
    )
    parser.add_argument(
        "--web-port",
        type=int,
        default=8089,
        help="Port for Locust Web UI (default: 8089)"
    )
    parser.add_argument(
        "--headless",
        action="store_true",
        help="Run Locust headlessly in terminal"
    )
    parser.add_argument(
        "--users", "-u",
        type=int,
        default=5,
        help="Number of concurrent virtual users (default: 5)"
    )
    parser.add_argument(
        "--spawn-rate", "-r",
        type=float,
        default=1.0,
        help="Users spawned per second (default: 1.0)"
    )
    parser.add_argument(
        "--run-time", "-t",
        default="30s",
        help="Test run duration (e.g. 30s, 1m, 5m) (default: 30s)"
    )
    parser.add_argument(
        "--html",
        default="load_test_report.html",
        help="HTML report output path for headless Locust run"
    )
    parser.add_argument(
        "--benchmark",
        action="store_true",
        help="Run standalone multi-threaded Python benchmark without Web UI"
    )
    parser.add_argument(
        "--requests",
        type=int,
        default=15,
        help="Total requests for standalone benchmark (default: 15)"
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=3,
        help="Concurrent workers for benchmark (default: 3)"
    )
    parser.add_argument(
        "--fetch-web-ids",
        action="store_true",
        help="Fetch fresh TMDB movie IDs from web before running test"
    )

    args = parser.parse_args()

    if args.fetch_web_ids:
        print("Scraping fresh TMDB movie IDs from web...")
        fetch_tmdb_ids_from_web()

    if args.benchmark:
        run_standalone_benchmark(
            host=args.host,
            total_requests=args.requests,
            concurrency=args.concurrency
        )
    elif args.headless:
        run_locust_headless(
            host=args.host,
            users=args.users,
            spawn_rate=args.spawn_rate,
            run_time=args.run_time,
            html_report=args.html
        )
    else:
        # Default mode is Locust Web UI
        run_locust_web(host=args.host, web_port=args.web_port)


if __name__ == "__main__":
    main()
