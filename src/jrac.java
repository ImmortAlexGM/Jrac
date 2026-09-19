
// Which file extensions should be considered audio files.
final Set<String> audioExts = Set.of("flac", "dsf", "mp3", "ogg", "opus");
// Which file extensions should simply be copied rather than transcoded.
final Set<String> skipConvertExts = Set.of("mp3", "ogg", "opus");

// Which file extensions should be considered cover art files.
// The list may be empty; in that case, the covers will not be copied.
final Set<String> coverExts = Set.of("jpg", "jpeg", "png");
// Cover filenames without extensions, in order of priority.
// If no file is found, but the directory contains a single file with a suitable extension,
// that file will be treated as the cover.
final List<String> coverNames = List.of("cover", "folder", "front", "album", "albumart");
// The cover file name to be used when copying to destination.
final String coverDstName = "cover";

// Output audio file extension.
// Must match the encoding setting below.
final String outputExt = "opus";

// The ffmpeg command line, split into individual tokens.
// The %i token will be replaced by the input filename, and the %o token by the output filename.
// There is no need to add extra quotes to escape tokens containing special characters, including filenames.
final String[] ffmpeg = {
        "ffmpeg",
        "-i", "%i",             // Input file
        "-c:a", "libopus",      // Encode to opus using libopus
        "-b:a", "160k",         // Transparent to absolutely everyone. There is no need to use a higher bitrate.
        "-apply_phase_inv", "0",// Turn off phase inversion for intensity stereo just for safety reason
            // Opus does not support a sample rate of 44.1 kHz, so we resample to 44 kHz at maximum quality.
        "-af", "aresample=resampler=soxr:osr=48000:precision=28",
        "-map_metadata", "0",   // Copy metadata from source files
        "-loglevel", "error",   // Silence output
        "-y",                   // Don't ask about anything. We handle the overwriting of files ourselves.
        "%o"                    // Output file
};

//  Can be overridden on command line.
boolean overwrite = false;
boolean verbose = false;
int threads = Math.max(1, Runtime.getRuntime().availableProcessors());

// See showHelp() below
void main(String[] args)
{
    if (args.length < 2) {
        showHelp();
        return;
    }
    Path src = Paths.get(args[args.length - 2]);
    Path dst = Paths.get(args[args.length - 1]);

    for (int i=0; i<args.length-2; i++) {
        if (args[i].equals("-o") || args[i].equals("--overwrite"))
            overwrite = true;
        if (args[i].equals("-v") || args[i].equals("--verbose"))
            verbose = true;
        else if (args[i].equals("-m") || args[i].equals("--threads"))
            threads = Integer.parseInt(args[++i]);
        else {
            showHelp();
            return;
        }
    }

    if (!Files.exists(src) && !Files.isDirectory(src)) {
        printError(src + " does not exist or is not a directory");
        return;
    }

    try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
        List<Future<Integer>> futures = new ArrayList<>();
        processDir(executor, futures, src, dst);
        int cntOk = 0, cntError = 0;
        for (Future<Integer> future : futures) {
            try {
                Integer exitCode = future.get();
                if (exitCode != 0) cntError++;
                else cntOk++;
            } catch (InterruptedException | ExecutionException e) {
                printError("Command execution interrupted", e);
            }
        }
        System.out.println("Processed OK: "+cntOk);
        System.out.println("Processed error: "+cntError);
    }
}

void showHelp()
{
    System.out.println("Usage: java jrac.java [-o|--overwrite] [-v|--verbose] [-m N|--threads N] <src dir> <dst dir>");
}

void print(String msg)
{
    if (verbose)
        System.out.println(msg);
}

void printError(String msg)
{
    System.err.println(msg);
}

void printError(String msg, Throwable t)
{
    System.err.println(msg);
    t.printStackTrace(System.err);
}


void processDir(ExecutorService executor, List<Future<Integer>> futures, Path src, Path dst)
{
    List<Path> dirs = new ArrayList<>();
    final List<Path> cues = new ArrayList<>();
    List<Path> audios = new ArrayList<>();
    List<Path> covers = new ArrayList<>();
    try (Stream<Path> files = Files.list(src)) {
        files.forEach(file -> {
            if (Files.isDirectory(file)) dirs.add(file);
            else if (Files.isRegularFile(file)) {
                String ext = getExtensionLC(file);
                if ("cue".equals(ext)) {
                    cues.add(file);
                }
                else if (audioExts.contains(ext)) {
                    audios.add(file);
                }
                else if (coverExts.contains(ext)) {
                    covers.add(file);
                }
            }
        });
    }
    catch (IOException e) {
        printError("Error retrieving the list of files in the directory '"+src+"'");
        return;
    }

    Path cover = null;
    if (covers.size()==1) cover = covers.getFirst();
    else if (covers.size()>1) {
        coverLoop:
        for (String coverName : coverNames) {
            for (Path file : covers) {
                String fn = file.getFileName().toString().toLowerCase();
                int idx = fn.lastIndexOf('.');
                fn = fn.substring(0, idx);
                if (coverName.equals(fn)) {
                    cover = file;
                    break coverLoop;
                }
            }
        }
    }

    if (!cues.isEmpty()) {
        processWithCue(executor, futures, cues, audios, cover, dst);
    }
    else if (!audios.isEmpty()) {
        process(executor, futures, audios, cover, dst);
    }
    else {
        for (Path dir : dirs) {
            processDir(executor, futures, dir, dst.resolve(dir.getFileName()));
        }
    }
}

// FILE "file name.ext" WAVE
final String CUE_FILE_PREFIX = "FILE ";
final String CUE_FILE_SUFFIX = " WAVE";

void processWithCue(ExecutorService executor, List<Future<Integer>> futures, List<Path> cues, List<Path> audios, Path cover, Path dst)
{
    List<Path> filesToProcess = new ArrayList<>();
    Map<Path, List<String>> cuesToSave = new HashMap<>();
    for (Path srcCue : cues) {
        Path dstCue = dst.resolve(srcCue.getFileName());
        if (!overwrite && Files.exists(dstCue)) {
            print(srcCue+": skip existing");
            continue;
        }
        else {
            print(srcCue.toString());
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(srcCue, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            printError(srcCue+": error reading file", e);
            return;
        }
        linesLoop:
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String testLine = line.trim().toUpperCase();
            if (testLine.startsWith(CUE_FILE_PREFIX) || testLine.endsWith(CUE_FILE_SUFFIX)) {
                String waveFileName = testLine.substring(CUE_FILE_PREFIX.length(), testLine.length() - CUE_FILE_SUFFIX.length()).trim();
                if (waveFileName.charAt(0)=='\"' && waveFileName.charAt(waveFileName.length() - 1)=='\"')
                    waveFileName = waveFileName.substring(1, waveFileName.length() - 1);
                for (Path audio : audios) {
                    String srcFileName = audio.getFileName().toString();
                    if (srcFileName.equalsIgnoreCase(waveFileName)) {
                        String dstFileName;
                        String ext = getExtensionLC(audio);
                        if (skipConvertExts.contains(ext))
                            dstFileName = srcFileName;
                        else
                            dstFileName = getDstName(audio);
                        line = CUE_FILE_PREFIX + "\"" + dstFileName + "\"" + CUE_FILE_SUFFIX;
                        lines.set(i, line);
                        filesToProcess.add(audio);
                        continue linesLoop;
                    }
                }
                printError(srcCue+": file not found: " + waveFileName);
                return;
            }
        }
        cuesToSave.put(dstCue, lines);
    }
    try {
        Files.createDirectories(dst);
        for (Map.Entry<Path, List<String>> entry : cuesToSave.entrySet()) {
            Files.write(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
        }
    }
    catch (IOException e) {
        printError("Error writing cues", e);
    }

    process(executor, futures, filesToProcess, cover, dst);
}

void process(ExecutorService executor, List<Future<Integer>> futures, List<Path> audioFiles, Path cover, Path dst)
{
    try {
        Files.createDirectories(dst);
    }
    catch (IOException e) {
        printError(dst+": error creating directories", e);
        return;
    }
    for (Path srcFile : audioFiles) {
        String ext = getExtensionLC(srcFile);
        boolean skipConvert = skipConvertExts.contains(ext);
        Path dstFile;
        if (skipConvert)
            dstFile = dst.resolve(srcFile.getFileName());
        else
            dstFile = dst.resolve(getDstName(srcFile));
        if (!overwrite && Files.exists(dstFile)) {
            print(srcFile+": skip existing");
            continue;
        }
        else {
            print(srcFile.toString());
        }
        Future<Integer> future = executor.submit(() -> {
            if (skipConvert) {
                try {
                    Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING);
                    return 0;
                }
                catch (IOException e) {
                    printError(srcFile+": error copying file", e);
                    return -1;
                }
            }
            String[] cmd = new String[ffmpeg.length];
            System.arraycopy(ffmpeg, 0, cmd, 0, ffmpeg.length);
            for (int i = 0; i < cmd.length; i++) {
                String s = cmd[i];
                if (s.equals("%i"))
                    cmd[i] = srcFile.toString();
                else if (s.equals("%o"))
                    cmd[i] = dstFile.toString();
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            try (Process process = pb.start()) {
                process.errorReader().lines().forEach(s -> printError(srcFile.getFileName()+": "+s));
                int exitCode = process.waitFor();
                String msg = srcFile +": exit code: "+exitCode;
                if (exitCode == 0)
                    msg += ": OK";
                else msg += ": with error code "+exitCode;
                print(msg);
                return exitCode;
            }
            catch (InterruptedException e) {
                printError(srcFile+": interrupted", e);
                return -1;
            }
        });
        futures.add(future);
    }
    if (cover!=null) {
        String ext = getExtensionLC(cover);
        try {
            Path coverDst = dst.resolve(coverDstName+"." + ext);
            if (overwrite || !Files.exists(coverDst))
                Files.copy(cover, coverDst,  StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) {
            printError(cover+": error copying cover file", e);
            //return;
        }
    }
}

String getExtensionLC(Path file)
{
    String fileName = file.getFileName().toString();
    int idx = fileName.lastIndexOf('.');
    if (idx>0) return fileName.substring(idx+1).toLowerCase();
    else return "";
}

String getDstName(Path audioFile)
{
    String dstName = audioFile.getFileName().toString();
    int idx = dstName.lastIndexOf('.');
    if (idx>0) return dstName.substring(0, idx+1)+outputExt;
    else return dstName;
}
