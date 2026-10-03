#!/usr/bin/env python3
"""
Locust Load Testing Suite for LiteWebView HLS Extraction API.
Target: http://10.244.21.31:8080/extract?url=https://vidfast.vc/movie/{tmdb_id}

Run with Locust:
    locust -f locustfile.py --host http://10.244.21.31:8080
"""

import json
import logging
import os
import random
from urllib.parse import quote

from locust import HttpUser, task, between, events
from locust.runners import MasterRunner, LocalRunner

try:
    from tmdb_fetcher import get_all_movie_ids, get_random_movie_url
except ImportError:
    from .tmdb_fetcher import get_all_movie_ids, get_random_movie_url

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# Configuration via environment variables with sensible defaults
DEFAULT_HOST = os.getenv("TARGET_HOST", "http://10.244.21.31:8080")
DEFAULT_TIMEOUT = int(os.getenv("EXTRACT_TIMEOUT", "5"))
BASE_MOVIE_URL = os.getenv("MOVIE_PROVIDER_BASE", "https://vidfast.vc/movie")

# Global counter for extracted streams
stats_collector = {
    "extractions_attempted": 0,
    "extractions_successful": 0,
    "discovered_streams": set(),
}


class StreamExtractUser(HttpUser):
    """
    Simulates a realistic client requesting HLS extraction, querying status,
    and navigating the LiteWebView server.
    """
    host = DEFAULT_HOST
    wait_time = between(1.0, 3.5)

    def on_start(self):
        """Executed once when a simulated virtual user starts."""
        self.movie_ids = get_all_movie_ids()
        logger.info(f"User started with {len(self.movie_ids)} available TMDB movie IDs.")

    @task(10)
    def extract_hls_stream(self):
        """
        Primary test task: Requests HLS extraction for a random TMDB movie ID.
        Endpoint: /extract?url=https://vidfast.vc/movie/{tmdb_id}&timeout={timeout}
        """
        movie_id = random.choice(self.movie_ids)
        target_movie_url = f"{BASE_MOVIE_URL.rstrip('/')}/{movie_id}"
        encoded_movie_url = quote(target_movie_url, safe="")

        extract_endpoint = f"/extract?url={encoded_movie_url}&timeout={DEFAULT_TIMEOUT}"
        metric_name = "/extract?url=https://vidfast.vc/movie/[tmdb_id]"

        stats_collector["extractions_attempted"] += 1

        with self.client.get(
            extract_endpoint,
            name=metric_name,
            catch_response=True,
            timeout=DEFAULT_TIMEOUT + 4
        ) as response:
            if response.status_code == 200:
                try:
                    data = response.json()
                    is_success = data.get("success", False)
                    hls_url = data.get("url")

                    if is_success and hls_url:
                        stats_collector["extractions_successful"] += 1
                        stats_collector["discovered_streams"].add(hls_url)
                        response.success()
                        headers_info = data.get("headers") or {}
                        origin = headers_info.get("Origin", "None")
                        referer = headers_info.get("Referer", "None")
                        logger.info(f"[200 OK] TMDB {movie_id} -> HLS: {hls_url[:60]}... | Headers: Origin={origin}, Referer={referer}")
                    else:
                        # Server replied properly (HLS not yet detected within timeout)
                        error_msg = data.get("error", "No stream detected")
                        response.success()  # 200 OK with success=false is valid API response
                        logger.debug(f"[200 OK] TMDB {movie_id} -> Result: {error_msg}")
                except json.JSONDecodeError:
                    response.failure(f"Invalid JSON returned: {response.text[:100]}")
            elif response.status_code == 400:
                response.failure(f"HTTP 400 Bad Request: {response.text}")
            elif response.status_code >= 500:
                response.failure(f"HTTP {response.status_code} Internal Server Error")
            else:
                response.failure(f"Unexpected HTTP {response.status_code}")

    @task(3)
    def check_server_status(self):
        """
        Queries server health, Lite Mode status, and currently detected HLS stream.
        Endpoint: /status
        """
        with self.client.get("/status", name="/status", catch_response=True) as response:
            if response.status_code == 200:
                try:
                    data = response.json()
                    if data.get("status") == "running":
                        response.success()
                    else:
                        response.failure("Status is not running")
                except Exception as e:
                    response.failure(f"Failed to parse status: {e}")
            else:
                response.failure(f"Status check failed: HTTP {response.status_code}")

    @task(2)
    def navigate_webview(self):
        """
        Commands WebView to navigate directly without waiting for extraction.
        Endpoint: /?url=https://vidfast.vc/movie/{tmdb_id}
        """
        movie_id = random.choice(self.movie_ids)
        target_movie_url = f"{BASE_MOVIE_URL.rstrip('/')}/{movie_id}"
        encoded_movie_url = quote(target_movie_url, safe="")

        with self.client.get(
            f"/?url={encoded_movie_url}",
            name="/?url=https://vidfast.vc/movie/[tmdb_id]",
            catch_response=True
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Navigation failed: HTTP {response.status_code}")

    @task(1)
    def toggle_lite_mode(self):
        """
        Toggles Lite Mode on or off to test runtime configuration handling.
        Endpoint: /mode?lite=on or /mode?lite=off
        """
        mode = random.choice(["on", "off"])
        with self.client.get(f"/mode?lite={mode}", name="/mode?lite=[mode]", catch_response=True) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Mode toggle failed: HTTP {response.status_code}")


@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    logger.info("=" * 60)
    logger.info(f" Starting Locust Load Test on {environment.host or DEFAULT_HOST}")
    logger.info(f" Default extract timeout: {DEFAULT_TIMEOUT}s")
    logger.info("=" * 60)


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    total = stats_collector["extractions_attempted"]
    success = stats_collector["extractions_successful"]
    streams = len(stats_collector["discovered_streams"])

    logger.info("=" * 60)
    logger.info(" Locust Load Test Completed")
    logger.info(f" Extractions Attempted  : {total}")
    logger.info(f" Extractions Successful : {success}")
    logger.info(f" Unique Streams Found   : {streams}")
    if streams > 0:
        logger.info(" Sample detected HLS streams:")
        for s in list(stats_collector["discovered_streams"])[:5]:
            logger.info(f"   -> {s}")
    logger.info("=" * 60)
