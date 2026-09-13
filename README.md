# Jrac

> **A Java-written, wheel-Reinventing Audio Converter.**

A personal, predictable, and robust single-file Java script to sync and normalize an audio library. It keeps the directory structure intact, converts lossless files (FLAC) to Opus, and copies everything else (MP3s, cues, covers) as-is.

Built with modern Java, meant to be run directly as a script without explicit compilation.

## Requirements

* **JDK 25+** — A full **JDK is required** (not just a JRE) to natively support running single-file programs directly from the source code.
* **FFmpeg** — Must be installed and available in your system `PATH`.

> **Compatibility Note:** If needed, the source code can be easily backported to support **JDK 11+**. You would just need to wrap the code in a standard class definition and tweak a few minor details. While some newer APIs might have been used, none of them are critical to the core logic.
>
> *Development environment:* This script was written and tested on **Arch Linux** using the latest rolling release of **OpenJDK**, which is why it targets modern Java by default.


## Usage

You don't need to build or compile anything. Just run the `.java` file directly:

```bash
java Jrac.java /path/to/source/music /path/to/output/music
```

## Configuration

Since **Jrac** is a single-file script designed for predictable and personal use, it does not use external configuration files. Instead, you can easily customize its behavior by modifying the variables at the very beginning of the `Jrac.java` file:

* **Audio Formats**:
    * `audioExts`: Defines which file extensions are treated as audio (`flac`, `dsf`, `mp3`, etc.).
    * `skipConvertExts`: Audio formats that should be copied directly to the destination without transcoding (e.g., `mp3`, `ogg`, `opus`).
* **Cover Art Handling**:
    * `coverExts`: Allowed image extensions (`jpg`, `png`). If empty, covers are ignored.
    * `coverNames`: Prioritized list of filenames to look for (e.g., `cover`, `folder`, `front`).
    * `coverDstName`: The standardized filename for the cover art in the destination folder (defaults to `cover`).
* **FFmpeg Pipeline**:
    * `outputExt`: Target extension.
    * `ffmpeg`: The exact command-line arguments passed to FFmpeg.

---

## How It Works

Jrac recursively traverses the source directory tree and mirrors its exact structure to the destination directory using the following logic:

### 1. File Routing Logic
For every file encountered in the directory tree, Jrac decides its fate based on the configuration:
* **Transcoding**: If a file is an audio format (found in `audioExts`) but *not* in `skipConvertExts`, it is asynchronously transcoded using FFmpeg.
* **Direct Copying**: If an audio file is already in a delivery format (found in `skipConvertExts`), it is copied as-is to preserve original quality and save time.
* **Cover Art Normalization**:
    * The script searches the directory for image files matching the `coverNames` list in order of priority.
    * **Fallback**: If no matching name is found, but the directory contains exactly *one* image file with a supported extension, Jrac picks it as the cover art.
    * The chosen image is copied to the destination folder and automatically renamed to your standardized `coverDstName` (e.g., `cover.jpg`), keeping your library perfectly uniform.

### 2. Multi-Threaded Processing
The script dynamically detects your CPU capabilities and defaults to using all available processor cores (`Runtime.getRuntime().availableProcessors()`) via a fixed thread pool (`ExecutorService`). You can override this using the `-m` flag.

### 3. Safety & Overwriting
By default, the script will not overwrite existing files in the destination directory unless explicitly forced via the `-o` or `--overwrite` flag.

## Why Opus & How It Is Configured

Jrac is configured out of the box to transcode lossless files into the **Opus** format at a bitrate of **160 kbps**.

According to numerous blind tests, Opus is currently the most advanced lossy audio format available. At 160 kbps, the result is practically indistinguishable from the lossless original under any real-world listening conditions. In fact, for most people, this bitrate is transparent and slightly redundant—you can safely lower it to something like **130 kbps** if you want to save more space.

### The 44.1 kHz to 48 kHz Resampling
Since the Opus format strictly mandates internal processing at **48 kHz** and does not support the standard CD sample rate of **44.1 kHz** directly, the FFmpeg command line includes a top-tier resampling filter.

This ensures that all 44.1 kHz source files are resampled using **SoX at maximum precision**. This conversion does not degrade the perceived sound quality, as modern software resamplers introduce distortion levels that are orders of magnitude lower than the artifacts introduced by lossy compression itself.
