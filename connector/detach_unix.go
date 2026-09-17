//go:build !windows

package main

import "syscall"

// detachedSysProcAttr puts the launched process in its own session (setsid), so it survives this
// process exiting and is not killed by a signal sent to this process's process group — the whole
// point of startDetached.
func detachedSysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Setsid: true}
}
