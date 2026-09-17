//go:build windows

package main

import "syscall"

// Windows has no setsid/process-session concept; the equivalent detachment is these two creation
// flags: DETACHED_PROCESS (no console — this process's console, if any, is not inherited) and
// CREATE_NEW_PROCESS_GROUP (its own process group, so it does not receive a Ctrl+C/Ctrl+Break sent
// to this process's group). Together they give startDetached the same "keeps running after this
// process exits, not tied to it" property Setsid gives on Unix.
const (
	detachedProcess      = 0x00000008
	createNewProcessGroup = 0x00000200
)

func detachedSysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{CreationFlags: detachedProcess | createNewProcessGroup}
}
