"""
rembg ONNX Models Downloader
============================

These models are for the rembg background removal library and will be
packaged with the installation. rembg (https://github.com/danielgatis/rembg)
is a tool to remove images background. By default it downloads models on
demand, but this script pre-downloads ALL available ONNX models so they
are available offline and bundled with the project.

Models are fetched from the danielgatis/rembg GitHub releases (tag v0.0.0)
and saved to:  C:\\TRAE\\LX\\models\\rembg\\

Usage:
    python download_models.py

Features:
    - Skips files that already exist with a matching hash
    - Shows download progress (file name, size, progress bar)
    - Verifies md5 / sha256 hashes after download
    - Handles errors gracefully and continues with the next file
    - Retries failed downloads (3 attempts with backoff)
    - Prints a summary at the end (downloaded / skipped / failed)

Dependencies:
    None beyond the Python standard library (urllib.request, hashlib).
"""

import hashlib
import os
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# Directory where all ONNX models will be saved
TARGET_DIR = r"C:\TRAE\LX\models\rembg"

# Number of download retry attempts per file
MAX_RETRIES = 3

# Delay (seconds) between retry attempts
RETRY_DELAY = 5

# Download chunk size: 64 KB
CHUNK_SIZE = 64 * 1024

# Minimum bytes of progress between progress-bar refreshes
PROGRESS_INTERVAL = 256 * 1024  # 256 KB

# ---------------------------------------------------------------------------
# Model definitions
#   (filename, download_url, expected_hash_or_None)
# ---------------------------------------------------------------------------

MODELS = [
    # (local_filename, download_url, expected_hash)
    # local_filename must match rembg's expected name: {model_name}.onnx
    ("u2net.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx", "md5:60024c5c889badc19c04ad937298a77b"),
    ("u2netp.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx", "md5:8e83ca70e441ab06c318d82300c84806"),
    ("u2net_human_seg.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net_human_seg.onnx", "md5:c09ddc2e0104f800e3e1bb4652583d1f"),
    ("u2net_cloth_seg.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net_cloth_seg.onnx", "md5:2434d1f3cb744e0e49386c906e5a08bb"),
    ("silueta.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/silueta.onnx", "md5:55e59e0d8062d2f5d013f4725ee84782"),
    ("isnet-general-use.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx", "md5:fc16ebd8b0c10d971d3513d564d01e29"),
    ("isnet-anime.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-anime.onnx", "md5:6f184e756bb3bd901c8849220a83e38e"),
    ("birefnet-general.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-general-epoch_244.onnx", "md5:7a35a0141cbbc80de11d9c9a28f52697"),
    ("birefnet-general-lite.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-general-bb_swin_v1_tiny-epoch_232.onnx", "md5:4fab47adc4ff364be1713e97b7e66334"),
    ("birefnet-portrait.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-portrait-epoch_150.onnx", "md5:c3a64a6abf20250d090cd055f12a3b67"),
    ("birefnet-dis.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-DIS-epoch_590.onnx", "md5:2d4d44102b446f33a4ebb2e56c051f2b"),
    ("birefnet-hrsod.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-HRSOD_DHU-epoch_115.onnx", "md5:c017ade5de8a50ff0fd74d790d268dda"),
    ("birefnet-cod.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-COD-epoch_125.onnx", "md5:f6d0d21ca89d287f17e7afe9f5fd3b45"),
    ("birefnet-massive.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-massive-TR_DIS5K_TR_TEs-epoch_420.onnx", "md5:33e726a2136a3d59eb0fdf613e31e3e9"),
    ("bria-rmbg.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/bria-rmbg-2.0.onnx", "sha256:5b486f08200f513f460da46dd701db5fbb47d79b4be4b708a19444bcd4e79958"),
    # SAM model has two files and no checksum
    ("sam_vit_b_01ec64.encoder.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/sam_vit_b_01ec64.encoder.onnx", None),
    ("sam_vit_b_01ec64.decoder.onnx", "https://github.com/danielgatis/rembg/releases/download/v0.0.0/sam_vit_b_01ec64.decoder.onnx", None),
]


# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

def format_size(num_bytes: float) -> str:
    """Format a byte count into a human-readable string (e.g. '1.5 MB')."""
    for unit in ["B", "KB", "MB", "GB"]:
        if abs(num_bytes) < 1024.0:
            return f"{num_bytes:.1f} {unit}"
        num_bytes /= 1024.0
    return f"{num_bytes:.1f} TB"


def parse_expected_hash(expected: str):
    """Parse a hash spec like 'md5:abc123' or 'sha256:def456'.

    Returns (algorithm, hex_digest) or (None, None) when *expected* is None.
    """
    if expected is None:
        return None, None
    if ":" in expected:
        algorithm, hex_digest = expected.split(":", 1)
        return algorithm.strip().lower(), hex_digest.strip().lower()
    # No prefix – assume md5
    return "md5", expected.strip().lower()


def compute_hash(filepath: str, algorithm: str) -> str:
    """Compute the hex digest of *filepath* using *algorithm*."""
    hasher = hashlib.new(algorithm)
    with open(filepath, "rb") as f:
        while True:
            chunk = f.read(CHUNK_SIZE)
            if not chunk:
                break
            hasher.update(chunk)
    return hasher.hexdigest()


def verify_hash(filepath: str, expected: str) -> bool:
    """Return True when *filepath* matches *expected*, or when no hash is given."""
    algorithm, expected_hex = parse_expected_hash(expected)
    if algorithm is None:
        print("    [INFO] No checksum provided – skipping hash verification.")
        return True
    print(f"    [VERIFY] Computing {algorithm} ...", end=" ", flush=True)
    actual_hex = compute_hash(filepath, algorithm)
    if actual_hex == expected_hex:
        print("OK")
        return True
    print("MISMATCH!")
    print(f"      Expected: {expected_hex}")
    print(f"      Actual:   {actual_hex}")
    return False


def file_exists_and_valid(filepath: str, expected_hash) -> bool:
    """Return True when *filepath* exists and its hash matches *expected_hash*."""
    if not os.path.exists(filepath):
        return False
    if os.path.getsize(filepath) == 0:
        return False
    algorithm, expected_hex = parse_expected_hash(expected_hash)
    if algorithm is None:
        # No checksum – accept any non-empty file
        return True
    return compute_hash(filepath, algorithm) == expected_hex


def print_progress_bar(downloaded: int, total: int, bar_width: int = 40):
    """Print (overwrite) a single-line progress bar."""
    if total > 0:
        percent = downloaded / total
        filled = int(bar_width * percent)
        bar = "=" * filled + "-" * (bar_width - filled)
        sys.stdout.write(
            f"\r    [{bar}] {percent * 100:5.1f}%  "
            f"{format_size(downloaded)} / {format_size(total)}"
        )
    else:
        sys.stdout.write(f"\r    {format_size(downloaded)} downloaded ...")
    sys.stdout.flush()


# ---------------------------------------------------------------------------
# Core download logic
# ---------------------------------------------------------------------------

def download_file(url: str, filepath: str, filename: str) -> bool:
    """Download *url* to *filepath* with progress display and retry logic.

    Returns True on success, False after all retries are exhausted.
    """
    for attempt in range(1, MAX_RETRIES + 1):
        temp_filepath = filepath + ".tmp"
        try:
            print(f"    [ATTEMPT {attempt}/{MAX_RETRIES}] Connecting to {url}")

            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": (
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        "rembg-model-downloader/1.0"
                    )
                },
            )

            with urllib.request.urlopen(request, timeout=60) as response:
                total_size = int(response.headers.get("Content-Length", 0))
                if total_size:
                    print(f"    [INFO] File size: {format_size(total_size)}")
                else:
                    print("    [INFO] File size: unknown")

                downloaded = 0
                last_print = 0

                with open(temp_filepath, "wb") as out_file:
                    while True:
                        chunk = response.read(CHUNK_SIZE)
                        if not chunk:
                            break
                        out_file.write(chunk)
                        downloaded += len(chunk)
                        if (
                            downloaded - last_print >= PROGRESS_INTERVAL
                            or (total_size and downloaded >= total_size)
                        ):
                            print_progress_bar(downloaded, total_size)
                            last_print = downloaded

                print()  # newline after the progress bar

                # Atomically rename temp file to final destination
                if os.path.exists(filepath):
                    os.remove(filepath)
                os.rename(temp_filepath, filepath)
                return True

        except KeyboardInterrupt:
            print("\n    [ABORT] Download interrupted by user.")
            if os.path.exists(temp_filepath):
                os.remove(temp_filepath)
            raise

        except (urllib.error.URLError, urllib.error.HTTPError,
                OSError, TimeoutError, ConnectionError) as exc:
            print(f"\n    [ERROR] Download failed (attempt {attempt}/{MAX_RETRIES}): {exc}")
            if os.path.exists(temp_filepath):
                try:
                    os.remove(temp_filepath)
                except OSError:
                    pass
            if attempt < MAX_RETRIES:
                print(f"    [INFO] Retrying in {RETRY_DELAY} seconds ...")
                time.sleep(RETRY_DELAY)
            else:
                print(f"    [ERROR] All {MAX_RETRIES} attempts failed for {filename}")
                return False

    return False


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

def main():
    print("=" * 72)
    print("  rembg ONNX Models Downloader")
    print("=" * 72)
    print()
    print(f"  Target directory : {TARGET_DIR}")
    print(f"  Models to process: {len(MODELS)}")
    print()

    # Ensure the target directory exists
    os.makedirs(TARGET_DIR, exist_ok=True)
    print(f"  [INFO] Directory ready: {TARGET_DIR}")
    print()

    downloaded_count = 0
    skipped_count = 0
    failed_count = 0
    failed_models = []

    for index, (filename, url, expected_hash) in enumerate(MODELS, start=1):
        filepath = os.path.join(TARGET_DIR, filename)

        print("-" * 72)
        print(f"  [{index}/{len(MODELS)}] {filename}")
        print(f"  URL  : {url}")
        print(f"  Hash : {expected_hash or '(none)'}")
        print()

        # ---- Check if the file already exists and is valid ----
        if os.path.exists(filepath):
            print("    [CHECK] File already exists – verifying hash ...")
            if file_exists_and_valid(filepath, expected_hash):
                print("    [SKIP] File exists and hash matches. Skipping.")
                skipped_count += 1
                continue
            else:
                print("    [WARN] File exists but hash mismatch – re-downloading.")

        # ---- Download ----
        success = download_file(url, filepath, filename)

        if success:
            if verify_hash(filepath, expected_hash):
                actual_size = os.path.getsize(filepath)
                print(f"    [SUCCESS] {filename} downloaded and verified "
                      f"({format_size(actual_size)}).")
                downloaded_count += 1
            else:
                print(f"    [WARN] Hash verification FAILED for {filename}.")
                print("    [WARN] The file may be corrupted – re-run the script to retry.")
                failed_count += 1
                failed_models.append(filename)
        else:
            failed_count += 1
            failed_models.append(filename)

        print()

    # ---- Summary ----
    print("=" * 72)
    print("  DOWNLOAD SUMMARY")
    print("=" * 72)
    print(f"  Total models : {len(MODELS)}")
    print(f"  Downloaded   : {downloaded_count}")
    print(f"  Skipped      : {skipped_count}")
    print(f"  Failed       : {failed_count}")

    if failed_models:
        print()
        print("  Failed models:")
        for fm in failed_models:
            print(f"    - {fm}")

    print()
    if failed_count == 0:
        print("  All models processed successfully!")
    else:
        print("  Some models failed. Check the errors above and re-run the script.")
    print("=" * 72)

    # Non-zero exit code if anything failed
    if failed_count > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
