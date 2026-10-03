#!/usr/bin/env python3
"""
TMDB Movie ID Provider & Web Fetcher
Retrieves TMDB movie IDs from web sources and provides a diverse seed dataset.
"""

import json
import logging
import os
import random
import re
import urllib.request
import urllib.error

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

CACHE_FILE = os.path.join(os.path.dirname(__file__), "tmdb_ids.json")

# Verified diverse set of TMDB movie IDs (classics, blockbusters, new releases)
DEFAULT_TMDB_IDS = [
    1265609,  # Specified by user
    550,      # Fight Club (1999)
    27205,    # Inception (2010)
    157336,   # Interstellar (2014)
    155,      # The Dark Knight (2008)
    299536,   # Avengers: Infinity War (2018)
    299534,   # Avengers: Endgame (2019)
    603,      # The Matrix (1999)
    19995,    # Avatar (2009)
    24428,    # The Avengers (2012)
    597,      # Titanic (1997)
    680,      # Pulp Fiction (1994)
    13,       # Forrest Gump (1994)
    278,      # The Shawshank Redemption (1994)
    238,      # The Godfather (1972)
    424,      # Schindler's List (1993)
    769,      # Goodfellas (1990)
    122,      # The Lord of the Rings: The Return of the King (2003)
    11,       # Star Wars: Episode IV - A New Hope (1977)
    8587,     # The Lion King (1994)
    389,      # 12 Angry Men (1957)
    129,      # Spirited Away (2001)
    496243,   # Parasite (2019)
    324857,   # Spider-Man: Into the Spider-Verse (2018)
    475557,   # Joker (2019)
    105,      # Back to the Future (1985)
    807,      # Se7en (1995)
    500,      # Reservoir Dogs (1992)
    1891,     # The Empire Strikes Back (1980)
    240,      # The Godfather Part II (1974)
    693134,   # Dune: Part Two (2024)
    872585,   # Oppenheimer (2023)
    346698,   # Barbie (2023)
    507089,   # Five Nights at Freddy's (2023)
    1022789,  # Inside Out 2 (2024)
    533535,   # Deadpool & Wolverine (2024)
    912649,   # Venom: The Last Dance (2024)
    823464,   # Godzilla x Kong: The New Empire (2024)
    653346,   # Kingdom of the Planet of the Apes (2024)
    519182,   # Despicable Me 4 (2024)
]


def fetch_tmdb_ids_from_web() -> list[int]:
    """
    Attempts to fetch trending/popular TMDB movie IDs from public web feeds/APIs.
    Merges with the default list and caches results.
    """
    discovered_ids = set(DEFAULT_TMDB_IDS)

    # Source 1: Public TMDB trending / popular endpoints & mirrors
    sources = [
        "https://api.themoviedb.org/3/trending/movie/week?api_key=8476a7ab80ad76f0936744df0430e67c",
        "https://api.themoviedb.org/3/movie/popular?api_key=8476a7ab80ad76f0936744df0430e67c",
        "https://raw.githubusercontent.com/theodore96/popular-movies/master/movies.json"
    ]

    for url in sources:
        try:
            req = urllib.request.Request(
                url,
                headers={"User-Agent": "Mozilla/5.0 (LiteWebView-LoadTester/1.0)"}
            )
            with urllib.request.urlopen(req, timeout=5) as response:
                if response.status == 200:
                    data = json.loads(response.read().decode("utf-8"))
                    results = data.get("results") or (data if isinstance(data, list) else [])
                    for item in results:
                        if isinstance(item, dict) and "id" in item:
                            try:
                                discovered_ids.add(int(item["id"]))
                            except (ValueError, TypeError):
                                pass
            logger.info(f"Successfully scraped IDs from: {url}")
        except Exception as e:
            logger.debug(f"Could not fetch from {url} ({e}), continuing...")

    id_list = sorted(list(discovered_ids))
    save_cached_ids(id_list)
    logger.info(f"Total available TMDB Movie IDs: {len(id_list)}")
    return id_list


def load_cached_ids() -> list[int]:
    """Loads cached TMDB IDs from disk, or falls back to defaults."""
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list) and len(data) > 0:
                    return [int(x) for x in data]
        except Exception as e:
            logger.warning(f"Failed to read cache file {CACHE_FILE}: {e}")

    # If no cache exists, save default
    save_cached_ids(DEFAULT_TMDB_IDS)
    return DEFAULT_TMDB_IDS


def save_cached_ids(ids: list[int]):
    """Persists TMDB IDs to JSON cache."""
    try:
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(ids, f, indent=2)
    except Exception as e:
        logger.warning(f"Failed to save cache file {CACHE_FILE}: {e}")


def get_random_movie_url(base_url: str = "https://vidfast.vc/movie") -> str:
    """Returns a full movie URL with a randomly selected TMDB ID."""
    ids = load_cached_ids()
    movie_id = random.choice(ids)
    return f"{base_url.rstrip('/')}/{movie_id}"


def get_all_movie_ids() -> list[int]:
    """Returns the full list of available TMDB IDs."""
    return load_cached_ids()


if __name__ == "__main__":
    print("Fetching/refreshing TMDB Movie IDs from web...")
    ids = fetch_tmdb_ids_from_web()
    print(f"Loaded {len(ids)} TMDB movie IDs:")
    print(ids[:15], "... and more.")
