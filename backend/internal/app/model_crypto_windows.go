//go:build windows

package app

import (
	"os/exec"
	"os/user"
	"syscall"
)

func protectModelKey(path string) error {
	account, e := user.Current()
	if e != nil {
		return e
	}
	c := exec.Command("icacls.exe", path, "/inheritance:r", "/grant:r", account.Username+":F", "SYSTEM:F")
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return c.Run()
}
