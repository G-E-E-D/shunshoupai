//go:build !windows

package app

import "os"

func protectModelKey(path string) error { return os.Chmod(path, 0600) }
