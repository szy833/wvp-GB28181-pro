package rtp

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
)

// PrepareMedia is offline: it neither loads runtime config nor starts devices.
func PrepareMedia(ctx context.Context, input, output string, fps int) error {
	if input == "" || output == "" || fps < 1 || fps > 120 {
		return fmt.Errorf("input, output and media FPS (1..120) are required")
	}
	inPath, err := filepath.Abs(input)
	if err != nil {
		return err
	}
	outPath, err := filepath.Abs(output)
	if err != nil || inPath == outPath {
		return fmt.Errorf("input and output must be different files")
	}
	if _, err := os.Stat(input); err != nil {
		return err
	}
	if _, err := os.Lstat(output); !os.IsNotExist(err) {
		return fmt.Errorf("output already exists or is inaccessible; choose a new PS path")
	}
	if err := os.MkdirAll(filepath.Dir(output), 0755); err != nil {
		return err
	}
	ps, err := os.CreateTemp(filepath.Dir(output), ".prepare-*.ps")
	if err != nil {
		return err
	}
	defer os.Remove(ps.Name())
	defer ps.Close()
	cmd := exec.CommandContext(ctx, "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-i", input,
		"-map", "0:v:0", "-an", "-vf", "fps="+strconv.Itoa(fps), "-c:v", "libx264", "-pix_fmt", "yuv420p",
		"-preset", "veryfast", "-tune", "zerolatency", "-bf", "0", "-g", strconv.Itoa(fps*2),
		"-x264-params", "aud=1:repeat-headers=1", "-f", "h264", "pipe:1")
	cmd.Stderr = os.Stderr
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start FFmpeg: %w", err)
	}
	const limit = 512 << 20
	data, readErr := io.ReadAll(io.LimitReader(stdout, limit+1))
	if readErr != nil || len(data) > limit {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		return fmt.Errorf("raw media exceeds 512 MiB or could not be read; use a shorter clip")
	}
	if err := cmd.Wait(); err != nil {
		return fmt.Errorf("FFmpeg media preparation failed: %w", err)
	}
	if err := MuxH264PS(data, ps, fps); err != nil {
		return err
	}
	if err := ps.Close(); err != nil {
		return err
	}
	if _, err := NewSharedMedia(ps.Name(), limit); err != nil {
		return fmt.Errorf("prepared media validation: %w", err)
	}
	// Link publishes the validated file without overwriting an existing sample.
	if err := os.Link(ps.Name(), output); err != nil {
		return fmt.Errorf("publish prepared media: %w", err)
	}
	return nil
}
