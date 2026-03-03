package com.example.images;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public class ImageProcessor {

    public static void main(String[] args) {
        // First we generate 100 images from source_images folder
        try {
            multiplyImages("source_images", "sample_images", 100);
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║    BENCHMARK: BEFORE vs AFTER optimisation       ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        // ── BEFORE: original all-in-memory approach ──────────────────────────
        System.out.println("\n[BEFORE] Sequential all-in-memory processing...");
        long beforeStart = System.currentTimeMillis();
        boolean beforeSuccess = false;
        try {
            processImageFolderOriginal("sample_images", "processed_before");
            beforeSuccess = true;
        } catch (OutOfMemoryError e) {
            System.err.println("[BEFORE] ❌ OutOfMemoryError: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[BEFORE] IO error: " + e.getMessage());
        }
        long beforeTime = System.currentTimeMillis() - beforeStart;

        // ── AFTER: optimised streaming + parallel approach ───────────────────
        System.out.println("\n[AFTER] Streaming + parallel processing...");
        long afterStart = System.currentTimeMillis();
        try {
            processImageFolderOptimized("sample_images", "processed_after");
        } catch (IOException e) {
            System.err.println("[AFTER] IO error: " + e.getMessage());
            e.printStackTrace();
        }
        long afterTime = System.currentTimeMillis() - afterStart;

        // ── Results ─────────────────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║                BENCHMARK RESULTS                 ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        if (beforeSuccess) {
            System.out.printf("║  Before (original):  %6d ms                    ║%n", beforeTime);
        } else {
            System.out.println("║  Before (original):  CRASHED (OutOfMemoryError) ║");
        }
        System.out.printf("║  After  (optimised): %6d ms                    ║%n", afterTime);
        if (beforeSuccess) {
            double speedup = (double) beforeTime / afterTime;
            System.out.printf("║  Speedup:            %6.2fx                     ║%n", speedup);
        }
        System.out.println("╚══════════════════════════════════════════════════╝");
    }

    public static void multiplyImages(String inputFolder, String outputFolder, int multiplicationFactor) throws IOException {
        File folder = new File(inputFolder);
        File[] imageFiles = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") ||
                        name.toLowerCase().endsWith(".png"));

        if (imageFiles == null || imageFiles.length == 0) {
            System.out.println("No images found in the folder");
            return;
        }

        // Create output directory if it doesn't exist
        File outputDir = new File(outputFolder);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Multiply each image file
        for (File imageFile : imageFiles) {
            String fileName = imageFile.getName();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            String extension = fileName.substring(fileName.lastIndexOf('.'));

            // Create multiple copies
            for (int i = 1; i <= multiplicationFactor; i++) {
                String newFileName = String.format("%s_%d%s", baseName, i, extension);
                File outputFile = new File(outputDir, newFileName);

                try {
                    // Copy the file using Java NIO for better performance
                    Files.copy(imageFile.toPath(), outputFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    System.err.println("Error copying file: " + fileName + " - " + e.getMessage());
                }
            }
        }
    }

    // ── ORIGINAL (problematic) implementation — kept for BEFORE benchmark ────
    public static void processImageFolderOriginal(String inputFolder, String outputFolder) throws IOException {
        // Get the Runtime instance to monitor memory
        Runtime runtime = Runtime.getRuntime();

        // Helper method to print memory stats
        BiConsumer<String, Runtime> printMemoryStats = (stage, rt) -> {
            long totalMemory = rt.totalMemory() / (1024 * 1024);
            long freeMemory = rt.freeMemory() / (1024 * 1024);
            long usedMemory = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMemory = rt.maxMemory() / (1024 * 1024);

            System.out.println("\n=== Memory Stats at " + stage + " ===");
            System.out.println("Used Memory: " + usedMemory + " MB");
            System.out.println("Free Memory: " + freeMemory + " MB");
            System.out.println("Total Memory: " + totalMemory + " MB");
            System.out.println("Maximum Memory: " + maxMemory + " MB");
            System.out.println("==============================\n");
        };

        // Print initial memory state
        printMemoryStats.accept("START", runtime);

        File folder = new File(inputFolder);
        File[] imageFiles = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") ||
                name.toLowerCase().endsWith(".png"));

        if (imageFiles == null || imageFiles.length == 0) {
            System.out.println("No images found in the folder");
            return;
        }

        // Create output directory if it doesn't exist
        File outputDir = new File(outputFolder);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Store all images in memory first
        List<BufferedImage> images = new ArrayList<>();
        System.out.println("Loading all images into memory...");

        for (File imageFile : imageFiles) {
            BufferedImage image = ImageIO.read(imageFile);
            images.add(image);
            System.out.println("Loaded: " + imageFile.getName());
        }

        // Print memory usage after loading
        printMemoryStats.accept("AFTER LOADING", runtime);

        // Process all images
        System.out.println("Processing all images...");
        List<BufferedImage> processedImages = new ArrayList<>();

        for (BufferedImage image : images) {
            BufferedImage processed = applyEffects(image);
            processedImages.add(processed);
        }

        // Print memory usage after processing
        printMemoryStats.accept("AFTER PROCESSING", runtime);

        // Save all processed images
        System.out.println("Saving all processed images...");
        for (int i = 0; i < imageFiles.length; i++) {
            String outputName = outputFolder + File.separator + "processed_" + imageFiles[i].getName();
            ImageIO.write(processedImages.get(i), getImageFormat(imageFiles[i].getName()), new File(outputName));
            System.out.println("Saved: " + outputName);
        }

        // Print final memory usage
        printMemoryStats.accept("END", runtime);

        System.out.println("All images processed successfully");
    }

    private static BufferedImage applyEffects(BufferedImage original) {
        // Simulate memory-intensive image processing
        int width = original.getWidth();
        int height = original.getHeight();

        // Create a new image with the same dimensions
        BufferedImage processed = new BufferedImage(width, height, original.getType());

        // Process each pixel - simple grayscale conversion for this example
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = original.getRGB(x, y);

                int alpha = (rgb >> 24) & 0xff;
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;

                // Convert to grayscale
                int gray = (red + green + blue) / 3;

                // Set new RGB
                int newRGB = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                processed.setRGB(x, y, newRGB);
            }
        }

        return processed;
    }

    // ── OPTIMISED implementation ──────────────────────────────────────────────
    //
    //  Optimisation 1 – Streaming (fixes OutOfMemoryError)
    //  ─────────────────────────────────────────────────────
    //  The original code accumulates ALL images and ALL processed copies in
    //  memory before saving anything.  For N images of size W×H the peak heap
    //  usage is O(N).  The fix: load → process → save → discard each image
    //  one at a time so peak memory stays O(1) regardless of batch size.
    //
    //  Optimisation 2 – Parallelism (CPU throughput)
    //  ──────────────────────────────────────────────
    //  A fixed thread pool with one thread per CPU core lets image decode,
    //  pixel processing and I/O overlap across different files.  The pool size
    //  also acts as a natural memory cap: at most `numThreads` images can be
    //  in-flight simultaneously.
    //
    //  Optimisation 3 – Bulk pixel access (CPU efficiency)
    //  ─────────────────────────────────────────────────────
    //  Per-pixel getRGB / setRGB each carry bounds-checking and per-call JNI
    //  overhead.  Replacing them with the single bulk getRGB(x,y,w,h,arr,0,w)
    //  call lets Java load the whole frame into one int[], which the JIT can
    //  then process with auto-vectorisation (SIMD).
    public static void processImageFolderOptimized(String inputFolder, String outputFolder) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        BiConsumer<String, Runtime> printMemoryStats = (stage, rt) -> {
            long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMB  = rt.maxMemory() / (1024 * 1024);
            System.out.printf("  [Memory @ %-20s]  used=%d MB  max=%d MB%n", stage, usedMB, maxMB);
        };

        File folder = new File(inputFolder);
        File[] imageFiles = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") ||
                name.toLowerCase().endsWith(".png"));

        if (imageFiles == null || imageFiles.length == 0) {
            System.out.println("No images found in the folder");
            return;
        }

        File outputDir = new File(outputFolder);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        printMemoryStats.accept("START", runtime);

        // Opt-2: use all available CPU cores
        int numThreads = Runtime.getRuntime().availableProcessors();
        System.out.println("  Using " + numThreads + " threads (available CPU cores)");

        AtomicInteger doneCount   = new AtomicInteger(0);
        AtomicLong    totalLoadMs = new AtomicLong(0);
        AtomicLong    totalProcMs = new AtomicLong(0);
        AtomicLong    totalSaveMs = new AtomicLong(0);
        int           total       = imageFiles.length;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (File imageFile : imageFiles) {
            executor.submit(() -> {
                try {
                    String outputPath = outputFolder + File.separator + "processed_" + imageFile.getName();

                    // Opt-1 step A: load
                    long t0 = System.currentTimeMillis();
                    BufferedImage image = ImageIO.read(imageFile);
                    if (image == null) return;
                    totalLoadMs.addAndGet(System.currentTimeMillis() - t0);

                    // Opt-1 step B + Opt-3: process with bulk pixel access
                    long t1 = System.currentTimeMillis();
                    BufferedImage result = applyEffectsOptimized(image);
                    totalProcMs.addAndGet(System.currentTimeMillis() - t1);

                    // Opt-1 step C: release original image memory immediately
                    image = null;

                    // Opt-1 step D: save
                    long t2 = System.currentTimeMillis();
                    ImageIO.write(result, getImageFormat(imageFile.getName()), new File(outputPath));
                    totalSaveMs.addAndGet(System.currentTimeMillis() - t2);

                    int done = doneCount.incrementAndGet();
                    if (done % 20 == 0 || done == total) {
                        System.out.printf("  Processed %d / %d images%n", done, total);
                    }
                } catch (IOException e) {
                    System.err.println("Error processing " + imageFile.getName() + ": " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Processing interrupted");
        }

        printMemoryStats.accept("END", runtime);
        System.out.println("\n  Per-phase wall-clock time (summed across all threads):");
        System.out.printf("    Load:    %5d ms%n", totalLoadMs.get());
        System.out.printf("    Process: %5d ms%n", totalProcMs.get());
        System.out.printf("    Save:    %5d ms%n", totalSaveMs.get());
        System.out.println("  All images processed successfully (optimised).");
    }

    // ── OPTIMISED pixel processing (Opt-3: bulk int[] access) ────────────────
    //
    //  Instead of calling getRGB(x,y) and setRGB(x,y) once per pixel — which
    //  each trigger bounds-checking and prevent loop vectorisation — we read
    //  the entire frame into one int[] with the bulk getRGB overload, process
    //  the array in a tight loop (which the JIT can auto-vectorise with SIMD),
    //  and write it back in one setRGB bulk call.
    //
    //  We also use the perceptually accurate ITU-R BT.601 luminance formula
    //  instead of the original's simple arithmetic average.
    private static BufferedImage applyEffectsOptimized(BufferedImage original) {
        int width  = original.getWidth();
        int height = original.getHeight();
        int total  = width * height;

        // Single bulk read: converts any internal format to ARGB int[] in one go
        int[] pixels = original.getRGB(0, 0, width, height, null, 0, width);

        // Tight loop — JIT can vectorise this; no per-iteration bounds checks
        for (int i = 0; i < total; i++) {
            int p     = pixels[i];
            int alpha = (p >> 24) & 0xff;
            int red   = (p >> 16) & 0xff;
            int green = (p >>  8) & 0xff;
            int blue  =  p        & 0xff;

            // ITU-R BT.601 luminance (more accurate than (r+g+b)/3)
            int gray = (int)(0.299 * red + 0.587 * green + 0.114 * blue);

            pixels[i] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
        }

        // Single bulk write back into the output image
        BufferedImage processed = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        processed.setRGB(0, 0, width, height, pixels, 0, width);
        return processed;
    }

    private static String getImageFormat(String filename) {
        if (filename.toLowerCase().endsWith(".png")) {
            return "png";
        } else {
            return "jpeg";
        }
    }
}