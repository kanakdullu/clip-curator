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
    private final String ffprobeCommand;
    private final String frameSamplingFps;

    public FfmpegService(
            @Value("${app.processing.ffmpeg.command:ffmpeg}") String ffmpegCommand,
            @Value("${app.processing.ffprobe.command:ffprobe}") String ffprobeCommand,
            @Value("${app.processing.frame-sampling-fps:1.0}") String frameSamplingFps
    ) {
        this.ffmpegCommand = ffmpegCommand;
        this.ffprobeCommand = ffprobeCommand;
        this.frameSamplingFps = requireNonBlank(frameSamplingFps, "app.processing.frame-sampling-fps");
    }

    public boolean hasAudioStream(Path inputMedia) {
        CommandResult commandResult = runCommand(List.of(
                ffprobeCommand,
                "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=index",
                "-of", "csv=p=0",
                inputMedia.toAbsolutePath().toString()
        ), false);

        if (commandResult.exitCode() != 0) {
            throw new IllegalStateException("FFprobe command failed with exit code "
                    + commandResult.exitCode() + ": " + commandResult.output());
        }

        return !commandResult.output().isBlank();
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
            "-vf", "fps=" + frameSamplingFps,
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
        runCommand(command, true);
    }

    private CommandResult runCommand(List<String> command, boolean failOnNonZeroExitCode) {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

            int exitCode = process.waitFor();
            if (failOnNonZeroExitCode && exitCode != 0) {
                throw new IllegalStateException("FFmpeg command failed with exit code " + exitCode + ": " + output);
            }
            return new CommandResult(exitCode, output);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to execute FFmpeg command.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg command interrupted.", ex);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }

    private String requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required configuration is missing: " + propertyName);
        }
        return value;
    }
}
