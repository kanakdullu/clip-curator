package com.portfolio.clipcurator.processing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class FfmpegService {

    private final String ffmpegCommand;

    public FfmpegService(@Value("${app.processing.ffmpeg.command:ffmpeg}") String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public Path extractAudio(Path inputVideo, Path outputAudio) {
        runCommand(List.of(
                ffmpegCommand,
                "-y",
                "-i", inputVideo.toAbsolutePath().toString(),
                "-vn",
                "-acodec", "libmp3lame",
                "-q:a", "2",
                outputAudio.toAbsolutePath().toString()
        ));

        return outputAudio;
    }

    public List<Path> extractFrames(Path inputVideo, Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create frames output directory.", ex);
        }

        String outputPattern = outputDirectory.resolve("%04d.jpg").toAbsolutePath().toString();

        runCommand(List.of(
                ffmpegCommand,
                "-y",
                "-i", inputVideo.toAbsolutePath().toString(),
                "-vf", "fps=0.5",
                outputPattern
        ));

        try (Stream<Path> frameFiles = Files.list(outputDirectory)) {
            return frameFiles
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jpg"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list extracted frame files.", ex);
        }
    }

    private void runCommand(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("FFmpeg command failed with exit code " + exitCode + ": " + output);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to execute FFmpeg command.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg command interrupted.", ex);
        }
    }
}
