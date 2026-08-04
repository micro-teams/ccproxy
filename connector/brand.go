// The ccproxy connector: a machine-side agent that lets the ccproxy control plane run terminal
// screens (the Claude Code login) and commands on a machine it cannot SSH into. The machine dials
// out; nothing dials in.
//
// This is deliberately thin. Everything that is actually hard — driving the terminal, hosting the
// login applet, keeping a screen alive across a self-update, enrolment, the dial-out transport — is
// micro-connector's, maintained once for all three products that share it (MicroTeams, ccproxy,
// cheese). All that lives here is the brand (the handful of names that make this ccproxy rather than
// something else) and a command tree that wires the library's pieces together. If this file grows a
// second copy of the library's logic, the fork we just deleted is growing back.
package main

import "github.com/micro-teams/micro-connector/cli/brand"

// ccproxyBrand is who this connector is. A machine's config, its private tmux socket, its service
// unit and the endpoints it enrolls and downloads against all follow from these names; sharing any
// of them with another connector on the same machine is how one product quietly ends up driving
// another's screens.
var ccproxyBrand = brand.Brand{
	Name:               "ccproxy-connector",
	EnvPrefix:          "CCPROXY",
	ConfigDir:          "ccproxy-connector",
	RuntimeDir:         "ccproxy-connector",
	ServiceName:        "ccproxy-connector",
	ServiceDisplayName: "CCProxy Connector",
	ServiceDescription: "Connects this machine to its CCProxy control plane.",
	// The control plane serves both of these under its public origin (which already carries the
	// "/ccproxy" gateway prefix when there is one, because the machine is handed that whole base).
	EnrollBase: "/machine/enroll",
	BinaryBase: "/connector/latest",
}
